package com.fitback.backend.global.security.entity;

import com.fitback.backend.domain.member.entity.Member;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Getter
@RequiredArgsConstructor
public class OAuthMember implements OAuth2User {

    private final Member member;                    // SuccessHandler가 꺼내 쓸 회원
    private final Map<String, Object> attributes;   // 카카오 원본
    private final boolean newMember;                // 회원 신규 가입 여부

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + member.getRole().name()));
    }

    @Override
    public String getName() {
        return member.getSocialUid();   // 유니크한 아무 식별값이면 됨
    }
}