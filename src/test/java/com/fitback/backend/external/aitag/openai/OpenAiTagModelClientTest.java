package com.fitback.backend.external.aitag.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.external.aitag.AiTagImage;
import com.fitback.backend.external.aitag.AiTagModelRequest;
import com.fitback.backend.external.aitag.AiTagModelResult;
import com.fitback.backend.external.aitag.config.AiTagProperties;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

class OpenAiTagModelClientTest {

    @Test
    void sendsImageWithStrictSchemaAndParsesCanonicalTags() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String outputText = objectMapper.writeValueAsString(Map.of(
                "garments", List.of(Map.of(
                        "piece", "BOTTOM",
                        "canonicalTags", List.of(Map.of(
                                "type", "MATERIAL",
                                "name", "데님"
                        )),
                        "suggestedTags", List.of(Map.of(
                                "type", "COLOR",
                                "name", "인디고 블루",
                                "confidence", 0.94,
                                "evidence", "하의의 짙은 청색 표면"
                        ))
                ))
        ));
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "output", List.of(Map.of(
                        "content", List.of(Map.of(
                                "type", "output_text",
                                "text", outputText
                        ))
                )),
                "usage", Map.of("input_tokens", 20, "output_tokens", 8)
        ));
        AtomicReference<String> requestBody = new AtomicReference<>();
        OpenAiTagModelClient.Transport transport = (endpoint, apiKey, timeout, body) -> {
            requestBody.set(body);
            return new OpenAiTagModelClient.TransportResponse(200, responseBody);
        };
        AiTagProperties.OpenAi properties = new AiTagProperties.OpenAi(
                "test-key",
                "test-model"
        );
        OpenAiTagModelClient client = new OpenAiTagModelClient(
                properties,
                Duration.ofSeconds(1),
                objectMapper,
                transport
        );

        AiTagModelResult result = client.analyze(
                new AiTagImage(new byte[]{1, 2, 3}, "image/jpeg"),
                new AiTagModelRequest("analyze", Map.of("type", "object"))
        );

        var payload = objectMapper.readTree(requestBody.get());
        assertThat(payload.path("reasoning").path("effort").asText()).isEqualTo("none");
        assertThat(payload.path("input").get(0).path("content").get(1).path("detail").asText())
                .isEqualTo("original");
        assertThat(requestBody.get()).contains(
                "input_image",
                "data:image/jpeg;base64,AQID",
                "json_schema"
        );
        assertThat(result.garments()).singleElement().satisfies(garment ->
                assertThat(garment.piece()).isEqualTo(
                        com.fitback.backend.external.aitag.GarmentPiece.BOTTOM
                ));
        assertThat(result.canonicalTags()).singleElement().satisfies(prediction -> {
            assertThat(prediction.type()).isEqualTo(TagType.MATERIAL);
            assertThat(prediction.name()).isEqualTo("데님");
        });
        assertThat(result.suggestedTags()).singleElement().satisfies(suggestion -> {
            assertThat(suggestion.type()).isEqualTo(TagType.COLOR);
            assertThat(suggestion.name()).isEqualTo("인디고 블루");
            assertThat(suggestion.confidence()).isEqualTo(0.94);
        });
        assertThat(result.inputTokens()).isEqualTo(20);
        assertThat(result.outputTokens()).isEqualTo(8);
    }

    @Test
    void translatesHttpErrorToAnalysisNotReady() {
        OpenAiTagModelClient client = clientReturning(429, "rate limited");

        assertAnalysisNotReady(client);
    }

    @Test
    void logsHttpStatusAndSafeProviderCategoryWithoutSensitiveValues() {
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiTagModelClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            OpenAiTagModelClient client = clientReturning(429, "provider-secret-response");

            assertAnalysisNotReady(client);

            String message = appender.list.getFirst().getFormattedMessage();
            assertThat(message)
                    .contains("provider=openai", "model=test-model", "httpStatus=429")
                    .contains("providerErrorCategory=RATE_LIMIT", "elapsedMillis=")
                    .doesNotContain("test-key", "provider-secret-response", "data:image");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void translatesMalformedResponseToAnalysisNotReady() {
        OpenAiTagModelClient client = clientReturning(200, "not-json");

        assertAnalysisNotReady(client);
    }

    @Test
    void logsResponseParsingFailureWithoutResponseBody() {
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiTagModelClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            OpenAiTagModelClient client = clientReturning(200, "provider-secret-response");

            assertAnalysisNotReady(client);

            String message = appender.list.getFirst().getFormattedMessage();
            assertThat(message)
                    .contains("provider=openai", "model=test-model", "responseStatus=200")
                    .contains("responseParsingCategory=INVALID_RESPONSE_JSON", "elapsedMillis=")
                    .doesNotContain("provider-secret-response", "test-key");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void logsResponseMetadataAndMissingOutputStageWithoutSensitiveValues() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String responseSecret = "provider-response-secret";
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "incomplete_details", Map.of("reason", "max_output_tokens"),
                "output", List.of(Map.of(
                        "type", "message",
                        "content", List.of(Map.of(
                                "type", "refusal",
                                "refusal", responseSecret
                        ))
                ))
        ));
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiTagModelClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            OpenAiTagModelClient client = clientReturning(200, responseBody);

            assertAnalysisNotReady(client);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains(
                                    "responseStatus=200",
                                    "incompleteDetailsReason=max_output_tokens",
                                    "outputTypes=[message]",
                                    "contentTypes=[refusal]",
                                    "responseParsingCategory=MISSING_OUTPUT_TEXT"
                            )
                            .doesNotContain(
                                    responseSecret,
                                    "test-key",
                                    "data:image"
                            ));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void logsInvalidModelOutputJsonWithoutOutputTextOrResponseBody() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String responseSecret = "provider-response-secret";
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "incomplete_details", Map.of("reason", "content_filter"),
                "output", List.of(Map.of(
                        "type", "message",
                        "content", List.of(Map.of(
                                "type", "output_text",
                                "text", responseSecret
                        ))
                ))
        ));
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiTagModelClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            OpenAiTagModelClient client = clientReturning(200, responseBody);

            assertAnalysisNotReady(client);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains(
                                    "responseStatus=200",
                                    "incompleteDetailsReason=content_filter",
                                    "outputTypes=[message]",
                                    "contentTypes=[output_text]",
                                    "responseParsingCategory=INVALID_MODEL_OUTPUT_JSON"
                            )
                            .doesNotContain(
                                    responseSecret,
                                    "test-key",
                                    "data:image"
                            ));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void classifiesGarmentWithTwoEmptyTagArraysAsInvalidModelOutputSchema() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String outputText = objectMapper.writeValueAsString(Map.of(
                "garments", List.of(Map.of(
                        "piece", "TOP",
                        "canonicalTags", List.of(),
                        "suggestedTags", List.of()
                ))
        ));
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "output", List.of(Map.of(
                        "type", "message",
                        "content", List.of(Map.of(
                                "type", "output_text",
                                "text", outputText
                        ))
                ))
        ));
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiTagModelClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            OpenAiTagModelClient client = clientReturning(200, responseBody);

            assertAnalysisNotReady(client);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains(
                                    "responseStatus=200",
                                    "outputTypes=[message]",
                                    "contentTypes=[output_text]",
                                    "responseParsingCategory=INVALID_MODEL_OUTPUT_SCHEMA:EMPTY_GARMENT_TAGS"
                            )
                    .doesNotContain(outputText, "test-key", "data:image"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void logsFixedSchemaCategoryWithoutLoggingInvalidModelFieldValue() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String invalidPiece = "MODEL_ONLY_PIECE_VALUE";
        String outputText = objectMapper.writeValueAsString(Map.of(
                "garments", List.of(Map.of(
                        "piece", invalidPiece,
                        "canonicalTags", List.of(Map.of("type", "STYLE", "name", "캐주얼")),
                        "suggestedTags", List.of()
                ))
        ));
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "output", List.of(Map.of(
                        "type", "message",
                        "content", List.of(Map.of("type", "output_text", "text", outputText))
                ))
        ));
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiTagModelClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertAnalysisNotReady(clientReturning(200, responseBody));

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains("responseParsingCategory=INVALID_MODEL_OUTPUT_SCHEMA:INVALID_GARMENT_PIECE")
                            .doesNotContain(invalidPiece, outputText, "test-key", "data:image"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void redactsNonStringResponseMetadataValues() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "incomplete_details", Map.of("reason", 7),
                "output", List.of(Map.of(
                        "type", true,
                        "content", List.of(Map.of("type", 9))
                ))
        ));
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiTagModelClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            OpenAiTagModelClient client = clientReturning(200, responseBody);

            assertAnalysisNotReady(client);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains(
                                    "incompleteDetailsReason=<redacted>",
                                    "outputTypes=[<redacted>]",
                                    "contentTypes=[<redacted>]",
                                    "responseParsingCategory=MISSING_OUTPUT_TEXT"
                            )
                            .doesNotContain("provider-secret-response", "test-key"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void classifiesNonArrayContentAsInvalidResponseShape() throws Exception {
        String responseBody = "{\"output\":[{\"type\":\"message\",\"content\":\"provider-secret-response\"}]}";
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiTagModelClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            OpenAiTagModelClient client = clientReturning(200, responseBody);

            assertAnalysisNotReady(client);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains(
                                    "outputTypes=[message]",
                                    "contentTypes=[]",
                                    "responseParsingCategory=INVALID_RESPONSE_SHAPE"
                            )
                            .doesNotContain("provider-secret-response", "test-key"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void translatesMissingOutputTextToAnalysisNotReady() throws Exception {
        String responseBody = new ObjectMapper().writeValueAsString(Map.of(
                "output", List.of(Map.of(
                        "content", List.of(Map.of("type", "refusal"))
                ))
        ));
        OpenAiTagModelClient client = clientReturning(200, responseBody);

        assertAnalysisNotReady(client);
    }

    private static OpenAiTagModelClient clientReturning(int statusCode, String responseBody) {
        AiTagProperties.OpenAi properties = new AiTagProperties.OpenAi(
                "test-key",
                "test-model"
        );
        OpenAiTagModelClient.Transport transport = (endpoint, apiKey, timeout, body) ->
                new OpenAiTagModelClient.TransportResponse(statusCode, responseBody);
        return new OpenAiTagModelClient(
                properties,
                Duration.ofSeconds(1),
                new ObjectMapper(),
                transport
        );
    }

    private static void assertAnalysisNotReady(OpenAiTagModelClient client) {
        assertThatThrownBy(() -> client.analyze(
                new AiTagImage(new byte[]{1}, "image/jpeg"),
                new AiTagModelRequest("analyze", Map.of("type", "object"))
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_NOT_READY)
        );
    }
}
