package com.fitback.backend.external.fashionclip;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class ModalFashionClipEmbeddingProviderTest {

    @Test
    void sendsAtMostEightImagesPerRequestAndPreservesOrder() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        List<Integer> requestSizes = new ArrayList<>();
        List<HttpRequest> requests = new ArrayList<>();
        ModalFashionClipEmbeddingProvider provider = new ModalFashionClipEmbeddingProvider(
                URI.create("https://example.test/embed"),
                "test-key", "test-secret", Duration.ofSeconds(5), request -> {
                    requests.add(request);
                    int requestIndex = requestCount.getAndIncrement();
                    int size = requestIndex == 0 ? 8 : 3;
                    requestSizes.add(size);
                    StringBuilder response = new StringBuilder("{\"embeddings\":[");
                    int offset = requestIndex == 0 ? 0 : 8;
                    for (int index = 0; index < size; index++) {
                        if (index > 0) {
                            response.append(',');
                        }
                        response.append("[").append(offset + index + 1).append(",0]");
                    }
                    response.append("]}");
                    return new StubHttpResponse(200, response.toString());
                });
        List<FashionClipImageInput> inputs = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            inputs.add(new FashionClipImageInput(new byte[]{(byte) index}, "image/jpeg"));
        }

        List<double[]> embeddings = provider.embedBatch(inputs);

        assertThat(requestCount).hasValue(2);
        assertThat(requestSizes).containsExactly(8, 3);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.headers().firstValue("Modal-Key")).hasValue("test-key");
            assertThat(request.headers().firstValue("Modal-Secret")).hasValue("test-secret");
        });
        assertThat(embeddings).hasSize(11);
        assertThat(embeddings.get(0)).containsExactly(1.0, 0.0);
        assertThat(embeddings.get(10)).containsExactly(11.0, 0.0);
    }

    private record StubHttpResponse(int statusCode, String body) implements HttpResponse<String> {
        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
        }

        @Override
        public URI uri() {
            return URI.create("https://example.test/embed");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }
    }
}
