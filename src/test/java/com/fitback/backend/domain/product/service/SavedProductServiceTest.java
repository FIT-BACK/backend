package com.fitback.backend.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.product.dto.SavedProductListResponse;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.entity.SavedProduct;
import com.fitback.backend.domain.product.entity.SavedProductId;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.product.repository.SavedProductRepository;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductDataStatus;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

class SavedProductServiceTest {

    private final SavedProductRepository savedProductRepository =
            mock(SavedProductRepository.class);
    private final ProductDetailService productDetailService =
            mock(ProductDetailService.class);
    private final SavedProductService savedProductService = new SavedProductService(
            savedProductRepository,
            mock(MemberRepository.class),
            mock(ProductRepository.class),
            productDetailService,
            new SavedProductCursorCodec()
    );

    @Test
    void keepsRelationshipAndReturnsMinimalItemWhenProductLookupFails() {
        SavedProduct savedProduct = mock(SavedProduct.class);
        when(savedProduct.getId()).thenReturn(
                SavedProductId.create(1L, 100L)
        );
        when(savedProduct.getCreatedAt())
                .thenReturn(Instant.parse("2026-07-26T03:00:00Z"));
        when(savedProductRepository.findFirstPage(
                eq(1L),
                any(PageRequest.class)
        )).thenReturn(new SliceImpl<>(List.of(savedProduct)));
        when(productDetailService.getDetail(100L))
                .thenThrow(new BusinessException(ErrorCode.PRODUCT_PROVIDER_QUOTA_EXCEEDED));

        SavedProductListResponse response = savedProductService.findAll(1L, null, 10);

        assertThat(response.partial()).isTrue();
        assertThat(response.warnings()).containsExactly("PRODUCT503_2");
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo(100L);
            assertThat(item.dataStatus()).isEqualTo(ProductDataStatus.STALE_SNAPSHOT);
            assertThat(item.availability())
                    .isEqualTo(ProductAvailability.TEMPORARILY_UNRESOLVED);
            assertThat(item.name()).isNull();
        });
        verify(savedProductRepository, never()).deleteSavedProduct(any(), any());
    }

    @Test
    void createsSavedAtAtDatabaseMicrosecondPrecision() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        Member member = mock(Member.class);
        Product product = mock(Product.class);

        when(member.getId()).thenReturn(1L);
        when(product.getId()).thenReturn(100L);
        when(memberRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(member));
        when(productRepository.findById(100L)).thenReturn(Optional.of(product));
        when(savedProductRepository.findByIdMemberIdAndIdProductId(1L, 100L))
                .thenReturn(Optional.empty());
        when(savedProductRepository.saveAndFlush(any(SavedProduct.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SavedProductService service = new SavedProductService(
                savedProductRepository,
                memberRepository,
                productRepository,
                productDetailService,
                new SavedProductCursorCodec()
        );

        SavedProductService.SaveResult result = service.save(1L, 100L);

        assertThat(result.response().savedAt()).isNotNull();
        assertThat(result.response().savedAt().getNano() % 1_000).isZero();
    }
}
