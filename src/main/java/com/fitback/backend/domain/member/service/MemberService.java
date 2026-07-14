package com.fitback.backend.domain.member.service;

import com.fitback.backend.domain.member.dto.MemberRequest;
import com.fitback.backend.domain.member.dto.MemberResponse;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.security.entity.AuthMember;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;

    //회원정보 수정
    @Transactional
    public MemberResponse.UpdateMemberResponse updateMember(AuthMember authMember, MemberRequest.UpdateMemberRequest dto) {
        Member member = memberRepository.findById(authMember.getMember().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        //닉네임: 전달된 경우에만 변경 (미전송 시 기존 유지)
        if(dto.nickname() != null){
            String newNickname = dto.nickname();
            if(newNickname.isBlank()){
                throw new BusinessException(ErrorCode.BAD_REQUEST);
            }
            //현재 닉네임과 다를 때만 중복 검사 후 변경 (본인 닉네임 허용)
            if(!newNickname.equals(member.getNickname())){
                if(memberRepository.existsByNickname(newNickname)){
                    throw new BusinessException(ErrorCode.NICKNAME_ALREADY_EXISTS);
                }
                member.changeNickname(newNickname);
            }
        }

        //프로필 이미지: 전달된 경우에만 교체 (미전송/null 시 기존 유지)
        if(dto.profileImageUrl() != null){
            member.changeProfileImageUrl(dto.profileImageUrl());
        }

        return MemberResponse.toUpdateMemberResponse(member);
    }



}
