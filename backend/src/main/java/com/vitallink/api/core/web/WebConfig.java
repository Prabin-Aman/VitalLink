package com.vitallink.api.core.web;

import com.vitallink.api.account.AccountService;
import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Settings;
import com.vitallink.api.core.consent.Consent.Viewer;
import com.vitallink.api.core.consent.ConsentGate;
import com.vitallink.api.core.consent.ConsentGates;
import com.vitallink.api.core.security.TokenVerifier;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS (only the web portal's origin) and the two parameters handlers ask for: the signed-in
 * {@link CurrentUser} and, on shared views, a {@link ConsentGate} for the {@code user_id} in the path.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String CALLER = "vitallink.caller";

    private final Settings settings;
    private final TokenVerifier tokens;
    private final AccountService accounts;
    private final ConsentGates gates;
    private final RateLimiter limits;

    public WebConfig(
            Settings settings, TokenVerifier tokens, AccountService accounts, ConsentGates gates, RateLimiter limits) {
        this.limits = limits;
        this.gates = gates;
        this.settings = settings;
        this.tokens = tokens;
        this.accounts = accounts;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(settings.corsOrigin().split("\\s*,\\s*"))
                .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE")
                .allowedHeaders("Authorization", "Content-Type")
                .exposedHeaders(RequestIdFilter.HEADER);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerResolver());
        resolvers.add(new GateResolver());
    }

    /** The verified caller's account, created on first use; resolved once per request. */
    CurrentUser caller(HttpServletRequest request) {
        CurrentUser cached = (CurrentUser) request.getAttribute(CALLER);
        if (cached == null) {
            var claims = tokens.verify(request.getHeader("Authorization"));
            limits.check(claims.subject(), request.getRequestURI().equals("/v1/me/health/ingest"));
            cached = new CurrentUser(accounts.getOrCreate(claims), claims);
            request.setAttribute(CALLER, cached);
        }
        return cached;
    }

    private final class CallerResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter p) {
            return p.getParameterType() == CurrentUser.class;
        }

        @Override
        public Object resolveArgument(
                MethodParameter p, ModelAndViewContainer m, NativeWebRequest web, WebDataBinderFactory b) {
            return caller(web.getNativeRequest(HttpServletRequest.class));
        }
    }

    private final class GateResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter p) {
            return p.getParameterType() == ConsentGate.class;
        }

        @Override
        public Object resolveArgument(
                MethodParameter p, ModelAndViewContainer m, NativeWebRequest web, WebDataBinderFactory b) {
            HttpServletRequest request = web.getNativeRequest(HttpServletRequest.class);
            CurrentUser me = caller(request);
            @SuppressWarnings("unchecked")
            Map<String, String> vars =
                    (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            UUID owner;
            try {
                owner = UUID.fromString(vars.get("user_id"));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new ApiError(
                        422,
                        "invalid_request",
                        "The request body or query is invalid.",
                        List.of(Map.of("loc", List.of("path", "user_id"), "msg", "invalid value")));
            }
            Viewer viewer = new Viewer(me.id(), me.user().email(), me.user().emailConfirmedAt() != null);
            return gates.open(viewer, owner, RequestIdFilter.current(request));
        }
    }
}
