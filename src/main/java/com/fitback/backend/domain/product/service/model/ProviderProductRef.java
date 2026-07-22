package com.fitback.backend.domain.product.service.model;

import java.util.Objects;

public record ProviderProductRef(
        String provider,
        String externalProductId,
        String externalVariantId,
        String merchantId,
        ProviderIdentityType identityType,
        boolean stable
) {

    public ProviderProductRef {
        provider = ModelValidation.requireNonBlank(provider, "provider");
        externalProductId = ModelValidation.validateNullableText(externalProductId, "externalProductId");
        externalVariantId = ModelValidation.validateNullableText(externalVariantId, "externalVariantId");
        merchantId = ModelValidation.validateNullableText(merchantId, "merchantId");
        Objects.requireNonNull(identityType, "identityType must not be null");

        if (stable && identityType != ProviderIdentityType.PROVIDER_KEY) {
            throw new IllegalArgumentException("stable identity must use PROVIDER_KEY");
        }
        if (!stable && identityType != ProviderIdentityType.SNAPSHOT_UUID) {
            throw new IllegalArgumentException("unstable identity must use SNAPSHOT_UUID");
        }
        if (stable && externalProductId == null) {
            throw new IllegalArgumentException("stable identity requires externalProductId");
        }
    }

    public static ProviderProductRef stable(
            String provider,
            String externalProductId,
            String externalVariantId,
            String merchantId
    ) {
        return new ProviderProductRef(
                provider,
                externalProductId,
                externalVariantId,
                merchantId,
                ProviderIdentityType.PROVIDER_KEY,
                true
        );
    }

    public static ProviderProductRef unstable(String provider) {
        return new ProviderProductRef(
                provider,
                null,
                null,
                null,
                ProviderIdentityType.SNAPSHOT_UUID,
                false
        );
    }
}
