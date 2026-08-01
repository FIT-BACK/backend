package com.fitback.backend.global.security.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fitback.backend.global.security.service.CustomUserDetailsService;
import com.fitback.backend.global.security.util.JwtUtil;
import io.jsonwebtoken.MalformedJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import tools.jackson.databind.ObjectMapper;

class JwtAuthFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsUnauthorizedOnlyForInvalidToken() throws Exception {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        CustomUserDetailsService userDetailsService = mock(CustomUserDetailsService.class);
        HandlerExceptionResolver handlerExceptionResolver = mock(HandlerExceptionResolver.class);
        FilterChain filterChain = mock(FilterChain.class);
        when(jwtUtil.getAccessTokenSubject("invalid-token"))
                .thenThrow(new MalformedJwtException("invalid token"));
        JwtAuthFilter filter = new JwtAuthFilter(
                jwtUtil,
                userDetailsService,
                new ObjectMapper(),
                handlerExceptionResolver
        );
        MockHttpServletRequest request = requestWithBearerToken("invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("COMMON401_1");
        verifyNoInteractions(userDetailsService, handlerExceptionResolver, filterChain);
    }

    @Test
    void returnsUnauthorizedWhenTokenMemberDoesNotExist() throws Exception {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        CustomUserDetailsService userDetailsService = mock(CustomUserDetailsService.class);
        HandlerExceptionResolver handlerExceptionResolver = mock(HandlerExceptionResolver.class);
        FilterChain filterChain = mock(FilterChain.class);
        when(jwtUtil.getAccessTokenSubject("valid-token"))
                .thenReturn("missing@fitback.com");
        when(userDetailsService.loadUserByUsername("missing@fitback.com"))
                .thenThrow(new UsernameNotFoundException("member not found"));
        JwtAuthFilter filter = new JwtAuthFilter(
                jwtUtil,
                userDetailsService,
                new ObjectMapper(),
                handlerExceptionResolver
        );
        MockHttpServletRequest request = requestWithBearerToken("valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("COMMON401_1");
        verifyNoInteractions(handlerExceptionResolver, filterChain);
    }

    @Test
    void delegatesMemberLookupSystemFailureToCommonExceptionResolver() throws Exception {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        CustomUserDetailsService userDetailsService = mock(CustomUserDetailsService.class);
        HandlerExceptionResolver handlerExceptionResolver = mock(HandlerExceptionResolver.class);
        FilterChain filterChain = mock(FilterChain.class);
        DataAccessResourceFailureException databaseFailure =
                new DataAccessResourceFailureException("database unavailable");
        when(jwtUtil.getAccessTokenSubject("valid-token"))
                .thenReturn("member@fitback.com");
        when(userDetailsService.loadUserByUsername("member@fitback.com"))
                .thenThrow(databaseFailure);
        when(handlerExceptionResolver.resolveException(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                isNull(),
                same(databaseFailure)
        )).thenReturn(new ModelAndView());
        JwtAuthFilter filter = new JwtAuthFilter(
                jwtUtil,
                userDetailsService,
                new ObjectMapper(),
                handlerExceptionResolver
        );
        MockHttpServletRequest request = requestWithBearerToken("valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isNotEqualTo(401);
        verify(handlerExceptionResolver).resolveException(
                eq(request),
                eq(response),
                isNull(),
                same(databaseFailure)
        );
        verifyNoInteractions(filterChain);
    }

    @Test
    void propagatesDownstreamFilterFailureWithoutConvertingItToUnauthorized() throws Exception {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        CustomUserDetailsService userDetailsService = mock(CustomUserDetailsService.class);
        HandlerExceptionResolver handlerExceptionResolver = mock(HandlerExceptionResolver.class);
        FilterChain filterChain = mock(FilterChain.class);
        UserDetails userDetails = new User("member@fitback.com", "password", List.of());
        ServletException downstreamFailure = new ServletException("downstream failure");
        when(jwtUtil.getAccessTokenSubject("valid-token"))
                .thenReturn("member@fitback.com");
        when(userDetailsService.loadUserByUsername("member@fitback.com"))
                .thenReturn(userDetails);
        doThrow(downstreamFailure).when(filterChain).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        JwtAuthFilter filter = new JwtAuthFilter(
                jwtUtil,
                userDetailsService,
                new ObjectMapper(),
                handlerExceptionResolver
        );
        MockHttpServletRequest request = requestWithBearerToken("valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, filterChain))
                .isSameAs(downstreamFailure);
        assertThat(response.getStatus()).isNotEqualTo(401);
    }

    private MockHttpServletRequest requestWithBearerToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
