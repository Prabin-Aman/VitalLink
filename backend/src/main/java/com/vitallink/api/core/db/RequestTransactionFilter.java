package com.vitallink.api.core.db;

import com.vitallink.api.core.web.RequestIdFilter;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * One transaction per API request (docs/02-architecture.md): it commits when the response is a success
 * and rolls back on any error status or exception. The body is held until the commit, so a client never
 * sees a success for data that wasn't saved. Audit entries use their own transaction ({@code AuditLog}).
 *
 * <p>Each request holds a connection for its whole length, and an audit entry (or another
 * REQUIRES_NEW write) needs a second one. If every connection were held by a request waiting for that
 * second one, nothing could move until the pool timed out. So fewer requests than connections run at
 * once; the rest queue here, and a request that waits too long gets 503 instead of a pool timeout.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTransactionFilter extends OncePerRequestFilter {

    static final long QUEUE_SECONDS = 15;

    private final TransactionTemplate transactions;
    private final Semaphore running;

    public RequestTransactionFilter(PlatformTransactionManager manager, DataSource dataSource) {
        this.transactions = new TransactionTemplate(manager);
        int pool = dataSource instanceof HikariDataSource h ? h.getMaximumPoolSize() : 10;
        // Keep a fifth of the pool (at least one connection) free for the nested writes.
        this.running = new Semaphore(Math.max(1, pool - Math.max(1, pool / 5)), true);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean admitted;
        try {
            admitted = running.tryAcquire(QUEUE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            admitted = false;
        }
        if (!admitted) {
            busy(request, response);
            return;
        }
        try {
            transact(request, response, chain);
        } finally {
            running.release();
        }
    }

    private static void busy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String id = RequestIdFilter.current(request);
        response.setStatus(503);
        response.setHeader("Retry-After", "5");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream()
                .write(("{\"error\":{\"code\":\"busy\",\"message\":\"VitalLink is busy right now. Try again in a"
                                + " few seconds.\",\"detail\":null,\"request_id\":"
                                + (id == null ? "null" : "\"" + id + "\"")
                                + "}}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private void transact(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
        try {
            transactions.executeWithoutResult(status -> {
                try {
                    chain.doFilter(request, buffered);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (ServletException e) {
                    throw new WrappedServletException(e);
                }
                if (buffered.getStatus() >= 400) {
                    status.setRollbackOnly();
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (WrappedServletException e) {
            throw (ServletException) e.getCause();
        }
        buffered.copyBodyToResponse();
    }

    private static final class WrappedServletException extends RuntimeException {
        WrappedServletException(ServletException cause) {
            super(cause);
        }
    }
}
