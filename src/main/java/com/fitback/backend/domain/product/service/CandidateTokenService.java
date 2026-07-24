package com.fitback.backend.domain.product.service;

import com.fitback.backend.domain.product.service.model.ProviderIdentityType;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.external.shopping.config.ShoppingProviderProperties;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class CandidateTokenService {

    private static final String VERSION = "v1";
    private static final String PURPOSE = "PRODUCT_MATERIALIZATION";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MAX_TOKEN_LENGTH = 4096;

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ShoppingProviderProperties properties;
    private final byte[] secret;

    public CandidateTokenService(
            ObjectMapper objectMapper,
            Clock clock,
            ShoppingProviderProperties properties,
            @Value("${jwt.token.secretKey}") String secret
    ) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(long memberId, ProviderProductRef providerRef) {
        if (memberId <= 0 || !providerRef.stable()) {
            throw new BusinessException(ErrorCode.PRODUCT_REFERENCE_UNSUPPORTED);
        }

        Instant expiresAt = clock.instant().plus(properties.candidateToken().ttl());
        Payload payload = new Payload(
                memberId,
                PURPOSE,
                expiresAt.getEpochSecond(),
                providerRef.provider(),
                providerRef.externalProductId(),
                providerRef.externalVariantId(),
                providerRef.merchantId(),
                providerRef.identityType().name(),
                providerRef.stable()
        );
        try {
            String encodedPayload = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
            String signedContent = VERSION + "." + encodedPayload;
            String signature = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(sign(signedContent));
            return signedContent + "." + signature;
        } catch (JacksonException | GeneralSecurityException exception) {
            throw new IllegalStateException("candidate token payload could not be encoded", exception);
        }
    }

    public ProviderProductRef verify(String token, long memberId) {
        try {
            if (token == null || token.length() > MAX_TOKEN_LENGTH) {
                throw invalidToken();
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw invalidToken();
            }

            byte[] actualSignature = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expectedSignature = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
                throw invalidToken();
            }

            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            Payload payload = objectMapper.readValue(payloadBytes, Payload.class);
            if (payload.memberId() != memberId
                    || !PURPOSE.equals(payload.purpose())
                    || payload.expiresAtEpochSecond() <= clock.instant().getEpochSecond()) {
                throw invalidToken();
            }

            ProviderIdentityType identityType =
                    ProviderIdentityType.valueOf(payload.identityType());
            return new ProviderProductRef(
                    payload.provider(),
                    payload.externalProductId(),
                    payload.externalVariantId(),
                    payload.merchantId(),
                    identityType,
                    payload.stable()
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidToken();
        }
    }

    private byte[] sign(String content) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
        return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
    }

    private static BusinessException invalidToken() {
        return new BusinessException(ErrorCode.PRODUCT_REFERENCE_INVALID);
    }

    private record Payload(
            long memberId,
            String purpose,
            long expiresAtEpochSecond,
            String provider,
            String externalProductId,
            String externalVariantId,
            String merchantId,
            String identityType,
            boolean stable
    ) {
    }
}
