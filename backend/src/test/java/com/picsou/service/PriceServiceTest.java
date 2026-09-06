package com.picsou.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.YahooFinancePriceProvider;
import com.picsou.model.AccountType;
import com.picsou.model.PriceSnapshot;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.PriceSnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins two contracts.
 *
 * <p><b>The resolution chain.</b> A price the provider cannot deliver right now must fall back to
 * the last one recorded in {@code price_snapshot}, and a failed attempt must not be retried on
 * every read. Without either, one rate-limited morning blanked the largest positions of an
 * account — which then reported a large loss, because the value side dropped those assets while
 * the cost side kept them — and every page render answered the rate limit with more requests.
 *
 * <p><b>The per-ticker guard in {@code backfillHistoricalPrices}</b>, which exists for two reasons
 * that are easy to undo by accident. It must <b>continue</b>: this runs from
 * {@code PriceBackfillRunner}, an {@code ApplicationRunner}, so an escaping exception fails Spring
 * Boot startup outright. And it must log at <b>ERROR</b>: the price providers swallow expected
 * upstream failures and return an empty map, so anything thrown here is a genuine bug. Logging it
 * at WARN would re-hide precisely what {@code CoinGeckoPriceProvider} was changed to rethrow.
 */
@ExtendWith(MockitoExtension.class)
class PriceServiceTest {

    @Mock CoinGeckoPriceProvider coinGecko;
    @Mock YahooFinancePriceProvider yahoo;
    @Mock PriceSnapshotRepository priceSnapshotRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock AccountRepository accountRepository;

    /** Moved by hand: the two cache TTLs are behaviour under test, not background noise. */
    private final SteppingClock clock = new SteppingClock();

    PriceService priceService;

    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void wire() {
        priceService = new PriceService(coinGecko, yahoo, priceSnapshotRepository,
            holdingRepository, accountRepository, clock);
    }

    @BeforeEach
    void captureLogs() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PriceService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private List<ILoggingEvent> eventsAt(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    /**
     * refreshPrices must honor the 15-minute cache TTL: GET /prices is polled
     * by the frontend on an interval, so serving fresh cache entries (instead
     * of re-fetching upstream every call) is what keeps an open dashboard tab
     * from hammering Yahoo/CoinGecko.
     */
    @Test
    void refreshPrices_servesFreshCacheWithoutUpstreamCall() {
        lenient().when(coinGecko.supports(any())).thenReturn(false);
        when(yahoo.getPricesEur(Set.of("AAPL"))).thenReturn(Map.of("AAPL", new BigDecimal("150")));
        lenient().when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());

        Map<String, BigDecimal> first = priceService.refreshPrices(Set.of("AAPL"));
        assertThat(first).containsEntry("AAPL", new BigDecimal("150"));

        Map<String, BigDecimal> second = priceService.refreshPrices(Set.of("AAPL"));
        assertThat(second).containsEntry("AAPL", new BigDecimal("150"));

        verify(yahoo, times(1)).getPricesEur(anySet());
        verify(priceSnapshotRepository, times(1)).save(any());
    }

    @Test
    void refreshPrices_fetchesOnlyMissingTickers() {
        lenient().when(coinGecko.supports(any())).thenReturn(false);
        when(yahoo.getPricesEur(Set.of("AAPL"))).thenReturn(Map.of("AAPL", new BigDecimal("150")));
        lenient().when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());
        priceService.refreshPrices(Set.of("AAPL"));

        when(yahoo.getPricesEur(Set.of("MSFT"))).thenReturn(Map.of("MSFT", new BigDecimal("410")));

        Map<String, BigDecimal> result = priceService.refreshPrices(Set.of("AAPL", "MSFT", "EUR"));

        assertThat(result)
            .containsEntry("AAPL", new BigDecimal("150"))
            .containsEntry("MSFT", new BigDecimal("410"))
            .containsEntry("EUR", BigDecimal.ONE);
        verify(yahoo).getPricesEur(Set.of("MSFT"));
    }

    private PriceSnapshot snapshot(String ticker, LocalDate date, String price) {
        return PriceSnapshot.builder().ticker(ticker).date(date).priceEur(new BigDecimal(price)).build();
    }

    @Test
    void price_fallsBackToTheLastRecordedOne_whenTheProviderReturnsNothing() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(coinGecko.supports("BTC")).thenReturn(true);
        when(coinGecko.getPricesEur(Set.of("BTC"))).thenReturn(Map.of());
        when(priceSnapshotRepository.findRecentByTickers(eq(Set.of("BTC")), any(), any()))
            .thenReturn(List.of(snapshot("BTC", yesterday, "54619")));

        assertThat(priceService.getPriceEur("BTC")).isEqualByComparingTo("54619");
    }

    @Test
    void quote_carriesTheRecordedDate_andSaysItIsNotLive() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(coinGecko.supports("SOL")).thenReturn(true);
        when(coinGecko.getPricesEur(Set.of("SOL"))).thenReturn(Map.of());
        when(priceSnapshotRepository.findRecentByTickers(eq(Set.of("SOL")), any(), any()))
            .thenReturn(List.of(snapshot("SOL", yesterday, "63.20")));

        PriceService.Quote quote = priceService.getCryptoQuote("SOL");

        // The client shows the figure and marks it -- both halves need the date and the flag.
        assertThat(quote).isNotNull();
        assertThat(quote.price()).isEqualByComparingTo("63.20");
        assertThat(quote.asOf()).isEqualTo(yesterday);
        assertThat(quote.live()).isFalse();
    }

    @Test
    void quote_fromTheProvider_isLiveAndDatedToday() {
        when(coinGecko.supports("BTC")).thenReturn(true);
        when(coinGecko.getPricesEur(Set.of("BTC"))).thenReturn(Map.of("BTC", new BigDecimal("54619")));

        PriceService.Quote quote = priceService.getCryptoQuote("BTC");

        assertThat(quote.live()).isTrue();
        assertThat(quote.asOf()).isEqualTo(LocalDate.now());
        verifyNoInteractions(priceSnapshotRepository);
    }

    @Test
    void fallback_onlyLooksBackAWeek() {
        when(coinGecko.supports("BTC")).thenReturn(true);
        when(coinGecko.getPricesEur(Set.of("BTC"))).thenReturn(Map.of());
        when(priceSnapshotRepository.findRecentByTickers(any(), any(), any())).thenReturn(List.of());

        assertThat(priceService.getPriceEur("BTC")).isNull();

        // The age limit is enforced by the query, so that is where it has to be asserted. A
        // month-old crypto price is not a stale number, it is a wrong one.
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        verify(priceSnapshotRepository).findRecentByTickers(any(), from.capture(), any());
        assertThat(from.getValue()).isEqualTo(LocalDate.now().minusDays(7));
    }

    @Test
    void aFailedLookupIsNotRetriedOnEveryRead() {
        when(coinGecko.supports("BTC")).thenReturn(true);
        when(coinGecko.getPricesEur(Set.of("BTC"))).thenReturn(Map.of());
        when(priceSnapshotRepository.findRecentByTickers(any(), any(), any())).thenReturn(List.of());

        priceService.getPriceEur("BTC");
        priceService.getPriceEur("BTC");
        priceService.getPriceEur("BTC");

        // Three reads, one attempt. The opposite -- a request per read -- is what kept an
        // instance rate-limited for hours after a momentary 429.
        verify(coinGecko, times(1)).getPricesEur(Set.of("BTC"));
    }

    @Test
    void aSetOfTickersCostsOneProviderCall() {
        Set<String> tickers = Set.of("BTC", "ETH", "SOL");
        tickers.forEach(t -> when(coinGecko.supports(t)).thenReturn(true));
        when(coinGecko.getPricesEur(any())).thenReturn(Map.of(
            "BTC", new BigDecimal("54619"),
            "ETH", new BigDecimal("1619"),
            "SOL", new BigDecimal("63.20")));

        assertThat(priceService.getCryptoQuotes(tickers)).containsOnlyKeys("BTC", "ETH", "SOL");

        verify(coinGecko, times(1)).getPricesEur(any());
    }

    @Test
    void cryptoLookupOfAnUnmappedTicker_neverReachesTheRecordedPrices() {
        when(coinGecko.supports("STX")).thenReturn(false);

        assertThat(priceService.getCryptoQuote("STX")).isNull();

        // price_snapshot is keyed by ticker alone, exactly like the cache: reading it here would
        // hand back the share price of the equity trading under the same symbol.
        verifyNoInteractions(priceSnapshotRepository);
        verify(coinGecko, org.mockito.Mockito.never()).getPricesEur(any());
    }

    @Test
    void refreshCryptoQuotes_valuesFromTheRecordedPrice_withoutRerecordingItAsToday() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(coinGecko.supports("ATOM")).thenReturn(true);
        when(coinGecko.getPricesEur(any())).thenReturn(Map.of());
        when(priceSnapshotRepository.findRecentByTickers(eq(Set.of("ATOM")), any(), any()))
            .thenReturn(List.of(snapshot("ATOM", yesterday, "1.065")));

        Map<String, PriceService.Quote> quotes = priceService.refreshCryptoQuotes(Set.of("ATOM"));

        assertThat(quotes.get("ATOM").price()).isEqualByComparingTo("1.065");
        assertThat(quotes.get("ATOM").live()).isFalse();
        // Writing the fallback back under today's date would launder a stale price into a fresh
        // one and let the fallback walk itself forward indefinitely.
        verify(priceSnapshotRepository, org.mockito.Mockito.never()).save(any());
    }

    /** A row every {@code stepDays} from {@code from} up to today, ordered as the query returns. */
    private List<PriceSnapshot> dailyHistory(String ticker, LocalDate from, int stepDays) {
        List<PriceSnapshot> rows = new java.util.ArrayList<>();
        for (LocalDate date = from; !date.isAfter(LocalDate.now()); date = date.plusDays(stepDays)) {
            rows.add(snapshot(ticker, date, "50000"));
        }
        return rows;
    }

    @Test
    void backfill_skipsATickerWhoseHistoryIsAlreadyThere() {
        LocalDate from = LocalDate.now().minusMonths(12);
        when(priceSnapshotRepository.findByTickerInAndDateBetween(eq(Set.of("BTC")), any(), any()))
            .thenReturn(dailyHistory("BTC", from, 1));

        assertThat(priceService.backfillHistoricalPrices(Set.of("BTC"), from)).isZero();

        // This runs at every boot, once per held ticker, against a free tier that counts
        // requests: re-fetching twelve months only to discard every row as a duplicate is what
        // exhausted the rate limit seconds after startup.
        verifyNoInteractions(coinGecko);
        verifyNoInteractions(yahoo);
    }

    @Test
    void backfill_toleratesTheGapsClosedMarketsLeave() {
        // Equities have no weekend rows, and an Easter or Christmas week stretches that further.
        // Treating those as holes would re-request every stock's full year at every boot.
        LocalDate from = LocalDate.now().minusMonths(12);
        when(priceSnapshotRepository.findByTickerInAndDateBetween(eq(Set.of("AAPL")), any(), any()))
            .thenReturn(dailyHistory("AAPL", from, 5));

        assertThat(priceService.backfillHistoricalPrices(Set.of("AAPL"), from)).isZero();

        verifyNoInteractions(yahoo);
    }

    @Test
    void backfill_refillsAHoleInTheMiddleOfAnExistingHistory() {
        // The failure the edge-probe version could not see: an instance offline for months has
        // history on both sides of the outage, so probing the two ends declares it covered and
        // the hole is never filled — the history chart then flat-lines across it forever.
        LocalDate from = LocalDate.now().minusMonths(12);
        List<PriceSnapshot> withHole = new java.util.ArrayList<>();
        withHole.addAll(dailyHistory("BTC", from, 1).subList(0, 30));
        withHole.add(snapshot("BTC", LocalDate.now(), "54619"));
        when(priceSnapshotRepository.findByTickerInAndDateBetween(eq(Set.of("BTC")), any(), any()))
            .thenReturn(withHole);
        when(coinGecko.supports("BTC")).thenReturn(true);
        when(coinGecko.getHistoricalPricesEur(eq("BTC"), any(), any()))
            .thenReturn(Map.of(from.plusDays(60), new BigDecimal("51000")));
        when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());

        assertThat(priceService.backfillHistoricalPrices(Set.of("BTC"), from)).isEqualTo(1);
    }

    @Test
    void backfill_continuesPastAFailingTicker_andLogsItAtError() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        when(coinGecko.supports("BTC")).thenReturn(true);
        when(coinGecko.supports("ETH")).thenReturn(true);
        when(coinGecko.getHistoricalPricesEur(eq("BTC"), any(), any()))
            .thenThrow(new IllegalStateException("a real bug"));
        when(coinGecko.getHistoricalPricesEur(eq("ETH"), any(), any()))
            .thenReturn(Map.of(from, new BigDecimal("3000")));
        when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());

        int saved = assertThatNoStartupFailure(() ->
            priceService.backfillHistoricalPrices(new java.util.LinkedHashSet<>(List.of("BTC", "ETH")), from));

        assertThat(saved).isEqualTo(1);
        verify(priceSnapshotRepository).save(any());

        assertThat(eventsAt(Level.ERROR)).hasSize(2);
        assertThat(eventsAt(Level.ERROR).get(0).getFormattedMessage()).contains("BTC");
        assertThat(eventsAt(Level.WARN)).isEmpty();
        assertThat(eventsAt(Level.ERROR).get(1).getFormattedMessage())
            .contains("1 of 2 tickers failing");
    }

    @Test
    void backfill_routesToYahoo_forTickersCoinGeckoDoesNotSupport() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        when(coinGecko.supports("AAPL")).thenReturn(false);
        when(yahoo.getHistoricalPricesEur(eq("AAPL"), any(), any()))
            .thenReturn(Map.of(from, new BigDecimal("200")));
        when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());

        assertThat(priceService.backfillHistoricalPrices(Set.of("AAPL"), from)).isEqualTo(1);
    }

    @Test
    void getPriceEur_cachesAMiss_soAnUnresolvableTickerIsFetchedOnce() {
        when(coinGecko.supports("MWRDF")).thenReturn(false);
        when(yahoo.getPricesEur(Set.of("MWRDF"))).thenReturn(Map.of());

        assertThat(priceService.getPriceEur("MWRDF")).isNull();
        assertThat(priceService.getPriceEur("MWRDF")).isNull();
        assertThat(priceService.getPriceEur("MWRDF")).isNull();

        verify(yahoo, times(1)).getPricesEur(Set.of("MWRDF"));
    }

    @Test
    void getPriceEur_stillCachesAndReturnsHits() {
        when(coinGecko.supports("AAPL")).thenReturn(false);
        when(yahoo.getPricesEur(Set.of("AAPL"))).thenReturn(Map.of("AAPL", new BigDecimal("200")));

        assertThat(priceService.getPriceEur("AAPL")).isEqualByComparingTo("200");
        assertThat(priceService.getPriceEur("AAPL")).isEqualByComparingTo("200");

        verify(yahoo, times(1)).getPricesEur(Set.of("AAPL"));
    }

    /** Runs the backfill and fails loudly if it throws — the ApplicationRunner contract. */
    private int assertThatNoStartupFailure(java.util.function.Supplier<Integer> backfill) {
        var result = new int[1];
        assertThatCode(() -> result[0] = backfill.get())
            .as("backfill must never propagate; PriceBackfillRunner would fail Spring Boot startup")
            .doesNotThrowAnyException();
        return result[0];
    }

    // ─── the negative cache and the write path ─────────────────────────────────────────────

    /**
     * The 2026-08-01 incident, rebuilt through the cache: a dashboard render during a CoinGecko
     * cooldown remembers the miss, and an exchange sync a minute later must still value the
     * asset from the recorded price. Serving the remembered miss as a null-priced live Quote
     * skipped that fallback and wrote a partial balance into balance_snapshot for good.
     */
    @Test
    void refreshCryptoQuotes_afterAReadPathMiss_stillValuesFromTheRecordedPrice() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(coinGecko.supports("BTC")).thenReturn(true);
        when(coinGecko.getPricesEur(Set.of("BTC"))).thenReturn(Map.of());
        when(priceSnapshotRepository.findRecentByTickers(eq(Set.of("BTC")), any(), any()))
            .thenReturn(List.of(snapshot("BTC", yesterday, "54619")));

        assertThat(priceService.getCryptoQuote("BTC").live()).isFalse(); // seeds the miss

        Map<String, PriceService.Quote> quotes = priceService.refreshCryptoQuotes(Set.of("BTC"));

        assertThat(quotes.get("BTC").price()).isEqualByComparingTo("54619");
        assertThat(quotes.get("BTC").live()).isFalse();
        assertThat(quotes.values()).allSatisfy(q -> assertThat(q.price()).isNotNull());
        // The miss is still honoured for the *fetch* decision: one attempt, not one per path.
        verify(coinGecko, times(1)).getPricesEur(any());
    }

    @Test
    void refreshPrices_leavesARememberedMissOut_ratherThanMappingItToNull() {
        when(coinGecko.supports("BTC")).thenReturn(true);
        when(coinGecko.getPricesEur(Set.of("BTC"))).thenReturn(Map.of());
        when(priceSnapshotRepository.findRecentByTickers(any(), any(), any())).thenReturn(List.of());
        priceService.getCryptoPriceEur("BTC");

        Map<String, BigDecimal> prices = priceService.refreshPrices(Set.of("BTC"));

        // WalletSyncService reads prices.get(ticker) == null either way; the exchange sync does
        // not, and a present-but-null entry is what defeated its fallback.
        assertThat(prices).doesNotContainKey("BTC");
        assertThat(prices.values()).doesNotContainNull();
        verify(coinGecko, times(1)).getPricesEur(any());
    }

    @Test
    void aQuoteNeverCarriesANullPrice() {
        assertThatThrownBy(() -> new PriceService.Quote(null, LocalDate.now(), true))
            .isInstanceOf(NullPointerException.class);
    }

    // ─── the two TTLs ──────────────────────────────────────────────────────────────────────

    @Test
    void aMissIsRetriedAfterSixtySeconds_whileAHitIsStillServed() {
        when(coinGecko.supports("BTC")).thenReturn(true);
        when(coinGecko.supports("ETH")).thenReturn(true);
        when(coinGecko.getPricesEur(Set.of("BTC", "ETH")))
            .thenReturn(Map.of("ETH", new BigDecimal("1619")));
        when(coinGecko.getPricesEur(Set.of("BTC"))).thenReturn(Map.of());
        when(priceSnapshotRepository.findRecentByTickers(any(), any(), any())).thenReturn(List.of());

        priceService.getCryptoQuotes(Set.of("BTC", "ETH")); // ETH hit, BTC miss, both at t0

        clock.advance(Duration.ofSeconds(59));
        priceService.getCryptoQuotes(Set.of("BTC", "ETH"));
        verify(coinGecko, never()).getPricesEur(Set.of("BTC"));

        // The ADR's trade-off is "not retried for 60 seconds" -- not for the 900 s a hit lives.
        // A 429 with a 30 s Retry-After would otherwise leave every affected ticker on the
        // amber fallback for a quarter of an hour after the provider recovered.
        clock.advance(Duration.ofSeconds(2));
        Map<String, PriceService.Quote> quotes = priceService.getCryptoQuotes(Set.of("BTC", "ETH"));

        verify(coinGecko, times(1)).getPricesEur(Set.of("BTC"));
        assertThat(quotes.get("ETH").live()).isTrue();
    }

    @Test
    void aHitExpiresAfterFifteenMinutes() {
        when(coinGecko.supports("ETH")).thenReturn(true);
        when(coinGecko.getPricesEur(Set.of("ETH"))).thenReturn(Map.of("ETH", new BigDecimal("1619")));

        priceService.getCryptoQuote("ETH");
        clock.advance(Duration.ofSeconds(899));
        priceService.getCryptoQuote("ETH");
        verify(coinGecko, times(1)).getPricesEur(any());

        clock.advance(Duration.ofSeconds(2));
        priceService.getCryptoQuote("ETH");
        verify(coinGecko, times(2)).getPricesEur(any());
    }

    // ─── the request path is type-aware ────────────────────────────────────────────────────

    /**
     * GET /api/prices receives every holding of every account and cannot tell a coin from a
     * share. A Synthetix position whose symbol CoinGecko does not map must not be displayed --
     * and recorded in price_snapshot -- at TD SYNNEX's share price.
     */
    @Test
    void refreshHeldPrices_neverSendsACryptoHoldingToYahoo() {
        when(holdingRepository.findDistinctTickersByAccountType(AccountType.CRYPTO))
            .thenReturn(Set.of("SNX", "BTC"));
        when(accountRepository.findDistinctTickersByType(AccountType.CRYPTO)).thenReturn(Set.of());
        when(coinGecko.supports("BTC")).thenReturn(true);
        when(coinGecko.supports("SNX")).thenReturn(false);
        when(coinGecko.supports("AAPL")).thenReturn(false);
        when(coinGecko.getPricesEur(Set.of("BTC"))).thenReturn(Map.of("BTC", new BigDecimal("54619")));
        when(yahoo.getPricesEur(Set.of("AAPL"))).thenReturn(Map.of("AAPL", new BigDecimal("150")));
        when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());

        Map<String, BigDecimal> prices = priceService.refreshHeldPrices(Set.of("SNX", "BTC", "AAPL"));

        assertThat(prices)
            .containsEntry("BTC", new BigDecimal("54619"))
            .containsEntry("AAPL", new BigDecimal("150"))
            .doesNotContainKey("SNX");
        verify(yahoo).getPricesEur(Set.of("AAPL"));
        verify(yahoo, never()).getPricesEur(argThatContains("SNX"));
        ArgumentCaptor<PriceSnapshot> saved = ArgumentCaptor.forClass(PriceSnapshot.class);
        verify(priceSnapshotRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(PriceSnapshot::getTicker)
            .containsExactlyInAnyOrder("BTC", "AAPL");
    }

    @Test
    void refreshHeldPrices_treatsAManualCryptoAccountsOwnTickerAsCrypto() {
        when(holdingRepository.findDistinctTickersByAccountType(AccountType.CRYPTO)).thenReturn(Set.of());
        when(accountRepository.findDistinctTickersByType(AccountType.CRYPTO)).thenReturn(Set.of("stx"));
        when(coinGecko.supports("STX")).thenReturn(false);

        assertThat(priceService.refreshHeldPrices(Set.of("STX"))).isEmpty();

        verifyNoInteractions(yahoo);
        verify(priceSnapshotRepository, never()).save(any());
    }

    private static Set<String> argThatContains(String ticker) {
        return org.mockito.ArgumentMatchers.argThat(set -> set != null && set.contains(ticker));
    }

    // ─── the backfill is type-aware ────────────────────────────────────────────────────────

    @Test
    void cryptoBackfill_skipsAnUnmappedTicker_insteadOfRecordingTheEquitysHistory() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        when(coinGecko.supports("STX")).thenReturn(false);
        when(coinGecko.supports("BTC")).thenReturn(true);
        when(priceSnapshotRepository.findByTickerInAndDateBetween(eq(Set.of("BTC")), any(), any()))
            .thenReturn(List.of());
        when(coinGecko.getHistoricalPricesEur(eq("BTC"), any(), any()))
            .thenReturn(Map.of(from, new BigDecimal("50000")));
        when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());

        int saved = priceService.backfillHistoricalCryptoPrices(Set.of("STX", "BTC"), from);

        // Seagate's closes under the Stacks ticker would become the "value at fromDate" of the
        // position in every range P&L -- at every boot until the history reads as covered.
        assertThat(saved).isEqualTo(1);
        verifyNoInteractions(yahoo);
        verify(priceSnapshotRepository, never()).findByTickerInAndDateBetween(eq(Set.of("STX")), any(), any());
        assertThat(eventsAt(Level.WARN)).hasSize(1);
        assertThat(eventsAt(Level.WARN).get(0).getFormattedMessage()).contains("STX");
    }

    // ─── concurrency ───────────────────────────────────────────────────────────────────────

    /**
     * The dashboard fires its requests in parallel and each of them values the same holdings.
     * On a cold cache, every thread that saw the gap used to issue its own provider call
     * before any of them had written the cache -- four identical calls counted by the free
     * tier. The second thread must wait for the first and reuse its answer.
     */
    @Test
    void concurrentReadsOfTheSameMissingTicker_costOneProviderCall() throws Exception {
        when(coinGecko.supports("BTC")).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(coinGecko.getPricesEur(Set.of("BTC"))).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Map.of("BTC", new BigDecimal("54619"));
        });

        PriceService.Quote[] seen = new PriceService.Quote[2];
        Thread first = new Thread(() -> seen[0] = priceService.getCryptoQuote("BTC"));
        Thread second = new Thread(() -> seen[1] = priceService.getCryptoQuote("BTC"));

        first.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).as("first thread reached the provider").isTrue();
        second.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (second.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        release.countDown();
        first.join(5_000);
        second.join(5_000);

        verify(coinGecko, times(1)).getPricesEur(any());
        assertThat(seen[0].price()).isEqualByComparingTo("54619");
        assertThat(seen[1].price()).isEqualByComparingTo("54619");
        assertThat(seen[1].live()).isTrue();
    }

    /**
     * Two callers recording a ticker's first price of the day can both find no row and both
     * insert; the loser's unique-constraint violation used to escape as a 500 from
     * GET /api/prices. It now adopts the winner's row.
     */
    @Test
    void refreshPrices_survivesLosingTheInsertRaceForTodaysSnapshot() {
        when(coinGecko.supports("AAPL")).thenReturn(false);
        when(yahoo.getPricesEur(Set.of("AAPL"))).thenReturn(Map.of("AAPL", new BigDecimal("150")));
        PriceSnapshot theirs = snapshot("AAPL", LocalDate.now(), "149");
        when(priceSnapshotRepository.findByTickerAndDate("AAPL", LocalDate.now()))
            .thenReturn(Optional.empty(), Optional.of(theirs));
        when(priceSnapshotRepository.save(any()))
            .thenThrow(new DataIntegrityViolationException("uk_price_snapshot_ticker_date"))
            .thenAnswer(inv -> inv.getArgument(0));

        Map<String, BigDecimal> prices = priceService.refreshPrices(Set.of("AAPL"));

        assertThat(prices).containsEntry("AAPL", new BigDecimal("150"));
        assertThat(theirs.getPriceEur()).isEqualByComparingTo("150");
        verify(priceSnapshotRepository, times(2)).save(any());
    }

    /** A clock the test moves by hand. Volatile: the concurrency test reads it from two threads. */
    private static final class SteppingClock extends Clock {
        private volatile Instant now = Instant.parse("2026-09-06T09:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
