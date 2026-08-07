package com.fitback.backend.domain.lookbook.service;

import static com.fitback.backend.domain.lookbook.LookbookImageFixtures.readyImage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.repository.LookbookLikeRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.notification.entity.MemberNotificationSetting;
import com.fitback.backend.domain.notification.entity.Notification;
import com.fitback.backend.domain.notification.entity.NotificationType;
import com.fitback.backend.domain.notification.repository.MemberNotificationSettingRepository;
import com.fitback.backend.domain.notification.repository.NotificationRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LookbookLikeCommandServiceTest {

    @Mock
    private LookbookRepository lookbookRepository;

    @Mock
    private LookbookLikeRepository lookbookLikeRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private MemberNotificationSettingRepository notificationSettingRepository;

    @InjectMocks
    private LookbookLikeCommandService lookbookLikeCommandService;

    private Member member;
    private Lookbook lookbook;

    @BeforeEach
    void setUp() {
        member = Member.create("member@fitback.com", "fitback", "password", LoginProvider.EMAIL);
        ReflectionTestUtils.setField(member, "id", 1L);
        lookbook = Lookbook.create(
                member,
                readyImage("like-command-original", member, ImagePurpose.LOOKBOOK),
                readyImage("like-command-matched", member, ImagePurpose.LOOKBOOK),
                null,
                null
        );
        ReflectionTestUtils.setField(lookbook, "id", 100L);
    }

    @Test
    void createLikeSavesRelationBeforeIncrementingLikeCount() {
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(lookbook));
        when(lookbookRepository.incrementLikeCount(100L)).thenReturn(1);
        when(lookbookRepository.findLikeCountByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(1));

        Integer likeCount = lookbookLikeCommandService.createLike(100L, member);

        assertThat(likeCount).isEqualTo(1);
        InOrder inOrder = inOrder(lookbookLikeRepository, lookbookRepository);
        inOrder.verify(lookbookLikeRepository).saveAndFlush(any());
        inOrder.verify(lookbookRepository).incrementLikeCount(100L);
    }

    @Test
    void createLikeFailsWhenLookbookIsDeletedBeforeCountUpdate() {
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(lookbook));
        when(lookbookRepository.incrementLikeCount(100L)).thenReturn(0);

        assertThatThrownBy(() -> lookbookLikeCommandService.createLike(100L, member))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
                );
    }

    @Test
    void createLikeDoesNotNotifyWhenLikerIsOwner() {
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(lookbook));
        when(lookbookRepository.incrementLikeCount(100L)).thenReturn(1);
        when(lookbookRepository.findLikeCountByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(1));

        lookbookLikeCommandService.createLike(100L, member);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void createLikeNotifiesOwnerWhenLikedByOtherMember() {
        Member liker = Member.create("liker@fitback.com", "liker", "password", LoginProvider.EMAIL);
        ReflectionTestUtils.setField(liker, "id", 2L);

        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(lookbook));
        when(lookbookRepository.incrementLikeCount(100L)).thenReturn(1);
        when(lookbookRepository.findLikeCountByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(1));
        when(notificationSettingRepository.findById(1L)).thenReturn(Optional.empty());

        lookbookLikeCommandService.createLike(100L, liker);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification notification = captor.getValue();
        assertThat(notification.getMember()).isEqualTo(member);
        assertThat(notification.getNotificationType()).isEqualTo(NotificationType.LOOKBOOK_LIKED);
        assertThat(notification.getActorMemberId()).isEqualTo(2L);
        assertThat(notification.getLookbookId()).isEqualTo(100L);
    }

    @Test
    void createLikeSkipsNotificationWhenOwnerDisabledLookbookLikedNotification() {
        Member liker = Member.create("liker@fitback.com", "liker", "password", LoginProvider.EMAIL);
        ReflectionTestUtils.setField(liker, "id", 2L);
        MemberNotificationSetting setting = MemberNotificationSetting.createDefault(member);
        setting.changeLookbookLikedEnabled(false);

        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(lookbook));
        when(lookbookRepository.incrementLikeCount(100L)).thenReturn(1);
        when(lookbookRepository.findLikeCountByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(1));
        when(notificationSettingRepository.findById(1L)).thenReturn(Optional.of(setting));

        lookbookLikeCommandService.createLike(100L, liker);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void deleteLikeHardDeletesRelationBeforeDecrementingLikeCount() {
        when(lookbookLikeRepository.deleteByLookbookIdAndMemberId(100L, 1L)).thenReturn(1);
        when(lookbookRepository.decrementLikeCount(100L)).thenReturn(1);
        when(lookbookRepository.findLikeCountByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(4));

        Integer likeCount = lookbookLikeCommandService.deleteLike(100L, member);

        assertThat(likeCount).isEqualTo(4);
        InOrder inOrder = inOrder(lookbookLikeRepository, lookbookRepository);
        inOrder.verify(lookbookLikeRepository).deleteByLookbookIdAndMemberId(100L, 1L);
        inOrder.verify(lookbookRepository).decrementLikeCount(100L);
    }

    @Test
    void deleteLikeReturnsCurrentCountWhenLikeDoesNotExist() {
        when(lookbookLikeRepository.deleteByLookbookIdAndMemberId(100L, 1L)).thenReturn(0);
        when(lookbookRepository.findLikeCountByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(5));

        Integer likeCount = lookbookLikeCommandService.deleteLike(100L, member);

        assertThat(likeCount).isEqualTo(5);
        verify(lookbookRepository, never()).decrementLikeCount(100L);
    }

    @Test
    void deleteLikeFailsWhenLookbookIsDeletedBeforeCountUpdate() {
        when(lookbookLikeRepository.deleteByLookbookIdAndMemberId(100L, 1L)).thenReturn(1);
        when(lookbookRepository.decrementLikeCount(100L)).thenReturn(0);

        assertThatThrownBy(() -> lookbookLikeCommandService.deleteLike(100L, member))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
                );
    }

    @Test
    void deleteLikeFailsWhenLookbookDoesNotExist() {
        when(lookbookLikeRepository.deleteByLookbookIdAndMemberId(100L, 1L)).thenReturn(0);
        when(lookbookRepository.findLikeCountByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> lookbookLikeCommandService.deleteLike(100L, member))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
                );
    }
}
