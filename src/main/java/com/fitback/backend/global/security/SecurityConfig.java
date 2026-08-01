package com.fitback.backend.global.security;

import com.fitback.backend.global.security.exception.CustomAccessDenied;
import com.fitback.backend.global.security.exception.CustomEntryPoint;
import com.fitback.backend.global.security.filter.JwtAuthFilter;
import com.fitback.backend.global.security.handler.OAuthFailureHandler;
import com.fitback.backend.global.security.handler.OAuthSuccessHandler;
import com.fitback.backend.global.security.service.CustomOAuthService;
import com.fitback.backend.global.security.service.CustomUserDetailsService;
import com.fitback.backend.global.security.util.JwtUtil;
import jakarta.servlet.DispatcherType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(CorsProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService customUserDetailsService;
    private final CorsProperties corsProperties;
    private final ObjectMapper objectMapper;

    private static final String[] SWAGGER_URLS = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**"
    };

    private static final String[] NO_AUTH_URLS = {
            "/api/v1/auth/sign",
            "/api/v1/auth/login",
            "/api/v1/auth/token/refresh",
            "/api/v1/auth/token/exchange",
            "/api/v1/auth/password-reset/request",
            "/api/v1/auth/password-reset",
            "/api/v1/auth/oauth2/**",
            "/api/v1/auth/callback/**"
    };

    private static final String[] PUBLIC_READ_URLS = {
            "/api/v1/trends/**",
            "/api/v1/tags/**",
            "/api/v1/content-search",
            "/api/v1/lookbooks",
            "/api/v1/lookbooks/*"
    };

    private static final String[] HEALTH_URLS = {
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            CustomOAuthService customOAuthService,
            OAuthSuccessHandler oAuthSuccessHandler,
            OAuthFailureHandler oAuthFailureHandler,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver
    ) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(SWAGGER_URLS).permitAll()
                        .requestMatchers(HEALTH_URLS).permitAll()
                        .requestMatchers(NO_AUTH_URLS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_READ_URLS).permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(auth -> auth.baseUri("/api/v1/auth/oauth2"))
                        .redirectionEndpoint(redirect -> redirect.baseUri("/api/v1/auth/callback/*"))
                        .userInfoEndpoint(userInfo -> userInfo.userService(customOAuthService))
                        .successHandler(oAuthSuccessHandler)
                        .failureHandler(oAuthFailureHandler)
                )
                .addFilterBefore(
                        new JwtAuthFilter(
                                jwtUtil,
                                customUserDetailsService,
                                objectMapper,
                                handlerExceptionResolver
                        ),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exception -> exception
                        .accessDeniedHandler(customAccessDenied())
                        .authenticationEntryPoint(customEntryPoint()))
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
        ));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE
        ));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CustomAccessDenied customAccessDenied() {
        return new CustomAccessDenied();
    }

    @Bean
    public CustomEntryPoint customEntryPoint() {
        return new CustomEntryPoint();
    }

}
