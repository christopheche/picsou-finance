package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.exception.SyncException;
import com.picsou.port.CryptoExchangePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Component
public class BinanceAdapter implements CryptoExchangePort {

    private static final Logger log = LoggerFactory.getLogger(BinanceAdapter.class);
    private static final String BASE_URL = "https://api.binance.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    private final Duration timeout;

    public BinanceAdapter() {
        this(WebClient.builder().baseUrl(BASE_URL).build(), TIMEOUT);
    }

    // Package-private constructor for tests — inject a WebClient backed by an ExchangeFunction.
    BinanceAdapter(WebClient webClient, Duration timeout) {
        this.webClient = webClient;
        this.timeout = timeout;
    }

    @Override
    public String exchangeName() {
        return "BINANCE";
    }

    @Override
    public List<ExchangePosition> fetchPositions(String apiKey, String apiSecret) {
        long timestamp = System.currentTimeMillis();
        String queryString = "timestamp=" + timestamp;
        String signature = hmacSha256(apiSecret, queryString);

        JsonNode response;
        try {
            response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/api/v3/account")
                    .query(queryString + "&signature=" + signature)
                    .build())
                .header("X-MBX-APIKEY", apiKey)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .block();
        } catch (RuntimeException ex) {
            throw failure(ex);
        }

        // Never an empty list for an unreadable response: the caller sums holdings into one
        // account balance and stamps it into a daily snapshot, so "no body" read as "no coins"
        // would write a EUR 0 cliff into the net-worth history (CryptoExchangePort#fetchPositions).
        if (response == null) {
            throw new SyncException("Binance returned an empty response for /api/v3/account.");
        }

        // Spot only: this adapter reads /api/v3/account, which does not cover Binance Earn.
        List<ExchangePosition> holdings = new ArrayList<>();
        JsonNode balances = response.path("balances");
        if (balances.isArray()) {
            for (JsonNode balance : balances) {
                String asset = balance.path("asset").asText("");
                BigDecimal free = new BigDecimal(balance.path("free").asText("0"));
                BigDecimal locked = new BigDecimal(balance.path("locked").asText("0"));
                BigDecimal total = free.add(locked);
                if (total.compareTo(BigDecimal.ZERO) > 0) {
                    holdings.add(ExchangePosition.spot(asset.toUpperCase(), total));
                }
            }
        }

        log.info("Binance: fetched {} non-zero holdings", holdings.size());
        return holdings;
    }

    @Override
    public boolean testConnection(String apiKey, String apiSecret) {
        try {
            long timestamp = System.currentTimeMillis();
            String queryString = "timestamp=" + timestamp;
            String signature = hmacSha256(apiSecret, queryString);

            webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/api/v3/account")
                    .query(queryString + "&signature=" + signature)
                    .build())
                .header("X-MBX-APIKEY", apiKey)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .block();
            return true;
        } catch (Exception ex) {
            log.warn("Binance connection test failed: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * Turns a failed {@code /api/v3/account} call into the {@link SyncException} the sync layer
     * knows how to report, keeping the original as the cause.
     *
     * <p>Without this, a revoked key left {@code CryptoExchangeSyncService} with a raw
     * {@code WebClientResponseException}: it fell into the generic {@code catch (Exception)},
     * logged a stack trace at ERROR and told the user to "try again later" — advice that never
     * resolves the one failure the user could actually fix. The mapping mirrors
     * {@code MeriaAdapter.failure} on the same port; docs/conventions/error-handling.md requires
     * adapters to wrap external errors in {@code SyncException} with the cause.
     *
     * <p>It unwraps first: {@code Mono.timeout()} signals a <em>checked</em>
     * {@link TimeoutException}, which {@code block()} wraps in a reactor {@code ReactiveException}.
     */
    private SyncException failure(RuntimeException ex) {
        Throwable cause = reactor.core.Exceptions.unwrap(ex);
        if (cause instanceof WebClientResponseException http) {
            int status = http.getStatusCode().value();
            log.warn("Binance answered HTTP {} for /api/v3/account -- failing the sync", status);
            if (status == 401 || status == 403) {
                return new SyncException("Binance rejected the API key. Please check it in your account settings.", ex);
            }
            // 418 is Binance's own "you are banned for ignoring 429s" status.
            if (status == 429 || status == 418) {
                return new SyncException("Binance rate-limited the sync. Please try again later.", ex);
            }
            return new SyncException("Binance answered HTTP " + status + ". Please try again later.", ex);
        }
        if (cause instanceof TimeoutException) {
            log.warn("Binance request for /api/v3/account timed out after {} -- failing the sync", timeout);
            return new SyncException("Binance took too long to answer. Please try again later.", ex);
        }
        if (cause instanceof WebClientRequestException) {
            log.warn("Binance request for /api/v3/account could not reach the API ({}) -- failing the sync",
                cause.getMessage());
            return new SyncException("Binance is unreachable. Please try again later.", ex);
        }
        // Not a recognised upstream failure: a defect on our side, or a shape nothing above
        // anticipated. Log the type and the stacktrace so it stays diagnosable.
        log.error("Binance request for /api/v3/account failed unexpectedly ({}) -- failing the sync",
            cause.getClass().getName(), ex);
        return new SyncException("Could not read your Binance balances. Please try again later.", ex);
    }

    private String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new RuntimeException("HMAC-SHA256 signing failed", ex);
        }
    }
}
