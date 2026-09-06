package com.picsou.adapter;

import com.picsou.adapter.util.BitcoinKeyUtils;
import com.picsou.exception.WalletRpcException;
import com.picsou.port.WalletPort.WalletBalance;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the adapter through an {@link ExchangeFunction}-backed {@link WebClient}, routing each
 * Esplora call by the address in its path. HD wallets use the BIP84 test vectors (see
 * {@code BitcoinKeyUtilsTest}), so the addresses the adapter asks for are known up front.
 */
class BitcoinWalletAdapterTest {

    private static final String XPUB =
        "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V";
    private static final String ZPUB =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs";
    private static final String DESCRIPTOR = "wpkh([73c5da0a/84h/0h/0h]" + ZPUB + "/0/*)#abcdefgh";
    private static final String YPUB =
        "ypub6XR9pJPUsVBFKweLeV85HtwdxjjmKEuUr6djm9mNdkh47X7ASsD6byaXFotRAKByFoWgSzCuoTjaYdrv2yoJroLAPtBuHFjVm5vNmhyNehE";

    /** m/0/0, m/0/1 and m/1/0 of the vector account. */
    private static final String RECEIVE_0 = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
    private static final String RECEIVE_1 = "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g";
    private static final String CHANGE_0 = "bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el";

    private static final String PLAIN = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq";

    private static final String UNUSED = stats(0, 0, 0);

    private static String stats(long funded, long spent, long txCount) {
        return "{\"chain_stats\":{\"funded_txo_sum\":" + funded + ",\"spent_txo_sum\":" + spent
            + ",\"tx_count\":" + txCount + "},\"mempool_stats\":{}}";
    }

    private static Mono<ClientResponse> ok(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
    }

    private static Mono<ClientResponse> status(HttpStatus status) {
        return Mono.just(ClientResponse.create(status).build());
    }

    /** Addresses the adapter asked Esplora for, in call order. */
    private final List<String> requested = new CopyOnWriteArrayList<>();

    private BitcoinWalletAdapter adapter(Function<String, Mono<ClientResponse>> byAddress) {
        ExchangeFunction exchange = request -> {
            String path = request.url().getPath();
            assertThat(path).startsWith("/api/address/");
            String address = path.substring("/api/address/".length());
            requested.add(address);
            return byAddress.apply(address);
        };
        return new BitcoinWalletAdapter(WebClient.builder()
            .baseUrl("http://esplora.test")
            .exchangeFunction(exchange)
            .build());
    }

    /** Known addresses answer from the map; every other derived address reads as unused. */
    private BitcoinWalletAdapter adapterWith(Map<String, Mono<ClientResponse>> byAddress) {
        return adapter(address -> byAddress.getOrDefault(address, ok(UNUSED)));
    }

    private static BigDecimal btc(String value) {
        return new BigDecimal(value);
    }

    // ─── HD wallet scan ───────────────────────────────────────────────────────

    @Test
    void xpubScan_sumsBothChains_atTheBip84Addresses() {
        var adapter = adapterWith(Map.of(
            RECEIVE_0, ok(stats(100_000, 0, 1)),
            CHANGE_0, ok(stats(50_000, 20_000, 2))));

        List<WalletBalance> balances = adapter.fetchBalances(XPUB);

        assertThat(balances).singleElement().satisfies(b -> {
            assertThat(b.symbol()).isEqualTo("BTC");
            assertThat(b.amount()).isEqualByComparingTo(btc("0.00130000"));
        });
        // One used address then GAP_LIMIT unused ones, on each of the two chains.
        assertThat(requested).hasSize(2 * (1 + BitcoinKeyUtils.GAP_LIMIT));
        assertThat(requested).contains(RECEIVE_0, RECEIVE_1, CHANGE_0);
    }

    @Test
    void zpubAndDescriptor_resolveToTheSameWalletAsTheXpub() {
        var fromZpub = adapterWith(Map.of(RECEIVE_0, ok(stats(100_000, 0, 1)))).fetchBalances(ZPUB);
        var fromDescriptor = adapterWith(Map.of(RECEIVE_0, ok(stats(100_000, 0, 1)))).fetchBalances(DESCRIPTOR);

        assertThat(fromZpub.get(0).amount()).isEqualByComparingTo(btc("0.001"));
        assertThat(fromDescriptor.get(0).amount()).isEqualByComparingTo(btc("0.001"));
    }

    @Test
    void xpubScan_genuinelyUnusedWallet_returnsZero_afterTheGapLimitOnBothChains() {
        var adapter = adapterWith(Map.of());

        List<WalletBalance> balances = adapter.fetchBalances(XPUB);

        assertThat(balances).singleElement().satisfies(b ->
            assertThat(b.amount()).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(requested).hasSize(2 * BitcoinKeyUtils.GAP_LIMIT);
    }

    @Test
    void xpubScan_transportFailureMidScan_throwsWalletRpcException_neverReadsAsZero() {
        // The first address is funded and already summed when the second one gets a 503:
        // the scan must abort, not report the partial (or a zero) balance as the wallet's.
        var adapter = adapterWith(Map.of(
            RECEIVE_0, ok(stats(100_000, 0, 1)),
            RECEIVE_1, status(HttpStatus.SERVICE_UNAVAILABLE)));

        assertThatThrownBy(() -> adapter.fetchBalances(XPUB))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("Bitcoin Esplora address " + RECEIVE_1)
            .hasMessageContaining("503")
            .hasCauseInstanceOf(WebClientResponseException.class);
    }

    @Test
    void xpubScan_emptyBodyOnAnAddress_throws_insteadOfCountingItAsUnused() {
        // An empty 200 mid-scan would otherwise both drop that address's balance and
        // advance the gap counter, ending the scan early.
        var adapter = adapterWith(Map.of(
            RECEIVE_0, ok(stats(100_000, 0, 1)),
            RECEIVE_1, status(HttpStatus.OK)));

        assertThatThrownBy(() -> adapter.fetchBalances(XPUB))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("returned no response");
    }

    @Test
    void xpubScan_responseWithoutChainStats_throws() {
        var adapter = adapterWith(Map.of(RECEIVE_0, ok("{\"address\":\"" + RECEIVE_0 + "\"}")));

        assertThatThrownBy(() -> adapter.fetchBalances(XPUB))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("chain_stats");
    }

    @Test
    void xpubScan_storedKeyThatNoLongerParses_throwsWalletRpcException_withoutCallingEsplora() {
        var adapter = adapterWith(Map.of());
        String corrupted = XPUB.substring(0, XPUB.length() - 1) + (XPUB.endsWith("V") ? "W" : "V");

        assertThatThrownBy(() -> adapter.fetchBalances(corrupted))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("could not be parsed")
            .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(XPUB.substring(4, 20)));
        assertThat(requested).isEmpty();
    }

    // ─── Unsupported script types ─────────────────────────────────────────────

    @Test
    void fetchBalances_storedYpub_throwsWalletRpcException_insteadOfScanningBc1qAddresses() {
        // A ypub denotes 3... (P2SH-P2WPKH) addresses; deriving bc1q ones would find no
        // history anywhere and report 0 BTC as if the wallet were empty.
        var adapter = adapterWith(Map.of());

        assertThatThrownBy(() -> adapter.fetchBalances(YPUB))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("ypub")
            .hasMessageContaining("xpub/zpub");
        assertThat(requested).isEmpty();
    }

    @Test
    void fetchBalances_storedPkhDescriptor_throwsWalletRpcException_withoutCallingEsplora() {
        var adapter = adapterWith(Map.of());

        assertThatThrownBy(() -> adapter.fetchBalances("pkh([d34db33f/44h/0h/0h]" + XPUB + "/0/*)"))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("pkh(");
        assertThat(requested).isEmpty();
    }

    // ─── validateAddress (400 gate) ───────────────────────────────────────────

    @Test
    void validateAddress_rejectsYpub_asABadRequest_namingTheSupportedFormats() {
        var adapter = adapterWith(Map.of());

        assertThatThrownBy(() -> adapter.validateAddress(YPUB))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ypub")
            .hasMessageContaining("xpub/zpub")
            .hasMessageContaining("wpkh(");
        assertThat(requested).isEmpty();
    }

    @Test
    void validateAddress_rejectsAnExtendedKeyThatDoesNotParse() {
        var adapter = adapterWith(Map.of());

        assertThatThrownBy(() -> adapter.validateAddress("xpubGARBAGE"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid Bitcoin extended key");
        assertThatThrownBy(() -> adapter.validateAddress("wpkh([73c5da0a/84h/0h/0h]/0/*)"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No xpub/zpub");
        assertThat(requested).isEmpty();
    }

    @Test
    void validateAddress_acceptsEverySupportedFormat_offline() {
        var adapter = adapterWith(Map.of());

        assertThatCode(() -> adapter.validateAddress(XPUB)).doesNotThrowAnyException();
        assertThatCode(() -> adapter.validateAddress(ZPUB)).doesNotThrowAnyException();
        assertThatCode(() -> adapter.validateAddress(DESCRIPTOR)).doesNotThrowAnyException();
        assertThatCode(() -> adapter.validateAddress(PLAIN)).doesNotThrowAnyException();
        assertThat(requested).isEmpty();
    }

    // ─── Single address ───────────────────────────────────────────────────────

    @Test
    void singleAddress_returnsFundedMinusSpent() {
        var adapter = adapterWith(Map.of(PLAIN, ok(stats(250_000_000, 50_000_000, 7))));

        List<WalletBalance> balances = adapter.fetchBalances(PLAIN);

        assertThat(balances).singleElement().satisfies(b -> {
            assertThat(b.symbol()).isEqualTo("BTC");
            assertThat(b.amount()).isEqualByComparingTo(btc("2"));
        });
        assertThat(requested).containsExactly(PLAIN);
    }

    @Test
    void singleAddress_genuinelyEmpty_returnsZero() {
        var adapter = adapterWith(Map.of(PLAIN, ok(stats(30_000, 30_000, 4))));

        assertThat(adapter.fetchBalances(PLAIN).get(0).amount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void singleAddress_rateLimited_isWrappedAsWalletRpcException() {
        // A raw WebClientResponseException would land in WalletSyncService's
        // ERROR-as-genuine-bug branch; a 429 from Blockstream is an expected failure.
        var adapter = adapterWith(Map.of(PLAIN, status(HttpStatus.TOO_MANY_REQUESTS)));

        assertThatThrownBy(() -> adapter.fetchBalances(PLAIN))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("429")
            .hasCauseInstanceOf(WebClientResponseException.class);
    }

    @Test
    void singleAddress_timeout_isWrappedAsWalletRpcException() {
        var adapter = adapterWith(Map.of(PLAIN, Mono.error(new TimeoutException("simulated timeout"))));

        assertThatThrownBy(() -> adapter.fetchBalances(PLAIN))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("TimeoutException")
            .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void singleAddress_emptyBody_throws_insteadOfReadingAsZero() {
        var adapter = adapterWith(Map.of(PLAIN, status(HttpStatus.OK)));

        assertThatThrownBy(() -> adapter.fetchBalances(PLAIN))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("returned no response");
    }
}
