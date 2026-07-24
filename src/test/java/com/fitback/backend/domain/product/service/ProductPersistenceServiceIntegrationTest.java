package com.fitback.backend.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductSnapshot;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class ProductPersistenceServiceIntegrationTest {

    private static final ProviderProductRef PROVIDER_REF = ProviderProductRef.stable(
            "fixture",
            "concurrent-product",
            "variant-1",
            "merchant-1"
    );
    private static final String IDENTITY_KEY =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Autowired
    private ProductPersistenceService persistenceService;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void cleanBefore() {
        productRepository.deleteAll();
    }

    @AfterEach
    void cleanAfter() {
        productRepository.deleteAll();
    }

    @Test
    void concurrentMaterializationReturnsOneProductForTheSameStableIdentity() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ProductPersistenceService.MaterializationResult>> futures =
                    List.of(
                            executor.submit(() -> materializeAfter(start)),
                            executor.submit(() -> materializeAfter(start))
                    );

            start.countDown();
            ProductPersistenceService.MaterializationResult first = futures.get(0).get();
            ProductPersistenceService.MaterializationResult second = futures.get(1).get();

            assertThat(first.product().getId()).isEqualTo(second.product().getId());
            assertThat(List.of(first.created(), second.created()))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(productRepository.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void existingMaterializationDoesNotRefreshSnapshotInsideDuplicateFallback() {
        ProductPersistenceService.MaterializationResult first =
                persistenceService.materializeStable(
                        PROVIDER_REF,
                        IDENTITY_KEY,
                        snapshot("10000.00")
                );

        ProductPersistenceService.MaterializationResult retry =
                persistenceService.materializeStable(
                        PROVIDER_REF,
                        IDENTITY_KEY,
                        snapshot("20000.00")
                );

        assertThat(retry.created()).isFalse();
        assertThat(retry.product().getId()).isEqualTo(first.product().getId());
        Product persisted = productRepository.findById(first.product().getId()).orElseThrow();
        assertThat(persisted.getCurrentPrice()).isEqualByComparingTo("10000.00");
    }

    private ProductPersistenceService.MaterializationResult materializeAfter(
            CountDownLatch start
    ) throws InterruptedException {
        start.await();
        return persistenceService.materializeStable(
                PROVIDER_REF,
                IDENTITY_KEY,
                snapshot("10000.00")
        );
    }

    private static ProductSnapshot snapshot(String currentPrice) {
        return new ProductSnapshot(
                "Concurrent Product",
                "Fixture Brand",
                "Fixture Store",
                ProductCategory.TOP,
                "https://fixture.example/images/concurrent.jpg",
                null,
                new BigDecimal(currentPrice),
                null,
                "KRW",
                Instant.parse("2026-07-24T00:00:00Z"),
                "https://fixture.example/products/concurrent",
                null,
                ProductAvailability.AVAILABLE,
                Instant.parse("2026-07-24T01:00:00Z")
        );
    }
}
