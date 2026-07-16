package com.fitback.backend.domain.lookbook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.lookbook.dto.LookbookRequest;
import com.fitback.backend.domain.lookbook.dto.LookbookResponse;
import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookTagRepository;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.mock.TagRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LookbookServiceTest {

    @Mock
    private LookbookRepository lookbookRepository;

    @Mock
    private LookbookTagRepository lookbookTagRepository;

    @Mock
    private TagRepository tagRepository;

    @InjectMocks
    private LookbookService lookbookService;

    private Member member;
    private Tag minimalTag;
    private Tag streetTag;

    @BeforeEach
    void setUp() {
        member = Member.create("member@fitback.com", "fitback", "password", LoginProvider.EMAIL);
        ReflectionTestUtils.setField(member, "id", 1L);

        minimalTag = Tag.create("미니멀", TagType.DETAIL);
        ReflectionTestUtils.setField(minimalTag, "id", 10L);
        streetTag = Tag.create("스트릿", TagType.DETAIL);
        ReflectionTestUtils.setField(streetTag, "id", 20L);
    }

    @Test
    void createLookbookSavesLookbookAndTagRelations() {
        LookbookRequest.LookbookCreate request = createRequest(List.of(10L, 20L, 10L));
        when(tagRepository.findAllById(List.of(10L, 20L)))
                .thenReturn(List.of(minimalTag, streetTag));
        when(lookbookRepository.save(any(Lookbook.class))).thenAnswer(invocation -> {
            Lookbook lookbook = invocation.getArgument(0);
            ReflectionTestUtils.setField(lookbook, "id", 100L);
            return lookbook;
        });

        LookbookResponse.LookbookCreate response = lookbookService.createLookbook(member, request);

        assertThat(response.lookbookId()).isEqualTo(100L);
        assertThat(response.memberId()).isEqualTo(1L);
        assertThat(response.originalImageUrl()).isEqualTo("https://s3.example.com/original.jpg");
        assertThat(response.matchedImageUrl()).isEqualTo("https://s3.example.com/matched.jpg");
        assertThat(response.tagIds()).containsExactly(10L, 20L);
        assertThat(response.purchaseUrl()).isEqualTo("https://shop.example.com/item");
        assertThat(response.comment()).isEqualTo("합리적인 가격으로 완성한 룩입니다.");
        assertThat(response.likeCount()).isZero();
        verify(lookbookTagRepository).saveAll(anyList());
    }

    @Test
    void createLookbookFailsWhenTagDoesNotExist() {
        LookbookRequest.LookbookCreate request = createRequest(List.of(10L, 999L));
        when(tagRepository.findAllById(List.of(10L, 999L)))
                .thenReturn(List.of(minimalTag));

        assertThatThrownBy(() -> lookbookService.createLookbook(member, request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(exception.getMessage()).contains("999");
                });
        verify(lookbookRepository, never()).save(any());
        verify(lookbookTagRepository, never()).saveAll(anyList());
    }

    private LookbookRequest.LookbookCreate createRequest(List<Long> tagIds) {
        return new LookbookRequest.LookbookCreate(
                "https://s3.example.com/original.jpg",
                "https://s3.example.com/matched.jpg",
                tagIds,
                "https://shop.example.com/item",
                "합리적인 가격으로 완성한 룩입니다."
        );
    }
}
