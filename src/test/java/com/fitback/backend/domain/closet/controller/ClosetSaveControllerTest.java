package com.fitback.backend.domain.closet.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.closet.dto.ClosetSaveRequest;
import com.fitback.backend.domain.closet.dto.ClosetSaveResponse;
import com.fitback.backend.domain.closet.entity.ClosetSave;
import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import com.fitback.backend.domain.closet.service.ClosetSaveService;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.response.ApiResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ClosetSaveControllerTest {

    @Mock
    private ClosetSaveService closetSaveService;

    @InjectMocks
    private ClosetSaveController closetSaveController;

    @Test
    void saveClosetReturnsCreatedResponse() {
        ClosetSaveRequest.Create request = new ClosetSaveRequest.Create(ClosetTargetType.LOOKBOOK, 12L);
        Member member = Member.create("member@fitback.com", "fitback", "password", LoginProvider.EMAIL);
        ReflectionTestUtils.setField(member, "id", 1L);
        ClosetSave closetSave = ClosetSave.create(member, ClosetTargetType.LOOKBOOK, 12L);
        when(closetSaveService.save(1L, request)).thenReturn(closetSave);

        ApiResponse<Void> response = closetSaveController.saveCloset(1L, request);

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON201_1");
        assertThat(response.data()).isNull();
        verify(closetSaveService).save(1L, request);
    }

    @Test
    void getClosetSavesReturnsSuccessResponse() {
        ClosetSaveResponse.ClosetSaveItem item = ClosetSaveResponse.ClosetSaveItem.builder()
                .targetType(ClosetTargetType.TREND)
                .targetId(1L)
                .thumbnailUrl(null)
                .tags(List.of())
                .build();
        ClosetSaveResponse.ClosetSaveList serviceResponse = ClosetSaveResponse.ClosetSaveList.builder()
                .items(List.of(item))
                .nextCursor(null)
                .hasNext(false)
                .pageSize(10)
                .build();
        when(closetSaveService.getClosetSaves(1L, null, null)).thenReturn(serviceResponse);

        ApiResponse<ClosetSaveResponse.ClosetSaveList> response = closetSaveController.getClosetSaves(1L, null, null);

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.data()).isEqualTo(serviceResponse);
        assertThat(response.data().items()).containsExactly(item);
        verify(closetSaveService).getClosetSaves(1L, null, null);
    }

    @Test
    void getClosetSavesPassesTargetTypeAndCursorToService() {
        ClosetSaveResponse.ClosetSaveList serviceResponse = ClosetSaveResponse.ClosetSaveList.builder()
                .items(List.of())
                .nextCursor(null)
                .hasNext(false)
                .pageSize(10)
                .build();
        when(closetSaveService.getClosetSaves(1L, ClosetTargetType.TREND, 5L)).thenReturn(serviceResponse);

        closetSaveController.getClosetSaves(1L, ClosetTargetType.TREND, 5L);

        verify(closetSaveService).getClosetSaves(1L, ClosetTargetType.TREND, 5L);
    }

    @Test
    void cancelClosetSaveReturnsSuccessResponse() {
        ApiResponse<Void> response = closetSaveController.cancelClosetSave(10L, 1L);

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.data()).isNull();
        verify(closetSaveService).cancel(1L, 10L);
    }
}
