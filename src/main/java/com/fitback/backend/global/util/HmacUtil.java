package com.fitback.backend.global.util;

import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HmacUtil {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Pattern HMAC_SHA_256_HEX_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final SecretKeySpec secretKeySpec;

    public HmacUtil(@Value("${hmac.secret-key}") String secretKey) {
        this.secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    // 원문을 복원할 수 없는 고정 길이 식별값 생성
    public String hashHex(String raw) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secretKeySpec);
            byte[] digest = mac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            return validateHashHex(toHex(digest));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // HMAC을 저장하는 도메인이 동일한 64자리 소문자 hex 형식을 사용하도록 검증
    public static String validateHashHex(String hashHex) {
        Objects.requireNonNull(hashHex, "hashHex must not be null");
        if (!HMAC_SHA_256_HEX_PATTERN.matcher(hashHex).matches()) {
            throw new IllegalArgumentException(
                    "hashHex must be an HMAC-SHA256 hex string"
            );
        }
        return hashHex;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
