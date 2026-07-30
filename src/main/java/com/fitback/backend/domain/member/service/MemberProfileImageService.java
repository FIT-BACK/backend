package com.fitback.backend.domain.member.service;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.repository.ImageRepository;
import com.fitback.backend.domain.image.service.ImageAccessUrlProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberProfileImageService {

    private final ImageRepository imageRepository;
    private final ImageAccessUrlProvider imageAccessUrlProvider;

    //회원 한 명의 프로필 이미지 ID를 조회 가능한 URL로 변환
    public String resolveProfileImageUrl(Member member) {
        if (member.getProfileImageId() == null) {
            return null;
        }
        Image profileImage = imageRepository.findById(member.getProfileImageId())
                .orElseThrow(() -> new BusinessException(ErrorCode.IMAGE_NOT_FOUND));
        return imageAccessUrlProvider.createReadUrl(profileImage);
    }

    //회원 목록의 프로필 이미지를 한 번에 조회해 회원 ID별 URL로 변환
    public Map<Long, String> resolveProfileImageUrls(Collection<Member> members) {
        List<String> profileImageIds = members.stream()
                .map(Member::getProfileImageId)
                .filter(imageId -> imageId != null)
                .distinct()
                .toList();
        if (profileImageIds.isEmpty()) {
            return Map.of();
        }

        Map<String, Image> imagesById = imageRepository.findAllById(profileImageIds)
                .stream()
                .collect(Collectors.toMap(Image::getId, Function.identity()));
        if (imagesById.size() != profileImageIds.size()) {
            throw new BusinessException(ErrorCode.IMAGE_NOT_FOUND);
        }

        Map<String, String> urlsByImageId = imagesById.values()
                .stream()
                .collect(Collectors.toMap(
                        Image::getId,
                        imageAccessUrlProvider::createReadUrl
                ));
        return members.stream()
                .filter(member -> member.getProfileImageId() != null)
                .collect(Collectors.toMap(
                        Member::getId,
                        member -> urlsByImageId.get(member.getProfileImageId()),
                        (existing, ignored) -> existing
                ));
    }
}
