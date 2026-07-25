package com.fitback.backend.global.security.token;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTempTokenStoreTest {

    //issue 후 consume하면 저장한 payload 그대로 반환
    @Test
    void issueThenConsumeReturnsPayloadTest() {
        InMemoryTempTokenStore store = new InMemoryTempTokenStore(180000);
        TempTokenPayload payload = new TempTokenPayload(1L, true);

        String token = store.issue(payload);
        Optional<TempTokenPayload> result = store.consume(token);

        assertThat(token).isNotBlank();
        assertThat(result).isPresent();
        assertThat(result.get().memberId()).isEqualTo(1L);
        assertThat(result.get().isNewMember()).isTrue();

        store.shutdown();
    }

    //같은 임시 토큰을 두 번 consume하면 두 번째는 비어있음 (일회용)
    @Test
    void consumeTwiceSecondEmptyTest() {
        InMemoryTempTokenStore store = new InMemoryTempTokenStore(180000);
        String token = store.issue(new TempTokenPayload(1L, false));

        assertThat(store.consume(token)).isPresent();
        //꺼내면서 삭제되므로 재사용 불가
        assertThat(store.consume(token)).isEmpty();

        store.shutdown();
    }

    //만료된 임시 토큰은 비어있음
    @Test
    void consumeExpiredReturnsEmptyTest() throws InterruptedException {
        //ttl 1ms로 발급 후 만료될 때까지 대기
        InMemoryTempTokenStore store = new InMemoryTempTokenStore(1);
        String token = store.issue(new TempTokenPayload(1L, true));
        Thread.sleep(50);

        assertThat(store.consume(token)).isEmpty();

        store.shutdown();
    }

    //consume되지 않은 만료 토큰도 cleanup으로 제거됨
    @Test
    @SuppressWarnings("unchecked")
    void expiredTokenIsAutomaticallyCleanedUpTest() throws InterruptedException {
        InMemoryTempTokenStore store = new InMemoryTempTokenStore(1000);
        String token = store.issue(new TempTokenPayload(1L, true));
        ConcurrentHashMap<String, ?> internalStore =
                (ConcurrentHashMap<String, ?>) ReflectionTestUtils.getField(store, "store");

        assertThat(internalStore).containsKey(token);
        for (int i = 0; i < 20 && internalStore.containsKey(token); i++) {
            Thread.sleep(100);
        }

        assertThat(internalStore).doesNotContainKey(token);
        store.shutdown();
    }

    //저장된 적 없는 토큰은 비어있음
    @Test
    void consumeUnknownTokenReturnsEmptyTest() {
        InMemoryTempTokenStore store = new InMemoryTempTokenStore(180000);

        assertThat(store.consume("never-issued")).isEmpty();

        store.shutdown();
    }
}
