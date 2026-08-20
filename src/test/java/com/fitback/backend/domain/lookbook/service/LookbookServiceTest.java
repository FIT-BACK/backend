package com.fitback.backend.domain.lookbook.service;

import static com.fitback.backend.domain.lookbook.LookbookImageFixtures.readyImage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.analysis.service.AnalysisReportSaveService;
import com.fitback.backend.domain.closet.entity.ClosetSave;
import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import com.fitback.backend.domain.closet.repository.ClosetSaveRepository;
import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.image.entity.ImageStatus;
import com.fitback.backend.domain.image.event.ImageReferencesReleasedEvent;
import com.fitback.backend.domain.image.service.ImageAccessUrlProvider;
import com.fitback.backend.domain.lookbook.dto.LookbookRequest;
import com.fitback.backend.domain.lookbook.dto.LookbookResponse;
import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.entity.LookbookModerationStatus;
import com.fitback.backend.domain.lookbook.entity.LookbookReport;
import com.fitback.backend.domain.lookbook.entity.LookbookReportReason;
import com.fitback.backend.domain.lookbook.entity.LookbookTag;
import com.fitback.backend.domain.lookbook.repository.LookbookImageRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookLikeRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookReportRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository.RelatedLookbookRank;
import com.fitback.backend.domain.lookbook.repository.LookbookTagRepository;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.entity.MemberRole;
import com.fitback.backend.domain.member.service.MemberProfileImageService;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.recommendation.repository.RecommendedItemRepository;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LookbookServiceTest {

    @Mock
    private LookbookRepository lookbookRepository;

    @Mock
    private LookbookImageRepository lookbookImageRepository;

    @Mock
    private LookbookTagRepository lookbookTagRepository;

    @Mock
    private LookbookLikeRepository lookbookLikeRepository;

    @Mock
    private ClosetSaveRepository closetSaveRepository;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private LookbookLikeCommandService lookbookLikeCommandService;

    @Mock
    private LookbookReportCommandService lookbookReportCommandService;

    @Mock
    private LookbookReportRepository lookbookReportRepository;

    @Mock
    private ImageAccessUrlProvider imageAccessUrlProvider;

    @Mock
    private AnalysisReportRepository analysisReportRepository;

    @Mock
    private AnalysisReportSaveService analysisReportSaveService;

    @Mock
    private RecommendedItemRepository recommendedItemRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private MemberProfileImageService memberProfileImageService;

    @Mock
    private LookbookProductImageResolver productImageResolver;

    @Mock
    private LookbookTransactionExecutor transactionExecutor;

    @InjectMocks
    private LookbookService lookbookService;

    private Member member;
    private Tag minimalTag;
    private Tag streetTag;
    private Image originalImage;
    private Image matchedImage;
    private Image updatedOriginalImage;
    private Image updatedMatchedImage;

    @BeforeEach
    void setUp() {
        member = Member.create("member@fitback.com", "fitback", "password", LoginProvider.EMAIL);
        ReflectionTestUtils.setField(member, "id", 1L);

        minimalTag = Tag.create("미니멀", TagType.DETAIL);
        ReflectionTestUtils.setField(minimalTag, "id", 10L);
        streetTag = Tag.create("스트릿", TagType.DETAIL);
        ReflectionTestUtils.setField(streetTag, "id", 20L);

        originalImage = readyImage("original", member, ImagePurpose.LOOKBOOK);
        matchedImage = readyImage("matched", member, ImagePurpose.LOOKBOOK);
        updatedOriginalImage = readyImage(
                "updated-original",
                member,
                ImagePurpose.LOOKBOOK
        );
        updatedMatchedImage = readyImage(
                "updated-matched",
                member,
                ImagePurpose.LOOKBOOK
        );
        lenient().when(lookbookImageRepository.findAllOwnedImages(
                List.of("original", "matched"),
                1L
        )).thenReturn(List.of(originalImage, matchedImage));
        lenient().when(lookbookImageRepository.findAllOwnedImages(
                List.of("updated-original", "updated-matched"),
                1L
        )).thenReturn(List.of(updatedOriginalImage, updatedMatchedImage));
        lenient().when(imageAccessUrlProvider.createReadUrl(any(Image.class)))
                .thenAnswer(invocation -> {
                    Image image = invocation.getArgument(0);
                    return "https://s3.example.com/" + image.getId() + ".jpg";
                });
        lenient().when(transactionExecutor.execute(any())).thenAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(0);
            return action.get();
        });
        lenient().when(productImageResolver.resolve(any(Product.class)))
                .thenAnswer(invocation -> {
                    Product product = invocation.getArgument(0);
                    return product.getImageUrl();
                });
    }

    @Test
    void createLookbookSavesLookbookAndTagRelations() {
        LookbookRequest.LookbookCreate request = createRequest(List.of(10L, 20L));
        when(tagRepository.findAllById(List.of(10L, 20L)))
                .thenReturn(List.of(minimalTag, streetTag));
        when(lookbookRepository.save(any(Lookbook.class))).thenAnswer(invocation -> {
            Lookbook lookbook = invocation.getArgument(0);
            ReflectionTestUtils.setField(lookbook, "id", 100L);
            return lookbook;
        });

        LookbookResponse.LookbookCreate response = lookbookService.createLookbook(member, request);

        assertThat(response.lookbookId()).isEqualTo(100L);
        verify(lookbookTagRepository).saveAll(anyList());
        verify(lookbookImageRepository).activateReadyImages(
                eq(List.of("original", "matched")),
                eq(ImageStatus.READY),
                eq(ImageStatus.ACTIVE),
                any(Instant.class)
        );
    }

    @Test
    void createLookbookReusesAnalysisImageAndSelectedRecommendationProduct() {
        Image analysisImage = readyImage(
                "analysis-original",
                member,
                ImagePurpose.ANALYSIS
        );
        Product product = mock(Product.class);
        when(product.getImageUrl()).thenReturn("https://shop.example.com/product.jpg");
        when(product.getPurchaseUrl()).thenReturn("https://shop.example.com/product");
        when(tagRepository.findAllById(List.of(10L))).thenReturn(List.of(minimalTag));
        when(lookbookImageRepository.findAllOwnedImages(
                List.of("analysis-original"),
                1L
        )).thenReturn(List.of(analysisImage));
        AnalysisReport report = AnalysisReport.create(
                member,
                analysisImage,
                70
        );
        report.markRecommendationGenerated(
                report.getRecommendationInputRevision(),
                "SIMILARITY_V1",
                Instant.parse("2026-07-26T00:00:00Z")
        );
        when(analysisReportRepository.findByIdAndMemberIdAndDeletedAtIsNull(501L, 1L))
                .thenReturn(Optional.of(report));
        when(recommendedItemRepository.existsByReportIdAndProductId(501L, 101L))
                .thenReturn(true);
        when(productRepository.findById(101L)).thenReturn(Optional.of(product));
        when(lookbookRepository.save(any(Lookbook.class))).thenAnswer(invocation -> {
            Lookbook lookbook = invocation.getArgument(0);
            ReflectionTestUtils.setField(lookbook, "id", 100L);
            return lookbook;
        });
        LookbookRequest.LookbookCreate request = new LookbookRequest.LookbookCreate(
                "analysis-original",
                null,
                101L,
                501L,
                null,
                List.of(10L),
                "분석 결과로 완성한 룩"
        );

        LookbookResponse.LookbookCreate response = lookbookService.createLookbook(
                member,
                request
        );

        assertThat(response.lookbookId()).isEqualTo(100L);
        ArgumentCaptor<Lookbook> lookbookCaptor = ArgumentCaptor.forClass(Lookbook.class);
        verify(lookbookRepository).save(lookbookCaptor.capture());
        assertThat(lookbookCaptor.getValue().getOriginalImage()).isEqualTo(analysisImage);
        assertThat(lookbookCaptor.getValue().getMatchedImage()).isNull();
        assertThat(lookbookCaptor.getValue().getMatchedProduct()).isEqualTo(product);
        assertThat(lookbookCaptor.getValue().getMatchedProductImageUrl())
                .isEqualTo("https://shop.example.com/product.jpg");
        assertThat(lookbookCaptor.getValue().getPurchaseUrl())
                .isEqualTo("https://shop.example.com/product");
        verify(lookbookImageRepository).activateReadyImages(
                eq(List.of("analysis-original")),
                eq(ImageStatus.READY),
                eq(ImageStatus.ACTIVE),
                any(Instant.class)
        );
        verify(analysisReportRepository, times(2))
                .findByIdAndMemberIdAndDeletedAtIsNull(501L, 1L);
        verify(recommendedItemRepository, times(2))
                .existsByReportIdAndProductId(501L, 101L);
    }

    @Test
    void createLookbookUsesResolvedIdentityOnlyProductImage() {
        Image analysisImage = readyImage(
                "identity-analysis-original",
                member,
                ImagePurpose.ANALYSIS
        );
        Product product = mock(Product.class);
        when(product.getImageUrl()).thenReturn(null);
        when(productImageResolver.resolve(product))
                .thenReturn("https://shopify.example.com/live-product.jpg");
        when(tagRepository.findAllById(List.of(10L))).thenReturn(List.of(minimalTag));
        when(lookbookImageRepository.findAllOwnedImages(
                List.of("identity-analysis-original"),
                1L
        )).thenReturn(List.of(analysisImage));
        AnalysisReport report = AnalysisReport.create(member, analysisImage, 70);
        report.markRecommendationGenerated(
                report.getRecommendationInputRevision(),
                "IMAGE_TAG_WEIGHTED_V1",
                Instant.parse("2026-08-12T00:00:00Z")
        );
        when(analysisReportRepository.findByIdAndMemberIdAndDeletedAtIsNull(501L, 1L))
                .thenReturn(Optional.of(report));
        when(recommendedItemRepository.existsByReportIdAndProductId(501L, 101L))
                .thenReturn(true);
        when(productRepository.findById(101L)).thenReturn(Optional.of(product));
        when(lookbookRepository.save(any(Lookbook.class))).thenAnswer(invocation -> {
            Lookbook lookbook = invocation.getArgument(0);
            ReflectionTestUtils.setField(lookbook, "id", 100L);
            return lookbook;
        });
        LookbookRequest.LookbookCreate request = new LookbookRequest.LookbookCreate(
                "identity-analysis-original",
                null,
                101L,
                501L,
                null,
                List.of(10L),
                null
        );

        lookbookService.createLookbook(member, request);

        ArgumentCaptor<Lookbook> lookbookCaptor = ArgumentCaptor.forClass(Lookbook.class);
        verify(lookbookRepository).save(lookbookCaptor.capture());
        assertThat(lookbookCaptor.getValue().getMatchedProductImageUrl())
                .isEqualTo("https://shopify.example.com/live-product.jpg");
        verify(analysisReportRepository, times(2))
                .findByIdAndMemberIdAndDeletedAtIsNull(501L, 1L);
        verify(recommendedItemRepository, times(2))
                .existsByReportIdAndProductId(501L, 101L);
    }

    @Test
    void createLookbookDoesNotSaveWhenProductImageLookupFails() {
        Image analysisImage = readyImage(
                "failed-lookup-analysis-original",
                member,
                ImagePurpose.ANALYSIS
        );
        Product product = mock(Product.class);
        when(productImageResolver.resolve(product)).thenThrow(new BusinessException(
                ErrorCode.PRODUCT_PROVIDER_UNAVAILABLE
        ));
        when(lookbookImageRepository.findAllOwnedImages(
                List.of("failed-lookup-analysis-original"),
                1L
        )).thenReturn(List.of(analysisImage));
        AnalysisReport report = AnalysisReport.create(member, analysisImage, 70);
        report.markRecommendationGenerated(
                report.getRecommendationInputRevision(),
                "IMAGE_TAG_WEIGHTED_V1",
                Instant.parse("2026-08-12T00:00:00Z")
        );
        when(analysisReportRepository.findByIdAndMemberIdAndDeletedAtIsNull(501L, 1L))
                .thenReturn(Optional.of(report));
        when(recommendedItemRepository.existsByReportIdAndProductId(501L, 101L))
                .thenReturn(true);
        when(productRepository.findById(101L)).thenReturn(Optional.of(product));
        LookbookRequest.LookbookCreate request = new LookbookRequest.LookbookCreate(
                "failed-lookup-analysis-original",
                null,
                101L,
                501L,
                null,
                List.of(10L),
                null
        );

        assertThatThrownBy(() -> lookbookService.createLookbook(member, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.PRODUCT_PROVIDER_UNAVAILABLE));

        verify(lookbookRepository, never()).save(any(Lookbook.class));
        verify(tagRepository, never()).findAllById(anyList());
        verify(transactionExecutor).execute(any());
    }

    @Test
    void createLookbookRejectsOriginalImageFromAnotherAnalysisReport() {
        Image requestedImage = readyImage(
                "analysis-original",
                member,
                ImagePurpose.ANALYSIS
        );
        Image reportImage = readyImage(
                "other-analysis-original",
                member,
                ImagePurpose.ANALYSIS
        );
        when(lookbookImageRepository.findAllOwnedImages(
                List.of("analysis-original"),
                1L
        )).thenReturn(List.of(requestedImage));
        AnalysisReport report = AnalysisReport.create(member, reportImage, 70);
        when(analysisReportRepository.findByIdAndMemberIdAndDeletedAtIsNull(501L, 1L))
                .thenReturn(Optional.of(report));
        LookbookRequest.LookbookCreate request = new LookbookRequest.LookbookCreate(
                "analysis-original",
                null,
                101L,
                501L,
                null,
                List.of(10L),
                null
        );

        assertThatThrownBy(() -> lookbookService.createLookbook(member, request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(exception.getMessage())
                            .isEqualTo("분석 리포트의 원본 이미지만 룩북에 사용할 수 있습니다.");
                });
        verify(recommendedItemRepository, never())
                .existsByReportIdAndProductId(any(), any());
        verify(lookbookRepository, never()).save(any());
    }

    @Test
    void createLookbookAcceptsFutureGenericLookbookPurpose() {
        LookbookRequest.LookbookCreate request = createRequest(List.of(10L));
        Image genericOriginal = readyImage("original", member, ImagePurpose.LOOKBOOK);
        Image genericMatched = readyImage("matched", member, ImagePurpose.LOOKBOOK);
        when(tagRepository.findAllById(List.of(10L))).thenReturn(List.of(minimalTag));
        when(lookbookImageRepository.findAllOwnedImages(
                List.of("original", "matched"),
                1L
        )).thenReturn(List.of(genericOriginal, genericMatched));
        when(lookbookRepository.save(any(Lookbook.class))).thenAnswer(invocation -> {
            Lookbook lookbook = invocation.getArgument(0);
            ReflectionTestUtils.setField(lookbook, "id", 100L);
            return lookbook;
        });

        LookbookResponse.LookbookCreate response = lookbookService.createLookbook(member, request);

        assertThat(response.lookbookId()).isEqualTo(100L);
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

    @Test
    void createLookbookRejectsDuplicateTagIds() {
        LookbookRequest.LookbookCreate request = createRequest(List.of(10L, 20L, 10L));

        assertThatThrownBy(() -> lookbookService.createLookbook(member, request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("태그 ID는 중복될 수 없습니다.");
                });
        verify(tagRepository, never()).findAllById(anyList());
        verify(lookbookRepository, never()).save(any());
    }

    @Test
    void createLookbookRejectsImageNotOwnedByMember() {
        LookbookRequest.LookbookCreate request = createRequest(List.of(10L));
        when(tagRepository.findAllById(List.of(10L))).thenReturn(List.of(minimalTag));
        when(lookbookImageRepository.findAllOwnedImages(
                List.of("original", "matched"),
                1L
        )).thenReturn(List.of(originalImage));

        assertThatThrownBy(() -> lookbookService.createLookbook(member, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IMAGE_NOT_FOUND)
                );
        verify(lookbookRepository, never()).save(any());
    }

    @Test
    void createLookbookRejectsImageWithWrongPurpose() {
        LookbookRequest.LookbookCreate request = createRequest(List.of(10L));
        Image invalidMatchedImage = readyImage(
                "matched",
                member,
                ImagePurpose.ANALYSIS
        );
        when(tagRepository.findAllById(List.of(10L))).thenReturn(List.of(minimalTag));
        when(lookbookImageRepository.findAllOwnedImages(
                List.of("original", "matched"),
                1L
        )).thenReturn(List.of(originalImage, invalidMatchedImage));

        assertThatThrownBy(() -> lookbookService.createLookbook(member, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.IMAGE_INVALID_STATE)
                );
        verify(lookbookRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(
            value = ImageStatus.class,
            names = {
                "PENDING_UPLOAD", "DELETING", "DELETE_FAILED", "DELETED", "REJECTED"
            }
    )
    void createLookbookRejectsUnavailableImageStatus(ImageStatus unavailableStatus) {
        LookbookRequest.LookbookCreate request = createRequest(List.of(10L));
        ReflectionTestUtils.setField(matchedImage, "status", unavailableStatus);
        when(tagRepository.findAllById(List.of(10L))).thenReturn(List.of(minimalTag));

        assertThatThrownBy(() -> lookbookService.createLookbook(member, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.IMAGE_INVALID_STATE)
                );
        verify(lookbookRepository, never()).save(any());
    }

    @Test
    void createLookbookAllowsActiveImages() {
        LookbookRequest.LookbookCreate request = createRequest(List.of(10L));
        ReflectionTestUtils.setField(originalImage, "status", ImageStatus.ACTIVE);
        ReflectionTestUtils.setField(matchedImage, "status", ImageStatus.ACTIVE);
        when(tagRepository.findAllById(List.of(10L))).thenReturn(List.of(minimalTag));
        when(lookbookRepository.save(any(Lookbook.class))).thenAnswer(invocation -> {
            Lookbook lookbook = invocation.getArgument(0);
            ReflectionTestUtils.setField(lookbook, "id", 100L);
            return lookbook;
        });

        LookbookResponse.LookbookCreate response = lookbookService.createLookbook(member, request);

        assertThat(response.lookbookId()).isEqualTo(100L);
        verify(lookbookImageRepository).activateReadyImages(
                eq(List.of("original", "matched")),
                eq(ImageStatus.READY),
                eq(ImageStatus.ACTIVE),
                any(Instant.class)
        );
    }

    @Test
    void updateLookbookReplacesContentAndTags() {
        Lookbook lookbook = createPersistedLookbook(LocalDateTime.of(2026, 7, 22, 12, 0));
        LookbookRequest.LookbookUpdate request = createUpdateRequest(List.of(20L, 10L));
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(lookbook));
        when(tagRepository.findAllById(List.of(20L, 10L)))
                .thenReturn(List.of(minimalTag, streetTag));

        LookbookResponse.LookbookUpdate response = lookbookService.updateLookbook(
                100L,
                member,
                request
        );

        assertThat(response.lookbookId()).isEqualTo(100L);
        assertThat(lookbook.getOriginalImage()).isEqualTo(updatedOriginalImage);
        assertThat(lookbook.getMatchedImage()).isEqualTo(updatedMatchedImage);
        assertThat(lookbook.getPurchaseUrl()).isNull();
        assertThat(lookbook.getComment()).isNull();
        verify(lookbookTagRepository).deleteAllByLookbookId(100L);
        verify(lookbookTagRepository).saveAll(anyList());
        verify(lookbookImageRepository).activateReadyImages(
                eq(List.of("updated-original", "updated-matched")),
                eq(ImageStatus.READY),
                eq(ImageStatus.ACTIVE),
                any(Instant.class)
        );
        verify(eventPublisher).publishEvent(
                new ImageReferencesReleasedEvent(List.of("original", "matched"))
        );
    }

    @Test
    void updateLookbookUsesResolvedIdentityOnlyProductImage() {
        Lookbook lookbook = createPersistedLookbook(LocalDateTime.of(2026, 8, 12, 12, 0));
        Image analysisImage = readyImage(
                "updated-analysis-original",
                member,
                ImagePurpose.ANALYSIS
        );
        Product product = mock(Product.class);
        when(product.getImageUrl()).thenReturn(null);
        when(productImageResolver.resolve(product))
                .thenReturn("https://shopify.example.com/updated-live-product.jpg");
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(lookbook));
        when(tagRepository.findAllById(List.of(10L))).thenReturn(List.of(minimalTag));
        when(lookbookImageRepository.findAllOwnedImages(
                List.of("updated-analysis-original"),
                1L
        )).thenReturn(List.of(analysisImage));
        AnalysisReport report = AnalysisReport.create(member, analysisImage, 70);
        report.markRecommendationGenerated(
                report.getRecommendationInputRevision(),
                "IMAGE_TAG_WEIGHTED_V1",
                Instant.parse("2026-08-12T00:00:00Z")
        );
        when(analysisReportRepository.findByIdAndMemberIdAndDeletedAtIsNull(501L, 1L))
                .thenReturn(Optional.of(report));
        when(recommendedItemRepository.existsByReportIdAndProductId(501L, 101L))
                .thenReturn(true);
        when(productRepository.findById(101L)).thenReturn(Optional.of(product));
        LookbookRequest.LookbookUpdate request = new LookbookRequest.LookbookUpdate(
                "updated-analysis-original",
                null,
                101L,
                501L,
                null,
                List.of(10L),
                null
        );

        lookbookService.updateLookbook(100L, member, request);

        assertThat(lookbook.getMatchedProduct()).isEqualTo(product);
        assertThat(lookbook.getMatchedProductImageUrl())
                .isEqualTo("https://shopify.example.com/updated-live-product.jpg");
        verify(lookbookRepository, times(2)).findByIdAndDeletedAtIsNull(100L);
        verify(analysisReportRepository, times(2))
                .findByIdAndMemberIdAndDeletedAtIsNull(501L, 1L);
        verify(recommendedItemRepository, times(2))
                .existsByReportIdAndProductId(501L, 101L);
    }

    @Test
    void updateLookbookRejectsMemberWhoIsNotOwner() {
        Lookbook lookbook = createPersistedLookbook(LocalDateTime.of(2026, 7, 22, 12, 0));
        Member otherMember = Member.create(
                "other@fitback.com",
                "other",
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(otherMember, "id", 2L);
        LookbookRequest.LookbookUpdate request = createUpdateRequest(List.of(10L));
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(lookbook));

        assertThatThrownBy(() -> lookbookService.updateLookbook(100L, otherMember, request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(exception.getMessage()).isEqualTo("룩북 수정 권한이 없습니다.");
                });
        verify(tagRepository, never()).findAllById(anyList());
        verify(lookbookTagRepository, never()).deleteAllByLookbookId(any());
        verify(lookbookTagRepository, never()).saveAll(anyList());
    }

    @Test
    void updateLookbookFailsWhenLookbookIsDeletedOrMissing() {
        LookbookRequest.LookbookUpdate request = createUpdateRequest(List.of(10L));
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lookbookService.updateLookbook(100L, member, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
                );
        verify(lookbookTagRepository, never()).deleteAllByLookbookId(any());
    }

    @Test
    void reportLookbookReturnsCreatedReportId() {
        Member reporter = Member.create(
                "reporter@fitback.com",
                "reporter",
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(reporter, "id", 2L);
        Lookbook lookbook = createPersistedLookbook(LocalDateTime.of(2026, 7, 23, 12, 0));
        LookbookReport report = LookbookReport.create(
                lookbook,
                reporter,
                LookbookReportReason.INAPPROPRIATE_IMAGE
        );
        ReflectionTestUtils.setField(report, "id", 101L);
        LookbookRequest.LookbookReport request = new LookbookRequest.LookbookReport(
                LookbookReportReason.INAPPROPRIATE_IMAGE
        );
        when(lookbookReportCommandService.createReport(
                100L,
                reporter,
                LookbookReportReason.INAPPROPRIATE_IMAGE
        )).thenReturn(report);

        LookbookResponse.LookbookReport response = lookbookService.reportLookbook(
                100L,
                reporter,
                request
        );

        assertThat(response.reportId()).isEqualTo(101L);
    }

    @Test
    void reportLookbookConvertsDuplicateConstraintViolationToBadRequest() {
        Member reporter = Member.create(
                "duplicate-reporter@fitback.com",
                "duplicate-reporter",
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(reporter, "id", 2L);
        LookbookRequest.LookbookReport request = new LookbookRequest.LookbookReport(
                LookbookReportReason.OTHER
        );
        when(lookbookReportCommandService.createReport(100L, reporter, LookbookReportReason.OTHER))
                .thenThrow(new DataIntegrityViolationException("duplicate report"));
        when(lookbookReportRepository.existsByLookbookIdAndMemberId(100L, 2L))
                .thenReturn(true);

        assertThatThrownBy(() -> lookbookService.reportLookbook(100L, reporter, request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(exception.getErrorCode().getCode()).isEqualTo("COMMON400_1");
                    assertThat(exception.getMessage()).isEqualTo("이미 신고한 룩북입니다.");
                });
    }

    @Test
    void getLookbookDetailReturnsAuthorTagsLikedAndOwnerStatus() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 16, 12, 0);
        Lookbook lookbook = createPersistedLookbook(createdAt);
        when(lookbookRepository.findByIdAndDeletedAtIsNullAndModerationStatus(
                100L,
                LookbookModerationStatus.VISIBLE
        )).thenReturn(Optional.of(lookbook));
        when(lookbookTagRepository.findAllByLookbookIdOrderByIdAsc(100L))
                .thenReturn(List.of(
                        LookbookTag.create(lookbook, minimalTag),
                        LookbookTag.create(lookbook, streetTag)
                ));
        when(lookbookLikeRepository.existsByLookbookIdAndMemberId(100L, 1L))
                .thenReturn(true);
        when(memberProfileImageService.resolveProfileImageUrl(member))
                .thenReturn("https://s3.example.com/profile.jpg");
        ClosetSave closetSave = ClosetSave.create(member, ClosetTargetType.LOOKBOOK, 100L);
        ReflectionTestUtils.setField(closetSave, "id", 15L);
        when(closetSaveRepository.findByMemberIdAndTargetTypeAndTargetId(
                1L, ClosetTargetType.LOOKBOOK, 100L))
                .thenReturn(Optional.of(closetSave));

        LookbookResponse.LookbookDetail response = lookbookService.getLookbookDetail(100L, member);

        assertThat(response.saveId()).isEqualTo(15L);
        assertThat(response.originalImageUrl()).isEqualTo("https://s3.example.com/original.jpg");
        assertThat(response.matchedImageUrl()).isEqualTo("https://s3.example.com/matched.jpg");
        assertThat(response.authorNickname()).isEqualTo("fitback");
        assertThat(response.authorProfileImageUrl())
                .isEqualTo("https://s3.example.com/profile.jpg");
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.purchaseUrl()).isEqualTo("https://shop.example.com/item");
        assertThat(response.comment()).isEqualTo("합리적인 가격으로 완성한 룩입니다.");
        assertThat(response.tags())
                .extracting(LookbookResponse.TagItem::tagId)
                .containsExactly(10L, 20L);
        assertThat(response.tags())
                .extracting(LookbookResponse.TagItem::tagName)
                .containsExactly("미니멀", "스트릿");
        assertThat(response.likeCount()).isEqualTo(5);
        assertThat(response.isLiked()).isTrue();
        assertThat(response.isOwner()).isTrue();
    }

    @Test
    void getLookbookDetailReturnsIsLikedFalseForAnonymousMember() {
        Lookbook lookbook = createPersistedLookbook(LocalDateTime.of(2026, 7, 16, 12, 0));
        when(lookbookRepository.findByIdAndDeletedAtIsNullAndModerationStatus(
                100L,
                LookbookModerationStatus.VISIBLE
        )).thenReturn(Optional.of(lookbook));
        when(lookbookTagRepository.findAllByLookbookIdOrderByIdAsc(100L))
                .thenReturn(List.of());

        LookbookResponse.LookbookDetail response = lookbookService.getLookbookDetail(100L, null);

        assertThat(response.isLiked()).isFalse();
        assertThat(response.isOwner()).isFalse();
        assertThat(response.saveId()).isNull();
        verify(lookbookLikeRepository, never()).existsByLookbookIdAndMemberId(any(), any());
        verify(closetSaveRepository, never())
                .findByMemberIdAndTargetTypeAndTargetId(any(), any(), any());
    }

    @Test
    void getLookbookDetailReturnsIsOwnerFalseForAuthenticatedNonOwner() {
        Lookbook lookbook = createPersistedLookbook(LocalDateTime.of(2026, 7, 16, 12, 0));
        Member otherMember = Member.create(
                "other@fitback.com",
                "other",
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(otherMember, "id", 2L);
        when(lookbookRepository.findByIdAndDeletedAtIsNullAndModerationStatus(
                100L,
                LookbookModerationStatus.VISIBLE
        )).thenReturn(Optional.of(lookbook));
        when(lookbookTagRepository.findAllByLookbookIdOrderByIdAsc(100L))
                .thenReturn(List.of());

        LookbookResponse.LookbookDetail response = lookbookService.getLookbookDetail(
                100L,
                otherMember
        );

        assertThat(response.isOwner()).isFalse();
    }

    @Test
    void getLookbookDetailReturnsNullSaveIdWhenNotSavedByMember() {
        Lookbook lookbook = createPersistedLookbook(LocalDateTime.of(2026, 7, 16, 12, 0));
        when(lookbookRepository.findByIdAndDeletedAtIsNullAndModerationStatus(
                100L,
                LookbookModerationStatus.VISIBLE
        )).thenReturn(Optional.of(lookbook));
        when(lookbookTagRepository.findAllByLookbookIdOrderByIdAsc(100L))
                .thenReturn(List.of());
        when(closetSaveRepository.findByMemberIdAndTargetTypeAndTargetId(
                1L, ClosetTargetType.LOOKBOOK, 100L))
                .thenReturn(Optional.empty());

        LookbookResponse.LookbookDetail response = lookbookService.getLookbookDetail(100L, member);

        assertThat(response.saveId()).isNull();
    }

    @Test
    void getLookbookDetailFailsWhenLookbookDoesNotExist() {
        when(lookbookRepository.findByIdAndDeletedAtIsNullAndModerationStatus(
                999L,
                LookbookModerationStatus.VISIBLE
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lookbookService.getLookbookDetail(999L, null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo("룩북을 찾을 수 없습니다.");
                });
    }

    @Test
    void getLookbookDetailFailsWhenLookbookIsHidden() {
        when(lookbookRepository.findByIdAndDeletedAtIsNullAndModerationStatus(
                100L,
                LookbookModerationStatus.VISIBLE
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lookbookService.getLookbookDetail(100L, null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo("룩북을 찾을 수 없습니다.");
                });

        verify(lookbookTagRepository, never()).findAllByLookbookIdOrderByIdAsc(any());
        verify(memberProfileImageService, never()).resolveProfileImageUrl(any());
    }

    @Test
    void deleteLookbookSoftDeletesOwnersLookbook() {
        Lookbook lookbook = createPersistedLookbook(LocalDateTime.of(2026, 7, 18, 12, 0));
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(lookbook));

        lookbookService.deleteLookbook(100L, member);

        assertThat(lookbook.getDeletedAt()).isNotNull();
        verify(eventPublisher).publishEvent(
                new ImageReferencesReleasedEvent(List.of("original", "matched"))
        );
    }

    @Test
    void deleteLookbookAllowsAdminMember() {
        Lookbook lookbook = createPersistedLookbook(LocalDateTime.of(2026, 7, 18, 12, 0));
        Member admin = Member.create("admin@fitback.com", "admin", "password", LoginProvider.EMAIL);
        ReflectionTestUtils.setField(admin, "id", 2L);
        admin.changeRole(MemberRole.ADMIN);
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(lookbook));

        lookbookService.deleteLookbook(100L, admin);

        assertThat(lookbook.getDeletedAt()).isNotNull();
    }

    @Test
    void deleteLookbookRejectsMemberWithoutPermission() {
        Lookbook lookbook = createPersistedLookbook(LocalDateTime.of(2026, 7, 18, 12, 0));
        Member otherMember = Member.create(
                "other@fitback.com",
                "other",
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(otherMember, "id", 2L);
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(lookbook));

        assertThatThrownBy(() -> lookbookService.deleteLookbook(100L, otherMember))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN)
                );
        assertThat(lookbook.getDeletedAt()).isNull();
    }

    @Test
    void deleteLookbookFailsWhenLookbookIsAlreadyDeleted() {
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lookbookService.deleteLookbook(100L, member))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
                );
    }

    @Test
    void likeLookbookReturnsChangedLikeCount() {
        when(lookbookLikeCommandService.createLike(100L, member)).thenReturn(6);

        LookbookResponse.LookbookLike response = lookbookService.likeLookbook(100L, member);

        assertThat(response.isLiked()).isTrue();
        assertThat(response.likeCount()).isEqualTo(6);
    }

    @Test
    void likeLookbookReturnsCurrentCountWhenAlreadyLiked() {
        when(lookbookLikeCommandService.createLike(100L, member))
                .thenThrow(new DataIntegrityViolationException("duplicate like"));
        when(lookbookLikeRepository.existsByLookbookIdAndMemberId(100L, 1L)).thenReturn(true);
        when(lookbookRepository.findLikeCountByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(5));

        LookbookResponse.LookbookLike response = lookbookService.likeLookbook(100L, member);

        assertThat(response.isLiked()).isTrue();
        assertThat(response.likeCount()).isEqualTo(5);
    }

    @Test
    void likeLookbookRethrowsUnexpectedIntegrityViolation() {
        DataIntegrityViolationException exception =
                new DataIntegrityViolationException("unexpected constraint violation");
        when(lookbookLikeCommandService.createLike(100L, member)).thenThrow(exception);
        when(lookbookLikeRepository.existsByLookbookIdAndMemberId(100L, 1L)).thenReturn(false);

        assertThatThrownBy(() -> lookbookService.likeLookbook(100L, member))
                .isSameAs(exception);
    }

    @Test
    void deleteLookbookLikeReturnsChangedLikeCount() {
        when(lookbookLikeCommandService.deleteLike(100L, member)).thenReturn(4);

        LookbookResponse.LookbookUnlike response = lookbookService.deleteLookbookLike(100L, member);

        assertThat(response.isLiked()).isFalse();
        assertThat(response.likeCount()).isEqualTo(4);
    }

    @Test
    void getLookbooksUsesRequestedPageSizeAndReturnsNextCursor() {
        LocalDateTime latestCreatedAt = LocalDateTime.of(2026, 7, 16, 12, 0);
        List<Lookbook> lookbookPage = IntStream.range(0, 6)
                .mapToObj(index -> createListLookbook(
                        100L - index,
                        latestCreatedAt.minusMinutes(index)
                ))
                .toList();
        List<Long> returnedLookbookIds = lookbookPage.subList(0, 5)
                .stream()
                .map(Lookbook::getId)
                .toList();
        when(lookbookRepository
                .findAllByDeletedAtIsNullAndModerationStatusOrderByCreatedAtDescIdDesc(
                eq(LookbookModerationStatus.VISIBLE),
                any(Pageable.class)
        ))
                .thenReturn(lookbookPage);
        when(lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(returnedLookbookIds))
                .thenReturn(List.of(LookbookTag.create(lookbookPage.get(0), minimalTag)));
        when(lookbookLikeRepository.findLikedLookbookIds(1L, returnedLookbookIds))
                .thenReturn(Set.of(100L));
        when(memberProfileImageService.resolveProfileImageUrls(anyList()))
                .thenReturn(Map.of(1L, "https://s3.example.com/profile.jpg"));

        LookbookResponse.LookbookList response = lookbookService.getLookbooks(
                null,
                5,
                "   ",
                member
        );

        assertThat(response.items()).hasSize(5);
        assertThat(response.items().get(0).lookbookId()).isEqualTo(100L);
        assertThat(response.items().get(0).authorNickname()).isEqualTo("fitback");
        assertThat(response.items().get(0).authorProfileImageUrl())
                .isEqualTo("https://s3.example.com/profile.jpg");
        assertThat(response.items().get(0).isLiked()).isTrue();
        assertThat(response.items().get(0).tags()).containsExactly("미니멀");
        assertThat(response.items().get(1).isLiked()).isFalse();
        assertThat(response.nextCursor()).isEqualTo(96L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.pageSize()).isEqualTo(5);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(lookbookRepository)
                .findAllByDeletedAtIsNullAndModerationStatusOrderByCreatedAtDescIdDesc(
                        eq(LookbookModerationStatus.VISIBLE),
                        pageableCaptor.capture()
                );
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(6);
        verify(memberProfileImageService).resolveProfileImageUrls(anyList());
    }

    // 상세로 안 들어가고 목록에서 바로 저장/저장취소할 수 있도록 각 항목에 saveId를 채워주는지 확인
    @Test
    void getLookbooksIncludesSaveIdOnlyForSavedLookbooks() {
        LocalDateTime latestCreatedAt = LocalDateTime.of(2026, 7, 16, 12, 0);
        List<Lookbook> lookbookPage = List.of(
                createListLookbook(100L, latestCreatedAt),
                createListLookbook(99L, latestCreatedAt.minusMinutes(1))
        );
        List<Long> returnedLookbookIds = List.of(100L, 99L);
        when(lookbookRepository
                .findAllByDeletedAtIsNullAndModerationStatusOrderByCreatedAtDescIdDesc(
                eq(LookbookModerationStatus.VISIBLE),
                any(Pageable.class)
        ))
                .thenReturn(lookbookPage);
        when(lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(returnedLookbookIds))
                .thenReturn(List.of());
        when(memberProfileImageService.resolveProfileImageUrls(anyList()))
                .thenReturn(Map.of());

        ClosetSave savedEntry = ClosetSave.create(member, ClosetTargetType.LOOKBOOK, 100L);
        ReflectionTestUtils.setField(savedEntry, "id", 555L);
        when(closetSaveRepository.findAllByMemberIdAndTargetTypeAndTargetIdIn(
                eq(1L),
                eq(ClosetTargetType.LOOKBOOK),
                eq(returnedLookbookIds)
        )).thenReturn(List.of(savedEntry));

        LookbookResponse.LookbookList response = lookbookService.getLookbooks(
                null,
                20,
                null,
                member
        );

        assertThat(response.items())
                .extracting(
                        LookbookResponse.LookbookItem::lookbookId,
                        LookbookResponse.LookbookItem::saveId
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(100L, 555L),
                        org.assertj.core.groups.Tuple.tuple(99L, null)
                );
    }

    @Test
    void getMyLookbooksUsesRequestedPageSizeAndReturnsNextCursor() {
        LocalDateTime latestCreatedAt = LocalDateTime.of(2026, 7, 16, 12, 0);
        List<Lookbook> lookbookPage = IntStream.range(0, 6)
                .mapToObj(index -> createListLookbook(
                        100L - index,
                        latestCreatedAt.minusMinutes(index)
                ))
                .toList();
        List<Long> returnedLookbookIds = lookbookPage.subList(0, 5)
                .stream()
                .map(Lookbook::getId)
                .toList();
        when(lookbookRepository.findAllByMemberIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
                eq(1L),
                any(Pageable.class)
        ))
                .thenReturn(lookbookPage);
        when(lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(returnedLookbookIds))
                .thenReturn(List.of(LookbookTag.create(lookbookPage.get(0), minimalTag)));
        when(lookbookLikeRepository.findLikedLookbookIds(1L, returnedLookbookIds))
                .thenReturn(Set.of(100L));
        when(memberProfileImageService.resolveProfileImageUrls(anyList()))
                .thenReturn(Map.of(1L, "https://s3.example.com/profile.jpg"));

        LookbookResponse.LookbookList response = lookbookService.getMyLookbooks(
                null,
                5,
                member
        );

        assertThat(response.items()).hasSize(5);
        assertThat(response.items().get(0).lookbookId()).isEqualTo(100L);
        assertThat(response.items().get(0).tags()).containsExactly("미니멀");
        assertThat(response.nextCursor()).isEqualTo(96L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.pageSize()).isEqualTo(5);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(lookbookRepository).findAllByMemberIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
                eq(1L),
                pageableCaptor.capture()
        );
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(6);
    }

    @Test
    void getMyLookbooksUsesCursorForNextPage() {
        LocalDateTime cursorCreatedAt = LocalDateTime.of(2026, 7, 16, 12, 0);
        Lookbook cursorLookbook = createListLookbook(100L, cursorCreatedAt);
        Lookbook nextLookbook = createListLookbook(99L, cursorCreatedAt.minusMinutes(1));
        when(lookbookRepository.findById(100L)).thenReturn(Optional.of(cursorLookbook));
        when(lookbookRepository.findNextPageByMemberId(
                eq(1L),
                eq(cursorCreatedAt),
                eq(100L),
                any(Pageable.class)
        )).thenReturn(List.of(nextLookbook));
        when(lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(List.of(99L)))
                .thenReturn(List.of());

        LookbookResponse.LookbookList response = lookbookService.getMyLookbooks(
                100L,
                5,
                member
        );

        assertThat(response.items())
                .extracting(LookbookResponse.LookbookItem::lookbookId)
                .containsExactly(99L);
    }

    @Test
    void getMyLookbooksRejectsNonPositiveCursor() {
        assertThatThrownBy(() -> lookbookService.getMyLookbooks(0L, 20, member))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR)
                );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MAX_VALUE})
    void getMyLookbooksRejectsPageSizeOutsideAllowedRange(int pageSize) {
        assertThatThrownBy(() -> lookbookService.getMyLookbooks(null, pageSize, member))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR)
                );
    }

    @Test
    void getLookbooksRejectsNonPositiveCursor() {
        assertThatThrownBy(() -> lookbookService.getLookbooks(0L, 20, null, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR)
                );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MAX_VALUE})
    void getLookbooksRejectsPageSizeOutsideAllowedRange(int pageSize) {
        assertThatThrownBy(() -> lookbookService.getLookbooks(null, pageSize, null, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR)
                );
    }

    @Test
    void getLookbooksReturnsLikedByMeFalseForAnonymousMember() {
        Lookbook lookbook = createListLookbook(100L, LocalDateTime.of(2026, 7, 16, 12, 0));
        when(lookbookRepository
                .findAllByDeletedAtIsNullAndModerationStatusOrderByCreatedAtDescIdDesc(
                eq(LookbookModerationStatus.VISIBLE),
                any(Pageable.class)
        ))
                .thenReturn(List.of(lookbook));
        when(lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(List.of(100L)))
                .thenReturn(List.of());

        LookbookResponse.LookbookList response = lookbookService.getLookbooks(
                null,
                null,
                null,
                null
        );

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).isLiked()).isFalse();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.pageSize()).isEqualTo(20);
        verify(lookbookLikeRepository, never()).findLikedLookbookIds(any(), anyList());
    }

    @Test
    void getLookbooksUsesTagFilteredCursorForNextPage() {
        LocalDateTime cursorCreatedAt = LocalDateTime.of(2026, 7, 16, 12, 0);
        Lookbook cursorLookbook = createListLookbook(100L, cursorCreatedAt);
        cursorLookbook.softDelete();
        Lookbook nextLookbook = createListLookbook(99L, cursorCreatedAt.minusMinutes(1));
        when(lookbookRepository.findCursorByIdAndTagName(100L, "미니멀"))
                .thenReturn(Optional.of(cursorLookbook));
        when(lookbookRepository.findNextPageByTagName(
                eq("미니멀"),
                eq(LookbookModerationStatus.VISIBLE),
                eq(cursorCreatedAt),
                eq(100L),
                any(Pageable.class)
        )).thenReturn(List.of(nextLookbook));
        when(lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(List.of(99L)))
                .thenReturn(List.of());

        LookbookResponse.LookbookList response = lookbookService.getLookbooks(
                100L,
                5,
                " 미니멀 ",
                null
        );

        assertThat(response.items())
                .extracting(LookbookResponse.LookbookItem::lookbookId)
                .containsExactly(99L);
    }

    @Test
    void getLookbooksUsesSoftDeletedCursorForNextPage() {
        LocalDateTime cursorCreatedAt = LocalDateTime.of(2026, 7, 16, 12, 0);
        Lookbook cursorLookbook = createListLookbook(100L, cursorCreatedAt);
        cursorLookbook.softDelete();
        Lookbook nextLookbook = createListLookbook(99L, cursorCreatedAt.minusMinutes(1));
        when(lookbookRepository.findById(100L)).thenReturn(Optional.of(cursorLookbook));
        when(lookbookRepository.findNextPage(
                eq(LookbookModerationStatus.VISIBLE),
                eq(cursorCreatedAt),
                eq(100L),
                any(Pageable.class)
        )).thenReturn(List.of(nextLookbook));
        when(lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(List.of(99L)))
                .thenReturn(List.of());

        LookbookResponse.LookbookList response = lookbookService.getLookbooks(
                100L,
                5,
                null,
                null
        );

        assertThat(response.items())
                .extracting(LookbookResponse.LookbookItem::lookbookId)
                .containsExactly(99L);
        verify(lookbookRepository, never()).findByIdAndDeletedAtIsNull(100L);
    }

    @Test
    void getLookbooksFiltersFirstPageByTag() {
        Lookbook lookbook = createListLookbook(100L, LocalDateTime.of(2026, 7, 16, 12, 0));
        when(lookbookRepository.findAllByTagName(
                eq("미니멀"),
                eq(LookbookModerationStatus.VISIBLE),
                any(Pageable.class)
        ))
                .thenReturn(List.of(lookbook));
        when(lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(List.of(100L)))
                .thenReturn(List.of(LookbookTag.create(lookbook, minimalTag)));

        LookbookResponse.LookbookList response = lookbookService.getLookbooks(
                null,
                20,
                "미니멀",
                null
        );

        assertThat(response.items())
                .extracting(LookbookResponse.LookbookItem::lookbookId)
                .containsExactly(100L);
        assertThat(response.items().get(0).tags()).containsExactly("미니멀");
    }

    @Test
    void getRelatedLookbooksReturnsThreeItemsInRankOrderAndNextCursor() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 7, 12, 0);
        List<RelatedLookbookRank> rankedPage = List.of(
                relatedRank(100L, 111L, createdAt),
                relatedRank(99L, 110L, createdAt.minusMinutes(1)),
                relatedRank(98L, 100L, createdAt.minusMinutes(2)),
                relatedRank(97L, 10L, createdAt.minusMinutes(3))
        );
        Lookbook first = createListLookbook(100L, createdAt);
        Lookbook second = createListLookbook(99L, createdAt.minusMinutes(1));
        Lookbook third = createListLookbook(98L, createdAt.minusMinutes(2));
        when(lookbookRepository.findRelatedLookbookRanks(
                eq(1L),
                eq(LookbookModerationStatus.VISIBLE),
                any(Pageable.class)
        )).thenReturn(rankedPage);
        when(lookbookRepository.findAllByIdInAndDeletedAtIsNullAndModerationStatus(
                List.of(100L, 99L, 98L),
                LookbookModerationStatus.VISIBLE
        ))
                .thenReturn(List.of(third, first, second));
        when(lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(
                List.of(100L, 99L, 98L)
        )).thenReturn(List.of(LookbookTag.create(first, minimalTag)));
        when(lookbookLikeRepository.findLikedLookbookIds(
                1L,
                List.of(100L, 99L, 98L)
        )).thenReturn(Set.of(100L));
        when(memberProfileImageService.resolveProfileImageUrls(anyList()))
                .thenReturn(Map.of(1L, "https://s3.example.com/profile.jpg"));

        LookbookResponse.LookbookList response = lookbookService.getRelatedLookbooks(
                1L,
                null,
                member
        );

        assertThat(response.items())
                .extracting(LookbookResponse.LookbookItem::lookbookId)
                .containsExactly(100L, 99L, 98L);
        assertThat(response.items().get(0).tags()).containsExactly("미니멀");
        assertThat(response.items().get(0).isLiked()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(98L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.pageSize()).isEqualTo(3);
    }

    @Test
    void getRelatedLookbooksFailsWhenRankedLookbookIsMissingFromVisibleResults() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 7, 12, 0);
        Lookbook first = createListLookbook(100L, createdAt);
        when(lookbookRepository.findRelatedLookbookRanks(
                eq(1L),
                eq(LookbookModerationStatus.VISIBLE),
                any(Pageable.class)
        )).thenReturn(List.of(
                relatedRank(100L, 111L, createdAt),
                relatedRank(99L, 110L, createdAt.minusMinutes(1))
        ));
        when(lookbookRepository.findAllByIdInAndDeletedAtIsNullAndModerationStatus(
                List.of(100L, 99L),
                LookbookModerationStatus.VISIBLE
        )).thenReturn(List.of(first));

        assertThatThrownBy(() -> lookbookService.getRelatedLookbooks(1L, null, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
                );
    }

    @Test
    void getRelatedLookbooksUsesCursorRankForNextPage() {
        LocalDateTime cursorCreatedAt = LocalDateTime.of(2026, 8, 7, 12, 0);
        RelatedLookbookRank cursorRank = relatedRank(100L, 111L, cursorCreatedAt);
        RelatedLookbookRank nextRank = relatedRank(
                99L,
                110L,
                cursorCreatedAt.minusMinutes(1)
        );
        Lookbook nextLookbook = createListLookbook(99L, cursorCreatedAt.minusMinutes(1));
        when(lookbookRepository.findRelatedLookbookRank(
                1L,
                100L
        )).thenReturn(Optional.of(cursorRank));
        when(lookbookRepository.findNextRelatedLookbookRanks(
                eq(1L),
                eq(LookbookModerationStatus.VISIBLE),
                eq(111L),
                eq(cursorCreatedAt),
                eq(100L),
                any(Pageable.class)
        )).thenReturn(List.of(nextRank));
        when(lookbookRepository.findAllByIdInAndDeletedAtIsNullAndModerationStatus(
                List.of(99L),
                LookbookModerationStatus.VISIBLE
        ))
                .thenReturn(List.of(nextLookbook));
        when(lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(List.of(99L)))
                .thenReturn(List.of());

        LookbookResponse.LookbookList response = lookbookService.getRelatedLookbooks(
                1L,
                100L,
                null
        );

        assertThat(response.items())
                .extracting(LookbookResponse.LookbookItem::lookbookId)
                .containsExactly(99L);
        assertThat(response.nextCursor()).isNull();
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void getRelatedLookbooksFailsWhenCursorIsNotRelatedToTrend() {
        when(lookbookRepository.findRelatedLookbookRank(
                1L,
                999L
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lookbookService.getRelatedLookbooks(1L, 999L, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
                );
    }

    @Test
    void getRelatedLookbooksReturnsEmptyPageWhenNoTagsOverlap() {
        when(lookbookRepository.findRelatedLookbookRanks(
                eq(1L),
                eq(LookbookModerationStatus.VISIBLE),
                any(Pageable.class)
        )).thenReturn(List.of());

        LookbookResponse.LookbookList response = lookbookService.getRelatedLookbooks(
                1L,
                null,
                null
        );

        assertThat(response.items()).isEmpty();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.pageSize()).isEqualTo(3);
        verify(lookbookRepository, never())
                .findAllByIdInAndDeletedAtIsNullAndModerationStatus(
                        anyList(),
                        any(LookbookModerationStatus.class)
                );
    }

    @Test
    void searchLookbooksMapsTagsAndLikedState() {
        Lookbook lookbook = createListLookbook(
                100L,
                LocalDateTime.of(2026, 7, 16, 12, 0)
        );
        when(lookbookRepository.searchByKeyword(
                eq("minimal"),
                eq(LookbookModerationStatus.VISIBLE),
                any(Pageable.class)
        )).thenReturn(List.of(lookbook));
        when(lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(List.of(100L)))
                .thenReturn(List.of(LookbookTag.create(lookbook, minimalTag)));
        when(lookbookLikeRepository.findLikedLookbookIds(1L, List.of(100L)))
                .thenReturn(Set.of(100L));

        List<LookbookResponse.LookbookItem> response = lookbookService.searchLookbooks(
                "minimal",
                member
        );

        assertThat(response).singleElement().satisfies(item -> {
            assertThat(item.lookbookId()).isEqualTo(100L);
            assertThat(item.tags()).containsExactly("미니멀");
            assertThat(item.isLiked()).isTrue();
            assertThat(item.originalImageUrl())
                    .isEqualTo("https://s3.example.com/original-100.jpg");
        });
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(lookbookRepository).searchByKeyword(
                eq("minimal"),
                eq(LookbookModerationStatus.VISIBLE),
                pageable.capture()
        );
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
    }

    private LookbookRequest.LookbookCreate createRequest(List<Long> tagIds) {
        return new LookbookRequest.LookbookCreate(
                "original",
                "matched",
                null,
                null,
                "https://shop.example.com/item",
                tagIds,
                "합리적인 가격으로 완성한 룩입니다."
        );
    }

    private LookbookRequest.LookbookUpdate createUpdateRequest(List<Long> tagIds) {
        return new LookbookRequest.LookbookUpdate(
                "updated-original",
                "updated-matched",
                null,
                null,
                "   ",
                tagIds,
                null
        );
    }

    @Test
    void findClosetViewsReturnsOriginalAndMatchedImageUrlWithTags() {
        Lookbook lookbook = createListLookbook(12L, LocalDateTime.of(2026, 8, 1, 12, 0));
        Tag streetTag = Tag.create("스트릿", TagType.DETAIL);
        when(lookbookRepository.findAllByIdInAndDeletedAtIsNull(List.of(12L)))
                .thenReturn(List.of(lookbook));
        when(lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(List.of(12L)))
                .thenReturn(List.of(LookbookTag.create(lookbook, streetTag)));
        when(imageAccessUrlProvider.createReadUrl(any(Image.class)))
                .thenReturn("https://cdn.fitback.app/signed.jpg");

        Map<Long, LookbookService.ClosetLookbookView> views =
                lookbookService.findClosetViews(List.of(12L));

        assertThat(views).containsOnlyKeys(12L);
        assertThat(views.get(12L).thumbnailUrl()).isEqualTo("https://cdn.fitback.app/signed.jpg");
        assertThat(views.get(12L).matchedImageUrl()).isEqualTo("https://cdn.fitback.app/signed.jpg");
        assertThat(views.get(12L).tags()).containsExactly("스트릿");
    }

    // 상품 매칭 룩북은 서명 URL 대신 저장된 상품 이미지 URL 사용
    @Test
    void findClosetViewsUsesProductImageUrlWhenMatchedByProduct() {
        Product product = mock(Product.class);
        Lookbook lookbook = Lookbook.createWithProduct(
                member,
                readyImage("original-13", member, ImagePurpose.LOOKBOOK),
                product,
                "https://shop.example.com/product.jpg",
                null,
                null
        );
        ReflectionTestUtils.setField(lookbook, "id", 13L);
        when(lookbookRepository.findAllByIdInAndDeletedAtIsNull(List.of(13L)))
                .thenReturn(List.of(lookbook));
        when(lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(List.of(13L)))
                .thenReturn(List.of());
        when(imageAccessUrlProvider.createReadUrl(any(Image.class)))
                .thenReturn("https://cdn.fitback.app/signed.jpg");

        Map<Long, LookbookService.ClosetLookbookView> views =
                lookbookService.findClosetViews(List.of(13L));

        assertThat(views.get(13L).matchedImageUrl()).isEqualTo("https://shop.example.com/product.jpg");
        assertThat(views.get(13L).tags()).isEmpty();
    }

    // 삭제된 룩북은 조회에서 빠져 호출측 목록에서도 제외됨
    @Test
    void findClosetViewsExcludesDeletedLookbooks() {
        when(lookbookRepository.findAllByIdInAndDeletedAtIsNull(List.of(12L, 99L)))
                .thenReturn(List.of());

        Map<Long, LookbookService.ClosetLookbookView> views =
                lookbookService.findClosetViews(List.of(12L, 99L));

        assertThat(views).isEmpty();
    }

    @Test
    void findClosetViewsSkipsQueryWhenNoLookbookSaved() {
        Map<Long, LookbookService.ClosetLookbookView> views =
                lookbookService.findClosetViews(List.of());

        assertThat(views).isEmpty();
        verify(lookbookRepository, never()).findAllByIdInAndDeletedAtIsNull(anyList());
    }

    private Lookbook createPersistedLookbook(LocalDateTime createdAt) {
        member.changeProfileImageId("profile-image");
        Lookbook lookbook = Lookbook.create(
                member,
                originalImage,
                matchedImage,
                "https://shop.example.com/item",
                "합리적인 가격으로 완성한 룩입니다."
        );
        ReflectionTestUtils.setField(lookbook, "id", 100L);
        ReflectionTestUtils.setField(lookbook, "likeCount", 5);
        ReflectionTestUtils.setField(lookbook, "createdAt", createdAt);
        return lookbook;
    }

    private Lookbook createListLookbook(Long lookbookId, LocalDateTime createdAt) {
        member.changeProfileImageId("profile-image");
        Lookbook lookbook = Lookbook.create(
                member,
                readyImage(
                        "original-" + lookbookId,
                        member,
                        ImagePurpose.LOOKBOOK
                ),
                readyImage(
                        "matched-" + lookbookId,
                        member,
                        ImagePurpose.LOOKBOOK
                ),
                null,
                null
        );
        ReflectionTestUtils.setField(lookbook, "id", lookbookId);
        ReflectionTestUtils.setField(lookbook, "createdAt", createdAt);
        return lookbook;
    }

    private RelatedLookbookRank relatedRank(
            Long lookbookId,
            Long relevanceScore,
            LocalDateTime createdAt
    ) {
        return new TestRelatedLookbookRank(lookbookId, relevanceScore, createdAt);
    }

    private record TestRelatedLookbookRank(
            Long lookbookId,
            Long relevanceScore,
            LocalDateTime createdAt
    ) implements RelatedLookbookRank {

        @Override
        public Long getLookbookId() {
            return lookbookId;
        }

        @Override
        public Long getRelevanceScore() {
            return relevanceScore;
        }

        @Override
        public LocalDateTime getCreatedAt() {
            return createdAt;
        }
    }
}
