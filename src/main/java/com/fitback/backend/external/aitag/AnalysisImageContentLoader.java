package com.fitback.backend.external.aitag;

import com.fitback.backend.domain.image.entity.Image;

public interface AnalysisImageContentLoader {

    AiTagImage load(Image image);
}
