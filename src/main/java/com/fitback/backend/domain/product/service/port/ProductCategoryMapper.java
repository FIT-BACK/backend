package com.fitback.backend.domain.product.service.port;

import com.fitback.backend.domain.product.service.model.ProductCategory;
import java.util.Optional;

public interface ProductCategoryMapper {

    Optional<ProductCategory> map(String provider, String categoryPath);
}
