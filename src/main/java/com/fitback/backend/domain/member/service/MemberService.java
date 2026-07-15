package com.fitback.backend.domain.member.service;

import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.closet.repository.ClosetSaveRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.member.dto.MemberRequest;
import com.fitback.backend.domain.member.dto.MemberResponse;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.security.entity.AuthMember;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final AnalysisReportRepository analysisReportRepository;
    private final ClosetSaveRepository closetSaveRepository;
    private final LookbookRepository lookbookRepository;

    private final PasswordEncoder passwordEncoder;

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


    //비밀번호 변경
    @Transactional
    public void changePassword(AuthMember authMember, MemberRequest.ChangePasswordRequest dto) {
        Member member = memberRepository.findById(authMember.getMember().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        //Email 로그인이 아닐 경우(소셜 로그인은 비밀번호 X)
        if(!member.getLoginProvider().equals(LoginProvider.EMAIL))
            throw new BusinessException(ErrorCode.PASSWORD_CHANGE_NOT_ALLOWED);

        //비밀번호가 일치하지 않을 경우
        if(!passwordEncoder.matches(dto.currentPassword(), member.getPassword()))
            throw new BusinessException(ErrorCode.PASSWORD_MISMATCH);

        String encodedPassword = passwordEncoder.encode(dto.newPassword());
        member.changePassword(encodedPassword);

    }

    //마이페이지
    @Transactional(readOnly = true)
    public MemberResponse.MyPageResponse myPage(AuthMember authMember) {

        //member entity에 대해 수정은 없으므로 UserDetails 객체에서 바로 얻어와 사용(쿼리 x)
        Member member = authMember.getMember();

        Long savedCount = closetSaveRepository.countByMemberId(member.getId());
        Long analysisCount = analysisReportRepository.countByMemberId(member.getId());
        Long uploadCount = lookbookRepository.countByMemberId(member.getId());

        return MemberResponse.toMyPageResponse(savedCount, analysisCount, uploadCount, member);
    }

    //회원 탈퇴
    @Transactional
    public void deleteAccount(AuthMember authMember) {
        Member deleteMember = memberRepository.findById(authMember.getMember().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        memberRepository.delete(deleteMember);
    }
}
