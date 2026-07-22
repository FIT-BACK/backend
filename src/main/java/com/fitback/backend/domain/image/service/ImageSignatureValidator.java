package com.fitback.backend.domain.image.service;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class ImageSignatureValidator {

    public boolean matches(String mimeType, byte[] content) {
        return switch (mimeType) {
            case "image/jpeg" -> content.length >= 3
                    && unsigned(content[0]) == 0xFF
                    && unsigned(content[1]) == 0xD8
                    && unsigned(content[2]) == 0xFF;
            case "image/png" -> content.length >= 8
                    && unsigned(content[0]) == 0x89
                    && unsigned(content[1]) == 0x50
                    && unsigned(content[2]) == 0x4E
                    && unsigned(content[3]) == 0x47
                    && unsigned(content[4]) == 0x0D
                    && unsigned(content[5]) == 0x0A
                    && unsigned(content[6]) == 0x1A
                    && unsigned(content[7]) == 0x0A;
            case "image/webp" -> content.length >= 12
                    && ascii(content, 0, 4).equals("RIFF")
                    && ascii(content, 8, 12).equals("WEBP");
            default -> false;
        };
    }

    private String ascii(byte[] content, int from, int to) {
        return new String(content, from, to - from, StandardCharsets.US_ASCII);
    }

    private int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }
}
