package com.fitback.backend.global.security;

import com.fitback.backend.global.security.exception.CustomAccessDenied;
import com.fitback.backend.global.security.exception.CustomEntryPoint;
import com.fitback.backend.global.security.filter.JwtAuthFilter;
import com.fitback.backend.global.security.service.CustomUserDetailsService;
import com.fitback.backend.global.security.util.JwtUtil;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService customUserDetailsService;

    private static final String[] SWAGGER_URLS = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**"
    };

    private static final String[] NO_AUTH_URLS = {
            "/api/v1/auth/sign",
            "/api/v1/auth/login",
            "/api/v1/auth/token/refresh"
              
    private static final String[] HEALTH_URLS = {
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness"
    };

    @Bean
      public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
          return http
                  .csrf(AbstractHttpConfigurer::disable)
                  .formLogin(AbstractHttpConfigurer::disable)
                  .httpBasic(AbstractHttpConfigurer::disable)
                  .sessionManagement(session ->
                          session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                  .authorizeHttpRequests(authorize -> authorize
                          .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                          .requestMatchers(SWAGGER_URLS).permitAll()
                          .requestMatchers(HEALTH_URLS).permitAll()
                          .requestMatchers(NO_AUTH_URLS).permitAll()
                          .anyRequest().authenticated())
                  .addFilterBefore(
                          new JwtAuthFilter(jwtUtil, customUserDetailsService),
                          UsernamePasswordAuthenticationFilter.class)
                  .exceptionHandling(exception -> exception
                          .accessDeniedHandler(customAccessDenied())
                          .authenticationEntryPoint(customEntryPoint()))
                  .build();
      }

    @Bean
    public PasswordEncoder passwordEncoder(){
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
