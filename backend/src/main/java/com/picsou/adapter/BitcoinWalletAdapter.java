package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.adapter.util.BitcoinKeyUtils;
import com.picsou.exception.WalletRpcException;
import com.picsou.model.Chain;
import com.picsou.port.WalletPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;

/**
 * Bitcoin wallet adapter using the Blockstream Esplora public API.
 *
 * Supports three input formats for the "address" field:
 *   - Plain address  (bc1q..., 1..., 3...) — single address lookup
 *   - xpub / zpub   — HD wallet extended public key; all derived addresses are scanned
 *   - Output descriptor  wpkh([fingerprint/path]xpub.../chain/*)#checksum — Proton Wallet format
 *
 * For extended keys the adapter derives P2WPKH addresses (BIP84) along both the external
 * (m/0/*) and internal/change (m/1/*) chains and stops after {@link BitcoinKeyUtils#GAP_LIMIT}
 * consecutive unused addresses per chain (BIP44 standard). Keys of another script type
 * (ypub, pkh(, sh() are rejected up front — see {@link BitcoinKeyUtils#rejectUnsupportedScriptType}.
 *
 * <p>Same rule as the other on-chain adapters: a failed fetch is never read as a 0 balance.
 * A transport error, an empty body or a malformed Esplora response on <em>any</em> of the
 * scanned addresses, and a stored key that no longer parses, all surface as
 * {@link WalletRpcException} so {@code WalletSyncService} leaves the wallet's last balance
 * and holdings untouched. Only a wallet whose addresses genuinely hold nothing returns 0.
 */
@Component
public class BitcoinWalletAdapter implements WalletPort {

    private static final Logger log = LoggerFactory.getLogger(BitcoinWalletAdapter.class);
    private static final String BASE_URL = "https://blockstream.info";
    private static final BigDecimal SATS_PER_BTC = new BigDecimal("100000000");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;

    public BitcoinWalletAdapter() {
        this(WebClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader("Accept", "application/json")
            .build());
    }

    // Package-private seam for tests: inject a WebClient backed by an ExchangeFunction.
    BitcoinWalletAdapter(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Chain chain() {
        return Chain.BITCOIN;
    }

    /**
     * Pre-persist gate called by {@code WalletSyncService.addWallet}: an extended key of a
     * script type this adapter cannot derive, or one that does not parse, is a <em>bad
     * request</em> (400) naming the expected formats — not a 422 "try again later" for input
     * that can never sync. Plain addresses are still left to the explorer (several encodings,
     * not worth checking offline). Everything here is offline; no Esplora call is made.
     */
    @Override
    public void validateAddress(String address) {
        BitcoinKeyUtils.rejectUnsupportedScriptType(address);
        if (BitcoinKeyUtils.isExtendedKey(address)) {
            try {
                BitcoinKeyUtils.parseXpub(BitcoinKeyUtils.normalizeToXpub(address));
            } catch (IllegalArgumentException ex) {
                // Never echo the key: the message is the 400 body and is logged.
                throw new IllegalArgumentException(
                    "Invalid Bitcoin extended key: " + ex.getMessage()
                        + " (expected an xpub/zpub or a wpkh(...) descriptor)", ex);
            }
        }
    }

    @Override
    public List<WalletBalance> fetchBalances(String address) {
        // The same check as validateAddress, for an address that is *already stored* (written
        // before the gate existed). That is a sync problem (422), not a bad request -- and it
        // must throw rather than fall through to a bc1q scan that reads as 0 BTC.
        try {
            BitcoinKeyUtils.rejectUnsupportedScriptType(address);
        } catch (IllegalArgumentException ex) {
            throw new WalletRpcException(ex.getMessage(), ex);
        }
        WalletBalance btc = BitcoinKeyUtils.isExtendedKey(address)
            ? fetchExtendedKeyBalance(address)
            : fetchSingleAddressBalance(address);
        return List.of(btc);
    }

    // ─── Single address ───────────────────────────────────────────────────────

    private WalletBalance fetchSingleAddressBalance(String address) {
        AddressStats stats = fetchAddressStats(address);
        BigDecimal btc = satsToBtc(stats.balanceSats());
        log.info("Bitcoin balance for {}: {} BTC", address, btc);
        return new WalletBalance("BTC", btc);
    }

    // ─── Extended key (xpub / zpub / descriptor) ──────────────────────────────

    private WalletBalance fetchExtendedKeyBalance(String input) {
        BitcoinKeyUtils.Xpub root = parseStoredKey(input);

        long totalSats = 0;
        // Scan external chain (m/0/*) and internal/change chain (m/1/*). Any failure inside
        // propagates as WalletRpcException: a partial scan must never be summed into a balance.
        for (int chain = 0; chain <= 1; chain++) {
            BitcoinKeyUtils.Xpub chainKey = BitcoinKeyUtils.deriveChild(root, chain);
            totalSats += scanChain(chainKey, chain == 0 ? "external" : "change");
        }

        BigDecimal btc = satsToBtc(totalSats);
        log.info("Bitcoin HD wallet balance for [xpub]: {} BTC ({} sats total)", btc, totalSats);
        return new WalletBalance("BTC", btc);
    }

    /**
     * Parses a stored extended key. A key that no longer parses (bad checksum, wrong length,
     * a descriptor without a key) is an expected sync failure -- WARN + 422 -- not a bug, and
     * certainly not an empty wallet. The message never contains the key.
     */
    private static BitcoinKeyUtils.Xpub parseStoredKey(String input) {
        try {
            return BitcoinKeyUtils.parseXpub(BitcoinKeyUtils.normalizeToXpub(input));
        } catch (IllegalArgumentException ex) {
            throw new WalletRpcException("Bitcoin extended key could not be parsed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Scans a single BIP32 chain (external or change) until GAP_LIMIT consecutive unused
     * addresses are found. Returns the total balance in satoshis.
     */
    private long scanChain(BitcoinKeyUtils.Xpub chainKey, String chainName) {
        long totalSats = 0;
        int consecutiveUnused = 0;
        int index = 0;

        while (consecutiveUnused < BitcoinKeyUtils.GAP_LIMIT) {
            BitcoinKeyUtils.Xpub childKey = BitcoinKeyUtils.deriveChild(chainKey, index);
            String address = BitcoinKeyUtils.toP2WPKHAddress(childKey.pubKey());

            AddressStats stats = fetchAddressStats(address);

            if (stats.txCount() > 0) {
                consecutiveUnused = 0;
                if (stats.balanceSats() > 0) {
                    totalSats += stats.balanceSats();
                    log.debug("Bitcoin {} chain index {}: {} sats ({})", chainName, index, stats.balanceSats(), address);
                }
            } else {
                consecutiveUnused++;
            }

            index++;
        }

        log.info("Bitcoin {} chain: scanned {} addresses, {} with history", chainName, index, index - consecutiveUnused);
        return totalSats;
    }

    // ─── Blockstream API ──────────────────────────────────────────────────────

    private record AddressStats(long balanceSats, long txCount) {}

    /**
     * One Esplora {@code /api/address/{address}} call with the shared error classification:
     * anything the transport raises (HTTP 4xx/5xx, connection reset, timeout) is wrapped as a
     * {@link WalletRpcException} inside the chain -- before {@code block()}, which would
     * otherwise deliver a checked {@code TimeoutException} reactor-wrapped -- so
     * {@code WalletSyncService} logs it as an expected WARN/422 rather than an ERROR-level bug.
     * An empty body is a failure too, never "no history": on an HD scan it would both drop
     * that address's balance and count toward the gap limit, ending the scan early.
     */
    private AddressStats fetchAddressStats(String address) {
        String context = "Bitcoin Esplora address " + address;
        JsonNode response = webClient.get()
            .uri("/api/address/{address}", address)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(TIMEOUT)
            .onErrorMap(ex -> ex instanceof WalletRpcException ? ex
                : new WalletRpcException(
                    context + ": request failed (" + ex.getClass().getSimpleName() + ") - " + ex.getMessage(), ex))
            .switchIfEmpty(Mono.error(new WalletRpcException(context + ": returned no response")))
            .block();

        // block() on a non-empty Mono never yields null; a shape change of the response would.
        // Esplora always emits chain_stats with all three counters, so their absence is a
        // broken response, not an unused address -- asLong(0) must not read it as one.
        JsonNode chainStats = response == null ? null : response.get("chain_stats");
        if (chainStats == null || !chainStats.isObject()) {
            throw new WalletRpcException(context + ": response has no chain_stats");
        }

        long funded  = chainStats.path("funded_txo_sum").asLong(0);
        long spent   = chainStats.path("spent_txo_sum").asLong(0);
        long txCount = chainStats.path("tx_count").asLong(0);

        return new AddressStats(funded - spent, txCount);
    }

    private BigDecimal satsToBtc(long sats) {
        return new BigDecimal(sats).divide(SATS_PER_BTC, 8, RoundingMode.HALF_UP);
    }
}
