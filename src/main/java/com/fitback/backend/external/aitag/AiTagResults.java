package com.fitback.backend.external.aitag;

import java.util.EnumSet;
import java.util.List;

final class AiTagResults {

    private AiTagResults() {
    }

    static List<AiTagGarment> validateGarments(List<AiTagGarment> garments) {
        List<AiTagGarment> result = List.copyOf(garments);
        if (result.isEmpty() || result.size() > AiTagRequestFactory.MAX_GARMENTS) {
            throw new IllegalArgumentException("garments must contain between 1 and 3 items");
        }
        EnumSet<GarmentPiece> pieces = EnumSet.noneOf(GarmentPiece.class);
        if (result.stream().map(AiTagGarment::piece).anyMatch(piece -> !pieces.add(piece))) {
            throw new IllegalArgumentException("garment pieces must be unique");
        }
        return result;
    }
}
