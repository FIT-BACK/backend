package com.fitback.backend.global.security.token;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryTempTokenStore implements TempTokenStore {

    // key = 임시토큰, value = payload + 만료시각
    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    private final Duration ttl;

    public InMemoryTempTokenStore(@Value("${app.oauth.temp-token-ttl}") long ttlMillis) {
        this.ttl = Duration.ofMillis(ttlMillis);
    }

    private record Entry(TempTokenPayload payload, Instant expiresAt) {}

    @Override
    public String issue(TempTokenPayload payload) {
        String token = UUID.randomUUID().toString().replace("-", "");  // 유니크 랜덤
        store.put(token, new Entry(payload, Instant.now().plus(ttl)));
        return token;
    }

    @Override
    public Optional<TempTokenPayload> consume(String token) {
        Entry entry = store.remove(token);   // 꺼내면서 삭제 (일회용)
        if (entry == null || Instant.now().isAfter(entry.expiresAt())) {
            return Optional.empty();          // 없거나 만료
        }
        return Optional.of(entry.payload());
    }
}
