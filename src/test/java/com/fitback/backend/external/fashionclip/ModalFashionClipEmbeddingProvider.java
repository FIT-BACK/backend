package com.fitback.backend.external.fashionclip;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Calls the protected Modal Fashion-CLIP endpoint in ordered batches. */
public final class ModalFashionClipEmbeddingProvider implements FashionClipEmbeddingProvider {

    static final int MAX_BATCH_SIZE = 8;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final URI endpointUri;
    private final String tokenId;
    private final String tokenSecret;
    private final Duration requestTimeout;
    private final RequestSender requestSender;

    public ModalFashionClipEmbeddingProvider(
            URI endpointUri,
            String tokenId,
            String tokenSecret,
            Duration requestTimeout
    ) {
        if (endpointUri == null || !endpointUri.isAbsolute()) {
            throw new IllegalArgumentException("endpointUri must be an absolute URI");
        }
        if (tokenId == null || tokenId.isBlank() || tokenSecret == null || tokenSecret.isBlank()) {
            throw new IllegalArgumentException("Modal proxy credentials must not be blank");
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.endpointUri = endpointUri;
        this.tokenId = tokenId;
        this.tokenSecret = tokenSecret;
        this.requestTimeout = requestTimeout;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
        this.requestSender = request -> httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());
    }

    ModalFashionClipEmbeddingProvider(
            URI endpointUri,
            String tokenId,
            String tokenSecret,
            Duration requestTimeout,
            RequestSender requestSender
    ) {
        if (endpointUri == null || !endpointUri.isAbsolute()) {
            throw new IllegalArgumentException("endpointUri must be an absolute URI");
        }
        if (tokenId == null || tokenId.isBlank() || tokenSecret == null || tokenSecret.isBlank()) {
            throw new IllegalArgumentException("Modal proxy credentials must not be blank");
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (requestSender == null) {
            throw new IllegalArgumentException("requestSender must not be null");
        }
        this.endpointUri = endpointUri;
        this.tokenId = tokenId;
        this.tokenSecret = tokenSecret;
        this.requestTimeout = requestTimeout;
        this.requestSender = requestSender;
    }

    @Override
    public double[] embed(byte[] imageBytes, String contentType) {
        return embedBatch(List.of(new FashionClipImageInput(imageBytes, contentType))).getFirst();
    }

    @Override
    public List<double[]> embedBatch(List<FashionClipImageInput> inputs) {
        FashionClipEmbeddingProvider.validateBatchInputs(inputs);
        List<double[]> embeddings = new ArrayList<>(inputs.size());
        for (int start = 0; start < inputs.size(); start += MAX_BATCH_SIZE) {
            int end = Math.min(start + MAX_BATCH_SIZE, inputs.size());
            embeddings.addAll(requestChunk(inputs.subList(start, end)));
        }
        return FashionClipEmbeddingProvider.validateBatchResult(inputs.size(), embeddings);
    }

    private List<double[]> requestChunk(List<FashionClipImageInput> inputs) {
        try {
            String requestBody = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "images", inputs.stream().map(input -> Map.of(
                            "contentType", input.contentType(),
                            "dataBase64", Base64.getEncoder().encodeToString(input.imageBytes())
                    )).toList()
            ));
            HttpRequest request = HttpRequest.newBuilder(endpointUri)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Modal-Key", tokenId)
                    .header("Modal-Secret", tokenSecret)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = requestSender.send(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Modal Fashion-CLIP endpoint returned HTTP " + response.statusCode());
            }
            JsonNode embeddingsNode = OBJECT_MAPPER.readTree(response.body()).path("embeddings");
            if (!embeddingsNode.isArray() || embeddingsNode.size() != inputs.size()) {
                throw new IllegalStateException("Modal Fashion-CLIP embedding count mismatch");
            }
            List<double[]> embeddings = new ArrayList<>(embeddingsNode.size());
            for (JsonNode embeddingNode : embeddingsNode) {
                embeddings.add(readEmbedding(embeddingNode));
            }
            return embeddings;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Modal Fashion-CLIP request interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Modal Fashion-CLIP request failed", exception);
        }
    }

    @FunctionalInterface
    interface RequestSender {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private static double[] readEmbedding(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalStateException("Modal Fashion-CLIP returned an invalid embedding");
        }
        double[] embedding = new double[node.size()];
        double normSquared = 0.0d;
        for (int index = 0; index < node.size(); index++) {
            JsonNode valueNode = node.get(index);
            if (!valueNode.isNumber()) {
                throw new IllegalStateException("Modal Fashion-CLIP returned a non-numeric embedding");
            }
            double value = valueNode.asDouble();
            if (!Double.isFinite(value)) {
                throw new IllegalStateException("Modal Fashion-CLIP returned a non-finite embedding");
            }
            embedding[index] = value;
            normSquared += value * value;
        }
        if (normSquared == 0.0d) {
            throw new IllegalStateException("Modal Fashion-CLIP returned a zero embedding");
        }
        return embedding;
    }
}
