package com.fitback.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.lookbook.dto.LookbookResponse;
import com.fitback.backend.domain.lookbook.service.LookbookService;
import com.fitback.backend.global.exception.GlobalExceptionHandler;
import com.fitback.backend.global.security.entity.AuthMember;
import com.fitback.backend.global.security.service.CustomUserDetailsService;
import com.fitback.backend.global.security.util.JwtUtil;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class SecurityLookbookAccessIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LookbookService lookbookService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Autowired
    private JwtUtil jwtUtil;

    //비로그인 룩북 목록 조회 허용
    @Test
    void allowsAnonymousLookbookListRead() throws Exception {
        LookbookResponse.LookbookList response = LookbookResponse.LookbookList.builder()
                .items(List.of())
                .pageSize(20)
                .hasNext(false)
                .build();
        when(lookbookService.getLookbooks(null, 20, null, null)).thenReturn(response);

        //get /api/v1/lookbooks 요청 생성
        mockMvc.perform(get("/api/v1/lookbooks"))
                .andExpect(status().isOk())     //http 200 확인
                .andExpect(jsonPath("$.success").value(true))   //응답 json에서 success가 true인지 확인
                .andExpect(jsonPath("$.code").value("COMMON200_1"));    //응답 코드 확인
    }

    //비로그인 룩북 상세 조회 허용
    @Test
    void allowsAnonymousLookbookDetailRead() throws Exception {
        LookbookResponse.LookbookDetail response = LookbookResponse.LookbookDetail.builder()
                .isLiked(false)
                .isOwner(false)
                .build();
        when(lookbookService.getLookbookDetail(1L, null)).thenReturn(response);

        // /api/v1/lookbooks/1 요청 생성 -> 검증
        mockMvc.perform(get("/api/v1/lookbooks/{lookbookId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON200_1"))
                .andExpect(jsonPath("$.data.isLiked").value(false))
                .andExpect(jsonPath("$.data.isOwner").value(false));
    }

    //비로그인 룩북 생성 요청은 인증 필요
    @Test
    void requiresAuthenticationForLookbookCreate() throws Exception {
        //인증이 필요한 post /api/v1/lookbooks 요청
        mockMvc.perform(post("/api/v1/lookbooks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    //비로그인 룩북 삭제 요청은 인증 필요
    @Test
    void requiresAuthenticationForLookbookDelete() throws Exception {
        //인증이 필요한 post /api/v1/lookbooks 요청
        mockMvc.perform(delete("/api/v1/lookbooks/{lookbookId}", 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    @Test
    void rejectsNonPositiveLookbookCursor() throws Exception {
        mockMvc.perform(get("/api/v1/lookbooks").param("cursor", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "2147483647"})
    void rejectsLookbookPageSizeOutsideAllowedRange(String pageSize) throws Exception {
        mockMvc.perform(get("/api/v1/lookbooks").param("pageSize", pageSize))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    @Test
    void rejectsUnsupportedRequestContentType() throws Exception {
        mockMvc.perform(post("/api/v1/auth/sign")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("COMMON415_1"));
    }

    @Test
    void rejectsUnsupportedResponseMediaType() throws Exception {
        mockMvc.perform(get("/api/v1/lookbooks").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("COMMON406_1"));
    }

    @Test
    void rejectsMalformedJwtWithoutCallingPublicEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/lookbooks")
                        .header("Authorization", "Bearer malformed.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    @Test
    void rejectsValidJwtWhenMemberDoesNotExist() throws Exception {
        String email = "missing@fitback.com";
        when(customUserDetailsService.loadUserByUsername(email))
                .thenThrow(new UsernameNotFoundException("member not found"));

        mockMvc.perform(get("/api/v1/lookbooks")
                        .header("Authorization", bearer(accessToken(email))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    @Test
    void returnsCommonInternalServerErrorAndLogsMemberLookupDatabaseFailure() throws Exception {
        String email = "database-failure@fitback.com";
        when(customUserDetailsService.loadUserByUsername(email))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            mockMvc.perform(get("/api/v1/lookbooks")
                            .header("Authorization", bearer(accessToken(email))))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("COMMON500_1"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains(
                                    "method=GET",
                                    "path=/api/v1/lookbooks",
                                    "errorCode=COMMON500_1",
                                    "failureType=DataAccessResourceFailureException"
                            ));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private String accessToken(String email) {
        Member member = Member.create(email, "jwt-member", "encoded-password", LoginProvider.EMAIL);
        return jwtUtil.createAccessToken(new AuthMember(member));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
