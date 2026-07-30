package com.fitback.backend.domain.image.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.image.entity.ImageVisibility;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.config.ImageStorageProperties;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CloudFrontImageAccessUrlProviderTest {

    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Test
    void createsSignedUrlFromPkcs1DerKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair keyPair = generator.generateKeyPair();
        byte[] pkcs1 = encodePkcs1((RSAPrivateCrtKey) keyPair.getPrivate());
        ImageStorageProperties properties = new ImageStorageProperties(
                "ap-northeast-2",
                "fitback-images",
                "https://images.example.com",
                "TESTKEY",
                Base64.getEncoder().encodeToString(pkcs1)
        );
        CloudFrontImageAccessUrlProvider provider = new CloudFrontImageAccessUrlProvider(
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        Member member = Member.create(
                "image-owner@fitback.test",
                "image-owner",
                "encoded-password",
                LoginProvider.EMAIL
        );
        Image image = Image.createPending(
                "image-id",
                member,
                "images/analysis/1/2026/07/image-id.jpg",
                ImagePurpose.ANALYSIS,
                "image/jpeg",
                128,
                ImageVisibility.PRIVATE,
                NOW.plusSeconds(300)
        );

        String signedUrl = provider.createReadUrl(image);

        assertThat(signedUrl)
                .startsWith("https://images.example.com/images/analysis/1/2026/07/image-id.jpg?")
                .contains("Expires=", "Signature=", "Key-Pair-Id=TESTKEY");
    }

    private byte[] encodePkcs1(RSAPrivateCrtKey key) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeInteger(body, BigInteger.ZERO);
        writeInteger(body, key.getModulus());
        writeInteger(body, key.getPublicExponent());
        writeInteger(body, key.getPrivateExponent());
        writeInteger(body, key.getPrimeP());
        writeInteger(body, key.getPrimeQ());
        writeInteger(body, key.getPrimeExponentP());
        writeInteger(body, key.getPrimeExponentQ());
        writeInteger(body, key.getCrtCoefficient());
        return writeValue(0x30, body.toByteArray());
    }

    private void writeInteger(ByteArrayOutputStream output, BigInteger value) {
        output.writeBytes(writeValue(0x02, value.toByteArray()));
    }

    private byte[] writeValue(int tag, byte[] value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(tag);
        writeLength(output, value.length);
        output.writeBytes(value);
        return output.toByteArray();
    }

    private void writeLength(ByteArrayOutputStream output, int length) {
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
