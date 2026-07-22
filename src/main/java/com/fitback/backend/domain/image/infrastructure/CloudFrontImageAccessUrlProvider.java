package com.fitback.backend.domain.image.infrastructure;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImageVisibility;
import com.fitback.backend.domain.image.service.ImageAccessUrlProvider;
import com.fitback.backend.global.config.ImageStorageProperties;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.model.CannedSignerRequest;

@Component
@RequiredArgsConstructor
public class CloudFrontImageAccessUrlProvider implements ImageAccessUrlProvider {

    private static final Duration PRIVATE_URL_EXPIRY = Duration.ofMinutes(10);

    private final ImageStorageProperties properties;
    private final Clock clock;
    private volatile PrivateKey cachedPrivateKey;

    @Override
    public String createReadUrl(Image image) {
        String resourceUrl = "%s/%s".formatted(
                properties.cdnBaseUrl().replaceAll("/$", ""),
                image.getObjectKey()
        );
        if (image.getVisibility() == ImageVisibility.PUBLIC) {
            return resourceUrl;
        }
        try {
            CannedSignerRequest request = CannedSignerRequest.builder()
                    .resourceUrl(resourceUrl)
                    .privateKey(readPrivateKey())
                    .keyPairId(properties.cloudfrontKeyPairId())
                    .expirationDate(clock.instant().plus(PRIVATE_URL_EXPIRY))
                    .build();
            return CloudFrontUtilities.create()
                    .getSignedUrlWithCannedPolicy(request)
                    .url();
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.IMAGE_STORAGE_ERROR);
        }
    }

    private PrivateKey readPrivateKey() {
        PrivateKey privateKey = cachedPrivateKey;
        if (privateKey != null) {
            return privateKey;
        }
        synchronized (this) {
            if (cachedPrivateKey == null) {
                cachedPrivateKey = parsePrivateKey();
            }
            return cachedPrivateKey;
        }
    }

    private PrivateKey parsePrivateKey() {
        try {
            byte[] encoded = Base64.getDecoder().decode(
                    properties.cloudfrontPrivateKeyBase64()
            );
            String decodedText = new String(encoded, StandardCharsets.US_ASCII);
            if (decodedText.contains("-----BEGIN PRIVATE KEY-----")) {
                String privateKeyBody = decodedText
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
                encoded = Base64.getDecoder().decode(privateKeyBody);
            }
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid CloudFront private key", exception);
        }
    }
}
