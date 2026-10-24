package com.vitallink.api.core.web;

import com.vitallink.api.core.ErrorHandler;
import com.vitallink.api.core.Settings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Two guards that fail closed:
 *
 * <ul>
 *   <li>On Render (which sets {@code RENDER}), the service refuses to start unless {@code ENV=production}, so
 *       a forgotten setting can never turn on dev tokens or local file storage in the real deployment.
 *   <li>Request bodies over 2 MB are refused with 413 before anything parses them (the largest real body,
 *       a 5,000-bucket ingest batch, is about 0.5 MB).
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class SafetyChecks extends OncePerRequestFilter {

    static final long MAX_BODY = 2L * 1024 * 1024;

    private final ObjectMapper json;

    public SafetyChecks(Settings settings, ObjectMapper json) {
        this.json = json;
        if (System.getenv("RENDER") != null && settings.isDev()) {
            throw new IllegalStateException("Running on Render with ENV=" + settings.env() + ": set ENV=production.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/local-storage/"); // uploads have their own 25 MB limit
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY) {
            var body = ErrorHandler.envelope(request, 413, "body_too_large", "Request bodies can be up to 2 MB.", null)
                    .getBody();
            response.setStatus(413);
            response.setContentType("application/json");
            response.getWriter().write(json.writeValueAsString(body));
            return;
        }
        chain.doFilter(request, response);
    }
}
