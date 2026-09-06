package com.picsou.controller;

import com.picsou.service.HistoryService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito controller test (no Spring context, no MockMvc).
 *
 * <p>{@code months} feeds {@code LocalDate.now().minusMonths(months)}: an unbounded value
 * overflows the supported year range and throws {@code DateTimeException}, which no handler
 * covers, so the caller sees a 500; a negative one silently inverts the range and returns an
 * empty series with a 200. Both are now a 400 naming the bound.
 */
@ExtendWith(MockitoExtension.class)
class HistoryControllerTest {

    private static final Long MEMBER_ID = 7L;

    @Mock HistoryService historyService;
    @Mock UserContext userContext;

    @InjectMocks HistoryController controller;

    @Test
    void getHistory_forwardsTheWindowAndTheResolvedMemberId() {
        when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
        when(historyService.buildHistory(List.of(1L), 12, false, MEMBER_ID)).thenReturn(List.of());

        assertThat(controller.getHistory(List.of(1L), 12, false)).isEmpty();
        verify(historyService).buildHistory(List.of(1L), 12, false, MEMBER_ID);
    }

    @Test
    void getHistory_acceptsTheWholeSupportedWindow() {
        when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
        when(historyService.buildHistory(List.of(1L), HistoryController.MAX_MONTHS, false, MEMBER_ID))
            .thenReturn(List.of());

        assertThat(controller.getHistory(List.of(1L), HistoryController.MAX_MONTHS, false)).isEmpty();
    }

    @Test
    void getHistory_rejectsAWindowThatOverflowsLocalDateArithmetic() {
        assertThatThrownBy(() -> controller.getHistory(List.of(1L), Integer.MAX_VALUE, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("months must be between 1 and " + HistoryController.MAX_MONTHS);

        verify(historyService, never()).buildHistory(anyList(), anyInt(), anyBoolean(), anyLong());
    }

    @Test
    void getHistory_rejectsANegativeWindowInsteadOfReturningAnEmptySeries() {
        assertThatThrownBy(() -> controller.getHistory(List.of(1L), -5, false))
            .isInstanceOf(IllegalArgumentException.class);

        verify(historyService, never()).buildHistory(anyList(), anyInt(), anyBoolean(), anyLong());
    }
}
