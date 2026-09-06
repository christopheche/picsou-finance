package com.picsou.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean-validation contract of the two credential bodies whose fields are encrypted before they
 * are written to a {@code varchar(500)} column ({@link IbkrConnectRequest},
 * {@link FinaryLoginRequest}).
 *
 * <p>Two failure modes are pinned here, both of which used to be a generic 500: a missing field
 * (the column is {@code NOT NULL}, and {@code CryptoEncryption.encrypt} returns null for null),
 * and a plaintext long enough that its Base64 AES-GCM ciphertext — roughly
 * {@code 4/3 * (n + 28)} characters — overflows the column.
 */
class EncryptedCredentialRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<String> violatedFields(Object body) {
        Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(body);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    // ─── IbkrConnectRequest (POST /api/ibkr/connect) ─────────────────────────

    @Test
    void ibkr_realisticTokenAndQueryId_pass() {
        assertThat(violatedFields(new IbkrConnectRequest("12345678901234567890", "654321"))).isEmpty();
    }

    @Test
    void ibkr_blankFields_areRejected() {
        assertThat(violatedFields(new IbkrConnectRequest(" ", null)))
            .containsExactlyInAnyOrder("token", "queryId");
    }

    @Test
    void ibkr_tokenTooLongForTheCiphertextColumn_isRejected() {
        // 400 plaintext chars encrypt to ~570 Base64 chars — past ibkr_connection.token's 500.
        assertThat(violatedFields(new IbkrConnectRequest("x".repeat(400), "654321")))
            .containsExactly("token");
    }

    @Test
    void ibkr_queryIdTooLongForTheCiphertextColumn_isRejected() {
        assertThat(violatedFields(new IbkrConnectRequest("token", "y".repeat(201))))
            .containsExactly("queryId");
    }

    // ─── FinaryLoginRequest (POST /api/finary/login) ─────────────────────────

    @Test
    void finary_credentials_pass() {
        assertThat(violatedFields(new FinaryLoginRequest("user@example.com", "hunter2"))).isEmpty();
    }

    @Test
    void finary_missingPassword_isRejected() {
        // finary_session.password is NOT NULL: this used to be a 500 at INSERT.
        assertThat(violatedFields(new FinaryLoginRequest("user@example.com", null)))
            .containsExactly("password");
    }

    @Test
    void finary_missingEmail_isRejected() {
        assertThat(violatedFields(new FinaryLoginRequest("  ", "hunter2"))).containsExactly("email");
    }

    @Test
    void finary_credentialsTooLongForTheCiphertextColumn_areRejected() {
        assertThat(violatedFields(new FinaryLoginRequest("a".repeat(255), "b".repeat(201))))
            .containsExactlyInAnyOrder("email", "password");
    }
}
