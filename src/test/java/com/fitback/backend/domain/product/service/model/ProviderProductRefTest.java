package com.fitback.backend.domain.product.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProviderProductRefTest {

    @Test
    void stableReferenceRequiresProviderProductIdentity() {
        ProviderProductRef reference = ProviderProductRef.stable(
                "fixture",
                "product-1",
                null,
                "store-1"
        );

        assertThat(reference.identityType()).isEqualTo(ProviderIdentityType.PROVIDER_KEY);
        assertThat(reference.stable()).isTrue();
        assertThat(reference.externalVariantId()).isNull();

        assertThatThrownBy(() -> ProviderProductRef.stable("fixture", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unstableReferenceKeepsUnknownIdentityFieldsNull() {
        ProviderProductRef reference = ProviderProductRef.unstable("fixture");

        assertThat(reference.identityType()).isEqualTo(ProviderIdentityType.SNAPSHOT_UUID);
        assertThat(reference.stable()).isFalse();
        assertThat(reference.externalProductId()).isNull();
        assertThat(reference.externalVariantId()).isNull();
        assertThat(reference.merchantId()).isNull();
    }

    @Test
    void rejectsIdentityTypeAndStableFlagMismatch() {
        assertThatThrownBy(() -> new ProviderProductRef(
                "fixture",
                "product-1",
                null,
                null,
                ProviderIdentityType.SNAPSHOT_UUID,
                true
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
