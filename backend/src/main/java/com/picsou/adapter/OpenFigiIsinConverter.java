package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.picsou.port.SymbolCatalogPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts ISIN codes to Yahoo Finance ticker symbols using the free OpenFIGI API.
 *
 * Trade Republic returns ISIN codes (e.g. IE00BYVQ9F29) but Yahoo Finance expects
 * ticker symbols (e.g. IWDA.AS, MC.PA). This adapter fills that gap.
 *
 * OpenFIGI answers with every listing of an instrument, not with the one Yahoo quotes,
 * so the pick is verified against Yahoo before it is returned — see {@link #priceable}.
 *
 * OpenFIGI API: https://www.openfigi.com/api
 * No authentication required. Rate limit: 25 requests/min without API key.
 */
@Component
public class OpenFigiIsinConverter {

    private static final Logger log = LoggerFactory.getLogger(OpenFigiIsinConverter.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Result of an ISIN conversion: Yahoo ticker + display name. */
    public record TickerResult(String ticker, String name) {}

    /** ISIN format: 2-letter country code + 9 alphanumerics + 1 check digit. */
    private static final java.util.regex.Pattern ISIN_PATTERN =
        java.util.regex.Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[A-Z0-9]");

    private static final java.util.regex.Pattern SYMBOL_PATTERN =
        java.util.regex.Pattern.compile("[A-Z0-9][A-Z0-9.-]{0,14}");

    /** How many of Yahoo's own matches for an ISIN are probed before giving up — see {@link #priceable}. */
    private static final int MAX_VERIFIED_CANDIDATES = 3;

    /**
     * Wall-clock ceiling on verifying one ISIN. {@code resolve()} runs on the write path, inside
     * the transaction of a user saving a transaction or importing a CSV — a bound on the number of
     * probes is not a bound on the time they take. Verification is an improvement, never a
     * requirement: when the budget runs out the OpenFIGI pick is returned as it would have been
     * without any of this.
     */
    private static final Duration VERIFY_BUDGET = Duration.ofSeconds(10);

    /**
     * How long an <em>authoritative</em> miss is trusted — both sources answered and neither
     * knows a Yahoo-quotable symbol for the ISIN. Long, because re-asking rarely changes the
     * answer; bounded all the same, so a newly-listed instrument resolves the same day instead
     * of waiting for a redeploy.
     */
    private static final Duration UNRESOLVED_TTL = Duration.ofHours(6);

    /**
     * How long a miss caused by a source that never answered is trusted. Short: the answer is
     * expected to change as soon as the quota window rolls over, and re-asking is the point.
     * Not zero — a sustained outage must not turn every {@code resolve()} back into a request.
     */
    private static final Duration UNAVAILABLE_TTL = Duration.ofMinutes(5);

    /**
     * Whether {@code s} looks like an ISIN (2-letter country code + 9 alphanumerics
     * + 1 check digit = 12 chars). Case-insensitive; trims surrounding whitespace.
     * Mirrors the detection in {@code YahooFinancePriceProvider.supports()}.
     */
    public static boolean isIsin(String s) {
        if (s == null) {
            return false;
        }
        String upper = s.trim().toUpperCase(Locale.ROOT);
        return upper.length() == 12 && ISIN_PATTERN.matcher(upper).matches();
    }

    /** OpenFIGI exchCode → Yahoo Finance exchange suffix. Empty string = no suffix (US markets). */
    private static final Map<String, String> EXCHANGE_SUFFIX = Map.ofEntries(
        // US — no suffix
        Map.entry("US", ""),  Map.entry("UN", ""), Map.entry("UA", ""),
        Map.entry("UC", ""),  Map.entry("UD", ""), Map.entry("UW", ""),
        Map.entry("NQ", ""),  Map.entry("NY", ""),
        Map.entry("OQ", ""),  Map.entry("PK", ""), Map.entry("PQ", ""),
        // Germany — .DE
        Map.entry("GR", ".DE"), Map.entry("GF", ".DE"), Map.entry("GD", ".DE"),
        Map.entry("GY", ".DE"), Map.entry("GS", ".DE"), Map.entry("GM", ".DE"),
        Map.entry("GT", ".DE"), Map.entry("GI", ".DE"), Map.entry("GH", ".DE"),
        Map.entry("GZ", ".DE"), Map.entry("TH", ".DE"), Map.entry("QT", ".DE"),
        // France — .PA
        Map.entry("FP", ".PA"), Map.entry("PA", ".PA"),
        // Netherlands — .AS
        Map.entry("NA", ".AS"),
        // UK — .L
        Map.entry("LN", ".L"),
        // Italy — .MI
        Map.entry("IM", ".MI"),
        // Belgium — .BR
        Map.entry("BR", ".BR"),
        // Switzerland — .SW
        Map.entry("SW", ".SW"), Map.entry("SZ", ".SW"),
        // Spain — .MC
        Map.entry("SM", ".MC"),
        // Canada — .TO
        Map.entry("TO", ".TO"), Map.entry("TV", ".TO"),
        // Japan — .T
        Map.entry("JT", ".T"),
        // Hong Kong — .HK
        Map.entry("HK", ".HK"),
        // Australia — .AX
        Map.entry("AU", ".AX"),
        // Singapore — .SI
        Map.entry("SG", ".SI"),
        // India — .NS
        Map.entry("IN", ".NS"),
        // Korea — .KS
        Map.entry("KS", ".KS")
    );

    /** Preferred EU exchanges for fallback. */
    private static final List<String> EU_PREFERRED = List.of(
        "NA", "FP", "GY", "GR", "GF", "LN", "IM", "BR"
    );

    /**
     * Trade Republic internal ISINs for on-platform crypto products (e.g. Bitcoin
     * held directly, not via an ETC) follow "XF000&lt;SYMBOL&gt;&lt;digits&gt;" — these
     * are not real market ISINs, so OpenFIGI never resolves them and {@code name}
     * stays null / {@code ticker} stays the fake ISIN downstream. See GH issue #22.
     * The symbol is parsed generically (not hardcoded per coin) and validated
     * against {@link CoinGeckoPriceProvider}'s known tickers so the holding's
     * ticker becomes price-resolvable, not just its display name. The display name
     * is derived from the same provider registry (no second per-coin map here).
     */
    private static final String TR_CRYPTO_ISIN_PREFIX = "XF000";

    private static final java.util.regex.Pattern TR_CRYPTO_ISIN_PATTERN =
        java.util.regex.Pattern.compile("^" + TR_CRYPTO_ISIN_PREFIX + "([A-Z]+)[0-9]+$");

    /**
     * Whether {@code isin} is a Trade Republic internal crypto identifier — prefix
     * {@code XF000}, e.g. {@code XF000BTC0017}. TR prices these on its own venue (TRD0),
     * not the {@code LSX} default used for equities/ETFs, so {@link TradeRepublicAdapter}
     * calls this to pick the ticker-subscription exchange. Sharing this one predicate (and
     * the prefix constant it and {@link #TR_CRYPTO_ISIN_PATTERN} both use) keeps the two
     * TR-crypto detection sites from drifting. Case/whitespace-insensitive.
     */
    public static boolean isTrCryptoIsin(String isin) {
        return isin != null && isin.trim().toUpperCase(Locale.ROOT).startsWith(TR_CRYPTO_ISIN_PREFIX);
    }

    /** ISIN country prefix → preferred exchange code for that market. */
    private static final Map<String, String> HOME_EXCHANGE = Map.ofEntries(
        Map.entry("US", "US"),  Map.entry("HK", "HK"),  Map.entry("JP", "JT"),
        Map.entry("AU", "AU"),  Map.entry("SG", "SG"),  Map.entry("IN", "IN"),
        Map.entry("KR", "KS"),  Map.entry("GB", "LN"),  Map.entry("DE", "GY"),
        Map.entry("FR", "FP"),  Map.entry("NL", "NA"),  Map.entry("IT", "IM"),
        Map.entry("CH", "SW"),  Map.entry("ES", "SM"),  Map.entry("CA", "TO")
    );

    private final WebClient webClient;
    private final CoinGeckoPriceProvider coinGecko;
    /**
     * The catalog the resolved symbol is verified against — {@link YahooFinancePriceProvider} in
     * production, behind {@link SymbolCatalogPort} because the only thing this class needs from it
     * is "do you carry this symbol", not a price source.
     */
    private final SymbolCatalogPort symbolCatalog;
    /**
     * Cache: ISIN → the resolution and when it stops being trusted.
     *
     * <p>A <em>resolved</em> ticker never expires — an instrument does not stop being listed
     * where it is listed. A <em>fallback</em> (the ISIN used as its own ticker) does, because it
     * is only ever "nobody could tell us better right now": {@link YahooFinancePriceProvider}
     * refuses an ISIN as a symbol, so a fallback the caller persists leaves the holding
     * unpriceable and out of its account's value (ADR 2026-08-01). Caching that for the process
     * lifetime meant a single bulk sync past OpenFIGI's 25 req/min keyless quota pinned every
     * ISIN past the limit to "unresolvable" until the JVM restarted.
     */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Only the cache TTLs read it; the verification budget stays on wall-clock time. */
    private final Clock clock;

    /** A cached resolution; {@code expiresAt == null} means "never re-resolve". */
    private record CacheEntry(TickerResult result, Instant expiresAt) {
        boolean isExpired(Clock clock) {
            return expiresAt != null && clock.instant().isAfter(expiresAt);
        }
    }

    /**
     * Thrown when OpenFIGI could not be <em>asked</em> — a 429 against the keyless quota, a
     * timeout, an unreachable API. Distinct from "OpenFIGI answered and knows nothing", which is
     * a {@code null} return: the first must not be remembered as the second.
     */
    private static final class OpenFigiUnavailableException extends RuntimeException {
        OpenFigiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public OpenFigiIsinConverter(CoinGeckoPriceProvider coinGecko, SymbolCatalogPort symbolCatalog) {
        this(coinGecko, symbolCatalog, WebClient.builder()
            .baseUrl("https://api.openfigi.com")
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("Accept", "application/json")
            .build(), Clock.systemUTC());
    }

    /**
     * Package-private for tests — inject a WebClient backed by an ExchangeFunction, and a Clock
     * so the cache TTLs above can be exercised without waiting hours.
     */
    OpenFigiIsinConverter(CoinGeckoPriceProvider coinGecko, SymbolCatalogPort symbolCatalog,
                          WebClient webClient, Clock clock) {
        this.coinGecko = coinGecko;
        this.symbolCatalog = symbolCatalog;
        this.webClient = webClient;
        this.clock = clock;
    }

    /**
     * Converts an ISIN to a Yahoo Finance ticker + display name.
     *
     * Returns a TickerResult with the original ISIN as ticker and null name
     * if conversion fails — callers should handle gracefully.
     *
     * @param isin The ISIN code (e.g. "IE00BYVQ9F29")
     * @return result with Yahoo ticker (e.g. "IWDA.AS") and name, or ISIN as fallback
     */
    public TickerResult resolve(String isin) {
        if (isin == null || isin.isBlank()) {
            return new TickerResult(isin, null);
        }

        // Normalized once and used throughout (matching, cache key, fallback ticker) so that
        // e.g. " xf000btc0017 " and "XF000BTC0017" resolve to and cache under the same entry.
        String normalized = isin.trim().toUpperCase(Locale.ROOT);

        // Cache first — covers OpenFIGI results/fallbacks and the TR-crypto short-circuit
        // below, so an unrecognized crypto symbol is warned about once (before its OpenFIGI
        // miss is cached), not on every resolve() of the same holding.
        CacheEntry cached = cache.get(normalized);
        if (cached != null && !cached.isExpired(clock)) {
            log.debug("ISIN {} resolved from cache -> {} ({})",
                normalized, cached.result().ticker, cached.result().name);
            return cached.result();
        }

        // TR-native crypto short-circuit (see TR_CRYPTO_ISIN_PATTERN): parse the symbol and,
        // if the price provider knows it, resolve straight to that ticker + display name.
        java.util.regex.Matcher trCrypto = TR_CRYPTO_ISIN_PATTERN.matcher(normalized);
        if (trCrypto.matches()) {
            String symbol = trCrypto.group(1);
            if (coinGecko.supports(symbol)) {
                TickerResult result = new TickerResult(symbol, coinGecko.displayName(symbol));
                cache.put(normalized, new CacheEntry(result, null));
                return result;
            }
            log.warn("TR-native crypto ISIN {} has unrecognized symbol '{}', falling back to OpenFIGI (will likely miss)",
                     normalized, symbol);
        }

        TickerResult figi;
        // Whether OpenFIGI actually answered. "We could not ask" and "there is nothing to find"
        // produce the same null here, but they must not be remembered for the same length of time.
        boolean openFigiAnswered = true;
        try {
            figi = fetchFromOpenFigi(normalized);
        } catch (OpenFigiUnavailableException ex) {
            figi = null;
            openFigiAnswered = false;
            log.warn("Could not reach OpenFIGI for ISIN {}: {}", normalized, ex.getMessage());
        } catch (Exception ex) {
            figi = null;
            log.warn("Failed to convert ISIN {} via OpenFIGI: {}", normalized, ex.getMessage());
        }

        TickerResult result = priceable(normalized, figi);
        Instant expiresAt = null;
        if (result == null) {
            // Cache the fallback too so we don't retry every call — but only for as long as the
            // miss is worth trusting, which depends on whether anyone actually answered.
            result = new TickerResult(normalized, null);
            expiresAt = clock.instant().plus(openFigiAnswered ? UNRESOLVED_TTL : UNAVAILABLE_TTL);
            log.warn("No Yahoo-quotable ticker for ISIN {} (OpenFIGI: {}), will use ISIN as-is and re-resolve after {}",
                     normalized, figi == null ? "no result" : figi.ticker(), expiresAt);
        } else if (figi != null && result.ticker.equals(figi.ticker)) {
            log.info("ISIN {} resolved via OpenFIGI -> {} ({})", normalized, result.ticker, result.name);
        }
        cache.put(normalized, new CacheEntry(result, expiresAt));
        return result;
    }

    /**
     * The ticker to actually persist for {@code isin}: OpenFIGI's pick when Yahoo quotes it, else
     * the first symbol Yahoo's own search returns for the ISIN that Yahoo quotes, else the
     * OpenFIGI pick unverified (null when there was none).
     *
     * <p>OpenFIGI knows every listing of an instrument but not which one Yahoo carries, and there
     * is no exchange-code heuristic that predicts it — {@link #pickBest} documents an attempt that
     * fixed one holding and broke two others. So instead of guessing better, verify: an Irish
     * UCITS ETF whose US OTC listing is delisted ({@code IE000BI8OT95} → {@code MWRDF}, no Yahoo
     * data) now resolves to the Paris listing Yahoo does quote ({@code MWRD.PA}), while the two
     * holdings that the reordering broke keep the US OTC tickers that work for them. Without this,
     * every position of an all-ETF account can end up unquotable, and since an unpriced holding is
     * excluded from its account's value, the account itself reads 0 € (GH issues #74, #78).
     *
     * <p>The OpenFIGI pick is only ever replaced on a <em>positive</em> quote for a different
     * symbol, never on a failure to quote the pick: a rate-limited or unreachable Yahoo leaves the
     * result exactly as it was before this validation existed.
     *
     * <p>Cost, per ISIN and once per process (the caller caches): <b>1</b> Yahoo request when the
     * pick quotes, which is the common case; <b>at most 5</b> otherwise — the probe, the search,
     * and up to {@link #MAX_VERIFIED_CANDIDATES} candidate probes. Yahoo returns its matches in
     * relevance order, so a listing that is not in the first few is not the one being looked for,
     * and probing the whole list would turn a miss into eight requests.
     */
    TickerResult priceable(String isin, TickerResult figi) {
        Instant deadline = Instant.now().plus(VERIFY_BUDGET);
        if (figi != null && symbolCatalog.hasQuote(figi.ticker)) {
            return figi;
        }
        int probed = 0;
        for (SymbolCatalogPort.SymbolMatch match : symbolCatalog.searchSymbols(isin)) {
            if (figi != null && match.symbol().equals(figi.ticker)) {
                continue; // already probed above, and it did not quote
            }
            if (probed == MAX_VERIFIED_CANDIDATES || Instant.now().isAfter(deadline)) {
                log.debug("ISIN {}: giving up on the remaining Yahoo matches ({})", isin,
                          probed == MAX_VERIFIED_CANDIDATES ? "candidate limit" : "verification budget spent");
                break;
            }
            probed++;
            if (symbolCatalog.hasQuote(match.symbol())) {
                // OpenFIGI's name is the instrument's official one; Yahoo's is a display label
                // truncated to ~32 chars ("ISHARES III PLC ISHRS CORE MSCI"), so it is only a
                // fallback for when OpenFIGI resolved nothing at all.
                String name = figi != null && figi.name != null ? figi.name : match.name();
                log.info("ISIN {} -> {} via Yahoo search ({})", isin, match.symbol(),
                         figi == null ? "OpenFIGI returned nothing" : figi.ticker + " has no Yahoo quote");
                return new TickerResult(match.symbol(), name);
            }
        }
        return figi;
    }

    /**
     * OpenFIGI's pick for {@code isin}, or {@code null} when OpenFIGI answered and has nothing.
     *
     * @throws OpenFigiUnavailableException when the call itself failed — a 429 against the
     *         keyless 25 req/min quota, a timeout, an unreachable API — so {@link #resolve} can
     *         tell that apart from an authoritative miss instead of caching one as the other.
     */
    private TickerResult fetchFromOpenFigi(String isin) {
        List<MappingJob> request = List.of(new MappingJob("ID_ISIN", isin));

        List<Map<String, Object>> responses;
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = webClient.post()
                .uri("/v3/mapping")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(List.class)
                .timeout(TIMEOUT)
                .block();
            responses = body;
        } catch (RuntimeException ex) {
            log.warn("OpenFIGI API request failed for ISIN {}: {}", isin, ex.getMessage());
            throw new OpenFigiUnavailableException(ex.getMessage(), ex);
        }

        try {
            if (responses == null || responses.isEmpty()) {
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> first = (Map<String, Object>) responses.get(0);

            if (first.containsKey("error")) {
                // A per-job error, not a per-ISIN verdict: OpenFIGI reports "no such instrument"
                // as a `warning` with empty data. So this is "could not ask", not "nothing found".
                log.warn("OpenFIGI error for ISIN {}: {}", isin, first.get("error"));
                throw new OpenFigiUnavailableException(String.valueOf(first.get("error")), null);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) first.get("data");

            if (data == null || data.isEmpty()) {
                return null;
            }

            return pickBest(isin, data);
        } catch (OpenFigiUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // A shape nothing above anticipated (a ClassCastException on a changed payload):
            // treat it as "OpenFIGI told us nothing" rather than failing the caller's write.
            log.warn("OpenFIGI answered an unreadable payload for ISIN {}: {}", isin, ex.getMessage());
            return null;
        }
    }

    /**
     * Picks the best Yahoo Finance ticker + name from OpenFIGI results.
     * Strategy:
     * 1. Home exchange (based on ISIN country) — best Yahoo coverage
     * 2. US OTC/ADR — for non-US ISINs, US listings often have best Yahoo coverage
     * 3. EU exchanges — EUR pricing
     * 4. Any known exchange
     *
     * Tried reversing 2 and 3 (2026-08-05) on the theory that Irish/Luxembourg-
     * domiciled UCITS ETFs — which have no {@code HOME_EXCHANGE} entry — would
     * be better served by their real European listing than a thin US OTC ticker.
     * That was true for one holding (IE000BI8OT95 "MWRD" — OTC "MWRDF" has no
     * live Yahoo quote, EU "WRDU.AS" does) but **broke two others on the same
     * live portfolio**: IE00BGSF1X88 and IE00BD6FTQ80 resolve to Yahoo-unlisted
     * EU tickers (`IB01.AS`, `SC0L.DE` — both confirmed "No data found, symbol
     * may be delisted"), while their original US OTC tickers (`ISHUF`,
     * `IBBCF`) are confirmed live and priced. Reverted: there is no exchange-
     * code heuristic that reliably predicts which specific ticker variant
     * actually has a live Yahoo quote for a given Irish/Luxembourg ISIN — it
     * varies per instrument, and swapping the global order traded one broken
     * holding for two different ones.
     *
     * <p>Which is why the order below is no longer the last word: {@link #priceable}
     * verifies the pick against Yahoo and falls back to Yahoo's own search for the
     * ISIN when it has no quote. This method stays a pure, offline heuristic — it
     * still decides which listing is <em>preferred</em> among those that work.
     */
    TickerResult pickBest(String isin, List<Map<String, Object>> entries) {
        // Build a map: exchCode → (yahooTicker, name)
        Map<String, String[]> byExchange = new java.util.LinkedHashMap<>();
        for (Map<String, Object> entry : entries) {
            String ticker = normalizeSymbol((String) entry.get("ticker"));
            String exchCode = (String) entry.get("exchCode");
            String name = (String) entry.get("name");
            if (ticker == null || exchCode == null) continue;

            String suffix = EXCHANGE_SUFFIX.get(exchCode);
            if (suffix != null && !byExchange.containsKey(exchCode)) {
                byExchange.put(exchCode, new String[]{ ticker + suffix, name });
            }
        }

        String country = isin.length() >= 2 ? isin.substring(0, 2).toUpperCase() : "";

        // 1. Try home exchange
        String home = HOME_EXCHANGE.get(country);
        if (home != null && byExchange.containsKey(home)) {
            String[] r = byExchange.get(home);
            return new TickerResult(r[0], r[1]);
        }

        // 2. For non-US ISINs, try US exchanges (OTC/ADR)
        if (!country.equals("US")) {
            for (String us : List.of("US", "NY", "NQ", "OQ", "PQ")) {
                if (byExchange.containsKey(us)) {
                    String[] r = byExchange.get(us);
                    return new TickerResult(r[0], r[1]);
                }
            }
        }

        // 3. EU exchanges
        for (String eu : EU_PREFERRED) {
            if (byExchange.containsKey(eu)) {
                String[] r = byExchange.get(eu);
                return new TickerResult(r[0], r[1]);
            }
        }

        // 4. Any known exchange
        if (!byExchange.isEmpty()) {
            String[] r = byExchange.values().iterator().next();
            return new TickerResult(r[0], r[1]);
        }

        // 5. Raw ticker from first entry
        for (Map<String, Object> entry : entries) {
            String ticker = normalizeSymbol((String) entry.get("ticker"));
            String name = (String) entry.get("name");
            if (ticker != null) {
                return new TickerResult(ticker, name);
            }
        }

        return null;
    }

    private static String normalizeSymbol(String ticker) {
        if (ticker == null) {
            return null;
        }
        String normalized = ticker.trim().toUpperCase(Locale.ROOT);
        return SYMBOL_PATTERN.matcher(normalized).matches() ? normalized : null;
    }

    record MappingJob(
        @JsonProperty("idType") String idType,
        @JsonProperty("idValue") String idValue
    ) {}
}
