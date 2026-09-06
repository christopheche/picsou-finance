package com.picsou.service;

import com.picsou.config.EnableBankingConfigProvider;
import com.picsou.dto.BoursoBankHealthResponse;
import com.picsou.dto.EnableBankingTestResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationsHealthServiceTest {

    private static final String EB_BASE_URL = "https://eb.test";
    private static final String ENV_BOURSO_URL = "http://bourso-auth:8001";
    private static final KeyPair KEY_PAIR = rsaKeyPair();

    @Mock EnableBankingConfigProvider configProvider;
    @Mock SetupService setupService;

    @Test
    void testEnableBanking_refusesToCallOutWithoutAnApplicationId() {
        when(configProvider.applicationId()).thenReturn(Optional.empty());
        AtomicReference<ClientRequest> sent = new AtomicReference<>();

        EnableBankingTestResponse response = serviceWith(recording(sent, HttpStatus.OK)).testEnableBanking();

        assertThat(response.ok()).isFalse();
        assertThat(response.code()).isEqualTo("invalid_application_id");
        assertThat(sent.get()).isNull();
    }

    @Test
    void testEnableBanking_signsAJwtWithTheStoredKeyAndListsAspsps() {
        credentialsConfigured();
        AtomicReference<ClientRequest> sent = new AtomicReference<>();

        EnableBankingTestResponse response = serviceWith(recording(sent, HttpStatus.OK)).testEnableBanking();

        assertThat(response.ok()).isTrue();
        assertThat(response.code()).isEqualTo("ok");
        ClientRequest request = sent.get();
        assertThat(request.url().toString()).isEqualTo(EB_BASE_URL + "/aspsps?country=FR");
        String authorization = request.headers().getFirst("Authorization");
        assertThat(authorization).startsWith("Bearer ");
        Jws<Claims> jws = Jwts.parser()
            .verifyWith(KEY_PAIR.getPublic())
            .build()
            .parseSignedClaims(authorization.substring("Bearer ".length()));
        assertThat(jws.getHeader().getKeyId()).isEqualTo("key-1");
        assertThat(jws.getPayload().getIssuer()).isEqualTo("app-1");
        assertThat(jws.getPayload().getAudience()).containsExactly("api.enablebanking.com");
    }

    @Test
    void testEnableBanking_mapsUnauthorizedToInvalidKeyId() {
        credentialsConfigured();

        EnableBankingTestResponse response = serviceWith(answering(HttpStatus.UNAUTHORIZED)).testEnableBanking();

        assertThat(response.ok()).isFalse();
        assertThat(response.code()).isEqualTo("invalid_key_id");
    }

    @Test
    void testEnableBanking_mapsForbiddenToPublicKeyNotUploaded() {
        credentialsConfigured();

        EnableBankingTestResponse response = serviceWith(answering(HttpStatus.FORBIDDEN)).testEnableBanking();

        assertThat(response.ok()).isFalse();
        assertThat(response.code()).isEqualTo("public_key_not_uploaded");
    }

    @Test
    void testEnableBanking_mapsOtherHttpFailuresToUnknownWithTheStatus() {
        credentialsConfigured();

        EnableBankingTestResponse response = serviceWith(answering(HttpStatus.BAD_GATEWAY)).testEnableBanking();

        assertThat(response.ok()).isFalse();
        assertThat(response.code()).isEqualTo("unknown");
        assertThat(response.hint()).contains("502");
    }

    @Test
    void testEnableBanking_mapsConnectionFailuresToNetwork() {
        credentialsConfigured();

        EnableBankingTestResponse response = serviceWith(unreachable()).testEnableBanking();

        assertThat(response.ok()).isFalse();
        assertThat(response.code()).isEqualTo("network");
    }

    @Test
    void checkBoursoBankSidecar_prefersTheUrlStoredInTheDatabase() {
        when(setupService.readSetting(SetupService.KEY_BOURSO_AUTH_URL))
            .thenReturn(Optional.of("http://sidecar.lan:9001"));
        AtomicReference<ClientRequest> sent = new AtomicReference<>();

        BoursoBankHealthResponse response = serviceWith(recording(sent, HttpStatus.OK)).checkBoursoBankSidecar();

        assertThat(response.ok()).isTrue();
        assertThat(response.url()).isEqualTo("http://sidecar.lan:9001");
        assertThat(response.hint()).isNull();
        assertThat(sent.get().url().toString()).isEqualTo("http://sidecar.lan:9001/health");
    }

    @Test
    void checkBoursoBankSidecar_fallsBackToTheEnvironmentUrlWhenTheSettingIsBlank() {
        when(setupService.readSetting(SetupService.KEY_BOURSO_AUTH_URL)).thenReturn(Optional.of("  "));
        AtomicReference<ClientRequest> sent = new AtomicReference<>();

        BoursoBankHealthResponse response = serviceWith(recording(sent, HttpStatus.OK)).checkBoursoBankSidecar();

        assertThat(response.url()).isEqualTo(ENV_BOURSO_URL);
        assertThat(sent.get().url().toString()).isEqualTo(ENV_BOURSO_URL + "/health");
    }

    @Test
    void checkBoursoBankSidecar_reportsAnUnreachableSidecarWithItsUrl() {
        when(setupService.readSetting(SetupService.KEY_BOURSO_AUTH_URL)).thenReturn(Optional.empty());

        BoursoBankHealthResponse response = serviceWith(unreachable()).checkBoursoBankSidecar();

        assertThat(response.ok()).isFalse();
        assertThat(response.url()).isEqualTo(ENV_BOURSO_URL);
        assertThat(response.hint()).contains(ENV_BOURSO_URL);
    }

    private void credentialsConfigured() {
        when(configProvider.applicationId()).thenReturn(Optional.of("app-1"));
        when(configProvider.keyId()).thenReturn(Optional.of("key-1"));
        when(configProvider.privateKey()).thenReturn(Optional.of(KEY_PAIR.getPrivate()));
    }

    private IntegrationsHealthService serviceWith(ExchangeFunction exchange) {
        return new IntegrationsHealthService(
            configProvider,
            setupService,
            WebClient.builder().exchangeFunction(exchange),
            EB_BASE_URL,
            ENV_BOURSO_URL
        );
    }

    private static ExchangeFunction answering(HttpStatus status) {
        return request -> Mono.just(ClientResponse.create(status).build());
    }

    private static ExchangeFunction recording(AtomicReference<ClientRequest> sent, HttpStatus status) {
        return request -> {
            sent.set(request);
            return Mono.just(ClientResponse.create(status).build());
        };
    }

    private static ExchangeFunction unreachable() {
        return request -> Mono.error(new ConnectException("connection refused"));
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
