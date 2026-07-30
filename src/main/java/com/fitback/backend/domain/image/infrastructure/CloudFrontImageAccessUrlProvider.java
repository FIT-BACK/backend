package com.fitback.backend.domain.image.infrastructure;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImageVisibility;
import com.fitback.backend.domain.image.service.ImageAccessUrlProvider;
import com.fitback.backend.global.config.ImageStorageProperties;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
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
    private static final byte[] RSA_ALGORITHM_IDENTIFIER = {
            0x30, 0x0d,
            0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00
    };

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
            byte[] encoded = Base64.getMimeDecoder().decode(
                    properties.cloudfrontPrivateKeyBase64()
            );
            String decodedText = new String(encoded, StandardCharsets.US_ASCII);
            if (decodedText.contains("-----BEGIN PRIVATE KEY-----")
                    || decodedText.contains("-----BEGIN RSA PRIVATE KEY-----")) {
                String privateKeyBody = decodedText
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                        .replace("-----END RSA PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
                encoded = Base64.getDecoder().decode(privateKeyBody);
            }
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            try {
                return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
            } catch (GeneralSecurityException pkcs8Exception) {
                return keyFactory.generatePrivate(
                        new PKCS8EncodedKeySpec(wrapPkcs1AsPkcs8(encoded))
                );
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid CloudFront private key", exception);
        }
    }

    private byte[] wrapPkcs1AsPkcs8(byte[] pkcs1) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(new byte[]{0x02, 0x01, 0x00});
        body.writeBytes(RSA_ALGORITHM_IDENTIFIER);
        writeDerValue(body, 0x04, pkcs1);

        ByteArrayOutputStream pkcs8 = new ByteArrayOutputStream();
        writeDerValue(pkcs8, 0x30, body.toByteArray());
        return pkcs8.toByteArray();
    }

    private void writeDerValue(ByteArrayOutputStream output, int tag, byte[] value) {
        output.write(tag);
        writeDerLength(output, value.length);
        output.writeBytes(value);
    }

    private void writeDerLength(ByteArrayOutputStream output, int length) {
        if (length < 128) {
            output.write(length);
            return;
        }
        int byteCount = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
        output.write(0x80 | byteCount);
        for (int shift = (byteCount - 1) * 8; shift >= 0; shift -= 8) {
            output.write((length >> shift) & 0xff);
        }
    }
}
