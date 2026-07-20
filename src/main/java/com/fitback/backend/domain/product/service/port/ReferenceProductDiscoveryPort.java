package com.fitback.backend.domain.product.service.port;

import com.fitback.backend.domain.product.service.model.ProviderCapabilities;
import com.fitback.backend.domain.product.service.model.ReferenceProductCandidate;
import com.fitback.backend.domain.product.service.model.ReferenceProductDiscoveryQuery;
import java.util.List;

public interface ReferenceProductDiscoveryPort {

    ProviderCapabilities capabilities();

    List<ReferenceProductCandidate> discover(ReferenceProductDiscoveryQuery query);
}
