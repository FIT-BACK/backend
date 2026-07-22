package com.fitback.backend.global.security.token;

// 임시토큰에 매달아둘 값 (isNewMember는 loadUser에서 판단한 값을 실어둠)
public record TempTokenPayload(Long memberId, boolean isNewMember) {}
