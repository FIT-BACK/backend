package com.fitback.backend.domain.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.notification.entity.Notification;
import com.fitback.backend.domain.notification.entity.NotificationType;
import com.fitback.backend.domain.notification.repository.NotificationRepository;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
@Transactional
class NotificationControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ObjectMapper objectMapper;

    //회원가입 후 access 토큰 반환
    private String signUpAndGetAccessToken(String email) throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "email", email,
                "password", "password123"
        ));
        String responseBody = mockMvc.perform(post("/api/v1/auth/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(responseBody)
                .get("data")
                .get("accessToken")
                .asText();
    }

    //Bearer 헤더 값 생성
    private String bearer(String token) {
        return "Bearer " + token;
    }

    //회원에게 전달된 테스트 알림 저장
    private Notification saveNotification(
            Member member,
            NotificationType type,
            Long lookbookId
    ) {
        Notification notification = Notification.create(
                member,
                type,
                null,
                lookbookId,
                null,
                null,
                "룩북에 좋아요가 눌렸어요",
                "회원님의 룩북에 좋아요가 눌렸습니다."
        );
        entityManager.persist(notification);
        entityManager.flush();
        return notification;
    }

    //토큰 없이 알림 목록 조회 시 401
    @Test
    void getNotificationsWithoutTokenTest() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    //토큰 없이 알림 단건 읽음 처리 시 401
    @Test
    void markNotificationAsReadWithoutTokenTest() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications/1/read"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    //토큰 없이 알림 전체 읽음 처리 시 401
    @Test
    void markAllNotificationsAsReadWithoutTokenTest() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications/read"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    //토큰 없이 알림 삭제 시 401
    @Test
    void deleteNotificationWithoutTokenTest() throws Exception {
        mockMvc.perform(delete("/api/v1/notifications/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    //알림 목록 조회 - 최신순 목록과 페이지 정보, 전체 미읽음 수 반환
    @Test
    void getNotificationsTest() throws Exception {
        String email = "list@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        Member member = memberRepository.findByEmail(email).orElseThrow();
        saveNotification(member, NotificationType.LOOKBOOK_LIKED, 10L);
        Notification second =
                saveNotification(member, NotificationType.LOOKBOOK_LIKED, 20L);

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", bearer(accessToken))
                        .queryParam("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200_1"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].notificationId").value(second.getId()))
                .andExpect(jsonPath("$.data.items[0].targetType").value("LOOKBOOK"))
                .andExpect(jsonPath("$.data.items[0].targetId").value(20L))
                .andExpect(jsonPath("$.data.unreadCount").value(2))
                .andExpect(jsonPath("$.data.nextCursor").value(second.getId()))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.pageSize").value(1));
    }

    //알림 목록 조회 - pageSize 범위를 벗어나면 400
    @Test
    void getNotificationsInvalidPageSizeTest() throws Exception {
        String accessToken = signUpAndGetAccessToken("invalid-page@fitback.com");

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", bearer(accessToken))
                        .queryParam("pageSize", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    //알림 단건 읽음 처리 - readAt 기록
    @Test
    void markNotificationAsReadTest() throws Exception {
        String email = "read@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        Member member = memberRepository.findByEmail(email).orElseThrow();
        Notification notification =
                saveNotification(member, NotificationType.LOOKBOOK_LIKED, 10L);

        mockMvc.perform(patch("/api/v1/notifications/{notificationId}/read", notification.getId())
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        //테스트 트랜잭션의 변경 내용을 DB에 반영한 후 다시 조회
        entityManager.flush();
        entityManager.clear();
        Notification readNotification =
                notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(readNotification.getReadAt()).isNotNull();
    }

    //알림 전체 읽음 처리 - 현재 회원의 미읽음 알림 모두 변경
    @Test
    void markAllNotificationsAsReadTest() throws Exception {
        String email = "read-all@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        Member member = memberRepository.findByEmail(email).orElseThrow();
        saveNotification(member, NotificationType.LOOKBOOK_LIKED, 10L);
        saveNotification(member, NotificationType.LOOKBOOK_LIKED, 20L);

        mockMvc.perform(patch("/api/v1/notifications/read")
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk());

        entityManager.clear();
        assertThat(
                notificationRepository.countByMemberIdAndReadAtIsNull(member.getId())
        ).isZero();
    }

    //알림 삭제 - 본인 알림 하드 삭제
    @Test
    void deleteNotificationTest() throws Exception {
        String email = "delete@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        Member member = memberRepository.findByEmail(email).orElseThrow();
        Notification notification =
                saveNotification(member, NotificationType.LOOKBOOK_LIKED, 10L);

        mockMvc.perform(delete("/api/v1/notifications/{notificationId}", notification.getId())
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk());

        assertThat(
                notificationRepository.findById(notification.getId())
        ).isEmpty();
    }

    //다른 회원의 알림은 존재 여부를 노출하지 않고 404
    @Test
    void markOtherMemberNotificationAsReadNotFoundTest() throws Exception {
        String accessToken = signUpAndGetAccessToken("owner-a@fitback.com");
        signUpAndGetAccessToken("owner-b@fitback.com");
        Member otherMember =
                memberRepository.findByEmail("owner-b@fitback.com").orElseThrow();
        Notification notification =
                saveNotification(otherMember, NotificationType.LOOKBOOK_LIKED, 10L);

        mockMvc.perform(patch("/api/v1/notifications/{notificationId}/read", notification.getId())
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION404_1"));
    }

    //다른 회원의 알림은 삭제하지 않고 404
    @Test
    void deleteOtherMemberNotificationNotFoundTest() throws Exception {
        String accessToken =
                signUpAndGetAccessToken("delete-owner-a@fitback.com");
        signUpAndGetAccessToken("delete-owner-b@fitback.com");
        Member otherMember =
                memberRepository.findByEmail("delete-owner-b@fitback.com").orElseThrow();
        Notification notification =
                saveNotification(otherMember, NotificationType.LOOKBOOK_LIKED, 10L);

        mockMvc.perform(delete(
                        "/api/v1/notifications/{notificationId}",
                        notification.getId()
                )
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION404_1"));

        //404 응답 후에도 다른 회원의 알림은 유지
        assertThat(notificationRepository.findById(notification.getId()))
                .isPresent();
    }
}
