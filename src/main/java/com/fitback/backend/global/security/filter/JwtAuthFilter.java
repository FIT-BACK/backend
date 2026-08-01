package com.fitback.backend.global.security.filter;

import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.service.CustomUserDetailsService;
import com.fitback.backend.global.security.util.JwtUtil;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService customUserDetailsService;
    private final ObjectMapper objectMapper;
    private final HandlerExceptionResolver handlerExceptionResolver;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String email;
        try {
            email = jwtUtil.getAccessTokenSubject(authorization.substring(7));
        } catch (JwtException | IllegalArgumentException exception) {
            SecurityContextHolder.clearContext();
            writeUnauthorizedResponse(response);
            return;
        }

        UserDetails userDetails;
        try {
            userDetails = customUserDetailsService.loadUserByUsername(email);
        } catch (UsernameNotFoundException exception) {
            SecurityContextHolder.clearContext();
            writeUnauthorizedResponse(response);
            return;
        } catch (RuntimeException exception) {
            SecurityContextHolder.clearContext();
            ModelAndView resolved = handlerExceptionResolver.resolveException(
                    request,
                    response,
                    null,
                    exception
            );
            if (resolved == null) {
                throw exception;
            }
            return;
        }
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails,
                null,
                userDetails.getAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private void writeUnauthorizedResponse(HttpServletResponse response) throws IOException {
        ErrorCode errorCode = ErrorCode.UNAUTHORIZED;
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setStatus(errorCode.getHttpStatus().value());
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiResponse.onFailure(errorCode.getCode(), errorCode.getMessage())
        );
    }
}
