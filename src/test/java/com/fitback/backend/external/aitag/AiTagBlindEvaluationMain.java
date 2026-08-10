package com.fitback.backend.external.aitag;

import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.external.aitag.bedrock.BedrockAiTagModelClient;
import com.fitback.backend.external.aitag.config.AiTagProperties;
import com.fitback.backend.external.aitag.openai.OpenAiTagModelClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class AiTagBlindEvaluationMain {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AiTagBlindEvaluationMain() {
    }

    public static void main(String[] args) throws Exception {
        Path imageDirectory = requiredPath("AI_TAG_BLIND_IMAGES_DIR");
        Path catalogPath = requiredPath("AI_TAG_BLIND_CATALOG");
        Path outputDirectory = Path.of(env(
                "AI_TAG_BLIND_OUTPUT_DIR",
                "build/ai-tag-blind"
        ));
        String openAiApiKey = requiredEnv("FITBACK_AI_OPENAI_API_KEY");

        List<Tag> catalog = readCatalog(catalogPath);
        AiTagModelRequest request = new AiTagRequestFactory().create(catalog);
        AiTagProperties properties = new AiTagProperties(
                "unavailable",
                Duration.parse(env("FITBACK_AI_REQUEST_TIMEOUT", "PT30S")),
                new AiTagProperties.OpenAi(
                        openAiApiKey,
                        requiredEnv("FITBACK_AI_OPENAI_MODEL")
                ),
                new AiTagProperties.Bedrock(
                        requiredEnv("AWS_REGION"),
                        requiredEnv("FITBACK_AI_BEDROCK_MODEL_ID")
                )
        );
        AiTagProperties.OpenAi openAi = properties.openai();
        AiTagProperties.Bedrock bedrock = properties.bedrock();
        openAi.validateForUse();
        bedrock.validateForUse();

        ClientOverrideConfiguration override = ClientOverrideConfiguration.builder()
                .apiCallTimeout(properties.requestTimeout())
                .apiCallAttemptTimeout(properties.requestTimeout())
                .build();
        try (BedrockRuntimeClient bedrockClient = BedrockRuntimeClient.builder()
                .region(Region.of(bedrock.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(override)
                .build()) {
            List<ProviderRun> providers = new ArrayList<>(List.of(
                    new ProviderRun(
                            "openai",
                            openAi.model(),
                            new OpenAiTagModelClient(
                                    openAi,
                                    properties.requestTimeout(),
                                    OBJECT_MAPPER
                            )
                    ),
                    new ProviderRun(
                            "bedrock",
                            bedrock.modelId(),
                            new BedrockAiTagModelClient(
                                    bedrock,
                                    OBJECT_MAPPER,
                                    bedrockClient
                            )
                    )
            ));
            Collections.shuffle(providers, new SecureRandom());
            writeEvaluation(imageDirectory, outputDirectory, request, providers);
        }
    }

    private static void writeEvaluation(
            Path imageDirectory,
            Path outputDirectory,
            AiTagModelRequest request,
            List<ProviderRun> providers
    ) throws Exception {
        List<Path> images;
        try (var stream = Files.list(imageDirectory)) {
            images = stream.filter(Files::isRegularFile)
                    .filter(path -> contentType(path) != null)
                    .sorted()
                    .toList();
        }
        if (images.isEmpty()) {
            throw new IllegalArgumentException("blind image directory has no JPEG, PNG, or WEBP files");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Path imagePath : images) {
            AiTagImage image = new AiTagImage(
                    Files.readAllBytes(imagePath),
                    contentType(imagePath)
            );
            Map<String, Object> imageResult = new LinkedHashMap<>();
            imageResult.put("image", imagePath.getFileName().toString());
            for (int index = 0; index < providers.size(); index++) {
                String slot = Character.toString('A' + index);
                imageResult.put(slot, evaluate(providers.get(index), image, request));
            }
            results.add(imageResult);
        }

        Files.createDirectories(outputDirectory);
        Map<String, Object> blindResult = new LinkedHashMap<>();
        blindResult.put("generatedAt", Instant.now().toString());
        blindResult.put("images", results);
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                outputDirectory.resolve("blind-results.json").toFile(),
                blindResult
        );

        Map<String, Object> key = new LinkedHashMap<>();
        for (int index = 0; index < providers.size(); index++) {
            ProviderRun provider = providers.get(index);
            key.put(Character.toString('A' + index), Map.of(
                    "provider", provider.provider(),
                    "model", provider.model()
            ));
        }
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                outputDirectory.resolve("blind-key.json").toFile(),
                key
        );
    }

    private static Map<String, Object> evaluate(
            ProviderRun provider,
            AiTagImage image,
            AiTagModelRequest request
    ) {
        try {
            AiTagModelResult result = provider.client().analyze(image, request);
            return successfulEvaluation(result);
        } catch (RuntimeException exception) {
            return Map.of("error", "ANALYSIS_FAILED");
        }
    }

    static Map<String, Object> successfulEvaluation(AiTagModelResult result) {
        Map<String, Object> evaluation = new LinkedHashMap<>();
        evaluation.put("garments", result.garments());
        evaluation.put("inputTokens", result.inputTokens());
        evaluation.put("outputTokens", result.outputTokens());
        evaluation.put("elapsedMillis", result.elapsedMillis());
        return evaluation;
    }

    private static List<Tag> readCatalog(Path path) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(Files.readString(path));
        if (!root.isArray()) {
            throw new IllegalArgumentException("catalog must be a JSON array");
        }
        List<Tag> tags = new ArrayList<>();
        for (JsonNode item : root) {
            tags.add(Tag.create(
                    item.path("name").asText(),
                    TagType.valueOf(item.path("type").asText())
            ));
        }
        return List.copyOf(tags);
    }

    private static String contentType(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return null;
    }

    private static Path requiredPath(String name) {
        Path path = Path.of(requiredEnv(name));
        if (!Files.exists(path)) {
            throw new IllegalArgumentException(name + " does not exist");
        }
        return path;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private record ProviderRun(String provider, String model, AiTagModelClient client) {
    }
}
