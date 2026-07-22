package com.fitback.backend.domain.image.service;

/**
 * 각 도메인이 이미지 참조 여부를 제공하는 확장 지점이다.
 * 룩북이나 프로필이 추가돼도 이미지 정리 정책은 변경하지 않는다.
 */
public interface ImageReferenceProbe {

    boolean exists(String imageId);
}
