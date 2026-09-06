package com.picsou.controller;

import com.picsou.config.EnableBankingConfigProvider;
import com.picsou.dto.AdminEnableBankingRequest;
import com.picsou.dto.AdminSecurityRequest;
import com.picsou.dto.EnableBankingImportRequest;
import com.picsou.dto.EnableBankingKeypairResponse;
import com.picsou.exception.InvalidKeyMaterialException;
import com.picsou.service.EnableBankingKeyPairService;
import com.picsou.service.IntegrationsService;
import com.picsou.service.SetupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.List;
import java.util.Optional;

import static com.picsou.service.SetupService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock SetupService setupService;
    @Mock IntegrationsService integrationsService;
    @Mock EnableBankingConfigProvider ebConfigProvider;
    @Mock EnableBankingKeyPairService keyPairService;

    @InjectMocks AdminController controller;

    @Test
    void getSettings_returnsAssembledResponse() {
        when(setupService.readSetting(KEY_CORS_ALLOWED_ORIGINS)).thenReturn(Optional.of("https://a.com,https://b.com"));
        when(setupService.readSetting(KEY_SECURE_COOKIES)).thenReturn(Optional.of("true"));
        // EB credentials now come from the resolved provider (DB-then-env), the same
        // source the connector uses — not raw setupService.readSetting calls.
        when(ebConfigProvider.applicationId()).thenReturn(Optional.of("app-id"));
        when(ebConfigProvider.redirectUri()).thenReturn(Optional.of("https://app.com/callback"));
        when(ebConfigProvider.privateKeyPresent()).thenReturn(true);
        for (String key : INTEGRATIONS) {
            when(integrationsService.isEffectivelyEnabled(key)).thenReturn("enablebanking".equals(key));
        }

        var response = controller.getSettings();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.security().allowedOrigins()).containsExactly("https://a.com", "https://b.com");
        assertThat(body.security().secureCookies()).isTrue();
        assertThat(body.enableBanking().applicationId()).isEqualTo("app-id");
        assertThat(body.enableBanking().privateKeyPresent()).isTrue();
        assertThat(body.integrations()).containsEntry("enablebanking", true);
        assertThat(body.integrations()).containsEntry("finary", false);
    }

    @Test
    void getSettings_reportsMissingPrivateKey() {
        when(ebConfigProvider.privateKeyPresent()).thenReturn(false);

        var body = controller.getSettings().getBody();

        assertThat(body).isNotNull();
        assertThat(body.enableBanking().privateKeyPresent()).isFalse();
    }

    @Test
    void generateKeyPair_returnsPublicPem_andFlagsNewlyGenerated() {
        when(keyPairService.exists()).thenReturn(false);
        when(keyPairService.getOrGeneratePublicPem()).thenReturn("PUBLIC-PEM");

        var response = controller.generateEnableBankingKeyPair();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().publicKeyPem()).isEqualTo("PUBLIC-PEM");
        assertThat(response.getBody().regenerated()).isTrue();
    }

    @Test
    void generateKeyPair_existingKey_isNotFlaggedRegenerated() {
        when(keyPairService.exists()).thenReturn(true);
        when(keyPairService.getOrGeneratePublicPem()).thenReturn("EXISTING-PUBLIC");

        var body = controller.generateEnableBankingKeyPair().getBody();

        assertThat(body).isNotNull();
        assertThat(body.regenerated()).isFalse();
    }

    @Test
    void importPrivateKey_valid_returnsDerivedPublic() {
        when(keyPairService.importPrivateKey("PRIV")).thenReturn("DERIVED-PUBLIC");

        var response = controller.importEnableBankingPrivateKey(new EnableBankingImportRequest("PRIV"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new EnableBankingKeypairResponse("DERIVED-PUBLIC", false));
    }

    @Test
    void importPrivateKey_invalid_propagatesToTheGlobalHandler() {
        // No try/catch in the controller (error-handling.md): the service's
        // InvalidKeyMaterialException reaches GlobalExceptionHandler, which maps it to 422.
        when(keyPairService.importPrivateKey("bad"))
            .thenThrow(new InvalidKeyMaterialException("Not a valid PKCS#8 private key PEM."));

        assertThatThrownBy(() -> controller.importEnableBankingPrivateKey(new EnableBankingImportRequest("bad")))
            .isInstanceOf(InvalidKeyMaterialException.class)
            .hasMessage("Not a valid PKCS#8 private key PEM.");
    }

    @Test
    void updateSecurity_delegatesToSetupService() {
        var request = new AdminSecurityRequest(List.of("https://app.com"), true);
        var response = controller.updateSecurity(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(setupService).writeSecurity(List.of("https://app.com"), true);
    }

    @Test
    void updateEnableBanking_delegatesToSetupService() {
        var request = new AdminEnableBankingRequest("my-app", "https://app.com/cb");
        var response = controller.updateEnableBanking(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(setupService).writeEnableBankingConfig("my-app", "https://app.com/cb");
    }

    @Test
    void toggleIntegration_enable_callsEnable() {
        var response = controller.toggleIntegration("finary", true);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(integrationsService).enable("finary");
        verify(integrationsService, never()).disable(any());
    }

    @Test
    void toggleIntegration_disable_callsDisable() {
        var response = controller.toggleIntegration("enablebanking", false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(integrationsService).disable("enablebanking");
    }
}
