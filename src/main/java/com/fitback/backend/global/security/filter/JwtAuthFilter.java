package com.fitback.backend.global.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.service.CustomUserDetailsService;
import com.fitback.backend.global.security.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService customUserDetailsService;


    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        try {
            //토큰 가져오기
            String token = request.getHeader("Authorization");
            //token이 없거나 Bearer가 아니면 넘기기
            if (token == null || !token.startsWith("Bearer ")) {
                filterChain.doFilter(request, response);
                return;
            }
            //Bearer이면 추출
            token = token.replace("Bearer ", "");
            //Token 검증
            if(jwtUtil.isValid(token)){
                //AccessToken 여부 확인
                if(jwtUtil.isAccessToken(token)){
                    //토큰에서 이메일 추출
                    String email = jwtUtil.getEmailFromToken(token);
                    //인증 객체 생성
                    UserDetails userDetails = customUserDetailsService.loadUserByUsername(email);
                    Authentication auth = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );
                    //인증 후 SecurityContextHolder에 넣기
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }

            }
            filterChain.doFilter(request, response);
        } catch (Exception e){
            ObjectMapper mapper = new ObjectMapper();
            ErrorCode code = ErrorCode.UNAUTHORIZED;

            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(code.getHttpStatus().value());

            ApiResponse<Void> errorResponse = ApiResponse.onFailure(code.getCode(), code.getMessage(), null);

            mapper.writeValue(response.getOutputStream(), errorResponse);
        }
    }
}
