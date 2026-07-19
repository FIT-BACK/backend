package com.fitback.backend.domain.member.dto;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.entity.MemberRole;
import com.fitback.backend.domain.member.entity.MemberTag;
import com.fitback.backend.domain.tag.entity.TagType;
import lombok.Builder;

import java.util.List;

// 회원 응답 DTO
public class MemberResponse {

    //이메일 회원가입 응답
    @Builder
    public record SignUpResponse(
            String accessToken,
            String refreshToken,
            Long memberId,
            String email,
            LoginProvider loginProvider,
            MemberRole role
    ) {}

    //회원가입 응답 변환
    public static SignUpResponse toSignUpResponse(
            String accessToken,
            String refreshToken,
            Member member
    ){
        return SignUpResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .memberId(member.getId())
                .email(member.getEmail())
                .loginProvider(member.getLoginProvider())
                .role(member.getRole())
                .build();
    }

    //이메일 로그인 응답
    @Builder
    public record LoginResponse(
            String accessToken,
            String refreshToken,
            Long memberId,
            String email,
            String nickname,
            String profileImageUrl,
            LoginProvider loginProvider
    ) {}

    //이메일 로그인 응답 DTO 변환
    public static LoginResponse toLoginResponse(
            String accessToken,
            String refreshToken,
            Member member
    ){
        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .memberId(member.getId())
                .email(member.getEmail())
                .nickname(member.getNickname())
                .profileImageUrl(member.getProfileImageUrl())
                .loginProvider(member.getLoginProvider())
                .build();
    }

    //토큰 재발급 응답 dto
    @Builder
    public record RefreshResponse (
        String accessToken,
        String refreshToken
    ){}

    //토큰 재발급 응답 dto로 변환
    public static RefreshResponse toRefreshResponse(
            String accessToken,
            String refreshToken
    ){
        return RefreshResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    //회원정보 수정 응답 dto
    @Builder
    public record UpdateMemberResponse(
       Long memberId,
       String nickname,
       String profileImageUrl
    ) {}

    //회원정보 수정 dto 변환
    public static UpdateMemberResponse toUpdateMemberResponse(
            Member member
    ){
        return UpdateMemberResponse.builder()
                .memberId(member.getId())
                .nickname(member.getNickname())
                .profileImageUrl(member.getProfileImageUrl())
                .build();
    }

    //마이페이지 응답 dto
    @Builder
    public record MyPageResponse(
        Long memberId,
        String email,
        String nickname,
        String profileImageUrl,
        Long savedCount,
        Long analysisCount,
        Long uploadCount,
        List<TagInfo> tags
    ) {}

    //마이페이지 응답 변환
    public static MyPageResponse toMyPageResponse(
            Long savedCount,
            Long analysisCount,
            Long uploadCount,
            Member member,
            List<MemberTag> memberTagList
    ){
        return MyPageResponse.builder()
                .memberId(member.getId())
                .email(member.getEmail())
                .nickname(member.getNickname())
                .profileImageUrl(member.getProfileImageUrl())
                .savedCount(savedCount).analysisCount(analysisCount).uploadCount(uploadCount)
                .tags(memberTagList.stream().map(MemberResponse::toTagInfo).toList())
                .build();
    }

    //회원 가입 시 프로필 설정
    @Builder
    public record OnboardingResponse(
            Long memberId,
            String email,
            String nickname,
            String profileImageUrl,
            LoginProvider loginProvider,
            List<TagInfo> tags
    ) {}

    //회원의 태그 관련 응답에 사용되는 태그 정보들
    @Builder
    public record TagInfo(
            Long tagId,
            String tagName,
            TagType tagType
    ){}

    public static OnboardingResponse toOnboardingResponse(
        Member member,
        List<MemberTag> memberTagList
    ){
        return OnboardingResponse.builder()
                .memberId(member.getId())
                .email(member.getEmail())
                .nickname(member.getNickname())
                .profileImageUrl(member.getProfileImageUrl())
                .loginProvider(member.getLoginProvider())
                .tags(memberTagList.stream().map(MemberResponse::toTagInfo).toList())
                .build();
    }

    public static TagInfo toTagInfo(
        MemberTag memberTag
    ){
        return TagInfo.builder()
                .tagId(memberTag.getTag().getId())
                .tagName(memberTag.getTag().getTagName())
                .tagType(memberTag.getTag().getTagType())
                .build();
    }

    @Builder
    public record UpdateTagsResponse(
            List<TagInfo> tags
    ) {}

    public static UpdateTagsResponse toUpdateTagsResponse(
            List<MemberTag> memberTagList
    )
    {
        return UpdateTagsResponse.builder()
                .tags(memberTagList.stream().map(MemberResponse::toTagInfo).toList())
                .build();
    }
}
