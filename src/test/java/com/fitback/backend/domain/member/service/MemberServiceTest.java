package com.fitback.backend.domain.member.service;

import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.closet.repository.ClosetSaveRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.member.dto.MemberRequest;
import com.fitback.backend.domain.member.dto.MemberResponse;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.entity.MemberTag;
import com.fitback.backend.domain.member.entity.WithdrawalEmailBlock;
import com.fitback.backend.domain.member.init.WithdrawnMember;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.member.repository.MemberTagRepository;
import com.fitback.backend.domain.member.repository.WithdrawalEmailBlockRepository;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.security.entity.AuthMember;
import com.fitback.backend.global.util.HmacUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private MemberTagRepository memberTagRepository;
    @Mock
    private TagRepository tagRepository;
    @Mock
    private AnalysisReportRepository analysisReportRepository;
    @Mock
    private ClosetSaveRepository closetSaveRepository;
    @Mock
    private LookbookRepository lookbookRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private WithdrawalEmailBlockRepository withdrawalEmailBlockRepository;
    @Mock
    private HmacUtil hmacUtil;

    //memberService 실제 객체 생성 후 mock 객체 주입
    @InjectMocks
    private MemberService memberService;

    //id는 db에서 채우는데 실제 db를 사용하지 않으므로 id 강제 세팅
    private Member createTestMember(Long id, String email, String nickname, String password, LoginProvider provider){
        Member member = Member.create(email, nickname, password, provider);
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    //응답 매핑에 tag id/name/type이 필요하므로 id까지 세팅한 태그 생성
    private Tag createTag(Long id, String name, TagType type){
        Tag tag = Tag.create(name, type);
        ReflectionTestUtils.setField(tag, "id", id);
        return tag;
    }

    // ---------- updateMember ----------

    //회원정보 수정 - 닉네임만 전달되면 닉네임만 변경, 태그 교체 미발생
    @Test
    void updateMemberNicknameOnlyTest(){
        Member member = createTestMember(1L, "test@fitback.com", "oldNick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        MemberRequest.UpdateMemberRequest request = new MemberRequest.UpdateMemberRequest("newNick", null, null);

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        //중복 아님
        when(memberRepository.existsByNickname("newNick")).thenReturn(false);
        //tagIds 미전송 -> 기존 태그 유지 조회
        when(memberTagRepository.findByMemberIdFetchTag(1L)).thenReturn(List.of());

        MemberResponse.UpdateMemberResponse response = memberService.updateMember(authMember, request);

        assertThat(member.getNickname()).isEqualTo("newNick");
        assertThat(response.nickname()).isEqualTo("newNick");
        //태그 교체 경로 미발생
        verify(memberTagRepository, never()).deleteByMemberId(anyLong());
    }

    //회원정보 수정 - 프로필 이미지만 전달되면 이미지만 변경, 닉네임 중복검사 미발생
    @Test
    void updateMemberProfileImageOnlyTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        MemberRequest.UpdateMemberRequest request = new MemberRequest.UpdateMemberRequest(null, "http://img/new.png", null);

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberTagRepository.findByMemberIdFetchTag(1L)).thenReturn(List.of());

        MemberResponse.UpdateMemberResponse response = memberService.updateMember(authMember, request);

        assertThat(member.getProfileImageUrl()).isEqualTo("http://img/new.png");
        assertThat(response.profileImageUrl()).isEqualTo("http://img/new.png");
        //닉네임 미전송 -> 중복검사 안 함
        verify(memberRepository, never()).existsByNickname(anyString());
    }

    //회원정보 수정 - tagIds가 null이면 기존 태그 그대로 유지
    @Test
    void updateMemberKeepTagsWhenNullTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        Tag tag = createTag(10L, "미니멀", TagType.SILHOUETTE);
        MemberTag existing = MemberTag.create(member, tag);
        MemberRequest.UpdateMemberRequest request = new MemberRequest.UpdateMemberRequest(null, null, null);

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberTagRepository.findByMemberIdFetchTag(1L)).thenReturn(List.of(existing));

        MemberResponse.UpdateMemberResponse response = memberService.updateMember(authMember, request);

        assertThat(response.tags()).hasSize(1);
        assertThat(response.tags().get(0).tagId()).isEqualTo(10L);
        //교체 미발생
        verify(memberTagRepository, never()).deleteByMemberId(anyLong());
    }

    //회원정보 수정 - tagIds가 빈 배열이면 관심 태그 전체 해제
    @Test
    void updateMemberClearTagsWhenEmptyTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        MemberRequest.UpdateMemberRequest request = new MemberRequest.UpdateMemberRequest(null, null, List.of());

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        //빈 요청 -> 조회 태그 없음, 개수 일치(0==0)로 통과
        when(tagRepository.findAllById(anyList())).thenReturn(List.of());
        //저장 인자 그대로 반환
        when(memberTagRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        MemberResponse.UpdateMemberResponse response = memberService.updateMember(authMember, request);

        assertThat(response.tags()).isEmpty();
        //기존 태그 삭제 후 빈 목록 저장
        verify(memberTagRepository).deleteByMemberId(1L);
        verify(memberTagRepository).flush();
    }

    //회원정보 수정 - 중복 tag id는 dedup되어 1개만 저장
    @Test
    void updateMemberDedupTagIdsTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        Tag tag = createTag(10L, "미니멀", TagType.SILHOUETTE);
        MemberRequest.UpdateMemberRequest request = new MemberRequest.UpdateMemberRequest(null, null, List.of(10L, 10L));

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        //distinct된 [10L]로 조회되어 1개 반환
        when(tagRepository.findAllById(anyList())).thenReturn(List.of(tag));
        when(memberTagRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        MemberResponse.UpdateMemberResponse response = memberService.updateMember(authMember, request);

        //saveAll에 실제 넘어간 태그 개수 검증
        ArgumentCaptor<List<MemberTag>> captor = ArgumentCaptor.forClass(List.class);
        verify(memberTagRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(response.tags()).hasSize(1);
    }

    //회원정보 수정 - 존재하지 않는 tag id 포함 시 MEMBER_TAG_NOT_FOUND
    @Test
    void updateMemberInvalidTagTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        Tag tag = createTag(10L, "미니멀", TagType.SILHOUETTE);
        MemberRequest.UpdateMemberRequest request = new MemberRequest.UpdateMemberRequest(null, null, List.of(10L, 999L));

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        //요청 2개 중 1개만 조회됨 -> 개수 불일치
        when(tagRepository.findAllById(anyList())).thenReturn(List.of(tag));

        assertThatThrownBy(() -> memberService.updateMember(authMember, request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.MEMBER_TAG_NOT_FOUND));

        //검증 실패 시 삭제 미발생
        verify(memberTagRepository, never()).deleteByMemberId(anyLong());
    }

    //회원정보 수정 - 다른 회원이 쓰는 닉네임이면 NICKNAME_ALREADY_EXISTS
    @Test
    void updateMemberDuplicateNicknameTest(){
        Member member = createTestMember(1L, "test@fitback.com", "oldNick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        MemberRequest.UpdateMemberRequest request = new MemberRequest.UpdateMemberRequest("takenNick", null, null);

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        //다른 회원이 사용 중
        when(memberRepository.existsByNickname("takenNick")).thenReturn(true);

        assertThatThrownBy(() -> memberService.updateMember(authMember, request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.NICKNAME_ALREADY_EXISTS));
    }

    //회원정보 수정 - 현재 닉네임과 같으면 중복검사 없이 유지
    @Test
    void updateMemberSameNicknameSkipTest(){
        Member member = createTestMember(1L, "test@fitback.com", "sameNick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        MemberRequest.UpdateMemberRequest request = new MemberRequest.UpdateMemberRequest("sameNick", null, null);

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberTagRepository.findByMemberIdFetchTag(1L)).thenReturn(List.of());

        memberService.updateMember(authMember, request);

        assertThat(member.getNickname()).isEqualTo("sameNick");
        //동일 닉이라 중복검사 안 함
        verify(memberRepository, never()).existsByNickname(anyString());
    }

    //회원정보 수정 - 공백 닉네임이면 BAD_REQUEST
    @Test
    void updateMemberBlankNicknameTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        MemberRequest.UpdateMemberRequest request = new MemberRequest.UpdateMemberRequest("   ", null, null);

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> memberService.updateMember(authMember, request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.BAD_REQUEST));
    }

    //회원정보 수정 - 회원 조회 실패 시 NOT_FOUND
    @Test
    void updateMemberMemberNotFoundTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        MemberRequest.UpdateMemberRequest request = new MemberRequest.UpdateMemberRequest("newNick", null, null);

        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.updateMember(authMember, request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---------- changePassword ----------

    //비밀번호 변경 - 이메일 회원은 성공, 암호화된 새 비밀번호로 저장
    @Test
    void changePasswordSuccessTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "oldEncoded", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        MemberRequest.ChangePasswordRequest request = new MemberRequest.ChangePasswordRequest("currentPw", "newPw");

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        //현재 비밀번호 일치
        when(passwordEncoder.matches("currentPw", "oldEncoded")).thenReturn(true);
        when(passwordEncoder.encode("newPw")).thenReturn("newEncoded");

        memberService.changePassword(authMember, request);

        //raw가 아닌 암호화된 값으로 교체
        assertThat(member.getPassword()).isEqualTo("newEncoded");
    }

    //비밀번호 변경 - 현재 비밀번호 불일치 시 PASSWORD_MISMATCH
    @Test
    void changePasswordMismatchTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "oldEncoded", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        MemberRequest.ChangePasswordRequest request = new MemberRequest.ChangePasswordRequest("wrongPw", "newPw");

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        //현재 비밀번호 불일치
        when(passwordEncoder.matches("wrongPw", "oldEncoded")).thenReturn(false);

        assertThatThrownBy(() -> memberService.changePassword(authMember, request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.PASSWORD_MISMATCH));

        //불일치 시 encode 미호출
        verify(passwordEncoder, never()).encode(anyString());
    }

    //비밀번호 변경 - 소셜 로그인 회원이면 PASSWORD_CHANGE_NOT_ALLOWED
    @Test
    void changePasswordSocialNotAllowedTest(){
        //카카오 회원은 비밀번호가 없음
        Member social = createTestMember(1L, "kakao@fitback.com", "nick", null, LoginProvider.KAKAO);
        AuthMember authMember = new AuthMember(social);
        MemberRequest.ChangePasswordRequest request = new MemberRequest.ChangePasswordRequest("currentPw", "newPw");

        when(memberRepository.findById(1L)).thenReturn(Optional.of(social));

        assertThatThrownBy(() -> memberService.changePassword(authMember, request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.PASSWORD_CHANGE_NOT_ALLOWED));

        //비밀번호 비교 자체를 안 함
        verify(passwordEncoder, never()).matches(anyString(), any());
    }

    //비밀번호 변경 - 회원 조회 실패 시 NOT_FOUND
    @Test
    void changePasswordMemberNotFoundTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        MemberRequest.ChangePasswordRequest request = new MemberRequest.ChangePasswordRequest("currentPw", "newPw");

        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.changePassword(authMember, request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---------- myPage ----------

    //마이페이지 - 기본 정보, 관심 태그, count 3개가 repository 결과대로 반환
    @Test
    void myPageSuccessTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        member.changeProfileImageUrl("http://img/p.png");
        AuthMember authMember = new AuthMember(member);
        Tag tag = createTag(10L, "미니멀", TagType.SILHOUETTE);
        MemberTag memberTag = MemberTag.create(member, tag);

        when(closetSaveRepository.countByMemberId(1L)).thenReturn(3L);
        when(analysisReportRepository.countByMemberIdAndDeletedAtIsNull(1L)).thenReturn(5L);
        when(lookbookRepository.countByMemberIdAndDeletedAtIsNull(1L)).thenReturn(7L);
        when(memberTagRepository.findByMemberIdFetchTag(1L)).thenReturn(List.of(memberTag));

        MemberResponse.MyPageResponse response = memberService.myPage(authMember);

        assertThat(response.memberId()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("test@fitback.com");
        assertThat(response.nickname()).isEqualTo("nick");
        assertThat(response.profileImageUrl()).isEqualTo("http://img/p.png");
        assertThat(response.loginProvider()).isEqualTo(LoginProvider.EMAIL);
        assertThat(response.savedCount()).isEqualTo(3L);
        assertThat(response.analysisCount()).isEqualTo(5L);
        assertThat(response.uploadCount()).isEqualTo(7L);
        assertThat(response.tags()).hasSize(1);
        assertThat(response.tags().get(0).tagName()).isEqualTo("미니멀");
        //authMember의 member를 바로 써서 재조회 없음
        verify(memberRepository, never()).findById(anyLong());
    }

    // ---------- onboarding ----------

    //온보딩 - 닉네임/프로필/태그 설정 성공
    @Test
    void onboardingSuccessTest(){
        Member member = createTestMember(1L, "test@fitback.com", "tempNick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        Tag tag = createTag(10L, "미니멀", TagType.SILHOUETTE);
        MemberRequest.OnboardingRequest request = new MemberRequest.OnboardingRequest("realNick", "http://img/p.png", List.of(10L));

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberRepository.existsByNickname("realNick")).thenReturn(false);
        when(tagRepository.findAllById(anyList())).thenReturn(List.of(tag));
        when(memberTagRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        MemberResponse.OnboardingResponse response = memberService.onboarding(authMember, request);

        assertThat(member.getNickname()).isEqualTo("realNick");
        assertThat(member.getProfileImageUrl()).isEqualTo("http://img/p.png");
        assertThat(response.tags()).hasSize(1);
        assertThat(response.tags().get(0).tagId()).isEqualTo(10L);
        verify(memberTagRepository).deleteByMemberId(1L);
    }

    //온보딩 - 탈퇴 회원 예약 닉네임("탈퇴한 유저") 사용 시 NICKNAME_ALREADY_EXISTS
    @Test
    void onboardingReservedNicknameTest(){
        Member member = createTestMember(1L, "test@fitback.com", "tempNick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        MemberRequest.OnboardingRequest request = new MemberRequest.OnboardingRequest(WithdrawnMember.NICKNAME, null, List.of());

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> memberService.onboarding(authMember, request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.NICKNAME_ALREADY_EXISTS));

        //예약어 단계에서 막혀 중복검사 미도달
        verify(memberRepository, never()).existsByNickname(anyString());
    }

    //온보딩 - 닉네임 중복 시 NICKNAME_ALREADY_EXISTS
    @Test
    void onboardingDuplicateNicknameTest(){
        Member member = createTestMember(1L, "test@fitback.com", "tempNick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        MemberRequest.OnboardingRequest request = new MemberRequest.OnboardingRequest("takenNick", null, List.of());

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberRepository.existsByNickname("takenNick")).thenReturn(true);

        assertThatThrownBy(() -> memberService.onboarding(authMember, request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.NICKNAME_ALREADY_EXISTS));
    }

    //온보딩 - 존재하지 않는 태그 포함 시 MEMBER_TAG_NOT_FOUND
    @Test
    void onboardingInvalidTagTest(){
        Member member = createTestMember(1L, "test@fitback.com", "tempNick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        Tag tag = createTag(10L, "미니멀", TagType.SILHOUETTE);
        MemberRequest.OnboardingRequest request = new MemberRequest.OnboardingRequest("realNick", null, List.of(10L, 999L));

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberRepository.existsByNickname("realNick")).thenReturn(false);
        //요청 2개 중 1개만 조회됨
        when(tagRepository.findAllById(anyList())).thenReturn(List.of(tag));

        assertThatThrownBy(() -> memberService.onboarding(authMember, request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.MEMBER_TAG_NOT_FOUND));
    }

    //온보딩 - 빈 태그 배열은 허용
    @Test
    void onboardingEmptyTagsAllowedTest(){
        Member member = createTestMember(1L, "test@fitback.com", "tempNick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        MemberRequest.OnboardingRequest request = new MemberRequest.OnboardingRequest("realNick", null, List.of());

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberRepository.existsByNickname("realNick")).thenReturn(false);
        when(tagRepository.findAllById(anyList())).thenReturn(List.of());
        when(memberTagRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        MemberResponse.OnboardingResponse response = memberService.onboarding(authMember, request);

        assertThat(member.getNickname()).isEqualTo("realNick");
        assertThat(response.tags()).isEmpty();
    }

    //온보딩 - 회원 조회 실패 시 NOT_FOUND
    @Test
    void onboardingMemberNotFoundTest(){
        Member member = createTestMember(1L, "test@fitback.com", "tempNick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        MemberRequest.OnboardingRequest request = new MemberRequest.OnboardingRequest("realNick", null, List.of());

        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.onboarding(authMember, request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---------- updateTags ----------

    //관심 태그 수정 - 기존 태그 삭제 후 새 태그로 교체
    @Test
    void updateTagsReplaceTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        Tag tag1 = createTag(10L, "미니멀", TagType.SILHOUETTE);
        Tag tag2 = createTag(20L, "블랙", TagType.COLOR);
        MemberRequest.UpdateTagsRequest request = new MemberRequest.UpdateTagsRequest(List.of(10L, 20L));

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(tagRepository.findAllById(anyList())).thenReturn(List.of(tag1, tag2));
        when(memberTagRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        MemberResponse.UpdateTagsResponse response = memberService.updateTags(authMember, request);

        //삭제 -> flush -> 저장 순서 보장
        InOrder inOrder = inOrder(memberTagRepository);
        inOrder.verify(memberTagRepository).deleteByMemberId(1L);
        inOrder.verify(memberTagRepository).flush();
        inOrder.verify(memberTagRepository).saveAll(anyList());
        assertThat(response.tags()).hasSize(2);
    }

    //관심 태그 수정 - 빈 배열이면 전체 해제
    @Test
    void updateTagsEmptyClearTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        MemberRequest.UpdateTagsRequest request = new MemberRequest.UpdateTagsRequest(List.of());

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(tagRepository.findAllById(anyList())).thenReturn(List.of());
        when(memberTagRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        MemberResponse.UpdateTagsResponse response = memberService.updateTags(authMember, request);

        assertThat(response.tags()).isEmpty();
        verify(memberTagRepository).deleteByMemberId(1L);
    }

    //관심 태그 수정 - 중복 tag id는 dedup되어 1개만 저장
    @Test
    void updateTagsDedupTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        Tag tag = createTag(10L, "미니멀", TagType.SILHOUETTE);
        MemberRequest.UpdateTagsRequest request = new MemberRequest.UpdateTagsRequest(List.of(10L, 10L));

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(tagRepository.findAllById(anyList())).thenReturn(List.of(tag));
        when(memberTagRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        MemberResponse.UpdateTagsResponse response = memberService.updateTags(authMember, request);

        ArgumentCaptor<List<MemberTag>> captor = ArgumentCaptor.forClass(List.class);
        verify(memberTagRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(response.tags()).hasSize(1);
    }

    //관심 태그 수정 - 존재하지 않는 태그 포함 시 MEMBER_TAG_NOT_FOUND
    @Test
    void updateTagsInvalidTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        Tag tag = createTag(10L, "미니멀", TagType.SILHOUETTE);
        MemberRequest.UpdateTagsRequest request = new MemberRequest.UpdateTagsRequest(List.of(10L, 999L));

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(tagRepository.findAllById(anyList())).thenReturn(List.of(tag));

        assertThatThrownBy(() -> memberService.updateTags(authMember, request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.MEMBER_TAG_NOT_FOUND));

        verify(memberTagRepository, never()).deleteByMemberId(anyLong());
    }

    //관심 태그 수정 - 회원 조회 실패 시 NOT_FOUND
    @Test
    void updateTagsMemberNotFoundTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);
        MemberRequest.UpdateTagsRequest request = new MemberRequest.UpdateTagsRequest(List.of(10L));

        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.updateTags(authMember, request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---------- checkNicknameAvailability ----------

    //닉네임 사용 가능 여부 확인 - 사용 중인 회원이 없으면 사용 가능
    @Test
    void checkNicknameAvailabilityAvailableTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberRepository.existsByNickname("newNick")).thenReturn(false);

        MemberResponse.NicknameAvailabilityResponse response =
                memberService.checkNicknameAvailability(authMember, "newNick");

        assertThat(response.nickname()).isEqualTo("newNick");
        assertThat(response.available()).isTrue();
    }

    //닉네임 사용 가능 여부 확인 - 다른 회원이 사용 중이면 사용 불가
    @Test
    void checkNicknameAvailabilityDuplicateTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberRepository.existsByNickname("takenNick")).thenReturn(true);

        MemberResponse.NicknameAvailabilityResponse response =
                memberService.checkNicknameAvailability(authMember, "takenNick");

        assertThat(response.nickname()).isEqualTo("takenNick");
        assertThat(response.available()).isFalse();
    }

    //닉네임 사용 가능 여부 확인 - 현재 내 닉네임은 중복 검사 없이 사용 가능
    @Test
    void checkNicknameAvailabilityOwnNicknameTest(){
        Member member = createTestMember(1L, "test@fitback.com", "sameNick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        MemberResponse.NicknameAvailabilityResponse response =
                memberService.checkNicknameAvailability(authMember, "sameNick");

        assertThat(response.available()).isTrue();
        verify(memberRepository, never()).existsByNickname(anyString());
    }

    //닉네임 사용 가능 여부 확인 - 탈퇴 회원 예약 닉네임은 사용 불가
    @Test
    void checkNicknameAvailabilityReservedNicknameTest(){
        Member member = createTestMember(1L, "test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(member);

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        MemberResponse.NicknameAvailabilityResponse response =
                memberService.checkNicknameAvailability(authMember, WithdrawnMember.NICKNAME);

        assertThat(response.available()).isFalse();
        verify(memberRepository, never()).existsByNickname(anyString());
    }

    // ---------- deleteAccount ----------

    //회원 탈퇴 - 신규 이메일 HMAC 차단 기록 생성, blockedUntil은 약 30일 뒤
    @Test
    void deleteAccountNewBlockTest(){
        Member deleteMember = createTestMember(1L, "user@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(deleteMember);
        Member withdrawnMember = createTestMember(99L, WithdrawnMember.EMAIL, WithdrawnMember.NICKNAME, null, LoginProvider.EMAIL);

        when(memberRepository.findById(1L)).thenReturn(Optional.of(deleteMember));
        when(memberRepository.findByEmail(WithdrawnMember.EMAIL)).thenReturn(Optional.of(withdrawnMember));
        when(hmacUtil.hashHex("user@fitback.com")).thenReturn("hashed-email");
        //기존 차단 기록 없음 -> 신규 저장
        when(withdrawalEmailBlockRepository.findByEmailHash("hashed-email")).thenReturn(Optional.empty());

        memberService.deleteAccount(authMember);

        ArgumentCaptor<WithdrawalEmailBlock> captor = ArgumentCaptor.forClass(WithdrawalEmailBlock.class);
        verify(withdrawalEmailBlockRepository).save(captor.capture());
        WithdrawalEmailBlock saved = captor.getValue();
        assertThat(saved.getEmailHash()).isEqualTo("hashed-email");
        //호출 시각 차이로 정확히 30일은 못 맞추므로 범위로 검증
        assertThat(saved.getBlockedUntil())
                .isAfter(LocalDateTime.now().plusDays(29))
                .isBefore(LocalDateTime.now().plusDays(31));
    }

    //회원 탈퇴 - 기존 차단 기록이 있으면 renew로 갱신, 신규 저장 안 함
    @Test
    void deleteAccountRenewBlockTest(){
        Member deleteMember = createTestMember(1L, "user@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(deleteMember);
        Member withdrawnMember = createTestMember(99L, WithdrawnMember.EMAIL, WithdrawnMember.NICKNAME, null, LoginProvider.EMAIL);
        //만료 전 기존 기록
        WithdrawalEmailBlock existing = WithdrawalEmailBlock.create("hashed-email", LocalDateTime.now().plusDays(5));

        when(memberRepository.findById(1L)).thenReturn(Optional.of(deleteMember));
        when(memberRepository.findByEmail(WithdrawnMember.EMAIL)).thenReturn(Optional.of(withdrawnMember));
        when(hmacUtil.hashHex("user@fitback.com")).thenReturn("hashed-email");
        when(withdrawalEmailBlockRepository.findByEmailHash("hashed-email")).thenReturn(Optional.of(existing));

        memberService.deleteAccount(authMember);

        //신규 저장 대신 기존 기록만 갱신
        verify(withdrawalEmailBlockRepository, never()).save(any());
        assertThat(existing.getBlockedUntil())
                .isAfter(LocalDateTime.now().plusDays(29))
                .isBefore(LocalDateTime.now().plusDays(31));
    }

    //회원 탈퇴 - 룩북을 탈퇴 회원 계정으로 재배정한 뒤 회원 삭제
    @Test
    void deleteAccountReassignThenDeleteTest(){
        Member deleteMember = createTestMember(1L, "user@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(deleteMember);
        Member withdrawnMember = createTestMember(99L, WithdrawnMember.EMAIL, WithdrawnMember.NICKNAME, null, LoginProvider.EMAIL);

        when(memberRepository.findById(1L)).thenReturn(Optional.of(deleteMember));
        when(memberRepository.findByEmail(WithdrawnMember.EMAIL)).thenReturn(Optional.of(withdrawnMember));
        when(hmacUtil.hashHex("user@fitback.com")).thenReturn("hashed-email");
        when(withdrawalEmailBlockRepository.findByEmailHash("hashed-email")).thenReturn(Optional.empty());

        memberService.deleteAccount(authMember);

        //재배정이 회원 삭제보다 먼저
        InOrder inOrder = inOrder(lookbookRepository, memberRepository);
        inOrder.verify(lookbookRepository).reassignToWithdrawnMember(1L, withdrawnMember);
        inOrder.verify(memberRepository).delete(deleteMember);
    }

    //회원 탈퇴 - 탈퇴 회원 계정 픽스처가 없으면 INTERNAL_SERVER_ERROR
    @Test
    void deleteAccountWithdrawnAccountMissingTest(){
        Member deleteMember = createTestMember(1L, "user@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(deleteMember);

        when(memberRepository.findById(1L)).thenReturn(Optional.of(deleteMember));
        //탈퇴 회원 계정 조회 실패
        when(memberRepository.findByEmail(WithdrawnMember.EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.deleteAccount(authMember))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR));

        //회원 삭제 미발생
        verify(memberRepository, never()).delete(any());
    }

    //회원 탈퇴 - 삭제 대상 회원이 없으면 NOT_FOUND
    @Test
    void deleteAccountMemberNotFoundTest(){
        Member deleteMember = createTestMember(1L, "user@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        AuthMember authMember = new AuthMember(deleteMember);

        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.deleteAccount(authMember))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.NOT_FOUND));
    }
}
