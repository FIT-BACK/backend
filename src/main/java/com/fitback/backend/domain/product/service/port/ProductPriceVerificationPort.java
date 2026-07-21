package com.fitback.backend.domain.product.service.port;

import com.fitback.backend.domain.product.service.model.ProviderCapabilities;
import com.fitback.backend.domain.product.service.model.ProviderPriceEvidence;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import java.net.URI;
import java.util.Optional;

public interface ProductPriceVerificationPort {

    ProviderCapabilities capabilities();

    Optional<ProviderPriceEvidence> verify(ProviderProductRef providerRef, URI sourceUrl);
}
