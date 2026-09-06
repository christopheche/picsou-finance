package com.picsou.adapter;

import com.picsou.exception.SyncException;
import com.picsou.port.CryptoExchangePort.ExchangePosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What this adapter owes its caller: a {@link SyncException} for every way Binance can refuse.
 *
 * <p>{@code CryptoExchangeSyncService} rethrows a {@code SyncException} verbatim and wraps
 * everything else in "Could not sync your BINANCE account. Please try again later." — so a raw
 * {@code WebClientResponseException} for a revoked API key told the user to retry a condition
 * retrying never resolves, and logged a stack trace at ERROR for it. Same mapping (and the same
 * "keep the cause") as {@code MeriaAdapter} on the same port.
 */
class BinanceAdapterTest {

    private static final String API_KEY = "binance-read-only-key";
    private static final String API_SECRET = "binance-secret";

    @Test
    void fetchPositions_sumsFreeAndLockedIntoOneSpotLinePerAsset() {
        BinanceAdapter adapter = adapterAnswering(HttpStatus.OK, """
            {"balances":[
              {"asset":"btc","free":"0.5","locked":"0.25"},
              {"asset":"ETH","free":"0","locked":"0"}
            ]}""");

        List<ExchangePosition> positions = adapter.fetchPositions(API_KEY, API_SECRET);

        assertThat(positions).extracting(ExchangePosition::symbol).containsExactly("BTC");
        assertThat(positions.get(0).quantity()).isEqualByComparingTo("0.75");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void fetchPositions_reportsARejectedKeyAsSuch_ratherThanAsTryAgainLater(int status) {
        BinanceAdapter adapter = adapterAnswering(HttpStatus.valueOf(status),
            "{\"code\":-2015,\"msg\":\"Invalid API-key, IP, or permissions for action.\"}");

        assertThatThrownBy(() -> adapter.fetchPositions(API_KEY, API_SECRET))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("rejected the API key")
            .hasCauseInstanceOf(RuntimeException.class);
    }

    // 418 is Binance's own "banned for ignoring 429s" status, so it must read the same way.
    @ParameterizedTest
    @ValueSource(ints = {418, 429})
    void fetchPositions_reportsRateLimiting(int status) {
        BinanceAdapter adapter = adapterAnswering(HttpStatus.valueOf(status), "{\"code\":-1003}");

        assertThatThrownBy(() -> adapter.fetchPositions(API_KEY, API_SECRET))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("rate-limited");
    }

    @Test
    void fetchPositions_reportsAServerErrorAsAnUpstreamFailure() {
        BinanceAdapter adapter = adapterAnswering(HttpStatus.SERVICE_UNAVAILABLE, "{}");

        assertThatThrownBy(() -> adapter.fetchPositions(API_KEY, API_SECRET))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("HTTP 503");
    }

    @Test
    void fetchPositions_reportsATimeout_despiteReactorWrappingIt() {
        // Mono.timeout() signals a *checked* TimeoutException, which block() wraps in a reactor
        // ReactiveException — classifying without unwrapping would miss it entirely.
        BinanceAdapter adapter = adapterFor(request -> Mono.never(), Duration.ofMillis(50));

        assertThatThrownBy(() -> adapter.fetchPositions(API_KEY, API_SECRET))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("took too long");
    }

    @Test
    void fetchPositions_refusesAnEmptyBody_ratherThanReportingNoCoins() {
        // The caller sums holdings into one account balance and snapshots it, so "no body" read
        // as "no coins" would write a EUR 0 cliff into the net-worth history.
        BinanceAdapter adapter = adapterFor(request -> Mono.just(
            ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build()),
            Duration.ofSeconds(5));

        assertThatThrownBy(() -> adapter.fetchPositions(API_KEY, API_SECRET))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("empty response");
    }

    @Test
    void testConnection_isFalseOnAnyRefusal_withoutThrowing() {
        assertThat(adapterAnswering(HttpStatus.UNAUTHORIZED, "{}").testConnection(API_KEY, API_SECRET))
            .isFalse();
        assertThat(adapterAnswering(HttpStatus.OK, "{\"balances\":[]}").testConnection(API_KEY, API_SECRET))
            .isTrue();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static BinanceAdapter adapterAnswering(HttpStatus status, String body) {
        return adapterFor(request -> Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build()), Duration.ofSeconds(5));
    }

    private static BinanceAdapter adapterFor(ExchangeFunction exchange, Duration timeout) {
        return new BinanceAdapter(
            WebClient.builder().baseUrl("https://api.binance.test").exchangeFunction(exchange).build(),
            timeout);
    }
}
