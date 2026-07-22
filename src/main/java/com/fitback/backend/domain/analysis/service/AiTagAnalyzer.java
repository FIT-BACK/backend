package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.domain.image.entity.ImageAsset;
import com.fitback.backend.domain.tag.entity.Tag;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface AiTagAnalyzer {

    List<Tag> analyze(MultipartFile image);

    List<Tag> analyze(ImageAsset image);
}
