package com.fitback.backend.external.aitag;

import java.util.EnumSet;
import java.util.List;

final class AiTagResults {

    private AiTagResults() {
    }

    static List<AiTagGarment> validateGarments(List<AiTagGarment> garments) {
        List<AiTagGarment> result = List.copyOf(garments);
        EnumSet<GarmentPiece> pieces = EnumSet.noneOf(GarmentPiece.class);
        for (AiTagGarment garment : result) {
            if (!pieces.add(garment.piece())) {
                throw new IllegalArgumentException("garment pieces must be unique");
            }
        }
        if (result.size() != AiTagRequestFactory.MAX_GARMENTS) {
            throw new IllegalArgumentException("garments must contain exactly 1 item");
        }
        return result;
    }
}
