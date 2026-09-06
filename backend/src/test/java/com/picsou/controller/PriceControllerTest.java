package com.picsou.controller;

import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.service.PriceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito controller test (no Spring context, no MockMvc).
 *
 * <p>{@code GET /api/prices} is fed every holding of every account by a frontend that cannot
 * tell a coin from a share. It must go through the type-aware refresh, never the generic one:
 * the generic route sends an unmapped coin to Yahoo Finance and records the same-named equity's
 * share price in {@code price_snapshot}.
 */
@ExtendWith(MockitoExtension.class)
class PriceControllerTest {

    @Mock PriceService priceService;
    @Mock PriceSnapshotRepository priceSnapshotRepository;

    @InjectMocks PriceController controller;

    @Test
    void getPrices_parsesTheListAndRoutesThroughTheTypeAwareRefresh() {
        Map<String, BigDecimal> prices = Map.of("BTC", new BigDecimal("54619"));
        when(priceService.refreshHeldPrices(Set.of("BTC", "SNX", "IWDA.AS"))).thenReturn(prices);

        assertThat(controller.getPrices(" BTC, SNX ,IWDA.AS,, ")).isSameAs(prices);

        verify(priceService, never()).refreshPrices(any());
    }
}
