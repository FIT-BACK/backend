package com.fitback.backend.external.aitag;

public interface AiTagModelClient {

    AiTagModelResult analyze(AiTagImage image, AiTagModelRequest request);
}
