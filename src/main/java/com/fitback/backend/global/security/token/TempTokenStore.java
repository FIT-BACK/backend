package com.fitback.backend.global.security.token;

import java.util.Optional;

//임시 토큰 저장 interface
public interface TempTokenStore {

    // 임시토큰 생성·저장 후 토큰 문자열 반환 (SuccessHandler에서 호출)
    String issue(TempTokenPayload payload);

    // 유효하면 payload 반환 + 즉시 삭제(일회용), 없거나 만료면 empty (교환 API에서 호출)
    Optional<TempTokenPayload> consume(String token);
}
