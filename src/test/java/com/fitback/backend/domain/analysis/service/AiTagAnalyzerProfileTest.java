package com.fitback.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fitback.backend.domain.tag.repository.TagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AiTagAnalyzerProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=prod")
            .withBean(TagRepository.class, () -> mock(TagRepository.class))
            .withUserConfiguration(
                    PrototypeAiTagAnalyzer.class,
                    UnavailableAiTagAnalyzer.class
            );

    @Test
    void productionUsesFailClosedAnalyzerByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AiTagAnalyzer.class);
            assertThat(context.getBean(AiTagAnalyzer.class))
                    .isInstanceOf(UnavailableAiTagAnalyzer.class);
        });
    }

    @Test
    void productionUsesPrototypeAnalyzerOnlyWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues("fitback.ai.tag-analyzer=prototype")
                .run(context -> {
                    assertThat(context).hasSingleBean(AiTagAnalyzer.class);
                    assertThat(context.getBean(AiTagAnalyzer.class))
                            .isInstanceOf(PrototypeAiTagAnalyzer.class);
                });
    }
}
