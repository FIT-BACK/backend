package com.fitback.backend.external.aitag;

public final class AiTagRequestIdSanitizer {

    public static final String UNAVAILABLE = "UNAVAILABLE";
    private static final int MAX_LENGTH = 128;

    private AiTagRequestIdSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            return UNAVAILABLE;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                return UNAVAILABLE;
            }
        }
        return value;
    }
}
