package com.fitback.backend.domain.member.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetTokenUtil {

    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final String HASH_ALGORITHM = "SHA-256";

    private final SecureRandom secureRandom = new SecureRandom();

    //URL에 포함할 256비트 랜덤값 생성
    public GeneratedToken generate() {
        byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(randomBytes);

        //패딩 없는 URL-safe 문자열로 변환
        String resetToken = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        //원문과 DB 저장용 해시값을 한 쌍으로 반환
        return new GeneratedToken(resetToken, hash(resetToken));
    }

    //전달받은 토큰을 DB 조회용 SHA-256 hex로 변환
    public String hash(String resetToken) {
        Objects.requireNonNull(resetToken, "resetToken must not be null");
        if (resetToken.isBlank()) {
            throw new IllegalArgumentException("resetToken must not be blank");
        }

        try {
            //문자열 인코딩을 고정해 항상 같은 해시 생성
            byte[] digest = MessageDigest.getInstance(HASH_ALGORITHM)
                    .digest(resetToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record GeneratedToken(
            String resetToken,
            String tokenHash
    ) {
    }
}
