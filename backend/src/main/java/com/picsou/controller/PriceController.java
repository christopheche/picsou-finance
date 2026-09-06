package com.picsou.controller;

import com.picsou.model.PriceSnapshot;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.service.PriceService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/prices")
public class PriceController {

    private final PriceService priceService;
    private final PriceSnapshotRepository priceSnapshotRepository;

    public PriceController(PriceService priceService, PriceSnapshotRepository priceSnapshotRepository) {
        this.priceService = priceService;
        this.priceSnapshotRepository = priceSnapshotRepository;
    }

    /**
     * Live EUR prices for a comma-separated ticker list.
     *
     * <p>The frontend sends every holding of every account here, crypto accounts included, and
     * knows nothing about which symbol is a coin. {@code refreshHeldPrices} routes the ones held
     * in a CRYPTO account crypto-only, so an unmapped coin is left unpriced instead of being
     * shown — and recorded in {@code price_snapshot} — at the share price of the equity trading
     * under the same symbol.
     */
    @GetMapping
    public Map<String, BigDecimal> getPrices(@RequestParam String tickers) {
        Set<String> tickerSet = Arrays.stream(tickers.split(","))
            .map(String::trim)
            .filter(t -> !t.isBlank())
            .collect(Collectors.toSet());

        return priceService.refreshHeldPrices(tickerSet);
    }

    /**
     * Historical daily prices for a single ticker from the price_snapshot table.
     *
     * <p>{@code months} is bounded: it feeds {@code LocalDate.now().minusMonths(months)}, which
     * throws {@code DateTimeException} — unhandled, so a 500 — once the result leaves the
     * supported year range, while a negative value silently inverts the range and returns an
     * empty series with a 200.
     */
    @GetMapping("/{ticker}/history")
    public List<Map<String, Object>> getPriceHistory(
        @PathVariable String ticker,
        @RequestParam(defaultValue = "12") int months
    ) {
        requireMonthsInRange(months);
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusMonths(months);
        List<PriceSnapshot> snapshots = priceSnapshotRepository
            .findByTickerInAndDateBetween(Set.of(ticker.toUpperCase()), from, to);

        List<Map<String, Object>> result = new ArrayList<>();
        for (PriceSnapshot ps : snapshots) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", ps.getDate().toString());
            point.put("priceEur", ps.getPriceEur());
            result.add(point);
        }
        return result;
    }

    /**
     * Intraday hourly prices for a single ticker (last 24h) from external providers.
     *
     * <p>Deliberately does <em>not</em> catch and degrade, unlike
     * {@code HistoryService.buildIntradayHistory}. That method loops over many tickers, so
     * swallowing one failure still leaves a useful chart; here the single ticker <em>is</em>
     * the whole response, and returning an empty list would be indistinguishable from "this
     * ticker genuinely has no intraday data". The price providers already swallow expected
     * upstream failures and return no prices, so anything propagating here is a real bug and
     * a 500 is the honest answer.
     */
    @GetMapping("/{ticker}/intraday")
    public List<Map<String, Object>> getPriceIntraday(@PathVariable String ticker) {
        // UTC, the one zone of the intraday pipeline (HistoryService.INTRADAY_ZONE): both
        // providers key their bars in UTC wall-clock and filter on this window, so building it
        // from the JVM default zone would drop or shift bars on any host that is not UTC.
        LocalDateTime to = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime from = to.minusHours(24);
        Map<LocalDateTime, BigDecimal> prices = priceService.getIntradayPricesEur(ticker, from, to);

        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : prices.entrySet()) {
            Map<String, Object> point = new LinkedHashMap<>();
            // Serialised with its offset: the keys are UTC wall-clock, and a zone-less string
            // is read by the browser as local time -- which is how a Paris viewer saw the
            // day's prices labelled two hours early.
            point.put("timestamp", entry.getKey().toInstant(ZoneOffset.UTC).toString());
            point.put("priceEur", entry.getValue());
            result.add(point);
        }
        return result;
    }

    /** Upper bound on the {@code months} window: 50 years, far past any snapshot history. */
    static final int MAX_MONTHS = 600;

    private static void requireMonthsInRange(int months) {
        if (months < 1 || months > MAX_MONTHS) {
            throw new IllegalArgumentException("months must be between 1 and " + MAX_MONTHS);
        }
    }
}
