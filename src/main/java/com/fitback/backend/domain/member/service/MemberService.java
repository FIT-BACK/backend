package com.fitback.backend.domain.member.service;

import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.closet.repository.ClosetSaveRepository;
import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.event.ImageReferencesReleasedEvent;
import com.fitback.backend.domain.image.repository.ImageRepository;
import com.fitback.backend.domain.image.service.ImageUploadService;
import com.fitback.backend.domain.lookbook.repository.LookbookLikeRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.member.entity.WithdrawalEmailBlock;
import com.fitback.backend.domain.member.init.WithdrawnMember;
import com.fitback.backend.domain.member.dto.MemberRequest;
import com.fitback.backend.domain.member.dto.MemberResponse;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.entity.MemberTag;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.member.repository.MemberTagRepository;
import com.fitback.backend.domain.member.repository.WithdrawalEmailBlockRepository;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.security.entity.AuthMember;
import com.fitback.backend.global.util.HmacUtil;
import com.fitback.backend.global.util.LowercaseNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;

    private final MemberTagRepository memberTagRepository;
    private final TagRepository tagRepository;

    private final AnalysisReportRepository analysisReportRepository;
    private final ImageRepository imageRepository;
    private final ImageUploadService imageUploadService;
    private final MemberProfileImageService memberProfileImageService;
    private final ClosetSaveRepository closetSaveRepository;
    private final LookbookRepository lookbookRepository;
    private final LookbookLikeRepository lookbookLikeRepository;

    private final PasswordEncoder passwordEncoder;

    private final WithdrawalEmailBlockRepository withdrawalEmailBlockRepository;
    private final HmacUtil hmacUtil;
    private final ApplicationEventPublisher eventPublisher;

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
        String profileImageUrl = dto.profileImageId() == null
                ? memberProfileImageService.resolveProfileImageUrl(member)
                : replaceProfileImage(member, dto.profileImageId());

        //관심 태그: 전달된 경우에만 교체 (미전송 시 기존 유지, [] 전체 해제)
        List<MemberTag> memberTagList;
        if(dto.tagIds() != null){
            memberTagList = setTags(member, dto.tagIds());
        } else {
            memberTagList = memberTagRepository.findByMemberIdFetchTag(member.getId());
        }

        return MemberResponse.toUpdateMemberResponse(member, memberTagList, profileImageUrl);
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
        Long analysisCount = analysisReportRepository.countByMemberIdAndDeletedAtIsNull(member.getId());
        Long uploadCount = lookbookRepository.countByMemberIdAndDeletedAtIsNull(member.getId());

        //현재 회원의 관심 태그 (fetch join으로 N+1 방지)
        List<MemberTag> memberTagList = memberTagRepository.findByMemberIdFetchTag(member.getId());

        String profileImageUrl = memberProfileImageService.resolveProfileImageUrl(member);
        return MemberResponse.toMyPageResponse(
                savedCount,
                analysisCount,
                uploadCount,
                member,
                memberTagList,
                profileImageUrl
        );
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
        String normalizedEmail = LowercaseNormalizer.normalize(deleteMember.getEmail());
        String hashedEmail = hmacUtil.hashHex(normalizedEmail);
        LocalDateTime blockedUntil = LocalDateTime.now().plusDays(30);

        //기존 차단 기록이 있으면 갱신, 없으면 신규 저장 (email_hash UNIQUE 충돌 방지)
        Optional<WithdrawalEmailBlock> existingBlock = withdrawalEmailBlockRepository.findByEmailHash(hashedEmail);
        if (existingBlock.isPresent()) {
            existingBlock.get().renew(blockedUntil);
        } else {
            withdrawalEmailBlockRepository.save(WithdrawalEmailBlock.create(hashedEmail, blockedUntil));
        }

        //회원 삭제 시 lookbook_like가 cascade 삭제되므로, 삭제 전에 좋아요 수를 먼저 보정
        List<Long> likedLookbookIds = lookbookLikeRepository.findLookbookIdsByMemberId(deleteMember.getId());
        if (!likedLookbookIds.isEmpty()) {
            lookbookRepository.decrementLikeCountByIds(likedLookbookIds);
        }

        //룩북은 삭제하지 않고 탈퇴 회원 계정으로 익명화 (member 삭제 전에)
        lookbookRepository.reassignToWithdrawnMember(deleteMember.getId(), withdrawnMember);

        //프로필 이미지 참조를 먼저 해제해야 이미지 소유자를 안전하게 변경할 수 있다.
        String profileImageId = deleteMember.getProfileImageId();
        deleteMember.clearProfileImageId();
        memberRepository.flush();

        //분석은 이미지와 복합 FK로 연결되어 있어 먼저 삭제하고,
        //룩북에 남을 수 있는 이미지는 탈퇴 회원 계정으로 재배정한다.
        analysisReportRepository.deleteAllByMemberId(deleteMember.getId());
        imageRepository.reassignToWithdrawnMember(deleteMember.getId(), withdrawnMember);

        //그 외(마이 클로젯·관심태그·본인 좋아요)는 cascade로 삭제
        memberRepository.delete(deleteMember);

        publishReleasedProfileImage(profileImageId);

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
        String profileImageUrl = dto.profileImageId() == null
                ? memberProfileImageService.resolveProfileImageUrl(member)
                : replaceProfileImage(member, dto.profileImageId());

        //태그 설정
        List<MemberTag> memberTagList = setTags(member, dto.tagIds());

        return MemberResponse.toOnboardingResponse(member, memberTagList, profileImageUrl);
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

    //닉네임 사용 가능 여부 확인
    @Transactional(readOnly = true)
    public MemberResponse.NicknameAvailabilityResponse checkNicknameAvailability(
            AuthMember authMember,
            String nickname
    ) {
        Member member = memberRepository.findById(authMember.getMember().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        boolean available = isNicknameAvailableFor(member, nickname);
        return MemberResponse.toNicknameAvailabilityResponse(nickname, available);
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

    private boolean isNicknameAvailableFor(Member member, String nickname) {
        if (WithdrawnMember.NICKNAME.equals(nickname)) {
            return false;
        }
        //현재 닉네임은 회원정보 수정 화면에서 그대로 유지할 수 있도록 사용 가능 처리
        if (nickname.equals(member.getNickname())) {
            return true;
        }
        return !memberRepository.existsByNickname(nickname);
    }

    private String replaceProfileImage(Member member, String profileImageId) {
        //업로드가 완료된 본인 소유 PROFILE 이미지만 회원 프로필에 연결
        Image profileImage = imageUploadService.activateProfileImage(
                member.getId(),
                profileImageId
        );
        String previousProfileImageId = member.getProfileImageId();
        member.changeProfileImageId(profileImage.getId());

        //교체된 이전 이미지는 트랜잭션 커밋 후 남은 참조를 확인해 정리
        if (!Objects.equals(previousProfileImageId, profileImage.getId())) {
            publishReleasedProfileImage(previousProfileImageId);
        }
        return imageUploadService.createReadUrl(profileImage);
    }

    private void publishReleasedProfileImage(String profileImageId) {
        if (profileImageId != null) {
            eventPublisher.publishEvent(
                    new ImageReferencesReleasedEvent(List.of(profileImageId))
            );
        }
    }

    //회원의 태그 설정 함수
    private List<MemberTag> setTags(Member member, List<Long> tagIds){

        //요청 으로 들어온 값에서 태그 중복에 대한 쿼리 방지
        List<Long> distinctIds = tagIds.stream().distinct().toList();
        List<Tag> tags = tagRepository.findAllById(distinctIds);

        //태그 id로 찾은 태그 개수와 태그 id의 수가 다르다면 잘못된 태그 id 포함
        if (tags.size() != distinctIds.size()) {
            throw new BusinessException(ErrorCode.MEMBER_TAG_NOT_FOUND);
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
