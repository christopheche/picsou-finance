package com.picsou.exception;

import com.picsou.service.ReAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void accessDenied_isA403WithTheServiceMessage_notTheGeneric500() {
        // AccountAccessResolver.requireOwner / FamilyViewService.getGoalContributions throw
        // this for "may read but not write"; inside the DispatcherServlet it never reaches
        // ExceptionTranslationFilter, so without a handler it would surface as a 500.
        ProblemDetail pd = handler.handleAccessDenied(
            new AccessDeniedException("Only the owning member can modify this account"));

        assertThat(pd.getStatus()).isEqualTo(403);
        assertThat(pd.getTitle()).isEqualTo("Forbidden");
        assertThat(pd.getDetail()).isEqualTo("Only the owning member can modify this account");
    }

    @Test
    void invalidKeyMaterial_isA422WithTheFixedMessage() {
        ProblemDetail pd = handler.handleInvalidKeyMaterial(new InvalidKeyMaterialException(
            "The PEM could not be parsed as an RSA PKCS#8 private key.",
            new java.security.spec.InvalidKeySpecException("invalid key format")));

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getDetail())
            .isEqualTo("The PEM could not be parsed as an RSA PKCS#8 private key.")
            .doesNotContain("Exception");
    }

    @Test
    void reAuthFailed_isA401WithAStableCode_andTheStandardTitle() {
        ProblemDetail pd = handler.handleReAuthFailed(
            new ReAuthService.ReAuthFailedException("Your password is incorrect."));

        assertThat(pd.getStatus()).isEqualTo(401);
        // api-rest.md: machine-readable markers travel in `code`, the title stays the reason phrase.
        assertThat(pd.getProperties()).containsEntry("code", "REAUTH_FAILED");
        assertThat(pd.getTitle()).isEqualTo("Unauthorized");
        assertThat(pd.getDetail()).isEqualTo("Your password is incorrect.");
    }

    @Test
    void generic_neverLeaksTheCause() {
        ProblemDetail pd = handler.handleGeneric(new IllegalStateException("db down at 10.0.0.1"));

        assertThat(pd.getStatus()).isEqualTo(500);
        assertThat(pd.getDetail()).isEqualTo("An unexpected error occurred");
    }
}
