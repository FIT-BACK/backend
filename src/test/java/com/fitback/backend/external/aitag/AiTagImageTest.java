package com.fitback.backend.external.aitag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class AiTagImageTest {

    @Test
    void normalizesContentTypeIndependentlyOfDefaultLocale() {
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));

            AiTagImage image = new AiTagImage(new byte[]{1}, "IMAGE/PNG");

            assertThat(image.contentType()).isEqualTo("image/png");
        } finally {
            Locale.setDefault(originalLocale);
        }
    }
}
