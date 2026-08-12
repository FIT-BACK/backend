package com.fitback.backend.domain.lookbook.service;

import static com.fitback.backend.domain.lookbook.LookbookImageFixtures.readyImage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.exception.ProductProviderFailure;
import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductOffer;
import com.fitback.backend.domain.product.service.model.ProductStorageMode;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LookbookProductImageResolverTest {

    private static final ProviderProductRef PROVIDER_REF = ProviderProductRef.stable(
            "shopify",
            "product-1",
            "variant-1",
            "merchant-1"
    );

    @Mock
    private ProductCatalogPort productCatalogPort;

    private LookbookProductImageResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new LookbookProductImageResolver(productCatalogPort);
    }

    @Test
    void resolvesSnapshotProductFromStoredImageUrl() {
        Product product = product(ProductStorageMode.SNAPSHOT);
        when(product.getImageUrl()).thenReturn("https://cdn.example.com/snapshot.jpg");

        assertThat(resolver.resolve(product))
                .isEqualTo("https://cdn.example.com/snapshot.jpg");
        verify(productCatalogPort, never()).lookup(PROVIDER_REF);
    }

    @Test
    void resolvesIdentityOnlyProductFromLiveLookupRegardlessOfAvailability() {
        Product product = product(ProductStorageMode.IDENTITY_ONLY);
        ExternalProductCandidate candidate = candidate(
                URI.create("https://cdn.example.com/live.jpg")
        );
        when(productCatalogPort.lookup(PROVIDER_REF)).thenReturn(Optional.of(candidate));

        assertThat(resolver.resolve(product))
                .isEqualTo("https://cdn.example.com/live.jpg");
    }

    @Test
    void rejectsIdentityOnlyProductWhenLiveImageIsMissing() {
        Product product = product(ProductStorageMode.IDENTITY_ONLY);
        when(productCatalogPort.lookup(PROVIDER_REF))
                .thenReturn(Optional.of(candidate(null)));

        assertThatThrownBy(() -> resolver.resolve(product))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void propagatesMappedProviderFailure() {
        Product product = product(ProductStorageMode.IDENTITY_ONLY);
        when(productCatalogPort.lookup(PROVIDER_REF)).thenThrow(new ProductProviderException(
                "shopify",
                ProductProviderFailure.TIMEOUT
        ));

        assertThatThrownBy(() -> resolver.resolve(product))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_PROVIDER_UNAVAILABLE);
    }

    @Test
    void keepsPublishedLookbookImageSnapshotWhenProviderImageChanges() {
        Product product = product(ProductStorageMode.IDENTITY_ONLY);
        when(productCatalogPort.lookup(PROVIDER_REF))
                .thenReturn(Optional.of(candidate(
                        URI.create("https://cdn.example.com/published.jpg")
                )))
                .thenReturn(Optional.of(candidate(
                        URI.create("https://cdn.example.com/changed.jpg")
                )));
        Member member = Member.create(
                "member@fitback.com",
                "fitback",
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(member, "id", 1L);

        String publishedImageUrl = resolver.resolve(product);
        Lookbook lookbook = Lookbook.createWithProduct(
                member,
                readyImage("original", member, ImagePurpose.LOOKBOOK),
                product,
                publishedImageUrl,
                null,
                null
        );

        assertThat(resolver.resolve(product))
                .isEqualTo("https://cdn.example.com/changed.jpg");
        assertThat(lookbook.getMatchedProductImageUrl())
                .isEqualTo("https://cdn.example.com/published.jpg");
    }

    private static Product product(ProductStorageMode storageMode) {
        Product product = mock(Product.class);
        when(product.getStorageMode()).thenReturn(storageMode);
        if (storageMode == ProductStorageMode.IDENTITY_ONLY) {
            when(product.getSourceApi()).thenReturn("shopify");
            when(product.getExternalProductId()).thenReturn("product-1");
            when(product.getExternalVariantId()).thenReturn("variant-1");
            when(product.getMerchantId()).thenReturn("merchant-1");
        }
        return product;
    }

    private static ExternalProductCandidate candidate(URI imageUrl) {
        return new ExternalProductCandidate(
                PROVIDER_REF,
                "상품",
                null,
                null,
                new ProductOffer(
                        null,
                        null,
                        null,
                        ProductAvailability.TEMPORARILY_UNRESOLVED,
                        null,
                        null,
                        null,
                        Instant.parse("2026-08-12T00:00:00Z")
                ),
                imageUrl,
                null,
                Instant.parse("2026-08-12T00:00:00Z")
        );
    }
}
