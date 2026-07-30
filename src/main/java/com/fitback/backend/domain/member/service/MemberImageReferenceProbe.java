package com.fitback.backend.domain.member.service;

import com.fitback.backend.domain.image.service.ImageReferenceProbe;
import com.fitback.backend.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemberImageReferenceProbe implements ImageReferenceProbe {

    private final MemberRepository memberRepository;

    @Override
    public boolean exists(String imageId) {
        //회원 프로필에서 사용 중인 이미지는 정리 대상에서 제외
        return memberRepository.existsByProfileImageId(imageId);
    }
}
