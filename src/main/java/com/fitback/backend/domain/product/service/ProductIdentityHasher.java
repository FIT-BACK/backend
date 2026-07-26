package com.fitback.backend.domain.product.service;

import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class ProductIdentityHasher {

    public String hash(ProviderProductRef providerRef) {
        if (!providerRef.stable()) {
            throw new IllegalArgumentException("provider identity hash requires stable identity");
        }
        String canonical = String.join(
                "\u0000",
                providerRef.provider(),
                providerRef.externalProductId(),
                nullable(providerRef.externalVariantId()),
                nullable(providerRef.merchantId())
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }
}
