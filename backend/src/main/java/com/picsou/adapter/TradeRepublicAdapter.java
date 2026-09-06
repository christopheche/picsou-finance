package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.model.AccountType;
import com.picsou.port.TradeRepublicPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapter for Trade Republic's unofficial API.
 *
 * Auth (HTTP) is delegated to the tr-auth Python sidecar, which handles
 * the AWS WAF browser challenge that cannot be solved from plain Java HTTP.
 *
 * Data fetching uses the TR WebSocket API directly (no WAF needed).
 * Protocol version: 31. Session token is passed in each subscription payload.
 *
 * WebSocket subscriptions used:
 *   - availableCash      → cash balance
 *   - compactPortfolioByType → list of positions (isin, netSize, averageBuyIn), grouped by category
 *   - ticker             → current price per instrument (subscribed dynamically)
 *
 * Portfolio value = sum(ticker.last.price × position.netSize) for each position.
 */
@Component
public class TradeRepublicAdapter implements TradeRepublicPort {

    private static final Logger log = LoggerFactory.getLogger(TradeRepublicAdapter.class);

    private static final String WS_URL     = "wss://api.traderepublic.com/";
    private static final int    WS_VERSION = 31;
    private static final Duration DEFAULT_REFRESH_TIMEOUT = Duration.ofSeconds(15);
    /** Idle ceiling between two WebSocket frames before the stream is declared complete. */
    private static final Duration DEFAULT_WS_TIMEOUT = Duration.ofSeconds(30);
    /** Slack added to the idle timeout for the whole session (connect + all subscriptions). */
    private static final Duration WS_SESSION_SLACK = Duration.ofSeconds(15);

    /**
     * How much of a frame goes into an error log. A portfolio frame is the user's complete
     * position list; the ticker branch and {@code IbkrFlexClient} apply the same cap.
     */
    private static final int LOG_PAYLOAD_CHARS = 300;

    /** The frame type TR uses for a subscription it rejected ({@code <id> E {"errors":[...]}}). */
    private static final String WS_TYPE_ERROR = "E";

    record SecAccount(
        String wrapper,
        String accountNumber,
        String cashAccountNumber,
        String externalId,
        String name,
        AccountType type
    ) {}

    private final WebClient    sidecarClient;
    private final ObjectMapper objectMapper;
    private final Duration     refreshTimeout;
    private final String       wsUrl;
    private final Duration     wsTimeout;

    @Autowired
    public TradeRepublicAdapter(
        ObjectMapper objectMapper,
        @Value("${app.tr-auth.url:http://tr-auth:8001}") String trAuthUrl
    ) {
        this(objectMapper, trAuthUrl, DEFAULT_REFRESH_TIMEOUT);
    }

    TradeRepublicAdapter(ObjectMapper objectMapper, String trAuthUrl, Duration refreshTimeout) {
        this(objectMapper, trAuthUrl, refreshTimeout, WS_URL, DEFAULT_WS_TIMEOUT);
    }

    /** Test seam: points the WebSocket at a local server and shortens its idle timeout. */
    TradeRepublicAdapter(ObjectMapper objectMapper, String trAuthUrl, Duration refreshTimeout,
                         String wsUrl, Duration wsTimeout) {
        this.objectMapper   = objectMapper;
        this.sidecarClient  = WebClient.builder()
            .baseUrl(trAuthUrl)
            .build();
        this.refreshTimeout = refreshTimeout;
        this.wsUrl          = wsUrl;
        this.wsTimeout      = wsTimeout;
    }

    // ─── Auth (delegated to Python sidecar) ───────────────────────────────────

    @Override
    public String initiateAuth(String phoneNumber, String pin) {
        log.info("Delegating TR auth initiation to tr-auth sidecar");

        JsonNode response = sidecarClient.post()
            .uri("/initiate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("phoneNumber", phoneNumber, "pin", pin))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                String body = ex.getResponseBodyAsString();
                log.error("tr-auth sidecar /initiate failed ({}) : {}", ex.getStatusCode(), body);
                return Mono.error(mapAuthError(body,
                    "Trade Republic authentication failed. Please check your credentials and try again."));
            })
            .timeout(Duration.ofSeconds(60)) // headless browser takes time
            .onErrorMap(ex -> !(ex instanceof SyncException), ex -> new SyncException(
                "Trade Republic authentication service is unavailable. Please make sure tr-auth is running on port 8001.",
                ex))
            .blockOptional()
            .orElseThrow(() -> new SyncException("No response from the Trade Republic service. Please try again later."));

        String processId = response.path("processId").asText(null);
        if (processId == null || processId.isBlank()) {
            throw new SyncException("Trade Republic did not return a valid session. Please try again.");
        }
        return processId;
    }

    @Override
    public TrTokens completeAuth(String processId, String tan) {
        log.info("Delegating TR 2FA completion to tr-auth sidecar, processId={}", processId);

        JsonNode response = sidecarClient.post()
            .uri("/complete")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("processId", processId, "tan", tan))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                String body = ex.getResponseBodyAsString();
                log.error("tr-auth sidecar /complete failed ({}) : {}", ex.getStatusCode(), body);
                return Mono.error(mapAuthError(body,
                    "The verification code is invalid or has expired. Please request a new one."));
            })
            .timeout(Duration.ofSeconds(60))
            .onErrorMap(ex -> !(ex instanceof SyncException), ex -> new SyncException(
                "Trade Republic authentication service is unavailable. Please make sure tr-auth is running on port 8001.",
                ex))
            .blockOptional()
            .orElseThrow(() -> new SyncException("No response from the Trade Republic service. Please try again later."));

        String sessionToken = response.path("sessionToken").asText(null);
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new SyncException("Trade Republic verification did not complete. Please try again.");
        }
        String refreshToken = response.path("refreshToken").asText(null);
        return new TrTokens(sessionToken, refreshToken);
    }

    @Override
    public TrTokens refreshSession(String refreshToken) {
        log.info("Refreshing TR session via tr-auth sidecar");

        JsonNode response = sidecarClient.post()
            .uri("/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("refreshToken", refreshToken))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                log.error("tr-auth sidecar /refresh failed ({}) : {}", ex.getStatusCode(), ex.getResponseBodyAsString());
                // The sidecar relays TR's status verbatim: only 401/403 mean the
                // refresh token was actually rejected. Anything else (TR 429
                // rate-limit, sidecar 5xx) is transient and must not destroy the
                // stored session.
                int status = ex.getStatusCode().value();
                if (status == 401 || status == 403) {
                    return Mono.error(new SyncException("SESSION_EXPIRED"));
                }
                return Mono.error(new SyncException(
                    "Trade Republic authentication service is unavailable. Please make sure tr-auth is running on port 8001.",
                    ex));
            })
            .timeout(refreshTimeout)
            .onErrorMap(ex -> !(ex instanceof SyncException), ex -> new SyncException(
                "Trade Republic authentication service is unavailable. Please make sure tr-auth is running on port 8001.",
                ex))
            .blockOptional()
            // An empty 2xx body is a sidecar/proxy defect, not a TR rejection —
            // it must not carry the SESSION_EXPIRED sentinel that destroys the
            // stored session. Only a 4xx above means TR refused the token.
            .orElseThrow(() -> new SyncException(
                "Trade Republic authentication service returned an empty response. Please try again later."));

        String newSession = response.path("sessionToken").asText(null);
        if (newSession == null || newSession.isBlank()) {
            throw new SyncException(
                "Trade Republic authentication service returned an empty response. Please try again later.");
        }
        String newRefresh = response.path("refreshToken").asText(null);
        log.info("TR session refreshed successfully");
        return new TrTokens(newSession, newRefresh != null ? newRefresh : refreshToken);
    }

    // ─── Data (WebSocket, no WAF needed) ──────────────────────────────────────

    @Override
    public List<TrAccountData> fetchAccounts(String sessionToken) {
        log.info("Fetching TR portfolio via WebSocket (protocol v{})", WS_VERSION);

        List<SecAccount> secAccounts = extractSecAccounts(sessionToken);
        log.info("TR JWT sec accounts: {}",
            secAccounts.stream()
                .map(acc -> acc.wrapper() + ":" + acc.name())
                .toList());

        AtomicReference<String> cashJson = new AtomicReference<>();
        // The default cash subscription answers once; a later delta frame under the same wsId
        // must not overwrite a good payload (same first-frame-wins rule as answeredTickerSubs).
        AtomicBoolean cashAnswered = new AtomicBoolean(false);
        // Set when TR rejected the cash subscription: the stream may complete, but no TR Cash
        // account is emitted — see parseCashJson.
        AtomicBoolean cashRejected = new AtomicBoolean(false);
        ConcurrentHashMap<String, String> scopedCashJsonByAccount = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, ConcurrentHashMap<String, JsonNode>> positionsByAccount = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, BigDecimal> tickerPrices = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, String> tickerSubToIsin = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, SecAccount> portfolioSubIds = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, SecAccount> scopedCashSubIds = new ConcurrentHashMap<>();
        Set<String> receivedPortfolioIds = ConcurrentHashMap.newKeySet();
        Set<Integer> receivedScopedCashSubs = ConcurrentHashMap.newKeySet();
        AtomicBoolean authExpired = new AtomicBoolean(false);
        AtomicInteger subIdCounter = new AtomicInteger(0);
        AtomicInteger expectedTickers = new AtomicInteger(-1);
        // Distinct ticker subscriptions that have answered (by wsId), not a raw message
        // count: a successful TR ticker sub streams an initial state plus continuous delta
        // updates under the same wsId, so counting messages let a fast-ticking position
        // complete the stream before slower positions had answered, dropping their prices
        // to the averageBuyIn fallback. See GH issue #23 / PR #25 review.
        Set<Integer> answeredTickerSubs = ConcurrentHashMap.newKeySet();
        AtomicInteger receivedPortfolios = new AtomicInteger(0);
        int totalPortfolioSubs = secAccounts.size();
        int totalScopedCashSubs = (int) secAccounts.stream()
            .filter(TradeRepublicAdapter::hasScopedCash)
            .count();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "https://app.traderepublic.com");
        String connectMsg = buildConnectMessage();

        new ReactorNettyWebSocketClient()
            .execute(URI.create(wsUrl), headers, session ->
                session.send(Mono.just(session.textMessage(connectMsg)))
                    .thenMany(
                        session.receive()
                            .map(msg -> msg.getPayloadAsText())
                            .concatMap(text -> {
                                log.info("TR WS <-- {}", text.length() > 500
                                        ? text.substring(0, 500) + "…" : text);

                                if ("connected".equals(text.trim())) {
                                    int id1 = subIdCounter.incrementAndGet();
                                    List<String> msgs = new ArrayList<>();
                                    msgs.add(sub(id1, "availableCash", sessionToken));
                                    log.info("TR WS --> sub {} availableCash", id1);

                                    for (SecAccount account : secAccounts) {
                                        if (hasScopedCash(account)) {
                                            int id = subIdCounter.incrementAndGet();
                                            scopedCashSubIds.put(id, account);
                                            msgs.add(subAvailableCash(id, account.cashAccountNumber(), sessionToken));
                                            log.info("TR WS --> sub {} availableCash account={}", id, account.name());
                                        }
                                    }

                                    if (secAccounts.isEmpty()) {
                                        log.info("TR WS: no securities account in JWT, skipping portfolio subscription");
                                        expectedTickers.set(0);
                                    } else {
                                        for (SecAccount account : secAccounts) {
                                            int id = subIdCounter.incrementAndGet();
                                            portfolioSubIds.put(id, account);
                                            msgs.add(subCompactPortfolio(id, account.accountNumber(), sessionToken));
                                            log.info("TR WS --> sub {} compactPortfolioByType account={}",
                                                id, account.name());
                                        }
                                    }

                                    return session.send(
                                        Flux.fromIterable(msgs).map(session::textMessage)
                                    ).thenReturn(text);
                                }

                                int wsId = extractWsId(text);
                                String payload = extractWsPayload(text);
                                // The frame type token TR puts between the id and the payload
                                // ("A" answer, "C"/"D" delta, "E" error). Without reading it, a
                                // rejected subscription looks exactly like an answer whose JSON we
                                // failed to understand — and got counted as one.
                                boolean rejected = WS_TYPE_ERROR.equals(extractWsType(text));

                                if (isAuthError(payload)) {
                                    log.warn("TR WS: session expired (AUTHENTICATION_ERROR)");
                                    authExpired.set(true);
                                    return Mono.just(text);
                                }

                                if (wsId == 1) {
                                    if (cashAnswered.compareAndSet(false, true)) {
                                        if (rejected) {
                                            log.warn("TR WS: availableCash subscription rejected: {}",
                                                truncate(payload));
                                            cashRejected.set(true);
                                        } else {
                                            cashJson.set(payload);
                                        }
                                    }

                                } else if (scopedCashSubIds.containsKey(wsId)) {
                                    SecAccount account = scopedCashSubIds.get(wsId);
                                    if (receivedScopedCashSubs.add(wsId)) {
                                        if (rejected) {
                                            log.warn("TR WS: availableCash rejected for account {}: {}",
                                                account.name(), truncate(payload));
                                        } else {
                                            scopedCashJsonByAccount.put(account.externalId(), payload);
                                        }
                                    }

                                } else if (portfolioSubIds.containsKey(wsId)) {
                                    SecAccount account = portfolioSubIds.get(wsId);
                                    if (rejected) {
                                        // Counted as answered so the stream can complete, but NOT
                                        // recorded as a received portfolio: the account is then
                                        // left out entirely rather than written at 0.
                                        log.warn("TR WS: compactPortfolioByType rejected for account {}: {}",
                                            account.name(), truncate(payload));
                                        receivedPortfolios.incrementAndGet();
                                        expectedTickers.compareAndSet(-1, 0);
                                        return Mono.just(text);
                                    }
                                    receivedPortfolios.incrementAndGet();
                                    receivedPortfolioIds.add(account.externalId());
                                    log.info("TR compactPortfolioByType [{}] raw: {}", account.name(),
                                             payload.length() > 2000
                                                     ? payload.substring(0, 2000) + "…" : payload);
                                    try {
                                        JsonNode root = objectMapper.readTree(payload);

                                        // compactPortfolioByType (since 2026-06-21):
                                        //   {"categories":[{"positions":[{"isin":"...","netSize":"...","averageBuyIn":"..."}]}]}
                                        // Legacy compactPortfolio:
                                        //   [{"instrumentId":"...","netSize":"...","averageBuyIn":"..."}]
                                        List<JsonNode> positions = new ArrayList<>();
                                        JsonNode categories = root.path("categories");
                                        if (!categories.isMissingNode() && categories.isArray()) {
                                            for (JsonNode cat : categories) {
                                                cat.path("positions").forEach(positions::add);
                                            }
                                        } else {
                                            JsonNode posArray = root.isArray() ? root : root.path("positions");
                                            if (posArray.isArray()) posArray.forEach(positions::add);
                                        }

                                        if (!positions.isEmpty()) {
                                            List<String> tickerMsgs = new ArrayList<>();
                                            for (JsonNode pos : positions) {
                                                // new API: "isin"; legacy: "instrumentId"
                                                String isin = pos.path("isin").asText(
                                                        pos.path("instrumentId").asText(""));
                                                if (!isin.isEmpty()) {
                                                    positionsByAccount
                                                        .computeIfAbsent(account.externalId(), key -> new ConcurrentHashMap<>())
                                                        .put(isin, pos);

                                                    // Private equity funds (instrumentType "privateFund") are
                                                    // not publicly traded — TR never sends a price tick for them.
                                                    // Private equity funds don't have live tickers and will cause the websocket
                                                    // to hang waiting for an initial price that never comes.
                                                    String instrumentType = pos.path("instrumentType").asText("");
                                                    if ("privateFund".equals(instrumentType)) {
                                                        log.info("TR compactPortfolioByType [{}]: skipping ticker subscription for privateFund {}", account.name(), isin);
                                                    } else {
                                                        int tid = subIdCounter.incrementAndGet();
                                                        tickerSubToIsin.put(tid, isin);
                                                        // compactPortfolioByType positions carry no exchangeId
                                                        // (unlike the legacy compactPortfolio payload), so this
                                                        // almost always falls through to the default. LSX (Lang &
                                                        // Schwarz Exchange) is TR's home exchange for equities/ETFs
                                                        // — see GH issue #23 (all ticker subs were FORBIDDEN with TRX).
                                                        // TR-native crypto (see OpenFigiIsinConverter.isTrCryptoIsin,
                                                        // e.g. XF000BTC0017) is priced on TRD0 instead — LSX doesn't
                                                        // list it, so using LSX here would still leave every crypto
                                                        // position FORBIDDEN and silently falling back to averageBuyIn.
                                                        String exchangeId = pos.path("exchangeId").asText("");
                                                        String defaultExchange =
                                                                OpenFigiIsinConverter.isTrCryptoIsin(isin) ? "TRD0" : "LSX";
                                                        String tickerId = isin + "." + (exchangeId.isEmpty() ? defaultExchange : exchangeId);
                                                        tickerMsgs.add(subWithId(tid, "ticker",
                                                                tickerId, sessionToken));
                                                    }
                                                }
                                            }
                                            int prev = expectedTickers.get();
                                            expectedTickers.set((prev < 0 ? 0 : prev) + tickerMsgs.size());
                                            log.info("TR compactPortfolioByType [{}]: {} positions, subscribing to {} tickers",
                                                     account.name(), positions.size(), tickerMsgs.size());

                                            if (!tickerMsgs.isEmpty()) {
                                                return session.send(
                                                    Flux.fromIterable(tickerMsgs)
                                                        .map(session::textMessage)
                                                ).thenReturn(text);
                                            }
                                        } else {
                                            expectedTickers.compareAndSet(-1, 0);
                                            log.info("TR compactPortfolioByType [{}]: no positions found", account.name());
                                        }
                                    } catch (Exception ex) {
                                        // Truncated: a portfolio frame is the user's complete
                                        // position list (ISINs, quantities, average buy-in), and
                                        // full payloads do not belong in server logs — same rule
                                        // as IbkrFlexClient and the ticker branch below.
                                        log.error("Failed to parse compactPortfolioByType [{}] ({} chars): {}",
                                            account.name(), payload.length(), truncate(payload), ex);
                                        expectedTickers.compareAndSet(-1, 0);
                                    }

                                } else if (tickerSubToIsin.containsKey(wsId)) {
                                    String isin = tickerSubToIsin.get(wsId);
                                    // Only the first message per subscription counts and is read;
                                    // later delta updates for the same wsId are ignored (see
                                    // answeredTickerSubs declaration). The initial message carries
                                    // the full ticker state, which is all a sync snapshot needs.
                                    if (answeredTickerSubs.add(wsId)) {
                                        try {
                                            JsonNode tickerRoot = objectMapper.readTree(payload);
                                            String priceStr = tickerRoot.path("last").path("price").asText(null);
                                            if (priceStr != null) {
                                                tickerPrices.put(isin, new BigDecimal(priceStr));
                                            } else {
                                                log.warn("TR ticker for {} — no last.price in: {}", isin,
                                                         payload.length() > 300 ? payload.substring(0, 300) : payload);
                                            }
                                        } catch (Exception ex) {
                                            log.warn("Failed to parse ticker for {}: {}", isin, payload);
                                        }
                                    }
                                }

                                return Mono.just(text);
                            })
                            .takeUntil(text -> {
                                if (authExpired.get()) return true;
                                boolean cashDone = (cashJson.get() != null || cashRejected.get())
                                        && receivedScopedCashSubs.size() >= totalScopedCashSubs;
                                boolean allPortfoliosIn = receivedPortfolios.get() >= totalPortfolioSubs;
                                int exp = expectedTickers.get();
                                boolean tickersDone = allPortfoliosIn
                                        && exp >= 0
                                        && answeredTickerSubs.size() >= exp;
                                return cashDone && tickersDone;
                            })
                            .timeout(wsTimeout)
                            .onErrorReturn("timeout")
                    )
                    .then()
            )
            .timeout(wsTimeout.plus(WS_SESSION_SLACK))
            .block();

        if (authExpired.get()) {
            throw new SyncException("SESSION_EXPIRED");
        }

        // ─── Build accounts from collected data ──────────────────────────────

        List<TrAccountData> accounts = new ArrayList<>();

        for (SecAccount secAccount : secAccounts) {
            Map<String, JsonNode> positionsByIsin = positionsByAccount.getOrDefault(
                secAccount.externalId(), new ConcurrentHashMap<>());

            // A PEA's balance is its positions PLUS its cash pocket. When a scoped availableCash
            // subscription was sent for this account and no value came back — the frame never
            // arrived before the idle timeout, TR rejected the subscription, or the payload could
            // not be parsed — the account is skipped rather than written at positions-only value:
            // upsertSnapshot overwrites the day's row, so the understated figure would stay in the
            // net-worth history for good.
            BigDecimal totalPortfolioValue = BigDecimal.ZERO;
            if (hasScopedCash(secAccount)) {
                Optional<BigDecimal> scopedCash =
                    parseCashValue(scopedCashJsonByAccount.get(secAccount.externalId()));
                if (scopedCash.isEmpty()) {
                    log.warn("TR [{}]: no cash balance received for the account's cash pocket — "
                        + "skipping it rather than writing a positions-only balance", secAccount.name());
                    continue;
                }
                totalPortfolioValue = scopedCash.get();
            }
            int priced = 0;
            for (var entry : positionsByIsin.entrySet()) {
                String isin = entry.getKey();
                JsonNode pos = entry.getValue();
                BigDecimal size = new BigDecimal(pos.path("netSize").asText("0"));
                BigDecimal price = tickerPrices.get(isin);

                if (size.compareTo(BigDecimal.ZERO) <= 0) continue;

                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    totalPortfolioValue = totalPortfolioValue.add(
                            price.multiply(size).setScale(2, RoundingMode.HALF_UP));
                    priced++;
                } else {
                    BigDecimal avgBuyIn = new BigDecimal(pos.path("averageBuyIn").asText("0"));
                    if (avgBuyIn.compareTo(BigDecimal.ZERO) > 0) {
                        totalPortfolioValue = totalPortfolioValue.add(
                                avgBuyIn.multiply(size).setScale(2, RoundingMode.HALF_UP));
                        log.warn("TR ticker price missing for {}, using averageBuyIn as fallback", isin);
                    }
                }
            }

            log.info("TR portfolio [{}]: {} positions, {} with live prices, total value: {}",
                     secAccount.name(), positionsByIsin.size(), priced, totalPortfolioValue);

            List<TradeRepublicPort.TrPosition> positions = new ArrayList<>();
            for (var entry : positionsByIsin.entrySet()) {
                String isin = entry.getKey();
                JsonNode pos = entry.getValue();
                BigDecimal size = new BigDecimal(pos.path("netSize").asText("0"));

                if (size.compareTo(BigDecimal.ZERO) <= 0) continue;

                BigDecimal averageBuyIn = new BigDecimal(pos.path("averageBuyIn").asText("0"));
                BigDecimal currentPrice = tickerPrices.getOrDefault(isin, averageBuyIn);

                positions.add(new TradeRepublicPort.TrPosition(isin, size, averageBuyIn, currentPrice));
            }

            if (totalPortfolioValue.compareTo(BigDecimal.ZERO) > 0
                    || !positions.isEmpty()
                    || receivedPortfolioIds.contains(secAccount.externalId())) {
                accounts.add(new TrAccountData(
                    secAccount.externalId(),
                    secAccount.name(),
                    secAccount.type(),
                    totalPortfolioValue,
                    positions));
            }
        }

        if (cashJson.get() != null
                && accounts.stream().noneMatch(a -> "tr_cash".equals(a.externalId()))) {
            accounts.addAll(parseCashJson(cashJson.get()));
        }

        if (accounts.isEmpty()) {
            throw new SyncException(
                "No portfolio data received from Trade Republic. Please try again later.");
        }

        log.info("TR portfolio fetched: {} account(s)", accounts.size());
        return accounts;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private SyncException mapAuthError(String responseBody, String fallback) {
        if (responseBody != null) {
            if (responseBody.contains("VALIDATION_CODE_INVALID")) {
                return new SyncException("VALIDATION_CODE_INVALID");
            }
            if (responseBody.contains("NUMBER_INVALID")) {
                return new SyncException("NUMBER_INVALID");
            }
            if (responseBody.contains("PIN_INVALID")) {
                return new SyncException("PIN_INVALID");
            }
            if (responseBody.contains("AUTHENTICATION_ERROR")) {
                return new SyncException("AUTHENTICATION_ERROR");
            }
        }
        return new SyncException(fallback);
    }

    private boolean isAuthError(String payload) {
        return payload != null && payload.contains("AUTHENTICATION_ERROR");
    }

    int extractWsId(String text) {
        int space = text.indexOf(' ');
        if (space <= 0) return -1;
        try {
            return Integer.parseInt(text.substring(0, space));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The frame-type token of a TR WebSocket frame ({@code <id> <type> <payload>}) — "A" for an
     * answer, "C"/"D" for a delta, "E" for a rejected subscription — or an empty string when the
     * frame has no type token. {@link #extractWsPayload} deliberately drops it; reading it is what
     * lets an error frame be told apart from an answer whose payload we simply could not parse.
     */
    String extractWsType(String text) {
        if (text == null) return "";
        int first = text.indexOf(' ');
        if (first < 0) return "";
        int second = text.indexOf(' ', first + 1);
        if (second < 0) return "";
        return text.substring(first + 1, second);
    }

    private String extractWsPayload(String text) {
        int first = text.indexOf(' ');
        if (first < 0) return text;
        int second = text.indexOf(' ', first + 1);
        if (second < 0) return text.substring(first + 1);
        return text.substring(second + 1);
    }

    private String buildConnectMessage() {
        try {
            Map<String, Object> payload = Map.of(
                "locale",          "fr",
                "platformId",      "webtrading",
                "platformVersion", "chrome - 125.0.0",
                "clientId",        "app.traderepublic.com",
                "clientVersion",   "3.151.3"
            );
            return "connect " + WS_VERSION + " " + objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new SyncException("Failed to build TR connect message: " + ex.getMessage(), ex);
        }
    }

    private String buildSub(int id, Map<String, Object> payload) {
        try {
            return "sub " + id + " " + objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new SyncException("Failed to build subscription message: " + ex.getMessage(), ex);
        }
    }

    private String sub(int id, String type, String token) {
        return buildSub(id, Map.of("type", type, "token", token));
    }

    private String subWithId(int id, String type, String idParam, String token) {
        return buildSub(id, Map.of("type", type, "id", idParam, "token", token));
    }

    private String subCompactPortfolio(int id, String secAccNo, String token) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "compactPortfolioByType");
        payload.put("secAccNo", secAccNo);
        payload.put("token", token);
        return buildSub(id, payload);
    }

    private String subAvailableCash(int id, String cashAccountNumber, String token) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "availableCash");
        payload.put("accountNumber", cashAccountNumber);
        payload.put("token", token);
        return buildSub(id, payload);
    }

    List<SecAccount> extractSecAccounts(String sessionToken) {
        try {
            String[] parts = sessionToken.split("\\.");
            if (parts.length < 2) return List.of();
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
            JsonNode root = objectMapper.readTree(payload);
            JsonNode ownerAccounts = root.path("act").path("acc").path("owner");
            if (!ownerAccounts.isObject()) return List.of();

            List<SecAccount> result = new ArrayList<>();
            Set<String> usedExternalIds = new HashSet<>();
            addSecAccountsFromWrapper(result, usedExternalIds, "default", ownerAccounts.path("default"));

            ownerAccounts.fields().forEachRemaining(entry -> {
                String wrapper = entry.getKey();
                if (!"default".equals(wrapper)) {
                    addSecAccountsFromWrapper(result, usedExternalIds, wrapper, entry.getValue());
                }
            });
            return result;
        } catch (Exception ex) {
            // With the exception object, not just its message: this is the input to the whole
            // portfolio subscription, and a claim-layout change surfaces as an empty portfolio
            // whose only trace used to be one line reading "null" for an NPE.
            log.warn("Failed to extract sec account numbers from JWT", ex);
        }
        return List.of();
    }

    private void addSecAccountsFromWrapper(
        List<SecAccount> result,
        Set<String> usedExternalIds,
        String wrapper,
        JsonNode wrapperNode
    ) {
        JsonNode secAccounts = wrapperNode.path("sec");
        if (!secAccounts.isArray()) return;

        AccountType type = accountTypeForWrapper(wrapper);
        String baseExternalId = externalIdForWrapper(wrapper);
        String baseName = nameForWrapper(wrapper);
        JsonNode cashAccounts = wrapperNode.path("cash");

        int position = 0;
        for (JsonNode acc : secAccounts) {
            String accountNumber = acc.asText(null);
            if (accountNumber == null || accountNumber.isBlank()) continue;
            String cashAccountNumber = cashAccounts.isArray() && cashAccounts.size() > position
                ? cashAccounts.get(position).asText(null)
                : null;

            // The dedup suffix counter is deliberately separate from `position`:
            // bumping it on an external-id collision must not shift cash-account pairing.
            int suffix = position;
            String externalId = suffix == 0 ? baseExternalId : baseExternalId + "_" + (suffix + 1);
            while (!usedExternalIds.add(externalId)) {
                suffix++;
                externalId = baseExternalId + "_" + (suffix + 1);
            }
            String name = suffix == 0 ? baseName : baseName + " " + (suffix + 1);
            result.add(new SecAccount(wrapper, accountNumber, cashAccountNumber, externalId, name, type));
            position++;
        }
    }

    private AccountType accountTypeForWrapper(String wrapper) {
        return "tax_wrapper_fr".equals(wrapper) ? AccountType.PEA : AccountType.COMPTE_TITRES;
    }

    private String externalIdForWrapper(String wrapper) {
        return switch (wrapper) {
            case "default" -> "tr_securities";
            case "tax_wrapper_fr" -> "tr_pea";
            default -> "tr_" + wrapper.replaceAll("[^a-zA-Z0-9]+", "_").toLowerCase();
        };
    }

    private String nameForWrapper(String wrapper) {
        return switch (wrapper) {
            case "default" -> "TR Titres";
            case "tax_wrapper_fr" -> "TR PEA";
            default -> "TR Titres";
        };
    }

    /** Whether a securities account has a cash pocket of its own to subscribe to (PEA). */
    private static boolean hasScopedCash(SecAccount account) {
        return account.type() == AccountType.PEA
            && account.cashAccountNumber() != null
            && !account.cashAccountNumber().isBlank();
    }

    /**
     * The TR Cash account, or nothing at all when the payload carried no readable amount.
     *
     * <p>Emitting a 0 EUR account instead is what this returns an empty list for: the value is
     * persisted as {@code account.currentBalance} and stamped as the day's balance snapshot, which
     * overwrites whatever an earlier successful sync wrote — so one unreadable frame turned into a
     * permanent hole in the net-worth chart. Skipping leaves the previous balance standing, the
     * same refusal {@code CryptoExchangeSyncService} and {@code WalletSyncService} make.
     */
    List<TrAccountData> parseCashJson(String json) {
        Optional<BigDecimal> value = parseCashValue(json);
        if (value.isEmpty()) {
            log.warn("TR availableCash carried no readable amount ({} chars) — skipping the TR Cash "
                + "account rather than recording a zero: {}",
                json == null ? 0 : json.length(), truncate(json));
            return List.of();
        }
        log.info("TR availableCash: {}", value.get());
        return List.of(new TrAccountData(
            "tr_cash", "TR Cash", AccountType.CHECKING, value.get(), List.of()));
    }

    /**
     * The cash amount in an {@code availableCash} payload, or {@link Optional#empty()} when there
     * is none to read — a blank body, an error frame, a shape carrying neither {@code value} nor
     * {@code amount}.
     *
     * <p>Returning {@code BigDecimal.ZERO} for all of those made "the account holds nothing" and
     * "we could not read the answer" the same value, and every caller here writes that value into
     * a balance.
     *
     * <p>Negative entries are skipped rather than returned, preserving the original scan: TR sends
     * one entry per currency and a negative one is not the euro cash pocket being looked for.
     */
    Optional<BigDecimal> parseCashValue(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode array = root.isArray() ? root : root.path("availableCash");
            if (array.isMissingNode()) array = root;

            if (array.isArray()) {
                for (JsonNode item : array) {
                    Optional<BigDecimal> value = extractValue(item);
                    if (value.isPresent() && value.get().signum() >= 0) {
                        return value;
                    }
                }
            } else if (array.isObject()) {
                Optional<BigDecimal> value = extractValue(array);
                if (value.isPresent() && value.get().signum() >= 0) {
                    return value;
                }
            }
        } catch (Exception ex) {
            log.error("Failed to parse TR availableCash ({} chars): {}",
                json.length(), truncate(json), ex);
        }
        return Optional.empty();
    }

    /** The amount carried by one {@code availableCash} entry, or empty when it carries none. */
    private Optional<BigDecimal> extractValue(JsonNode node) {
        if (node == null || node.isMissingNode()) return Optional.empty();
        try {
            if (node.has("value"))   return Optional.of(new BigDecimal(node.get("value").asText("0")));
            if (node.has("amount"))  return Optional.of(new BigDecimal(node.get("amount").asText("0")));
            if (node.isNumber())     return Optional.of(node.decimalValue());
        } catch (NumberFormatException ex) {
            log.warn("TR availableCash entry carries an unreadable amount: {}", truncate(node.toString()));
        }
        return Optional.empty();
    }

    /** A payload prefix safe to log — see {@link #LOG_PAYLOAD_CHARS}. */
    private static String truncate(String payload) {
        if (payload == null) return "";
        return payload.length() > LOG_PAYLOAD_CHARS
            ? payload.substring(0, LOG_PAYLOAD_CHARS) + "…"
            : payload;
    }
}
