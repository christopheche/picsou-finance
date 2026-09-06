package com.picsou.config;

import com.picsou.model.AccountType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.service.PriceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The boot-time backfill must split crypto holdings from the rest exactly as the hourly refresh
 * does. The generic route hands whatever CoinGecko cannot map to Yahoo Finance and saves every
 * candle, so a coin sharing its symbol with a listed equity would get twelve months of that
 * company's closes recorded under its ticker — the rows every range P&amp;L then reads.
 */
@ExtendWith(MockitoExtension.class)
class PriceBackfillRunnerTest {

    @Mock PriceService priceService;
    @Mock AccountHoldingRepository holdingRepository;

    @InjectMocks PriceBackfillRunner runner;

    @Test
    void run_backfillsCryptoHoldingsCryptoOnly_andTheRestGenerically() {
        when(holdingRepository.findDistinctTickersByAccountType(AccountType.CRYPTO))
            .thenReturn(Set.of("STX", "BTC"));
        when(holdingRepository.findDistinctTickers()).thenReturn(Set.of("STX", "BTC", "AAPL"));

        runner.run(null);

        verify(priceService).backfillHistoricalCryptoPrices(eq(Set.of("BTC", "STX")), any());
        verify(priceService).backfillHistoricalPrices(eq(Set.of("AAPL")), any());
        verify(priceService, never()).backfillHistoricalPrices(argThatContains("STX"), any());
    }

    @Test
    void run_doesNothingWhenNothingIsHeld() {
        when(holdingRepository.findDistinctTickersByAccountType(AccountType.CRYPTO)).thenReturn(Set.of());
        when(holdingRepository.findDistinctTickers()).thenReturn(Set.of());

        runner.run(null);

        verifyNoInteractions(priceService);
    }

    private static Set<String> argThatContains(String ticker) {
        return org.mockito.ArgumentMatchers.argThat(set -> set != null && set.contains(ticker));
    }
}
