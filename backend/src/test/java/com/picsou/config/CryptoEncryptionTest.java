package com.picsou.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain JUnit, no Spring: {@code CryptoEncryption} guards every stored provider
 * secret and is otherwise only ever mocked, so a broken IV offset or key check
 * would go unnoticed by the rest of the suite.
 */
class CryptoEncryptionTest {

    private static String key(int bytes) {
        byte[] raw = new byte[bytes];
        for (int i = 0; i < bytes; i++) raw[i] = (byte) (i * 7 + 3);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static final String KEY_32 = key(32);

    @Test
    void roundTrip_returnsThePlaintext_includingNonAscii() {
        CryptoEncryption crypto = new CryptoEncryption(KEY_32);

        assertThat(crypto.decrypt(crypto.encrypt("clé€ secret"))).isEqualTo("clé€ secret");
        assertThat(crypto.decrypt(crypto.encrypt(""))).isEmpty();
    }

    @Test
    void encrypt_usesAFreshIvEachCall_soCiphertextsDiffer_butBothDecrypt() {
        CryptoEncryption crypto = new CryptoEncryption(KEY_32);

        String first = crypto.encrypt("same");
        String second = crypto.encrypt("same");

        assertThat(first).isNotEqualTo(second);
        assertThat(crypto.decrypt(first)).isEqualTo("same");
        assertThat(crypto.decrypt(second)).isEqualTo("same");
    }

    @Test
    void decrypt_rejectsATamperedCiphertext() {
        CryptoEncryption crypto = new CryptoEncryption(KEY_32);
        byte[] combined = Base64.getDecoder().decode(crypto.encrypt("payload"));
        combined[12] ^= 0x01; // first byte after the 12-byte IV
        String tampered = Base64.getEncoder().encodeToString(combined);

        assertThatThrownBy(() -> crypto.decrypt(tampered))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("Decryption failed");
    }

    @Test
    void decrypt_rejectsACiphertextFromAnotherKey() {
        String cipherText = new CryptoEncryption(KEY_32).encrypt("payload");
        CryptoEncryption other = new CryptoEncryption(key(16));

        assertThatThrownBy(() -> other.decrypt(cipherText))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("Decryption failed");
    }

    @Test
    void nullInNullOut_bothDirections() {
        CryptoEncryption crypto = new CryptoEncryption(KEY_32);

        assertThat(crypto.encrypt(null)).isNull();
        assertThat(crypto.decrypt(null)).isNull();
    }

    @Test
    void constructor_acceptsEveryAesKeySize() {
        for (int bytes : new int[] {16, 24, 32}) {
            CryptoEncryption crypto = new CryptoEncryption(key(bytes));
            assertThat(crypto.decrypt(crypto.encrypt("x"))).isEqualTo("x");
        }
    }

    @Test
    void constructor_blankKey_failsFastWithTheGeneratorHint() {
        assertThatThrownBy(() -> new CryptoEncryption("  "))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("openssl rand -base64 32");
    }

    @Test
    void constructor_wrongLengthKey_failsAtStartupNotOnFirstUse() {
        // A hand-typed key or the 48-byte JWT secret pasted by mistake: ADR 2026-04-08 wants
        // the instance to refuse to start, not to 500 on the first TOTP enrolment.
        assertThatThrownBy(() -> new CryptoEncryption(key(20)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("16, 24 or 32 bytes")
            .hasMessageContaining("got 20");
        assertThatThrownBy(() -> new CryptoEncryption(key(48)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("got 48");
    }

    @Test
    void constructor_nonBase64Key_failsAtStartup() {
        assertThatThrownBy(() -> new CryptoEncryption("not*base64*at*all"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not valid Base64");
    }
}
