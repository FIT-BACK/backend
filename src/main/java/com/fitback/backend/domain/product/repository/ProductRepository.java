package com.fitback.backend.domain.product.repository;

import com.fitback.backend.domain.product.entity.Product;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySourceApiAndProviderIdentityKey(
            String sourceApi,
            String providerIdentityKey
    );
}
