package com.fitback.backend.domain.member.service;


import com.fitback.backend.domain.member.dto.MemberRequest;
import com.fitback.backend.domain.member.dto.MemberResponse;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.entity.MemberRole;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.member.repository.WithdrawnEmailBlockRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.security.entity.AuthMember;
import com.fitback.backend.global.security.util.JwtUtil;
import com.fitback.backend.global.util.HmacUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private WithdrawnEmailBlockRepository withdrawnEmailBlockRepository;
    @Mock
    private HmacUtil hmacUtil;

    //authService 실제 객체 생성 후 mock 객체 주입
    @InjectMocks
    private AuthService authService;

    //id는 db에서 채우는데 실제 db를 사용하지 않으므로 id 강제 세팅
    private Member createTestMember(Long id, String email, String encodedPassword){
        Member member = Member.create(email, "test_user", encodedPassword, LoginProvider.EMAIL);
        //id 강제 세팅
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    //회원가입 성공 테스트
    @Test
    void signUpSuccessTest(){
        MemberRequest.SignUpRequest request = new MemberRequest.SignUpRequest("test@fitback.com", "password123");

        //given
        //회원가입을 위해 중복 여부는 false가 반환되도록
        when(memberRepository.existsByEmail(request.email())).thenReturn(false);
        //재가입 차단 대상 아님
        when(hmacUtil.hashHex(request.email())).thenReturn("hashed-email");
        when(withdrawnEmailBlockRepository.existsByEmailHashAndBlockedUntilAfter(anyString(), any(LocalDateTime.class))).thenReturn(false);
        when(memberRepository.existsByNickname(anyString())).thenReturn(false);
        //테스트용 비밀번호
        when(passwordEncoder.encode(request.password())).thenReturn("encodedPw");
        //0번째 인자값 반환(member 객체)
        when(memberRepository.save(any(Member.class))).thenAnswer(inv -> inv.getArgument(0));
        //테스트용 토큰
        when(jwtUtil.createAccessToken(any(AuthMember.class))).thenReturn("access-token");
        when(jwtUtil.createRefreshToken(any(AuthMember.class))).thenReturn("refresh-token");

        //when
        MemberResponse.SignUpResponse response = authService.signUp(request);

        //then
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.email()).isEqualTo("test@fitback.com");
        assertThat(response.loginProvider()).isEqualTo(LoginProvider.EMAIL);
        assertThat(response.role()).isEqualTo(MemberRole.USER);

        //실제 저장된 member 필드 검증 (raw 아닌 암호화 비밀번호 포함)
        ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(memberCaptor.capture());
        Member savedMember = memberCaptor.getValue();
        assertThat(savedMember.getEmail()).isEqualTo("test@fitback.com");
        assertThat(savedMember.getPassword()).isEqualTo("encodedPw");
        assertThat(savedMember.getLoginProvider()).isEqualTo(LoginProvider.EMAIL);
        assertThat(savedMember.getRole()).isEqualTo(MemberRole.USER);
    }

    //회원가입 실패 - 이미 존재하는 이메일이면 EMAIL_ALREADY_EXISTS
    @Test
    void signUpduplicateEmailTest() {
        MemberRequest.SignUpRequest request = new MemberRequest.SignUpRequest("dup@fitback.com", "password123");
        //이메일 중복 검사 시 true 반환 설정
        when(memberRepository.existsByEmail(request.email())).thenReturn(true);

        // 발생한 예외 타입이 BusinessException인지 해당 BusinessException의 ErrorCode가 EMAIL_ALREADY_EXISTS인지 검증
        assertThatThrownBy(() -> authService.signUp(request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS));

        //save가 한번도 호출되지 않았는지 검증
        verify(memberRepository, never()).save(any(Member.class));
    }

    //회원가입 실패 - 30일 재가입 차단 기간이면 REJOIN_BLOCKED
    @Test
    void signUpRejoinBlockedTest(){
        MemberRequest.SignUpRequest request = new MemberRequest.SignUpRequest("blocked@fitback.com", "password123");
        //이메일 중복은 아님
        when(memberRepository.existsByEmail(request.email())).thenReturn(false);
        //해시된 이메일이 만료 전 차단 기록에 존재
        when(hmacUtil.hashHex(request.email())).thenReturn("hashed-email");
        when(withdrawnEmailBlockRepository.existsByEmailHashAndBlockedUntilAfter(anyString(), any(LocalDateTime.class))).thenReturn(true);

        //예외 타입과 ErrorCode가 REJOIN_BLOCKED인지 검증
        assertThatThrownBy(() -> authService.signUp(request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.REJOIN_BLOCKED));

        //차단 시 save 미호출 검증
        verify(memberRepository, never()).save(any(Member.class));
    }

    //로그인 성공 테스트 - 자격 증명이 맞으면 토큰을 반환하고 refresh를 저장
    @Test
    void loginSuccessTest(){
        MemberRequest.LoginRequest request = new MemberRequest.LoginRequest("test@fitback.com", "password123");
        //id, 이메일, 암호화된 비밀번호를 가진 테스트 회원 생성
        Member member = createTestMember(1L, "test@fitback.com", "encodedPw");

        //given
        //이메일로 회원 조회 시 위 회원 반환
        when(memberRepository.findByEmail(request.email())).thenReturn(Optional.of(member));
        //비밀번호 일치하도록 설정
        when(passwordEncoder.matches(request.password(), "encodedPw")).thenReturn(true);
        //테스트용 토큰
        when(jwtUtil.createAccessToken(any(AuthMember.class))).thenReturn("access-token");
        when(jwtUtil.createRefreshToken(any(AuthMember.class))).thenReturn("refresh-token");

        //when
        MemberResponse.LoginResponse response = authService.login(request);

        //then
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.memberId()).isEqualTo(1L);
        //발급한 refresh 토큰이 회원에 저장되었는지 검증
        assertThat(member.getRefreshToken()).isEqualTo("refresh-token");
    }

    //로그인 실패 테스트 - 이메일이 없으면 INVALID_CREDENTIALS
    @Test
    void loginEmailNotFoundTest(){
        MemberRequest.LoginRequest request = new MemberRequest.LoginRequest("none@fitback.com", "password123");

        //이메일로 회원 조회 시 빈 Optional 반환
        when(memberRepository.findByEmail(request.email())).thenReturn(Optional.empty());

        //예외 타입과 ErrorCode가 INVALID_CREDENTIALS인지 검증
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        //인증 실패 시 토큰 생성 미호출 검증
        verify(jwtUtil, never()).createAccessToken(any());
        verify(jwtUtil, never()).createRefreshToken(any());
    }

    //로그인 실패 - 비밀번호가 틀리면 INVALID_CREDENTIALS
    @Test
    void loginWrongPasswordTest(){
        MemberRequest.LoginRequest request = new MemberRequest.LoginRequest("test@fitback.com", "wrongPw");
        Member member = createTestMember(1L, "test@fitback.com", "encodedPw");

        when(memberRepository.findByEmail(request.email())).thenReturn(Optional.of(member));
        //비밀번호 불일치하도록 설정
        when(passwordEncoder.matches(request.password(), "encodedPw")).thenReturn(false);

        //예외 타입과 ErrorCode가 INVALID_CREDENTIALS인지 검증
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        //인증 실패 시 토큰 생성 미호출 검증
        verify(jwtUtil, never()).createAccessToken(any());
        verify(jwtUtil, never()).createRefreshToken(any());
    }

    //토큰 재발급 성공 테스트 - 저장된 refresh 토큰과 일치하면 새 access, refresh 토큰
    @Test
    void refreshSuccessTest(){
        String oldRefresh = "old-refresh";
        MemberRequest.RefreshRequest request = new MemberRequest.RefreshRequest(oldRefresh);
        Member member = createTestMember(1L, "test@fitback.com", "encodedPw");
        //회원에 기존 refresh 토큰 저장
        member.updateRefreshToken(oldRefresh);

        //토큰 유효성, refresh 타입 검증을 통과하도록 설정
        when(jwtUtil.isValid(oldRefresh)).thenReturn(true);
        when(jwtUtil.isRefreshToken(oldRefresh)).thenReturn(true);
        //토큰에서 이메일 추출
        when(jwtUtil.getEmailFromToken(oldRefresh)).thenReturn("test@fitback.com");
        when(memberRepository.findByEmail("test@fitback.com")).thenReturn(Optional.of(member));
        //새로 발급될 토큰
        when(jwtUtil.createAccessToken(any(AuthMember.class))).thenReturn("new-access");
        when(jwtUtil.createRefreshToken(any(AuthMember.class))).thenReturn("new-refresh");

        //when
        MemberResponse.RefreshResponse response = authService.refresh(request);

        //then
        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
        //회원의 refresh 토큰이 새 토큰으로 회전되었는지 검증
        assertThat(member.getRefreshToken()).isEqualTo("new-refresh");
    }

    //토큰 재발급 실패 테스트 - 유효하지 않은 토큰이면 INVALID_REFRESH_TOKEN
    @Test
    void refreshInvalidTokenTest(){
        MemberRequest.RefreshRequest request = new MemberRequest.RefreshRequest("bad-token");

        //토큰 유효성 검증 실패하도록 설정
        when(jwtUtil.isValid("bad-token")).thenReturn(false);
        //isValid가 false면 -> isRefreshToken은 호출되지 않음

        //예외 타입과 ErrorCode가 INVALID_REFRESH_TOKEN인지 검증
        assertThatThrownBy(() -> authService.refresh(request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN));

        //인증 실패 시 토큰 생성 미호출 검증
        verify(jwtUtil, never()).createAccessToken(any());
        verify(jwtUtil, never()).createRefreshToken(any());
    }

    //토큰 재발급 실패 테스트 - 저장된 토큰과 다르면 INVALID_REFRESH_TOKEN
    @Test
    void refreshTokenMismatchTest(){
        String requestToken = "request-refresh";
        MemberRequest.RefreshRequest request = new MemberRequest.RefreshRequest(requestToken);
        Member member = createTestMember(1L, "test@fitback.com", "encodedPw");
        //회원에 저장된 refresh 토큰은 요청 토큰과 다르게 설정
        member.updateRefreshToken("stored-different-refresh");

        //given
        when(jwtUtil.isValid(requestToken)).thenReturn(true);
        when(jwtUtil.isRefreshToken(requestToken)).thenReturn(true);
        when(jwtUtil.getEmailFromToken(requestToken)).thenReturn("test@fitback.com");
        when(memberRepository.findByEmail("test@fitback.com")).thenReturn(Optional.of(member));

        //요청 토큰과 저장된 토큰이 달라 예외 발생, ErrorCode가 INVALID_REFRESH_TOKEN인지 검증
        assertThatThrownBy(() -> authService.refresh(request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN));

        //인증 실패 시 토큰 생성 미호출 검증
        verify(jwtUtil, never()).createAccessToken(any());
        verify(jwtUtil, never()).createRefreshToken(any());
    }

    //로그아웃 성공 테스트 - refresh 토큰 초기화
    @Test
    void logoutSuccessTest(){
        Member member = createTestMember(1L, "test@fitback.com", "encodedPw");
        //초기화 대상인 refresh 토큰 저장
        member.updateRefreshToken("some-refresh");
        //@AuthenticationPrincipal로 주입될 AuthMember 생성
        AuthMember authMember = new AuthMember(member);

        //given
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        //when
        authService.logout(authMember);

        //then
        //refresh 토큰이 null로 초기화되었는지 검증
        assertThat(member.getRefreshToken()).isNull();
    }

    //로그아웃 실패 테스트 - 회원이 없으면 NOT_FOUND
    @Test
    void logoutMemberNotFoundTest(){
        Member member = createTestMember(1L, "test@fitback.com", "encodedPw");
        AuthMember authMember = new AuthMember(member);

        //회원 조회 시 빈 Optional 반환
        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        //예외 타입과 ErrorCode가 NOT_FOUND인지 검증
        assertThatThrownBy(() -> authService.logout(authMember))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.NOT_FOUND));
    }

}
