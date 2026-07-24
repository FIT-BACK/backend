package com.fitback.backend.global.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class SecurityCorsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:3000",
            "http://localhost:5173",
            "http://127.0.0.1:3000",
            "http://127.0.0.1:5173"
    })
    void allowsConfiguredLocalQaOriginPreflightBeforeAuthentication(String origin) throws Exception {
        mockMvc.perform(options("/api/v1/images/presigned-uploads")
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "authorization,content-type"
                        ))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        origin
                ))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        containsString("POST")
                ))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsString("authorization")
                ))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsString("content-type")
                ))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_MAX_AGE,
                        "3600"
                ))
                .andExpect(header().doesNotExist(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS
                ));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GET",
            "POST",
            "PUT",
            "PATCH",
            "DELETE",
            "OPTIONS"
    })
    void allowsConfiguredHttpMethodPreflight(String method) throws Exception {
        mockMvc.perform(options("/api/v1/images/presigned-uploads")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "authorization,content-type"
                        ))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        containsString(method)
                ));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://localhost:5173",
            "http://localhost:8080",
            "https://untrusted.example"
    })
    void rejectsOriginOutsideTheExactAllowlist(String origin) throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "content-type"
                        ))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN
                ));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:3000",
            "http://localhost:5173",
            "http://127.0.0.1:3000",
            "http://127.0.0.1:5173"
    })
    void includesCorsHeaderOnActualLoginResponse(String origin) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, origin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email",
                                "unknown@fitback.com",
                                "password",
                                "password123"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        origin
                ))
                .andExpect(header().doesNotExist(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS
                ));
    }
}
