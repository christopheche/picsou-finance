package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.picsou.port.PriceProviderPort;
import com.picsou.port.SymbolCatalogPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Fetches stock/ETF prices from Yahoo Finance (unofficial, no API key needed).
 * Used for PEA/Compte-Titres positions with tickers like "IWDA.AS", "MC.PA", etc.
 *
 * Prices are converted to EUR using Yahoo's own FX endpoint ({CURRENCY}EUR=X)
 * when the security is quoted in a non-EUR currency. Rates are cached for 15
 * minutes to limit API calls. London pence (GBp/GBX) is handled as GBP/100.
 *
 * Note: This is an unofficial API. For production use consider Alpha Vantage or similar.
 */
@Component
public class YahooFinancePriceProvider implements PriceProviderPort, SymbolCatalogPort {

    private static final Logger log = LoggerFactory.getLogger(YahooFinancePriceProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * Timeout for the {@link SymbolCatalogPort} calls, shorter than the one a price read gets.
     *
     * <p>The two are not worth the same wait. A price that fails to arrive leaves a holding with no
     * value, so it is worth waiting for. A verification that fails to arrive costs nothing — the
     * caller keeps the ticker it already had — but it is paid on the write path, inside the
     * transaction of a user saving a transaction or importing a CSV. Three seconds is already an
     * order of magnitude above what the chart endpoint answers in.
     */
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(3);

    private static final Duration FX_CACHE_TTL = Duration.ofMinutes(15);

    /** Applied when a 429 arrives without a usable {@code Retry-After}. */
    private static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(60);

    /**
     * Ceiling on a server-supplied {@code Retry-After}, for the same reason
     * {@code CoinGeckoPriceProvider} caps its own: a "86400" would otherwise leave the instance
     * unable to price a single equity for a day, long after the real limit lifted.
     */
    private static final Duration MAX_COOLDOWN = Duration.ofMinutes(15);

    private static final java.util.regex.Pattern SYMBOL_PATTERN =
        java.util.regex.Pattern.compile("(?:\\^[A-Z0-9][A-Z0-9.=-]{0,18}|[A-Z0-9][A-Z0-9.=-]{0,19})");

    // Tickers that are handled by CoinGecko — we skip those
    private static final Set<String> CRYPTO_TICKERS = Set.of(
        "BTC", "ETH", "SOL", "BNB", "ADA", "XRP", "DOGE", "DOT", "MATIC", "AVAX"
    );

    private final WebClient webClient;
    private final Map<String, CachedFx> fxCache = new ConcurrentHashMap<>();

    /**
     * When a 429 stops being in force. Prices are read per ticker, so without this a portfolio
     * that trips Yahoo's limit at ticker 20 still fires the remaining 40 requests (plus one FX
     * request each), and the 15-minute scheduler repeats that every cycle -- answering a rate
     * limit with more traffic is what turns a one-minute limit into a morning of missing prices.
     * Same pause, same reasoning as {@code CoinGeckoPriceProvider.rateLimitedUntil}.
     *
     * <p>Volatile, not a lock: concurrent readers racing on the boundary either skip one call
     * they could have made or make one they could have skipped, and neither matters.
     */
    private volatile Instant rateLimitedUntil = Instant.EPOCH;

    public YahooFinancePriceProvider() {
        this(WebClient.builder()
            .baseUrl("https://query1.finance.yahoo.com")
            .defaultHeader("Accept", "application/json")
            .defaultHeader("User-Agent", "Mozilla/5.0")
            .build());
    }

    // Package-private constructor for tests — inject a WebClient backed by an ExchangeFunction.
    YahooFinancePriceProvider(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public boolean supports(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return false;
        }
        String upper = ticker.toUpperCase(Locale.ROOT);

        // Don't support crypto tickers
        if (CRYPTO_TICKERS.contains(upper)) {
            return false;
        }

        // Don't support plain ISIN codes (12-character alphanumeric starting with 2-letter country code)
        // ISIN format: AA########X (2 letters, 9 digits, 1 check digit)
        if (upper.length() == 12 && upper.matches("[A-Z]{2}[A-Z0-9]{9}[A-Z0-9]")) {
            log.debug("Rejecting unsupported ISIN: {}", ticker);
            return false;
        }

        if (!SYMBOL_PATTERN.matcher(upper).matches()) {
            log.debug("Rejecting non-symbol ticker: {}", ticker);
            return false;
        }

        return true;
    }

    @Override
    public Map<String, BigDecimal> getPricesEur(Set<String> tickers) {
        Set<String> supported = tickers.stream()
            .filter(this::supports)
            .map(ticker -> ticker.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());

        if (supported.isEmpty()) return Map.of();

        Map<String, BigDecimal> result = new HashMap<>();

        // Yahoo Finance is fetched per-ticker (no batch endpoint for EUR conversion)
        for (String ticker : supported) {
            // A 429 applies to the whole endpoint, not to one symbol: once it is in force the
            // remaining tickers of this batch have nothing to gain from being asked.
            if (coolingDown(ticker)) break;
            try {
                BigDecimal price = fetchSinglePrice(ticker);
                if (price != null) result.put(ticker, price);
            } catch (RuntimeException ex) {
                handleFetchFailure(ticker, ex);
            }
        }

        return result;
    }

    /**
     * Classifies a failed price read, and decides whether it is ours to swallow.
     *
     * <p><b>Expected upstream failures</b> (HTTP error, unreachable API, timeout) are logged and
     * the ticker stays unpriced this cycle -- the contract {@code SchedulerService} and
     * {@code PriceService} rely on, where a missing price means "not valued now", never "not
     * held", and the last recorded price is kept.
     *
     * <p><b>Anything else</b> -- an NPE, a {@link ClassCastException}, a parse defect against a
     * changed Yahoo payload -- is <em>rethrown</em>. Swallowing a bug into "no price" makes it
     * indistinguishable from an outage, which is exactly how a parser defect survives for days.
     * Same contract as {@code CoinGeckoPriceProvider.handleFetchFailure}, documented in
     * docs/features/price-service.md; the batch callers guard their own loops, so one bad ticker
     * cannot abort a run.
     *
     * <p>It unwraps first: {@code Mono.timeout()} signals a <em>checked</em>
     * {@link TimeoutException}, which {@code block()} wraps in a reactor {@code ReactiveException}.
     *
     * <p>A 429 additionally arms {@link #rateLimitedUntil}.
     */
    private void handleFetchFailure(String ticker, RuntimeException ex) {
        Throwable cause = reactor.core.Exceptions.unwrap(ex);
        if (cause instanceof WebClientResponseException http) {
            int status = http.getStatusCode().value();
            if (status == 429) {
                Duration cooldown = retryAfter(http);
                rateLimitedUntil = Instant.now().plus(cooldown);
                log.warn("Yahoo Finance rate-limited (429) fetching {} -- skipping the rest of the "
                    + "batch and pausing calls for {}s", ticker, cooldown.toSeconds());
            } else {
                // 404 is the ordinary "Yahoo does not carry this symbol"; 5xx is their outage.
                // Both leave the holding on its last known price, so neither is an alert.
                log.warn("Yahoo Finance answered HTTP {} for {} -- no price this cycle", status, ticker);
            }
        } else if (cause instanceof TimeoutException) {
            log.warn("Yahoo Finance request for {} timed out after {} -- no price this cycle",
                ticker, TIMEOUT);
        } else if (cause instanceof WebClientRequestException) {
            log.warn("Yahoo Finance request for {} could not reach the API ({}) -- no price this cycle",
                ticker, cause.getMessage());
        } else {
            throw ex;
        }
    }

    /**
     * True when a recent 429 is still in force, in which case the caller must return no prices
     * without touching the network.
     *
     * <p>DEBUG, not WARN: the 429 that armed the pause was already logged once at WARN, and this
     * runs on every read for as long as the pause lasts.
     */
    private boolean coolingDown(String ticker) {
        Instant until = rateLimitedUntil;
        if (Instant.now().isBefore(until)) {
            log.debug("Yahoo Finance still rate-limited until {} -- skipping the price request for {}",
                until, ticker);
            return true;
        }
        return false;
    }

    /**
     * The pause a 429 buys us: the server's {@code Retry-After} when it sends a sane one,
     * {@link #DEFAULT_COOLDOWN} otherwise. Only the delta-seconds form is read -- an HTTP-date
     * would need clock-skew handling for no practical gain.
     */
    private static Duration retryAfter(WebClientResponseException http) {
        String header = http.getHeaders().getFirst("Retry-After");
        if (header == null || header.isBlank()) return DEFAULT_COOLDOWN;
        try {
            long seconds = Long.parseLong(header.trim());
            if (seconds <= 0) return DEFAULT_COOLDOWN;
            return Duration.ofSeconds(Math.min(seconds, MAX_COOLDOWN.toSeconds()));
        } catch (NumberFormatException ex) {
            return DEFAULT_COOLDOWN;
        }
    }

    private BigDecimal fetchSinglePrice(String ticker) {
        Meta meta = fetchMeta(ticker);
        if (meta == null) return null;

        double price = meta.regularMarketPrice();
        if (price <= 0) return null;

        return applyFx(price, meta.currency());
    }

    /**
     * The {@code meta} block of the chart endpoint — quote, currency and instrument type in one
     * response. Null when Yahoo has no data for {@code ticker}. Propagates transport failures to
     * the caller, which decides between logging a price miss and reporting "no such symbol".
     */
    private Meta fetchMeta(String ticker) {
        return fetchMeta(ticker, TIMEOUT);
    }

    private Meta fetchMeta(String ticker, Duration timeout) {
        YahooResponse response = webClient.get()
            .uri("/v8/finance/chart/{ticker}?range=1d&interval=1d", ticker)
            .retrieve()
            .bodyToMono(YahooResponse.class)
            .timeout(timeout)
            .block();

        if (response == null || response.chart() == null || response.chart().result() == null
            || response.chart().result().isEmpty()) {
            return null;
        }
        return response.chart().result().get(0).meta();
    }

    /**
     * Whether Yahoo currently quotes {@code ticker} at all — a symbol check, not a price read.
     *
     * <p>Used by {@link OpenFigiIsinConverter} to verify that the symbol it derived from an ISIN
     * is one Yahoo actually carries, before that symbol is persisted on a holding and every later
     * valuation depends on it. FX is deliberately not applied: an unavailable EUR rate says
     * nothing about whether the symbol exists, and treating it as "no such symbol" would send a
     * perfectly good ticker to the search fallback.
     *
     * <p>False on any failure — a rate-limited or unreachable Yahoo must never be read as
     * "this symbol is dead", since the caller only ever <em>replaces</em> a symbol on a positive
     * quote from a different one.
     */
    @Override
    public boolean hasQuote(String ticker) {
        if (!supports(ticker)) return false;
        try {
            Meta meta = fetchMeta(ticker, VERIFY_TIMEOUT);
            return meta != null && meta.regularMarketPrice() > 0;
        } catch (Exception ex) {
            log.debug("Yahoo quote probe failed for {}: {}", ticker, ex.getMessage());
            return false;
        }
    }

    /**
     * The symbols Yahoo's own search returns for {@code query} — an ISIN, in practice — in Yahoo's
     * relevance order, restricted to entries it indexes itself ({@code isYahooFinance}) and to
     * symbols this provider can request.
     *
     * <p>This is the authority OpenFIGI cannot be: OpenFIGI knows every listing of an instrument,
     * Yahoo knows which of them <em>it</em> quotes. Searching an ISIN that Yahoo does not know
     * returns nothing rather than a fuzzy near-match ({@code enableFuzzyQuery=false}), so a miss
     * stays a miss.
     */
    @Override
    public List<SymbolMatch> searchSymbols(String query) {
        if (query == null || query.isBlank()) return List.of();
        try {
            SearchResponse response = webClient.get()
                .uri("/v1/finance/search?q={query}&quotesCount=6&newsCount=0&listsCount=0"
                    + "&enableFuzzyQuery=false", query)
                .retrieve()
                .bodyToMono(SearchResponse.class)
                .timeout(VERIFY_TIMEOUT)
                .block();

            if (response == null || response.quotes() == null) return List.of();

            return response.quotes().stream()
                .filter(q -> Boolean.TRUE.equals(q.isYahooFinance()))
                .filter(q -> supports(q.symbol()))
                .map(q -> new SymbolMatch(
                    q.symbol().toUpperCase(Locale.ROOT),
                    q.longname() != null ? q.longname() : q.shortname()))
                .toList();
        } catch (Exception ex) {
            log.debug("Yahoo symbol search failed for {}: {}", query, ex.getMessage());
            return List.of();
        }
    }

    /**
     * Returns Yahoo's classification of the instrument ("ETF", "EQUITY",
     * "CRYPTOCURRENCY", "MUTUALFUND"...) read from the same unauthenticated
     * chart endpoint already used for prices. Empty if unavailable.
     */
    public Optional<String> getInstrumentType(String ticker) {
        if (!supports(ticker)) return Optional.empty();
        try {
            Meta meta = fetchMeta(ticker);
            if (meta == null) return Optional.empty();
            return Optional.ofNullable(meta.instrumentType()).filter(s -> !s.isBlank());
        } catch (Exception ex) {
            log.debug("Yahoo instrumentType fetch failed for {}: {}", ticker, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Apply FX conversion to a native-currency price. Returns null if the FX
     * rate cannot be fetched (caller treats that the same way as a missing
     * price — skip the snapshot rather than store a wrong value).
     */
    private BigDecimal applyFx(double rawPrice, String currency) {
        BigDecimal rate = getFxRateToEur(currency);
        if (rate == null) {
            log.warn("Skipping price {} in {}: FX rate unavailable", rawPrice, currency);
            return null;
        }
        return BigDecimal.valueOf(rawPrice).multiply(rate);
    }

    /**
     * Resolve the FX rate from `currency` to EUR. Cached for 15 minutes.
     * Returns BigDecimal.ONE when the price is already in EUR (or currency
     * is unknown — preserves the pre-fix behavior for cassé payloads).
     * Returns null when a real fetch fails — caller must handle.
     */
    BigDecimal getFxRateToEur(String currency) {
        if (currency == null || currency.isBlank() || "EUR".equalsIgnoreCase(currency)) {
            return BigDecimal.ONE;
        }

        // London pence: 1 GBp = 0.01 GBP. Yahoo returns the exact string "GBp"
        // (case-sensitive) for LSE-listed stocks like LLOY.L. GBX is the
        // alternative ISO-4217 code used by some feeds.
        if ("GBp".equals(currency) || "GBX".equalsIgnoreCase(currency)) {
            BigDecimal gbpRate = getFxRateToEur("GBP");
            if (gbpRate == null) return null;
            return gbpRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        }

        String upper = currency.toUpperCase(Locale.ROOT);
        CachedFx cached = fxCache.get(upper);
        if (cached != null && cached.isFresh()) {
            return cached.rate();
        }

        BigDecimal rate = fetchFxRate(upper);
        if (rate != null) {
            fxCache.put(upper, new CachedFx(rate, Instant.now()));
        }
        return rate;
    }

    BigDecimal fetchFxRate(String currency) {
        try {
            YahooResponse response = webClient.get()
                .uri("/v8/finance/chart/{pair}?range=1d&interval=1d", currency + "EUR=X")
                .retrieve()
                .bodyToMono(YahooResponse.class)
                .timeout(TIMEOUT)
                .block();

            if (response == null || response.chart() == null || response.chart().result() == null
                || response.chart().result().isEmpty()) {
                return null;
            }
            var result = response.chart().result().get(0);
            if (result.meta() == null) return null;
            double rate = result.meta().regularMarketPrice();
            if (rate <= 0) return null;
            return BigDecimal.valueOf(rate);
        } catch (Exception ex) {
            log.debug("FX fetch failed for {}EUR=X: {}", currency, ex.getMessage());
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record YahooResponse(Chart chart) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Chart(List<ChartResult> result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChartResult(Meta meta, List<Long> timestamp, Indicators indicators) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Indicators(List<Quote> quote) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Quote(List<Double> close) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Meta(double regularMarketPrice, String currency, String instrumentType) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<SearchQuote> quotes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchQuote(String symbol, String shortname, String longname, Boolean isYahooFinance) {}

    private record CachedFx(BigDecimal rate, Instant cachedAt) {
        boolean isFresh() { return Instant.now().isBefore(cachedAt.plus(FX_CACHE_TTL)); }
    }

    /**
     * Fetch hourly prices for a stock/ETF ticker from Yahoo Finance over the last 24H.
     * Uses interval=1h for intraday granularity.
     */
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(String ticker, LocalDateTime from, LocalDateTime to) {
        if (!supports(ticker)) return Map.of();
        try {
            YahooResponse response = webClient.get()
                .uri("/v8/finance/chart/{ticker}?range=1d&interval=1h", ticker)
                .retrieve()
                .bodyToMono(YahooResponse.class)
                .timeout(Duration.ofSeconds(15))
                .block();

            if (response == null || response.chart() == null || response.chart().result() == null
                || response.chart().result().isEmpty()) {
                return Map.of();
            }

            var result = response.chart().result().get(0);
            if (result.timestamp() == null
                || result.indicators() == null
                || result.indicators().quote() == null
                || result.indicators().quote().isEmpty()
                || result.indicators().quote().get(0).close() == null) return Map.of();

            // Series use today's FX rate for all historical points; per-day FX
            // would multiply API calls 250× for marginal accuracy on a personal
            // finance app.
            BigDecimal fx = result.meta() != null
                ? getFxRateToEur(result.meta().currency())
                : BigDecimal.ONE;
            if (fx == null) {
                log.warn("Skipping intraday series for {}: FX rate unavailable for {}",
                        ticker, result.meta() != null ? result.meta().currency() : "null");
                return Map.of();
            }

            Map<LocalDateTime, BigDecimal> prices = new LinkedHashMap<>();
            List<Long> timestamps = result.timestamp();
            List<Double> closes = result.indicators().quote().get(0).close();

            for (int i = 0; i < timestamps.size() && i < closes.size(); i++) {
                Double close = closes.get(i);
                if (close == null) continue;
                // UTC, like CoinGecko's intraday series: HistoryService merges both onto one
                // LocalDateTime axis against a UTC grid, and keying these bars in Europe/Paris
                // put every stock point one or two hours ahead of that axis -- valuing the hour
                // at a stale close and dropping the freshest bars of the day as "after `to`".
                LocalDateTime dt = Instant.ofEpochSecond(timestamps.get(i))
                    .atZone(ZoneOffset.UTC).toLocalDateTime();
                if (!dt.isBefore(from) && !dt.isAfter(to) && close > 0) {
                    prices.put(dt, BigDecimal.valueOf(close).multiply(fx).setScale(8, RoundingMode.HALF_UP));
                }
            }

            log.debug("Fetched {} intraday prices for {} from Yahoo", prices.size(), ticker);
            return prices;
        } catch (Exception ex) {
            log.warn("Yahoo intraday price fetch failed for {}: {}", ticker, ex.getMessage());
            return Map.of();
        }
    }

    /**
     * Fetch historical daily prices for a single ticker from Yahoo Finance.
     * Returns a map of date -> priceEur.
     */
    public Map<LocalDate, BigDecimal> getHistoricalPricesEur(String ticker, LocalDate from, LocalDate to) {
        if (!supports(ticker)) return Map.of();

        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        String range = days <= 7 ? "5d" : days <= 30 ? "1mo" : days <= 90 ? "3mo" : days <= 365 ? "1y" : "5y";

        try {
            YahooResponse response = webClient.get()
                .uri("/v8/finance/chart/{ticker}?range={range}&interval=1d", ticker, range)
                .retrieve()
                .bodyToMono(YahooResponse.class)
                .timeout(Duration.ofSeconds(15))
                .block();

            if (response == null || response.chart() == null || response.chart().result() == null
                || response.chart().result().isEmpty()) {
                return Map.of();
            }

            var result = response.chart().result().get(0);
            if (result.timestamp() == null
                || result.indicators() == null
                || result.indicators().quote() == null
                || result.indicators().quote().isEmpty()
                || result.indicators().quote().get(0).close() == null) return Map.of();

            // Series use today's FX rate for all historical points; per-day FX
            // would multiply API calls 250× for marginal accuracy on a personal
            // finance app.
            BigDecimal fx = result.meta() != null
                ? getFxRateToEur(result.meta().currency())
                : BigDecimal.ONE;
            if (fx == null) {
                log.warn("Skipping historical series for {}: FX rate unavailable for {}",
                        ticker, result.meta() != null ? result.meta().currency() : "null");
                return Map.of();
            }

            Map<LocalDate, BigDecimal> prices = new HashMap<>();
            List<Long> timestamps = result.timestamp();
            List<Double> closes = result.indicators().quote().get(0).close();

            for (int i = 0; i < timestamps.size() && i < closes.size(); i++) {
                Double close = closes.get(i);
                if (close == null) continue;
                LocalDate date = Instant.ofEpochSecond(timestamps.get(i))
                    .atZone(ZoneOffset.UTC).toLocalDate();
                if (!date.isBefore(from) && !date.isAfter(to) && close > 0) {
                    prices.put(date, BigDecimal.valueOf(close).multiply(fx).setScale(8, RoundingMode.HALF_UP));
                }
            }

            log.debug("Fetched {} historical prices for {} from Yahoo", prices.size(), ticker);
            return prices;
        } catch (Exception ex) {
            log.warn("Yahoo historical price fetch failed for {}: {}", ticker, ex.getMessage());
            return Map.of();
        }
    }
}
