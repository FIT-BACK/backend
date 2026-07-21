package com.fitback.backend.domain.member.service;

import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.closet.repository.ClosetSaveRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.member.entity.WithdrawnEmailBlock;
import com.fitback.backend.domain.member.init.WithdrawnMember;
import com.fitback.backend.domain.member.dto.MemberRequest;
import com.fitback.backend.domain.member.dto.MemberResponse;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.entity.MemberTag;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.member.repository.MemberTagRepository;
import com.fitback.backend.domain.member.repository.WithdrawnEmailBlockRepository;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.security.entity.AuthMember;
import com.fitback.backend.global.util.HmacUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;

    private final MemberTagRepository memberTagRepository;
    private final TagRepository tagRepository;

    private final AnalysisReportRepository analysisReportRepository;
    private final ClosetSaveRepository closetSaveRepository;
    private final LookbookRepository lookbookRepository;

    private final PasswordEncoder passwordEncoder;

    private final WithdrawnEmailBlockRepository withdrawnEmailBlockRepository;
    private final HmacUtil hmacUtil;

    //회원정보 수정
    @Transactional
    public MemberResponse.UpdateMemberResponse updateMember(AuthMember authMember, MemberRequest.UpdateMemberRequest dto) {
        Member member = memberRepository.findById(authMember.getMember().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        //닉네임: 전달된 경우에만 변경 (미전송 시 기존 유지)
        if(dto.nickname() != null){
            applyNickname(member, dto.nickname());
        }

        //프로필 이미지: 전달된 경우에만 교체 (미전송/null 시 기존 유지)
        if(dto.profileImageUrl() != null){
            member.changeProfileImageUrl(dto.profileImageUrl());
        }

        //관심 태그: 전달된 경우에만 교체 (미전송 시 기존 유지, [] 전체 해제)
        List<MemberTag> memberTagList;
        if(dto.tagIds() != null){
            memberTagList = setTags(member, dto.tagIds());
        } else {
            memberTagList = memberTagRepository.findByMemberIdFetchTag(member.getId());
        }

        return MemberResponse.toUpdateMemberResponse(member, memberTagList);
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

        //현재 회원의 관심 태그 (fetch join으로 N+1 방지)
        List<MemberTag> memberTagList = memberTagRepository.findByMemberIdFetchTag(member.getId());

        return MemberResponse.toMyPageResponse(savedCount, analysisCount, uploadCount, member, memberTagList);
    }

    //회원 탈퇴
    @Transactional
    public void deleteAccount(AuthMember authMember) {
        Member deleteMember = memberRepository.findById(authMember.getMember().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        //탈퇴 회원 계정 조회 (익명 처리)
        Member withdrawnMember = memberRepository.findByEmail(WithdrawnMember.EMAIL)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));

        //동일 이메일 30일 재가입 방지
        String hashedEmail = hmacUtil.hashHex(deleteMember.getEmail());
        LocalDateTime blockedUntil = LocalDateTime.now().plusDays(30);

        //기존 차단 기록이 있으면 갱신, 없으면 신규 저장 (email_hash UNIQUE 충돌 방지)
        Optional<WithdrawnEmailBlock> existingBlock = withdrawnEmailBlockRepository.findByEmailHash(hashedEmail);
        if (existingBlock.isPresent()) {
            existingBlock.get().renew(blockedUntil);
        } else {
            withdrawnEmailBlockRepository.save(WithdrawnEmailBlock.create(hashedEmail, blockedUntil));
        }

        //룩북은 삭제하지 않고 탈퇴 회원 계정으로 익명화 (member 삭제 전에)
        lookbookRepository.reassignToWithdrawnMember(deleteMember.getId(), withdrawnMember);

        //그 외(마이 클로젯·분석·관심태그·본인 좋아요)는 cascade로 삭제
        memberRepository.delete(deleteMember);


    }

    //회원가입 프로필 설정
    @Transactional
    public MemberResponse.OnboardingResponse onboarding(
            AuthMember authMember,
            MemberRequest.OnboardingRequest dto)
    {
        Member member = memberRepository.findById(authMember.getMember().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        //닉네임 설정
        applyNickname(member,dto.nickname());

        //프로필 이미지가 전달된 경우 프로필 이미지 설정
        if(dto.profileImageUrl() != null){
            member.changeProfileImageUrl(dto.profileImageUrl());
        }

        //태그 설정
        List<MemberTag> memberTagList = setTags(member, dto.tagIds());

        return MemberResponse.toOnboardingResponse(member, memberTagList);
    }

    //회원 태그 변경
    @Transactional
    public MemberResponse.UpdateTagsResponse updateTags(
            AuthMember authMember,
            MemberRequest.UpdateTagsRequest dto
    ){
        Member member = memberRepository.findById(authMember.getMember().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        //태그 변경
        List<MemberTag> memberTagList = setTags(member, dto.tagIds());

        return MemberResponse.toUpdateTagsResponse(memberTagList);
    }


    //닉네임의 중복을 확인한 후 닉네임을 설정하는 함수
    private void applyNickname(Member member, String newNickname){
        //닉네임이 비어있다면
        if(newNickname.isBlank()){
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        if (WithdrawnMember.NICKNAME.equals(newNickname)) {
            throw new BusinessException(ErrorCode.NICKNAME_ALREADY_EXISTS);
        }
        //현재 닉네임과 동일하다면 변경 X
        if(newNickname.equals(member.getNickname())){
            return;
        }
        //같은 닉네임을 가진 다른 사람이 있다면
        if(memberRepository.existsByNickname(newNickname)){
            throw new BusinessException(ErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        member.changeNickname(newNickname);
    }

    //회원의 태그 설정 함수
    private List<MemberTag> setTags(Member member, List<Long> tagIds){

        //요청 으로 들어온 값에서 태그 중복에 대한 쿼리 방지
        List<Long> distinctIds = tagIds.stream().distinct().toList();
        List<Tag> tags = tagRepository.findAllById(distinctIds);

        //태그 id로 찾은 태그 개수와 태그 id의 수가 다르다면 잘못된 태그 id 포함
        if (tags.size() != distinctIds.size()) {
            throw new BusinessException(ErrorCode.TAG_NOT_FOUND);
        }

        //기존 태그 삭제
        memberTagRepository.deleteByMemberId(member.getId());

        //delete -> save 의 순서를 보장
        memberTagRepository.flush();

        List<MemberTag> memberTags = tags.stream()
                .map(t -> MemberTag.create(member, t))
                .toList();

        return memberTagRepository.saveAll(memberTags);
    }
}
