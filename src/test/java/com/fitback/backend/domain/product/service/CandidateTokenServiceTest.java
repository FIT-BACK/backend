package com.fitback.backend.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.external.shopping.config.ShoppingProviderProperties;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CandidateTokenServiceTest {

    private static final String SECRET =
            "candidate-token-test-secret-key-at-least-32-bytes";
    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");
    private static final ProviderProductRef STABLE_REF = ProviderProductRef.stable(
            "fixture",
            "product-1",
            "variant-1",
            "merchant-1"
    );

    @Test
    void roundTripsStableIdentityForTheSameMember() {
        CandidateTokenService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        String token = service.issue(10L, STABLE_REF);

        assertThat(service.verify(token, 10L)).isEqualTo(STABLE_REF);
        assertThat(token).doesNotContain("product-1", "merchant-1");
    }

    @Test
    void rejectsTamperedToken() {
        CandidateTokenService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
        String token = service.issue(10L, STABLE_REF);
        String[] parts = token.split("\\.");
        String signature = parts[2];
        String tampered = parts[0] + "." + parts[1] + "."
                + (signature.startsWith("A") ? "B" : "A")
                + signature.substring(1);

        assertInvalid(() -> service.verify(tampered, 10L));
    }

    @Test
    void rejectsTokenUsedByAnotherMember() {
        CandidateTokenService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
        String token = service.issue(10L, STABLE_REF);

        assertInvalid(() -> service.verify(token, 11L));
    }

    @Test
    void rejectsExpiredToken() {
        String token = service(Clock.fixed(NOW, ZoneOffset.UTC)).issue(10L, STABLE_REF);
        CandidateTokenService expiredService = service(
                Clock.fixed(NOW.plus(Duration.ofMinutes(10)), ZoneOffset.UTC)
        );

        assertInvalid(() -> expiredService.verify(token, 10L));
    }

    @Test
    void rejectsOversizedToken() {
        CandidateTokenService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        assertInvalid(() -> service.verify("x".repeat(4097), 10L));
    }

    @Test
    void doesNotIssueTokenForUnstableIdentity() {
        CandidateTokenService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.issue(10L, ProviderProductRef.unstable("fixture")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.PRODUCT_REFERENCE_UNSUPPORTED)
                );
    }

    private static CandidateTokenService service(Clock clock) {
        return new CandidateTokenService(
                new ObjectMapper(),
                clock,
                new ShoppingProviderProperties(
                        ShoppingProviderProperties.Provider.FIXTURE,
                        new ShoppingProviderProperties.Shopify(false),
                        new ShoppingProviderProperties.CandidateToken(Duration.ofMinutes(10))
                ),
                SECRET
        );
    }

    private static void assertInvalid(ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.PRODUCT_REFERENCE_INVALID)
                );
    }

    @FunctionalInterface
    private interface ThrowingOperation {

        void run();
    }
}
