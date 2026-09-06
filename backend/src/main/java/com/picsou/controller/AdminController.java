package com.picsou.controller;

import com.picsou.config.EnableBankingConfigProvider;
import com.picsou.dto.AdminEnableBankingRequest;
import com.picsou.dto.AdminSecurityRequest;
import com.picsou.dto.AdminSettingsResponse;
import com.picsou.dto.EnableBankingImportRequest;
import com.picsou.dto.EnableBankingKeypairResponse;
import com.picsou.service.EnableBankingKeyPairService;
import com.picsou.service.IntegrationsService;
import com.picsou.service.SetupService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.picsou.service.SetupService.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SetupService setupService;
    private final IntegrationsService integrationsService;
    private final EnableBankingConfigProvider ebConfigProvider;
    private final EnableBankingKeyPairService keyPairService;
    private final String envAllowedOrigins;

    public AdminController(SetupService setupService,
                           IntegrationsService integrationsService,
                           EnableBankingConfigProvider ebConfigProvider,
                           EnableBankingKeyPairService keyPairService,
                           @Value("${app.cors.allowed-origins:}") String envAllowedOrigins) {
        this.setupService = setupService;
        this.integrationsService = integrationsService;
        this.ebConfigProvider = ebConfigProvider;
        this.keyPairService = keyPairService;
        this.envAllowedOrigins = envAllowedOrigins;
    }

    @GetMapping("/settings")
    public ResponseEntity<AdminSettingsResponse> getSettings() {
        List<String> origins = setupService.readSetting(KEY_CORS_ALLOWED_ORIGINS)
            .map(s -> Arrays.asList(s.split(",")))
            .orElse(List.of());
        boolean secureCookies = setupService.readSetting(KEY_SECURE_COOKIES)
            .map(Boolean::parseBoolean).orElse(false);
        // Read the *resolved* config (DB first, then env-var fallback) — the same
        // source the connector uses — so an install configured purely via .env
        // doesn't show empty fields / a false "not configured" banner.
        String appId = ebConfigProvider.applicationId().orElse("");
        String redirectUri = ebConfigProvider.redirectUri().orElse("");

        // Effective state, not the raw wizard flag: an integration configured
        // purely via .env / compose (and thus live) reports on even though its
        // DB toggle was never flipped by the wizard.
        Map<String, Boolean> integrations = new LinkedHashMap<>();
        for (String name : INTEGRATIONS) {
            integrations.put(name, integrationsService.isEffectivelyEnabled(name));
        }

        return ResponseEntity.ok(new AdminSettingsResponse(
            new AdminSettingsResponse.SecuritySettings(origins, secureCookies),
            new AdminSettingsResponse.EnableBankingSettings(
                appId, redirectUri, ebConfigProvider.privateKeyPresent()),
            integrations
        ));
    }

    @PutMapping("/settings/security")
    public ResponseEntity<Void> updateSecurity(@Valid @RequestBody AdminSecurityRequest request) {
        setupService.writeSecurity(request.allowedOrigins(), request.secureCookies());
        return ResponseEntity.noContent().build();
    }

    /**
     * Overwrites the persisted CORS origins with the live value of the
     * {@code ALLOWED_ORIGINS} env var. Useful when the operator has changed
     * the public URL (e.g. moved from http to https) but the wizard already
     * locked a stale value into {@code app_setting}.
     */
    @PostMapping("/settings/cors/reload-from-env")
    public ResponseEntity<Map<String, Object>> reloadCorsFromEnv() {
        setupService.reloadCorsFromEnv(envAllowedOrigins);
        List<String> origins = Arrays.stream(envAllowedOrigins.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return ResponseEntity.ok(Map.of("allowedOrigins", origins));
    }

    @PutMapping("/settings/enablebanking")
    public ResponseEntity<Void> updateEnableBanking(@Valid @RequestBody AdminEnableBankingRequest request) {
        setupService.writeEnableBankingConfig(request.applicationId(), request.redirectUri());
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the current public key PEM, generating a new pair on disk only if
     * none exists yet. Idempotent — re-calling never invalidates a public key
     * already uploaded to the Enable Banking dashboard. Mirrors the setup-wizard
     * endpoint but without the "setup not complete" guard, so an operator can
     * recover a missing key after first-run setup is done.
     */
    @PostMapping("/settings/enablebanking/keypair")
    public ResponseEntity<EnableBankingKeypairResponse> generateEnableBankingKeyPair() {
        boolean existedBefore = keyPairService.exists();
        String publicPem = keyPairService.getOrGeneratePublicPem();
        return ResponseEntity.ok(new EnableBankingKeypairResponse(publicPem, !existedBefore));
    }

    /** A bad PEM raises {@code InvalidKeyMaterialException}, mapped to 422 by {@code GlobalExceptionHandler}. */
    @PostMapping("/settings/enablebanking/keypair/import")
    public ResponseEntity<EnableBankingKeypairResponse> importEnableBankingPrivateKey(
            @Valid @RequestBody EnableBankingImportRequest request) {
        String publicPem = keyPairService.importPrivateKey(request.privatePem());
        return ResponseEntity.ok(new EnableBankingKeypairResponse(publicPem, false));
    }

    @PatchMapping("/settings/integrations/{key}")
    public ResponseEntity<Void> toggleIntegration(@PathVariable String key, @RequestParam boolean enabled) {
        if (enabled) integrationsService.enable(key);
        else integrationsService.disable(key);
        return ResponseEntity.noContent().build();
    }
}
