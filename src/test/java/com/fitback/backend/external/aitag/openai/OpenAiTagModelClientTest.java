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
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

class OpenAiTagModelClientTest {

    @Test
    void sendsImageWithStrictSchemaAndParsesCanonicalTags() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> garments = new LinkedHashMap<>();
        garments.put("TOP", null);
        garments.put("BOTTOM", Map.of(
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
        ));
        garments.put("DRESS", null);
        garments.put("OUTER", null);
        String outputText = objectMapper.writeValueAsString(Map.of("garments", garments));
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
            return new OpenAiTagModelClient.TransportResponse(200, responseBody, "req-200");
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
        assertThat(result.xRequestId()).isEqualTo("req-200");
    }

    @Test
    void acceptsStructuredOutputAfterNonMessageOutputItem() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> garments = new LinkedHashMap<>();
        garments.put("TOP", null);
        garments.put("BOTTOM", Map.of(
                "canonicalTags", List.of(Map.of("type", "MATERIAL", "name", "데님")),
                "suggestedTags", List.of()
        ));
        garments.put("DRESS", null);
        garments.put("OUTER", null);
        String outputText = objectMapper.writeValueAsString(Map.of("garments", garments));
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "output", List.of(
                        Map.of("type", "reasoning", "summary", List.of()),
                        Map.of(
                                "type", "message",
                                "content", List.of(Map.of(
                                        "type", "output_text",
                                        "text", outputText
                                ))
                        )
                )
        ));

        AiTagModelResult result = analyze(clientReturning(200, responseBody));

        assertThat(result.garments()).singleElement().satisfies(garment ->
                assertThat(garment.piece()).isEqualTo(
                        com.fitback.backend.external.aitag.GarmentPiece.BOTTOM
                ));
    }

    @Test
    void findsOutputTextAcrossMultipleMessageContentEntries() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> garments = new LinkedHashMap<>();
        garments.put("TOP", Map.of(
                "canonicalTags", List.of(Map.of("type", "STYLE", "name", "캐주얼")),
                "suggestedTags", List.of()
        ));
        garments.put("BOTTOM", null);
        garments.put("DRESS", null);
        garments.put("OUTER", null);
        String outputText = objectMapper.writeValueAsString(Map.of("garments", garments));
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "output", List.of(Map.of(
                        "type", "message",
                        "content", List.of(
                                Map.of("type", "refusal", "refusal", "unused"),
                                Map.of("type", "output_text", "text", outputText)
                        )
                ))
        ));

        AiTagModelResult result = analyze(clientReturning(200, responseBody));

        assertThat(result.garments()).singleElement().satisfies(garment ->
                assertThat(garment.piece()).isEqualTo(
                        com.fitback.backend.external.aitag.GarmentPiece.TOP
                ));
    }

    @Test
    void rejectsMalformedFencedProseAndTruncatedOutputTextAsInvalidModelOutputJson()
            throws Exception {
        String validJson = "{\"garments\":{\"TOP\":null,\"BOTTOM\":null,"
                + "\"DRESS\":null,\"OUTER\":null}}";
        List<String> invalidOutputs = List.of(
                "not-json",
                "```json\n" + validJson + "\n```",
                "leading prose " + validJson,
                validJson + " trailing prose",
                validJson.substring(0, validJson.length() - 1)
        );

        for (String invalidOutput : invalidOutputs) {
            assertParsingCategory(
                    responseBodyWithOutputText(invalidOutput),
                    "INVALID_MODEL_OUTPUT_JSON"
            );
        }
    }

    @Test
    void classifiesMissingOutputTextSeparatelyFromInvalidModelJson() throws Exception {
        String responseBody = new ObjectMapper().writeValueAsString(Map.of(
                "output", List.of(Map.of(
                        "type", "message",
                        "content", List.of(Map.of("type", "refusal", "refusal", "declined"))
                ))
        ));

        assertParsingCategory(responseBody, "MISSING_OUTPUT_TEXT");
    }

    @Test
    void translatesHttpErrorToAnalysisNotReady() {
        OpenAiTagModelClient client = clientReturning(429, "rate limited");

        assertAnalysisNotReady(client);
    }

    @Test
    void exposesOnlySafeMetadataForProviderHttpFailures() {
        OpenAiTagModelClient client = clientReturning(500, "provider-secret-response", "req-500");

        assertThatThrownBy(() -> client.analyze(
                new AiTagImage(new byte[]{1}, "image/jpeg"),
                new AiTagModelRequest("analyze", Map.of("type", "object"))
        )).isInstanceOfSatisfying(OpenAiTagModelClient.ProviderFailure.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_NOT_READY);
            assertThat(failure.providerHttpStatus()).isEqualTo(500);
            assertThat(failure.providerErrorCategory()).isEqualTo("SERVER_ERROR");
            assertThat(failure.responseParsingCategory()).isNull();
            assertThat(failure.elapsedMillis()).isNotNegative();
            assertThat(failure.xRequestId()).isEqualTo("req-500");
        });
    }

    @Test
    void mapsMissingInvalidAndOversizedRequestIdsToUnavailable() {
        assertThat(new OpenAiTagModelClient.TransportResponse(500, "body").xRequestId())
                .isEqualTo("UNAVAILABLE");
        assertThat(new OpenAiTagModelClient.TransportResponse(500, "body", null).xRequestId())
                .isEqualTo("UNAVAILABLE");
        assertThat(new OpenAiTagModelClient.TransportResponse(500, "body", "").xRequestId())
                .isEqualTo("UNAVAILABLE");
        assertThat(new OpenAiTagModelClient.TransportResponse(500, "body", "   ").xRequestId())
                .isEqualTo("UNAVAILABLE");
        assertThat(new OpenAiTagModelClient.TransportResponse(500, "body", "bad\nvalue").xRequestId())
                .isEqualTo("UNAVAILABLE");
        assertThat(new OpenAiTagModelClient.TransportResponse(500, "body", "x".repeat(129)).xRequestId())
                .isEqualTo("UNAVAILABLE");
    }

    @Test
    void extractsOnlyPrintableRequestIdFromHttpHeaders() {
        assertThat(OpenAiTagModelClient.sanitizeXRequestId(HttpHeaders.of(
                Map.of("x-request-id", List.of("req-header")), (name, value) -> true)))
                .isEqualTo("req-header");
        assertThat(OpenAiTagModelClient.sanitizeXRequestId(HttpHeaders.of(
                Map.of("x-request-id", List.of("bad\tvalue")), (name, value) -> true)))
                .isEqualTo("UNAVAILABLE");
    }

    @Test
    void extractsOnlyBoundedNumericRateLimitMetadataFromHttpHeaders() {
        OpenAiTagModelClient.RateLimitMetadata metadata = OpenAiTagModelClient.rateLimitMetadata(
                HttpHeaders.of(Map.of(
                        "Retry-After", List.of("56"),
                        "x-ratelimit-remaining-requests", List.of("0"),
                        "x-ratelimit-remaining-tokens", List.of("149984"),
                        "x-ratelimit-reset-requests", List.of("1s"),
                        "x-ratelimit-reset-tokens", List.of("6m0s"),
                        "x-ratelimit-remaining-project-tokens", List.of("0"),
                        "x-ratelimit-reset-project-tokens", List.of("3s"),
                        "x-provider-secret", List.of("must-not-be-retained")
                ), (name, value) -> true));

        assertThat(metadata).isEqualTo(new OpenAiTagModelClient.RateLimitMetadata(
                56_000L, 0L, 149_984L, 1_000L, 360_000L, 0L, 3_000L
        ));

        assertThat(OpenAiTagModelClient.rateLimitMetadata(HttpHeaders.of(Map.of(
                "Retry-After", List.of("-1"),
                "x-ratelimit-remaining-requests", List.of("1\n2"),
                "x-ratelimit-reset-tokens", List.of("provider-secret"),
                "x-ratelimit-reset-project-tokens", List.of("25h")
        ), (name, value) -> true))).isNull();

        for (String invalidRetryAfter : List.of("+56", "1e2", "1.5")) {
            assertThat(OpenAiTagModelClient.rateLimitMetadata(HttpHeaders.of(
                    Map.of("Retry-After", List.of(invalidRetryAfter)),
                    (name, value) -> true
            ))).as("Retry-After=%s", invalidRetryAfter).isNull();
        }
        assertThat(OpenAiTagModelClient.rateLimitMetadata(HttpHeaders.of(
                Map.of("x-ratelimit-remaining-requests", List.of("１２")),
                (name, value) -> true
        ))).isNull();
    }

    @Test
    void exposesAllowlistedRateLimitEvidenceWithoutRawProviderValues() {
        OpenAiTagModelClient.RateLimitMetadata metadata =
                new OpenAiTagModelClient.RateLimitMetadata(
                        56_000L, 0L, 0L, 1_000L, 2_000L, 0L, 3_000L
                );
        String rawBody = "{\"error\":{\"code\":\"rate_limit_exceeded\","
                + "\"message\":\"provider-secret-message\"}}";
        OpenAiTagModelClient.Transport transport = (endpoint, apiKey, timeout, body) ->
                new OpenAiTagModelClient.TransportResponse(429, rawBody, "req-429", metadata);
        OpenAiTagModelClient client = new OpenAiTagModelClient(
                new AiTagProperties.OpenAi("test-key", "test-model"),
                Duration.ofSeconds(1),
                new ObjectMapper(),
                transport
        );

        assertThatThrownBy(() -> analyze(client))
                .isInstanceOfSatisfying(OpenAiTagModelClient.ProviderFailure.class, failure -> {
                    assertThat(failure.providerErrorCode()).isEqualTo("rate_limit_exceeded");
                    assertThat(failure.rateLimitMetadata()).isEqualTo(metadata);
                    assertThat(failure.getMessage()).doesNotContain(
                            "provider-secret-message", "test-key", rawBody
                    );
                });
    }

    @Test
    void redactsUnknownProviderErrorCodes() {
        String rawBody = "{\"error\":{\"code\":\"provider-secret-code\"}}";
        OpenAiTagModelClient.Transport transport = (endpoint, apiKey, timeout, body) ->
                new OpenAiTagModelClient.TransportResponse(429, rawBody, "req-429");
        OpenAiTagModelClient client = new OpenAiTagModelClient(
                new AiTagProperties.OpenAi("test-key", "test-model"),
                Duration.ofSeconds(1),
                new ObjectMapper(),
                transport
        );

        assertThatThrownBy(() -> analyze(client))
                .isInstanceOfSatisfying(OpenAiTagModelClient.ProviderFailure.class, failure -> {
                    assertThat(failure.providerErrorCode()).isEqualTo("UNKNOWN");
                    assertThat(failure.getMessage()).doesNotContain("provider-secret-code", rawBody);
                });
    }

    @Test
    void exposesExistingSafeParsingCategoryWithoutResponseText() {
        OpenAiTagModelClient client = clientReturning(200, "provider-secret-response");

        assertThatThrownBy(() -> client.analyze(
                new AiTagImage(new byte[]{1}, "image/jpeg"),
                new AiTagModelRequest("analyze", Map.of("type", "object"))
        )).isInstanceOfSatisfying(OpenAiTagModelClient.ProviderFailure.class, failure -> {
            assertThat(failure.providerHttpStatus()).isEqualTo(200);
            assertThat(failure.providerErrorCategory()).isNull();
            assertThat(failure.responseParsingCategory()).isEqualTo("INVALID_RESPONSE_JSON");
            assertThat(failure.elapsedMillis()).isNotNegative();
        });
    }

    @Test
    void logsHttpStatusAndSafeProviderCategoryWithoutSensitiveValues() {
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiTagModelClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            OpenAiTagModelClient client = clientReturning(429, "provider-secret-response", "req-429");

            assertAnalysisNotReady(client);

            String message = appender.list.getFirst().getFormattedMessage();
            assertThat(message)
                    .contains("provider=openai", "model=test-model", "httpStatus=429")
                    .contains("providerErrorCategory=RATE_LIMIT", "elapsedMillis=", "xRequestId=req-429")
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
    void retainsGarmentCountErrorCategoryForMultipleModelGarments() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String outputText = objectMapper.writeValueAsString(Map.of(
                "garments", List.of(
                        Map.of(
                                "piece", "TOP",
                                "canonicalTags", List.of(Map.of(
                                        "type", "STYLE",
                                        "name", "캐주얼"
                                )),
                                "suggestedTags", List.of()
                        ),
                        Map.of(
                                "piece", "BOTTOM",
                                "canonicalTags", List.of(Map.of(
                                        "type", "MATERIAL",
                                        "name", "데님"
                                )),
                                "suggestedTags", List.of()
                        )
                )
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
            assertAnalysisNotReady(clientReturning(200, responseBody));

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains(
                                    "responseParsingCategory=INVALID_MODEL_OUTPUT_SCHEMA:"
                                            + "GARMENT_COUNT_OUT_OF_RANGE"
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

    @Test
    void retriesTransientProviderStatusesOnceAndRecoversWithinLogicalBudget() throws Exception {
        for (int retryableStatus : List.of(500, 502, 503, 504)) {
            FakeNanoClock clock = new FakeNanoClock();
            List<Duration> attemptTimeouts = new ArrayList<>();
            List<Long> delays = new ArrayList<>();
            AtomicInteger attempts = new AtomicInteger();
            String successBody = successfulResponseBody();
            OpenAiTagModelClient.Transport transport = (endpoint, apiKey, timeout, body) -> {
                attemptTimeouts.add(timeout);
                int attempt = attempts.incrementAndGet();
                clock.advanceMillis(1_000L);
                if (attempt == 1) {
                    return new OpenAiTagModelClient.TransportResponse(
                            retryableStatus, "provider-secret-response", "req-" + retryableStatus + "-1"
                    );
                }
                return new OpenAiTagModelClient.TransportResponse(
                        200, successBody, "req-" + retryableStatus + "-2"
                );
            };
            Logger logger = (Logger) LoggerFactory.getLogger(OpenAiTagModelClient.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                OpenAiTagModelClient client = productionRetryClient(
                        Duration.ofSeconds(30), transport, clock,
                        millis -> {
                            delays.add(millis);
                            clock.advanceMillis(millis);
                        }, bound -> 0L
                );

                AiTagModelResult result = client.analyze(
                        new AiTagImage(new byte[]{1}, "image/jpeg"),
                        new AiTagModelRequest("analyze", Map.of("type", "object"))
                );

                assertThat(attempts).hasValue(2);
                assertThat(delays).containsExactly(250L);
                assertThat(attemptTimeouts).hasSize(2);
                assertThat(attemptTimeouts.get(0)).isEqualTo(Duration.ofSeconds(30));
                assertThat(attemptTimeouts.get(1)).isEqualTo(Duration.ofMillis(28_750L));
                assertThat(result.xRequestId()).isEqualTo("req-" + retryableStatus + "-2");
                assertThat(appender.list)
                        .extracting(ILoggingEvent::getFormattedMessage)
                        .anySatisfy(message -> assertThat(message)
                                .contains(
                                        "attemptCount=1",
                                        "attemptLatencyMillis=1000",
                                        "xRequestId=req-" + retryableStatus + "-1"
                                )
                                .doesNotContain("provider-secret-response"))
                        .anySatisfy(message -> assertThat(message)
                                .contains(
                                        "logicalRequestCount=1",
                                        "providerAttemptCount=2",
                                        "attemptCount=2",
                                        "recoveredByRetry=true",
                                        "logicalLatencyMillis=2250",
                                        "attemptLatencyMillis=1000",
                                        "xRequestId=req-" + retryableStatus + "-2"
                                ));
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    @Test
    void returnsFinalFailureAfterTwoRetryableProviderFailures() {
        FakeNanoClock clock = new FakeNanoClock();
        AtomicInteger attempts = new AtomicInteger();
        OpenAiTagModelClient.Transport transport = (endpoint, apiKey, timeout, body) -> {
            int attempt = attempts.incrementAndGet();
            clock.advanceMillis(10L);
            return new OpenAiTagModelClient.TransportResponse(
                    500, "provider-secret-response", "req-final-" + attempt
            );
        };
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiTagModelClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            OpenAiTagModelClient client = productionRetryClient(
                    Duration.ofSeconds(30), transport, clock,
                    clock::advanceMillis, bound -> 0L
            );

            assertThatThrownBy(() -> analyze(client))
                    .isInstanceOfSatisfying(OpenAiTagModelClient.ProviderFailure.class, failure -> {
                        assertThat(failure.providerHttpStatus()).isEqualTo(500);
                        assertThat(failure.xRequestId()).isEqualTo("req-final-2");
                    });

            assertThat(attempts).hasValue(2);
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains(
                            "logicalRequestCount=1",
                            "providerAttemptCount=2",
                            "attemptCount=2",
                            "recoveredByRetry=false",
                            "final5xx=true",
                            "xRequestId=req-final-2"
                    ));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void doesNotRetry429OrOtherClientErrors() {
        for (int status : List.of(400, 401, 403, 408, 429)) {
            FakeNanoClock clock = new FakeNanoClock();
            AtomicInteger attempts = new AtomicInteger();
            OpenAiTagModelClient.Transport transport = (endpoint, apiKey, timeout, body) -> {
                attempts.incrementAndGet();
                OpenAiTagModelClient.RateLimitMetadata rateLimitMetadata = status == 429
                        ? new OpenAiTagModelClient.RateLimitMetadata(
                                1_000L, 0L, 0L, 1_000L, 1_000L, 0L, 1_000L
                        )
                        : null;
                return new OpenAiTagModelClient.TransportResponse(
                        status,
                        status == 429
                                ? "{\"error\":{\"code\":\"rate_limit_exceeded\"}}"
                                : "body",
                        "req-" + status,
                        rateLimitMetadata
                );
            };
            OpenAiTagModelClient client = productionRetryClient(
                    Duration.ofSeconds(30), transport, clock,
                    millis -> {
                        throw new AssertionError("client errors must not sleep");
                    }, bound -> 0L
            );

            assertThatThrownBy(() -> analyze(client))
                    .isInstanceOf(OpenAiTagModelClient.ProviderFailure.class);
            assertThat(attempts).hasValue(1);
        }
    }

    @Test
    void doesNotRetryUnselectedServerErrorsButReportsFinal5xx() {
        for (int status : List.of(501, 505, 599)) {
            FakeNanoClock clock = new FakeNanoClock();
            AtomicInteger attempts = new AtomicInteger();
            OpenAiTagModelClient.Transport transport = (endpoint, apiKey, timeout, body) -> {
                attempts.incrementAndGet();
                return new OpenAiTagModelClient.TransportResponse(status, "body", "req-" + status);
            };
            Logger logger = (Logger) LoggerFactory.getLogger(OpenAiTagModelClient.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                OpenAiTagModelClient client = productionRetryClient(
                        Duration.ofSeconds(30), transport, clock,
                        millis -> {
                            throw new AssertionError("unselected server errors must not sleep");
                        }, bound -> 0L
                );

                assertThatThrownBy(() -> analyze(client))
                        .isInstanceOf(OpenAiTagModelClient.ProviderFailure.class);

                assertThat(attempts).hasValue(1);
                assertThat(appender.list)
                        .extracting(ILoggingEvent::getFormattedMessage)
                        .anySatisfy(message -> assertThat(message).contains(
                                "providerAttemptCount=1",
                                "attemptCount=1",
                                "final5xx=true",
                                "xRequestId=req-" + status
                        ));
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    @Test
    void doesNotRetryTimeoutOrTransportErrors() {
        for (IOException failure : List.of(
                new HttpTimeoutException("provider-secret-timeout"),
                new IOException("provider-secret-transport")
        )) {
            FakeNanoClock clock = new FakeNanoClock();
            AtomicInteger attempts = new AtomicInteger();
            OpenAiTagModelClient.Transport transport = (endpoint, apiKey, timeout, body) -> {
                attempts.incrementAndGet();
                throw failure;
            };
            OpenAiTagModelClient client = productionRetryClient(
                    Duration.ofSeconds(30), transport, clock,
                    millis -> {
                        throw new AssertionError("transport failures must not sleep");
                    }, bound -> 0L
            );

            assertThatThrownBy(() -> analyze(client))
                    .isInstanceOf(OpenAiTagModelClient.ProviderFailure.class);
            assertThat(attempts).hasValue(1);
        }
    }

    @Test
    void doesNotRetryParserOrSchemaFailures() throws Exception {
        for (String responseBody : List.of("not-json", invalidSchemaResponseBody())) {
            FakeNanoClock clock = new FakeNanoClock();
            AtomicInteger attempts = new AtomicInteger();
            OpenAiTagModelClient.Transport transport = (endpoint, apiKey, timeout, body) -> {
                attempts.incrementAndGet();
                return new OpenAiTagModelClient.TransportResponse(200, responseBody, "req-parse");
            };
            OpenAiTagModelClient client = productionRetryClient(
                    Duration.ofSeconds(30), transport, clock,
                    millis -> {
                        throw new AssertionError("parsing failures must not sleep");
                    }, bound -> 0L
            );

            assertThatThrownBy(() -> analyze(client))
                    .isInstanceOf(OpenAiTagModelClient.ProviderFailure.class);
            assertThat(attempts).hasValue(1);
        }
    }

    @Test
    void skipsRetryWhenLogicalDeadlineCannotFitDelayAndMinimumAttemptWindow() {
        FakeNanoClock clock = new FakeNanoClock();
        AtomicInteger attempts = new AtomicInteger();
        List<Duration> attemptTimeouts = new ArrayList<>();
        OpenAiTagModelClient.Transport transport = (endpoint, apiKey, timeout, body) -> {
            attempts.incrementAndGet();
            attemptTimeouts.add(timeout);
            clock.advanceMillis(29_600L);
            return new OpenAiTagModelClient.TransportResponse(500, "body", "req-budget");
        };
        OpenAiTagModelClient client = productionRetryClient(
                Duration.ofSeconds(30), transport, clock,
                millis -> {
                    throw new AssertionError("insufficient budget must not sleep");
                }, bound -> bound - 1L
        );

        assertThatThrownBy(() -> analyze(client))
                .isInstanceOf(OpenAiTagModelClient.ProviderFailure.class);

        assertThat(attempts).hasValue(1);
        assertThat(attemptTimeouts).containsExactly(Duration.ofSeconds(30));
        assertThat(clock.nanoTime()).isEqualTo(Duration.ofMillis(29_600L).toNanos());
    }

    private static OpenAiTagModelClient clientReturning(int statusCode, String responseBody) {
        return clientReturning(statusCode, responseBody, null);
    }

    private static OpenAiTagModelClient clientReturning(
            int statusCode, String responseBody, String xRequestId
    ) {
        AiTagProperties.OpenAi properties = new AiTagProperties.OpenAi(
                "test-key",
                "test-model"
        );
        OpenAiTagModelClient.Transport transport = (endpoint, apiKey, timeout, body) ->
                new OpenAiTagModelClient.TransportResponse(statusCode, responseBody, xRequestId);
        return new OpenAiTagModelClient(
                properties,
                Duration.ofSeconds(1),
                new ObjectMapper(),
                transport
        );
    }

    private static OpenAiTagModelClient productionRetryClient(
            Duration logicalTimeout,
            OpenAiTagModelClient.Transport transport,
            OpenAiTagModelClient.NanoClock clock,
            OpenAiTagModelClient.Sleeper sleeper,
            OpenAiTagModelClient.Jitter jitter
    ) {
        AiTagProperties.OpenAi properties = new AiTagProperties.OpenAi("test-key", "test-model");
        return OpenAiTagModelClient.forProduction(
                properties, logicalTimeout, new ObjectMapper(), transport, clock, sleeper, jitter
        );
    }

    private static AiTagModelResult analyze(OpenAiTagModelClient client) {
        return client.analyze(
                new AiTagImage(new byte[]{1}, "image/jpeg"),
                new AiTagModelRequest("analyze", Map.of("type", "object"))
        );
    }

    private static String successfulResponseBody() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> garments = new LinkedHashMap<>();
        garments.put("TOP", null);
        garments.put("BOTTOM", Map.of(
                "canonicalTags", List.of(Map.of("type", "MATERIAL", "name", "데님")),
                "suggestedTags", List.of()
        ));
        garments.put("DRESS", null);
        garments.put("OUTER", null);
        String outputText = objectMapper.writeValueAsString(Map.of(
                "garments", garments
        ));
        return objectMapper.writeValueAsString(Map.of(
                "output", List.of(Map.of(
                        "content", List.of(Map.of("type", "output_text", "text", outputText))
                ))
        ));
    }

    private static String invalidSchemaResponseBody() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String outputText = objectMapper.writeValueAsString(Map.of(
                "garments", List.of(Map.of(
                        "piece", "TOP",
                        "canonicalTags", List.of(),
                        "suggestedTags", List.of()
                ))
        ));
        return objectMapper.writeValueAsString(Map.of(
                "output", List.of(Map.of(
                        "content", List.of(Map.of("type", "output_text", "text", outputText))
                ))
        ));
    }

    private static String responseBodyWithOutputText(String outputText) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of(
                "output", List.of(Map.of(
                        "type", "message",
                        "content", List.of(Map.of("type", "output_text", "text", outputText))
                ))
        ));
    }

    private static void assertParsingCategory(String responseBody, String expectedCategory) {
        assertThatThrownBy(() -> analyze(clientReturning(200, responseBody)))
                .isInstanceOfSatisfying(OpenAiTagModelClient.ProviderFailure.class, failure ->
                        assertThat(failure.responseParsingCategory()).isEqualTo(expectedCategory)
                );
    }

    private static final class FakeNanoClock implements OpenAiTagModelClient.NanoClock {

        private long nowNanos;

        @Override
        public long nanoTime() {
            return nowNanos;
        }

        private void advanceMillis(long millis) {
            nowNanos += Duration.ofMillis(millis).toNanos();
        }
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
