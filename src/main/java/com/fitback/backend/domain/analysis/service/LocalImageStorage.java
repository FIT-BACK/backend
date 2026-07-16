package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class LocalImageStorage implements ImageStorage {

    private static final long MAX_IMAGE_SIZE = 5L * 1024 * 1024;
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );

    private final Path uploadDirectory;

    public LocalImageStorage(@Value("${fitback.storage.upload-directory}") String uploadDirectory) {
        this.uploadDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize();
    }

    @Override
    public String store(MultipartFile image) {
        if (image == null || image.isEmpty() || image.getSize() > MAX_IMAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_ANALYSIS_IMAGE);
        }

        String contentType = image.getContentType();
        String normalizedContentType = contentType == null
                ? ""
                : contentType.toLowerCase(Locale.ROOT);
        String extension = EXTENSIONS.get(normalizedContentType);
        if (extension == null) {
            throw new BusinessException(ErrorCode.INVALID_ANALYSIS_IMAGE);
        }

        try {
            byte[] content = image.getBytes();
            if (!hasExpectedSignature(content, extension)) {
                throw new BusinessException(ErrorCode.INVALID_ANALYSIS_IMAGE);
            }

            Files.createDirectories(uploadDirectory);
            String fileName = UUID.randomUUID() + "." + extension;
            Path target = uploadDirectory.resolve(fileName).normalize();
            if (!target.getParent().equals(uploadDirectory)) {
                throw new BusinessException(ErrorCode.INVALID_ANALYSIS_IMAGE);
            }
            Files.write(target, content, StandardOpenOption.CREATE_NEW);
            return "/uploads/" + fileName;
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.ANALYSIS_IMAGE_STORAGE_ERROR);
        }
    }

    private boolean hasExpectedSignature(byte[] content, String extension) {
        return switch (extension) {
            case "jpg" -> content.length >= 3
                    && unsigned(content[0]) == 0xFF
                    && unsigned(content[1]) == 0xD8
                    && unsigned(content[2]) == 0xFF;
            case "png" -> content.length >= 8
                    && unsigned(content[0]) == 0x89
                    && unsigned(content[1]) == 0x50
                    && unsigned(content[2]) == 0x4E
                    && unsigned(content[3]) == 0x47
                    && unsigned(content[4]) == 0x0D
                    && unsigned(content[5]) == 0x0A
                    && unsigned(content[6]) == 0x1A
                    && unsigned(content[7]) == 0x0A;
            case "webp" -> content.length >= 12
                    && content[0] == 'R'
                    && content[1] == 'I'
                    && content[2] == 'F'
                    && content[3] == 'F'
                    && content[8] == 'W'
                    && content[9] == 'E'
                    && content[10] == 'B'
                    && content[11] == 'P';
            default -> false;
        };
    }

    private int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }
}
