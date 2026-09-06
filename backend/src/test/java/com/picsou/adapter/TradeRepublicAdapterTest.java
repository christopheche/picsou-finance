package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.model.AccountType;
import com.picsou.port.TradeRepublicPort.TrAccountData;
import com.picsou.port.TradeRepublicPort.TrTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class TradeRepublicAdapterTest {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(2);

    private DisposableServer server;

    @Test
    void productionConstructorIsExplicitSpringInjectionPoint() throws NoSuchMethodException {
        assertThat(TradeRepublicAdapter.class
            .getConstructor(ObjectMapper.class, String.class)
            .isAnnotationPresent(Autowired.class))
            .isTrue();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.disposeNow();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void refreshSession_rejectedStatusMapsToSessionExpired(int status) {
        TradeRepublicAdapter adapter = adapterReturning(status, "{\"detail\":\"rejected\"}");

        Throwable thrown = catchThrowable(() -> adapter.refreshSession("refresh-token"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessage("SESSION_EXPIRED");
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void refreshSession_transientStatusMapsToUnavailable(int status) {
        TradeRepublicAdapter adapter = adapterReturning(status, "{\"detail\":\"temporary\"}");

        Throwable thrown = catchThrowable(() -> adapter.refreshSession("refresh-token"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("unavailable");
        assertThat(thrown.getMessage()).isNotEqualTo("SESSION_EXPIRED");
    }

    @Test
    void refreshSession_timeoutMapsToUnavailable() {
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> Mono.never())
            .bindNow();
        TradeRepublicAdapter adapter = adapterFor(server, Duration.ofMillis(25));

        Throwable thrown = catchThrowable(() -> adapter.refreshSession("refresh-token"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("unavailable");
        assertThat(thrown.getMessage()).isNotEqualTo("SESSION_EXPIRED");
    }

    @Test
    void refreshSession_emptySuccessBodyIsNotSessionExpired() {
        TradeRepublicAdapter adapter = adapterReturning(200, "");

        Throwable thrown = catchThrowable(() -> adapter.refreshSession("refresh-token"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("empty response");
        assertThat(thrown.getMessage()).isNotEqualTo("SESSION_EXPIRED");
    }

    @Test
    void refreshSession_missingSessionTokenIsNotSessionExpired() {
        TradeRepublicAdapter adapter = adapterReturning(200, "{\"refreshToken\":\"rotated\"}");

        Throwable thrown = catchThrowable(() -> adapter.refreshSession("refresh-token"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("empty response");
        assertThat(thrown.getMessage()).isNotEqualTo("SESSION_EXPIRED");
    }

    @Test
    void refreshSession_validResponseKeepsPreviousRefreshTokenWhenNotRotated() {
        TradeRepublicAdapter adapter = adapterReturning(200, "{\"sessionToken\":\"new-session\"}");

        TrTokens tokens = adapter.refreshSession("refresh-token");

        assertThat(tokens.sessionToken()).isEqualTo("new-session");
        assertThat(tokens.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    void refreshSession_validResponseUsesRotatedRefreshToken() {
        TradeRepublicAdapter adapter = adapterReturning(
            200,
            "{\"sessionToken\":\"new-session\",\"refreshToken\":\"rotated\"}"
        );

        TrTokens tokens = adapter.refreshSession("refresh-token");

        assertThat(tokens.sessionToken()).isEqualTo("new-session");
        assertThat(tokens.refreshToken()).isEqualTo("rotated");
    }


    // ─── availableCash parsing ────────────────────────────────────────────────

    private static final TradeRepublicAdapter PARSER =
        new TradeRepublicAdapter(new ObjectMapper(), "http://unused", RESPONSE_TIMEOUT);

    @Test
    void parseCashValue_readsBothPayloadShapesTradeRepublicSends() {
        assertThat(PARSER.parseCashValue("[{\"currencyId\":\"EUR\",\"value\":\"12.34\"}]"))
            .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("12.34"));
        assertThat(PARSER.parseCashValue("{\"availableCash\":[{\"amount\":\"3\"}]}"))
            .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("3"));
    }

    /**
     * The distinction the whole finding turns on: "the account holds nothing" and "we could not
     * read the answer" used to be the same {@code BigDecimal.ZERO}, and every caller writes that
     * value into a balance that is then snapshotted for the day.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "   ",
        "{\"errors\":[{\"errorCode\":\"FORBIDDEN\"}]}",
        "{\"foo\":1}",
        "not json at all"
    })
    void parseCashValue_reportsNothingRatherThanZero_forAnUnreadablePayload(String payload) {
        assertThat(PARSER.parseCashValue(payload)).isEmpty();
    }

    @Test
    void parseCashValue_reportsNothingForANullPayload() {
        assertThat(PARSER.parseCashValue(null)).isEmpty();
    }

    @Test
    void parseCashJson_emitsNoTrCashAccountWhenThereIsNoAmountToRead() {
        // A 0 EUR "TR Cash" account would be persisted as the balance AND stamped as today's
        // snapshot, which overwrites the row an earlier good sync wrote.
        assertThat(PARSER.parseCashJson("{\"errors\":[{\"errorCode\":\"FORBIDDEN\"}]}")).isEmpty();
        assertThat(PARSER.parseCashJson("")).isEmpty();

        assertThat(PARSER.parseCashJson("[{\"currencyId\":\"EUR\",\"value\":\"0\"}]"))
            .singleElement()
            .satisfies(account -> {
                assertThat(account.externalId()).isEqualTo("tr_cash");
                assertThat(account.balanceEur()).isEqualByComparingTo("0");
            });
    }

    @Test
    void extractWsType_readsTheFrameTypeExtractWsPayloadDrops() {
        assertThat(PARSER.extractWsType("1 A [{\"value\":\"1\"}]")).isEqualTo("A");
        assertThat(PARSER.extractWsType("2 E {\"errors\":[]}")).isEqualTo("E");
        assertThat(PARSER.extractWsType("connected")).isEmpty();
    }

    @Test
    void extractSecAccounts_pairsEachSecuritiesAccountWithItsOwnCashAccount() {
        List<TradeRepublicAdapter.SecAccount> accounts = PARSER.extractSecAccounts(sessionToken());

        assertThat(accounts).extracting(
                TradeRepublicAdapter.SecAccount::externalId,
                TradeRepublicAdapter.SecAccount::name,
                TradeRepublicAdapter.SecAccount::type,
                TradeRepublicAdapter.SecAccount::cashAccountNumber)
            .containsExactlyInAnyOrder(
                org.assertj.core.api.Assertions.tuple("tr_securities", "TR Titres",
                    AccountType.COMPTE_TITRES, "CASH-1"),
                org.assertj.core.api.Assertions.tuple("tr_pea", "TR PEA",
                    AccountType.PEA, "CASH-2"));
    }

    // ─── fetchAccounts over a real WebSocket ──────────────────────────────────

    @Test
    void fetchAccounts_valuesEachAccount_andAddsThePeaCashPocketToItsPositions() {
        TradeRepublicAdapter adapter = adapterTalkingTo(SUB -> switch (SUB.type()) {
            case "availableCash" -> answer(SUB.id(), SUB.accountNumber() == null
                ? "[{\"currencyId\":\"EUR\",\"value\":\"123.45\"}]"
                : "[{\"currencyId\":\"EUR\",\"value\":\"50\"}]");
            case "compactPortfolioByType" -> answer(SUB.id(), ONE_ETF_POSITION);
            case "ticker" -> answer(SUB.id(), "{\"last\":{\"price\":\"100\"}}");
            default -> List.of();
        });

        List<TrAccountData> accounts = adapter.fetchAccounts(sessionToken());

        assertThat(balanceOf(accounts, "tr_cash")).isEqualByComparingTo("123.45");
        // 10 x 100 = 1000 of positions...
        assertThat(balanceOf(accounts, "tr_securities")).isEqualByComparingTo("1000.00");
        // ...plus the PEA's own 50 EUR cash pocket.
        assertThat(balanceOf(accounts, "tr_pea")).isEqualByComparingTo("1050.00");
    }

    @Test
    void fetchAccounts_fallsBackToAverageBuyIn_whenATickerNeverPrices() {
        TradeRepublicAdapter adapter = adapterTalkingTo(SUB -> switch (SUB.type()) {
            case "availableCash" -> answer(SUB.id(), "[{\"currencyId\":\"EUR\",\"value\":\"0\"}]");
            case "compactPortfolioByType" -> answer(SUB.id(), ONE_ETF_POSITION);
            // TR rejects the ticker subscription: documented behaviour, the position keeps its
            // purchase price rather than dropping out of the account.
            case "ticker" -> List.of(SUB.id() + " E {\"errors\":[{\"errorCode\":\"FORBIDDEN\"}]}");
            default -> List.of();
        });

        List<TrAccountData> accounts = adapter.fetchAccounts(sessionToken());

        assertThat(balanceOf(accounts, "tr_securities")).isEqualByComparingTo("800.00");
    }

    /**
     * The reported failure: TR answers the cash subscription with an error frame. The frame type
     * was never read, so the error payload was stored as the cash JSON, parsed to 0, and written
     * as a 0 EUR "TR Cash" account over whatever the account really held.
     */
    @Test
    void fetchAccounts_emitsNoCashAccount_whenTradeRepublicRejectsTheCashSubscription() {
        TradeRepublicAdapter adapter = adapterTalkingTo(SUB -> switch (SUB.type()) {
            case "availableCash" -> SUB.accountNumber() == null
                ? List.of(SUB.id() + " E {\"errors\":[{\"errorCode\":\"FORBIDDEN\"}]}")
                : answer(SUB.id(), "[{\"currencyId\":\"EUR\",\"value\":\"50\"}]");
            case "compactPortfolioByType" -> answer(SUB.id(), ONE_ETF_POSITION);
            case "ticker" -> answer(SUB.id(), "{\"last\":{\"price\":\"100\"}}");
            default -> List.of();
        });

        List<TrAccountData> accounts = adapter.fetchAccounts(sessionToken());

        assertThat(accounts).extracting(TrAccountData::externalId).doesNotContain("tr_cash");
        assertThat(balanceOf(accounts, "tr_securities")).isEqualByComparingTo("1000.00");
    }

    /**
     * And the PEA half: without its cash pocket the account is skipped rather than written at
     * positions-only value, which would understate net worth by exactly the cash it holds.
     */
    @Test
    void fetchAccounts_skipsThePea_whenItsCashPocketNeverArrives() {
        TradeRepublicAdapter adapter = adapterTalkingTo(SUB -> switch (SUB.type()) {
            case "availableCash" -> SUB.accountNumber() == null
                ? answer(SUB.id(), "[{\"currencyId\":\"EUR\",\"value\":\"123.45\"}]")
                : List.of(SUB.id() + " E {\"errors\":[{\"errorCode\":\"BAD_REQUEST\"}]}");
            case "compactPortfolioByType" -> answer(SUB.id(), ONE_ETF_POSITION);
            case "ticker" -> answer(SUB.id(), "{\"last\":{\"price\":\"100\"}}");
            default -> List.of();
        });

        List<TrAccountData> accounts = adapter.fetchAccounts(sessionToken());

        assertThat(accounts).extracting(TrAccountData::externalId)
            .contains("tr_cash", "tr_securities")
            .doesNotContain("tr_pea");
    }

    @Test
    void fetchAccounts_reportsAnExpiredSession() {
        TradeRepublicAdapter adapter = adapterTalkingTo(
            SUB -> List.of(SUB.id() + " E {\"errors\":[{\"errorCode\":\"AUTHENTICATION_ERROR\"}]}"));

        assertThat(catchThrowable(() -> adapter.fetchAccounts(sessionToken())))
            .isInstanceOf(SyncException.class)
            .hasMessage("SESSION_EXPIRED");
    }

    // ─── WebSocket fixtures ───────────────────────────────────────────────────

    private static final String ONE_ETF_POSITION =
        "{\"categories\":[{\"positions\":[{\"isin\":\"IE00B4L5Y983\",\"netSize\":\"10\","
            + "\"averageBuyIn\":\"80\",\"instrumentType\":\"ETF\"}]}]}";

    /** One {@code sub} frame as the fake server sees it. */
    private record Subscription(int id, String type, String accountNumber) {}

    private static List<String> answer(int id, String payload) {
        return List.of(id + " A " + payload);
    }

    private static BigDecimal balanceOf(List<TrAccountData> accounts, String externalId) {
        return accounts.stream()
            .filter(account -> account.externalId().equals(externalId))
            .findFirst().orElseThrow(() ->
                new AssertionError("no account " + externalId + " in " + accounts))
            .balanceEur();
    }

    /**
     * A session token shaped like TR's: a JWT whose {@code act.acc.owner} claim carries one
     * default securities account and one {@code tax_wrapper_fr} (PEA) account, each with its own
     * cash account number. Only the payload segment is ever read — nothing verifies the signature.
     */
    private static String sessionToken() {
        String payload = "{\"act\":{\"acc\":{\"owner\":{"
            + "\"default\":{\"sec\":[\"SEC-1\"],\"cash\":[\"CASH-1\"]},"
            + "\"tax_wrapper_fr\":{\"sec\":[\"SEC-2\"],\"cash\":[\"CASH-2\"]}}}}}";
        return "header."
            + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8))
            + ".signature";
    }

    /**
     * A local WebSocket server speaking TR's protocol: it answers {@code connect} with
     * {@code connected} and hands every {@code sub} frame to {@code responder}, which decides
     * what (if anything) comes back.
     */
    private TradeRepublicAdapter adapterTalkingTo(Function<Subscription, List<String>> responder) {
        ObjectMapper mapper = new ObjectMapper();
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> routes.ws("/", (in, out) -> out.sendString(
                in.receive().asString().concatMap(frame -> {
                    if (frame.startsWith("connect ")) {
                        return Flux.just("connected");
                    }
                    if (!frame.startsWith("sub ")) {
                        return Flux.empty();
                    }
                    String[] parts = frame.split(" ", 3);
                    int id = Integer.parseInt(parts[1]);
                    try {
                        JsonNode body = mapper.readTree(parts[2]);
                        return Flux.fromIterable(responder.apply(new Subscription(
                            id,
                            body.path("type").asText(""),
                            body.hasNonNull("accountNumber") ? body.get("accountNumber").asText() : null)));
                    } catch (Exception ex) {
                        return Flux.error(ex);
                    }
                }))))
            .bindNow();

        return new TradeRepublicAdapter(mapper, "http://127.0.0.1:" + server.port(),
            RESPONSE_TIMEOUT, "ws://127.0.0.1:" + server.port() + "/", Duration.ofSeconds(2));
    }

    private TradeRepublicAdapter adapterReturning(int status, String body) {
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> response
                .status(status)
                .header("Content-Type", "application/json")
                .sendString(Mono.just(body)))
            .bindNow();
        return adapterFor(server, RESPONSE_TIMEOUT);
    }

    private static TradeRepublicAdapter adapterFor(DisposableServer server, Duration timeout) {
        return new TradeRepublicAdapter(
            new ObjectMapper(),
            "http://127.0.0.1:" + server.port(),
            timeout
        );
    }
}
