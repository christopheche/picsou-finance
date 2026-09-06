package com.picsou.adapter;

import com.picsou.config.EnableBankingConfigProvider;
import com.picsou.exception.SyncException;
import com.picsou.port.BankConnectorPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnableBankingBankConnectorTest {

    @Mock EnableBankingConfigProvider configProvider;

    private EnableBankingBankConnector connector() {
        return new EnableBankingBankConnector(configProvider, "https://api.enablebanking.test");
    }

    /** One RSA key for the whole class: generating a 2048-bit pair per test is the slow part. */
    private static final java.security.PrivateKey SIGNING_KEY = generateKey();

    private static java.security.PrivateKey generateKey() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair().getPrivate();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A connector whose HTTP calls are answered by {@code route}, keyed on the request path. */
    private EnableBankingBankConnector connectorRouting(
        java.util.function.Function<String, String> route) {
        lenient().when(configProvider.applicationId()).thenReturn(Optional.of("app-id"));
        lenient().when(configProvider.keyId()).thenReturn(Optional.of("key-id"));
        lenient().when(configProvider.privateKey()).thenReturn(Optional.of(SIGNING_KEY));

        org.springframework.web.reactive.function.client.ExchangeFunction exchange = request -> {
            String body = route.apply(request.url().getPath());
            if (body == null) {
                return reactor.core.publisher.Mono.just(
                    org.springframework.web.reactive.function.client.ClientResponse
                        .create(org.springframework.http.HttpStatus.NOT_FOUND)
                        .header("Content-Type", "application/json").body("{}").build());
            }
            return reactor.core.publisher.Mono.just(
                org.springframework.web.reactive.function.client.ClientResponse
                    .create(org.springframework.http.HttpStatus.OK)
                    .header("Content-Type", "application/json").body(body).build());
        };
        return new EnableBankingBankConnector(configProvider,
            org.springframework.web.reactive.function.client.WebClient.builder()
                .baseUrl("https://api.enablebanking.test")
                .exchangeFunction(exchange)
                .build());
    }

    private static final String SESSION_WITH_ONE_ACCOUNT =
        "{\"session_id\":\"session-1\",\"accounts\":[\"acc-1\"]}";

    // ─── Missing balances ─────────────────────────────────────────────────────

    /**
     * The reported failure mode: a bank's PSD2 API answers 200 with an empty {@code balances}
     * list — common while a freshly-linked account is still propagating, or when consent covered
     * details but not balances. Read as {@code 0.00} it overwrote a real balance and stamped a
     * zero into that day's snapshot, which nothing later goes back to fix.
     */
    @Test
    void fetchBalances_reportsNoBalance_ratherThanZero_whenTheBankReturnsAnEmptyBalanceList() {
        var connector = connectorRouting(path -> switch (path) {
            case "/sessions/session-1" -> SESSION_WITH_ONE_ACCOUNT;
            case "/accounts/acc-1/balances" -> "{\"balances\":[]}";
            case "/accounts/acc-1/details" -> "{\"account\":{\"name\":\"Compte courant\",\"iban\":\"FR76\"}}";
            default -> null;
        });

        assertThat(connector.fetchBalances("session-1"))
            .singleElement()
            .satisfies(account -> {
                assertThat(account.externalId()).isEqualTo("acc-1");
                assertThat(account.balance()).isNull();
            });
    }

    /** Same refusal for an item that exists but carries no {@code balance_amount}. */
    @Test
    void fetchBalances_reportsNoBalance_whenTheBalanceItemCarriesNoAmount() {
        var connector = connectorRouting(path -> switch (path) {
            case "/sessions/session-1" -> SESSION_WITH_ONE_ACCOUNT;
            case "/accounts/acc-1/balances" -> "{\"balances\":[{\"balance_type\":\"closingBooked\"}]}";
            case "/accounts/acc-1/details" -> "{\"account\":{\"name\":\"Livret A\"}}";
            default -> null;
        });

        assertThat(connector.fetchBalances("session-1")).singleElement()
            .extracting(BankConnectorPort.AccountData::balance).isNull();
    }

    @Test
    void fetchBalances_prefersTheBookedBalance_andReadsItsCurrency() {
        var connector = connectorRouting(path -> switch (path) {
            case "/sessions/session-1" -> SESSION_WITH_ONE_ACCOUNT;
            case "/accounts/acc-1/balances" -> "{\"balances\":["
                + "{\"balance_type\":\"interimAvailable\",\"balance_amount\":{\"amount\":\"10.00\",\"currency\":\"EUR\"}},"
                + "{\"balance_type\":\"closingBooked\",\"balance_amount\":{\"amount\":\"3200.45\",\"currency\":\"EUR\"}}]}";
            case "/accounts/acc-1/details" -> "{\"account\":{\"name\":\"Compte courant\"}}";
            default -> null;
        });

        var account = connector.fetchBalances("session-1").get(0);

        assertThat(account.balance()).isEqualByComparingTo("3200.45");
        assertThat(account.currency()).isEqualTo("EUR");
    }

    @Test
    void searchInstitutions_missingPrivateKey_namesTheKey_notGenericNotConfigured() {
        // The reported bug: app-id/key-id present (in DB) but the key file is absent.
        lenient().when(configProvider.applicationId()).thenReturn(Optional.of("app-id"));
        lenient().when(configProvider.keyId()).thenReturn(Optional.of("key-id"));
        when(configProvider.privateKey()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> connector().searchInstitutions("ci", "FR"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("private key");
    }

    @Test
    void searchInstitutions_missingApplicationId_namesApplicationId() {
        lenient().when(configProvider.keyId()).thenReturn(Optional.of("key-id"));
        when(configProvider.applicationId()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> connector().searchInstitutions("ci", "FR"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("Application ID");
    }

    // ─── PSU type resolution ──────────────────────────────────────────────────

    /**
     * The reported bug: Swan is published under "business" only, so asking Enable
     * Banking for psu_type=personal made it invisible in the bank picker even though
     * the account existed and the credentials were valid.
     */
    @Test
    void resolvePsuType_businessWhenTheBankOffersNothingElse() {
        assertThat(EnableBankingBankConnector.resolvePsuType(List.of("business"))).isEqualTo("business");
    }

    @Test
    void resolvePsuType_prefersPersonalWheneverTheBankOffersIt() {
        assertThat(EnableBankingBankConnector.resolvePsuType(List.of("business", "personal"))).isEqualTo("personal");
    }

    /** An ASPSP that declares nothing is treated as retail — the pre-existing behaviour. */
    @Test
    void resolvePsuType_defaultsToPersonalWhenUnknown() {
        assertThat(EnableBankingBankConnector.resolvePsuType(null)).isEqualTo("personal");
        assertThat(EnableBankingBankConnector.resolvePsuType(List.of())).isEqualTo("personal");
    }

    /** An unrecognised type is passed through, not mistranslated into "business". */
    @Test
    void resolvePsuType_passesThroughAnUnknownProviderValue() {
        assertThat(EnableBankingBankConnector.resolvePsuType(List.of("corporate"))).isEqualTo("corporate");
    }

    // ─── Catalog mapping ──────────────────────────────────────────────────────

    @Test
    void toInstitutions_filtersByNameAndEncodesPsuTypeInTheId() {
        var swan = new EnableBankingBankConnector.AspspResponse(
            "Swan", "SWNBFR22", "https://logos.example/swan.png", "FR", List.of("business"));
        var bnp = new EnableBankingBankConnector.AspspResponse(
            "BNP Paribas", "BNPAFRPP", "https://logos.example/bnp.png", "FR", List.of("personal"));

        var results = EnableBankingBankConnector.toInstitutions(List.of(swan, bnp), "swan", "FR");

        assertThat(results).singleElement().satisfies(i -> {
            assertThat(i.id()).isEqualTo("Swan::FR::business");
            assertThat(i.name()).isEqualTo("Swan");
            assertThat(i.psuType()).isEqualTo("business");
            assertThat(i.country()).isEqualTo("FR");
        });
    }

    /** Enable Banking can list the same bank twice (different auth methods) -- one row, one React key. */
    @Test
    void toInstitutions_deduplicatesIdenticalCompositeIds() {
        var first = new EnableBankingBankConnector.AspspResponse(
            "Swan", "SWNBFR22", "https://logos.example/swan.png", "FR", List.of("business"));
        var duplicate = new EnableBankingBankConnector.AspspResponse(
            "Swan", "SWNBFR22", null, "FR", List.of("business"));

        var results = EnableBankingBankConnector.toInstitutions(List.of(first, duplicate), "swan", "FR");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).logoUrl()).isEqualTo("https://logos.example/swan.png");
    }

    /**
     * The reverse order of the test above: keeping the first entry unconditionally would
     * publish a null logo for a bank whose second listing carries one, and the picker has
     * no second chance -- it renders whatever this returns.
     */
    @Test
    void toInstitutions_keepsTheLogoWhenOnlyTheLaterDuplicateCarriesOne() {
        var logoless = new EnableBankingBankConnector.AspspResponse(
            "Swan", "SWNBFR22", null, "FR", List.of("business"));
        var withLogo = new EnableBankingBankConnector.AspspResponse(
            "Swan", "SWNBFR22", "https://logos.example/swan.png", "FR", List.of("business"));

        var results = EnableBankingBankConnector.toInstitutions(List.of(logoless, withLogo), "swan", "FR");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).logoUrl()).isEqualTo("https://logos.example/swan.png");
    }

    @Test
    void toInstitutions_fallsBackToTheRequestedCountryWhenTheAspspOmitsIt() {
        var noCountry = new EnableBankingBankConnector.AspspResponse(
            "Swan", null, null, null, List.of("business"));

        var results = EnableBankingBankConnector.toInstitutions(List.of(noCountry), "", "FR");

        assertThat(results).singleElement().satisfies(i -> {
            assertThat(i.country()).isEqualTo("FR");
            assertThat(i.id()).isEqualTo("Swan::FR::business");
        });
    }

    // ─── Institution id parsing ───────────────────────────────────────────────

    @Test
    void parseInstitutionId_readsTheThirdSegment() {
        var ref = EnableBankingBankConnector.parseInstitutionId("Swan::FR::business");

        assertThat(ref.bankName()).isEqualTo("Swan");
        assertThat(ref.country()).isEqualTo("FR");
        assertThat(ref.psuType()).isEqualTo("business");
    }

    /** Requisitions linked before PSU types existed store two segments only. */
    @Test
    void parseInstitutionId_defaultsLegacyTwoSegmentIdsToPersonal() {
        var ref = EnableBankingBankConnector.parseInstitutionId("BoursoBank::FR");

        assertThat(ref.bankName()).isEqualTo("BoursoBank");
        assertThat(ref.country()).isEqualTo("FR");
        assertThat(ref.psuType()).isEqualTo("personal");
    }

    /** The id comes off the wire and its PSU segment lands in an outbound provider request. */
    @Test
    void parseInstitutionId_coercesAnUnexpectedPsuSegmentToPersonal() {
        assertThat(EnableBankingBankConnector.parseInstitutionId("Swan::FR::../../etc").psuType())
            .isEqualTo("personal");
    }
}
