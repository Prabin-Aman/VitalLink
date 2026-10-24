package com.vitallink.api.core.db;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
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
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTransactionFilter extends OncePerRequestFilter {

    private final TransactionTemplate transactions;

    public RequestTransactionFilter(PlatformTransactionManager manager) {
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
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
