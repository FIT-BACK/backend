package com.fitback.backend.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.repository.ImageRepository;
import com.fitback.backend.domain.image.service.ImageAccessUrlProvider;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MemberProfileImageServiceTest {

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private ImageAccessUrlProvider imageAccessUrlProvider;

    @Mock
    private Image firstImage;

    @Mock
    private Image secondImage;

    @Test
    void returnsNullWhenMemberHasNoProfileImage() {
        Member member = member(1L, null);
        MemberProfileImageService service =
                new MemberProfileImageService(imageRepository, imageAccessUrlProvider);

        String profileImageUrl = service.resolveProfileImageUrl(member);

        assertThat(profileImageUrl).isNull();
        verify(imageRepository, never()).findById(member.getProfileImageId());
    }

    @Test
    void resolvesSingleProfileImageUrl() {
        Member member = member(1L, "profile-1");
        MemberProfileImageService service =
                new MemberProfileImageService(imageRepository, imageAccessUrlProvider);
        when(imageRepository.findById("profile-1")).thenReturn(Optional.of(firstImage));
        when(imageAccessUrlProvider.createReadUrl(firstImage))
                .thenReturn("https://cdn.example.com/profile-1");

        String profileImageUrl = service.resolveProfileImageUrl(member);

        assertThat(profileImageUrl).isEqualTo("https://cdn.example.com/profile-1");
    }

    @Test
    void resolvesMultipleProfileImagesWithSingleRepositoryCall() {
        Member firstMember = member(1L, "profile-1");
        Member secondMember = member(2L, "profile-2");
        Member memberWithoutProfile = member(3L, null);
        MemberProfileImageService service =
                new MemberProfileImageService(imageRepository, imageAccessUrlProvider);
        when(firstImage.getId()).thenReturn("profile-1");
        when(secondImage.getId()).thenReturn("profile-2");
        when(imageRepository.findAllById(List.of("profile-1", "profile-2")))
                .thenReturn(List.of(firstImage, secondImage));
        when(imageAccessUrlProvider.createReadUrl(firstImage))
                .thenReturn("https://cdn.example.com/profile-1");
        when(imageAccessUrlProvider.createReadUrl(secondImage))
                .thenReturn("https://cdn.example.com/profile-2");

        Map<Long, String> profileImageUrls = service.resolveProfileImageUrls(
                List.of(firstMember, firstMember, secondMember, memberWithoutProfile)
        );

        assertThat(profileImageUrls).containsExactlyInAnyOrderEntriesOf(Map.of(
                1L, "https://cdn.example.com/profile-1",
                2L, "https://cdn.example.com/profile-2"
        ));
        verify(imageRepository).findAllById(List.of("profile-1", "profile-2"));
    }

    @Test
    void failsWhenStoredProfileImageDoesNotExist() {
        Member member = member(1L, "missing-profile");
        MemberProfileImageService service =
                new MemberProfileImageService(imageRepository, imageAccessUrlProvider);
        when(imageRepository.findAllById(anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> service.resolveProfileImageUrls(List.of(member)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IMAGE_NOT_FOUND)
                );
    }

    private Member member(Long memberId, String profileImageId) {
        Member member = Member.create(
                "member-%d@fitback.com".formatted(memberId),
                "member-%d".formatted(memberId),
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(member, "id", memberId);
        if (profileImageId != null) {
            member.changeProfileImageId(profileImageId);
        }
        return member;
    }
}
