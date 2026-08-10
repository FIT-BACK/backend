package com.fitback.backend.external.aitag;

import java.util.List;

public record AiTagModelOutput(List<AiTagGarment> garments) {

    public AiTagModelOutput {
        garments = AiTagResults.validateGarments(garments);
    }
}
