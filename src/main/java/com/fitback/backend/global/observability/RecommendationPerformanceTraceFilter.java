package com.fitback.backend.global.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RecommendationPerformanceTraceFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !request.getRequestURI().matches("/api/v1/analyses/[^/]+/recommendations");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try (RecommendationPerformanceTrace.Scope scope =
                     RecommendationPerformanceTrace.beginIfRequested(
                             request.getHeader(RecommendationPerformanceTrace.REQUEST_HEADER)
                     )) {
            if (!scope.active()) {
                filterChain.doFilter(request, response);
                return;
            }
            response.setHeader(RecommendationPerformanceTrace.RESPONSE_HEADER, scope.traceId());
            try {
                filterChain.doFilter(request, response);
                scope.complete(response.getStatus());
            } catch (IOException | ServletException | RuntimeException exception) {
                scope.fail(response.getStatus());
                throw exception;
            }
        }
    }
}
