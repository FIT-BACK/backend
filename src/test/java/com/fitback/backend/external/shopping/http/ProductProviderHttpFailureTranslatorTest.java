package com.fitback.backend.external.shopping.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.exception.ProductProviderFailure;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProductProviderHttpFailureTranslatorTest {

    @Test
    void translatesTimeoutWithoutLeakingRequestDetails() {
        ProductProviderException exception =
                ProductProviderHttpFailureTranslator.timeout("shopify");

        assertThat(exception.getProvider()).isEqualTo("shopify");
        assertThat(exception.getFailure()).isEqualTo(ProductProviderFailure.TIMEOUT);
        assertThat(exception.getMessage()).doesNotContain("url", "token", "key");
    }

    @Test
    void translatesRateLimitResponse() {
        ProductProviderException exception =
                ProductProviderHttpFailureTranslator.fromStatus("shopify", 429);

        assertThat(exception.getFailure()).isEqualTo(ProductProviderFailure.RATE_LIMITED);
    }

    @ParameterizedTest
    @MethodSource("serverErrors")
    void translatesServerErrorsToUnavailable(int statusCode) {
        ProductProviderException exception =
                ProductProviderHttpFailureTranslator.fromStatus("shopify", statusCode);

        assertThat(exception.getFailure()).isEqualTo(ProductProviderFailure.UNAVAILABLE);
    }

    @Test
    void translatesMalformedPayloadSeparatelyFromHttpFailure() {
        ProductProviderException exception =
                ProductProviderHttpFailureTranslator.malformedResponse("shopify");

        assertThat(exception.getFailure())
                .isEqualTo(ProductProviderFailure.MALFORMED_RESPONSE);
    }

    @Test
    void rejectsSuccessfulStatusCodes() {
        assertThatThrownBy(
                () -> ProductProviderHttpFailureTranslator.fromStatus("shopify", 200)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> serverErrors() {
        return Stream.of(
                Arguments.of(500),
                Arguments.of(502),
                Arguments.of(503),
                Arguments.of(504)
        );
    }
}
