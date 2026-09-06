package com.picsou.service;

import com.picsou.exception.InvalidKeyMaterialException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnableBankingKeyPairServiceTest {

    @Test
    void firstCall_generatesKeyPairAndWritesPrivatePem(@TempDir Path dir) throws Exception {
        Path privPath = dir.resolve("enablebanking-private.pem");
        EnableBankingKeyPairService svc = new EnableBankingKeyPairService(privPath.toString());

        assertThat(svc.exists()).isFalse();
        String publicPem = svc.getOrGeneratePublicPem();

        assertThat(publicPem).contains("-----BEGIN PUBLIC KEY-----");
        assertThat(publicPem).contains("-----END PUBLIC KEY-----");
        assertThat(Files.exists(privPath)).isTrue();
        assertThat(Files.readString(privPath)).contains("-----BEGIN PRIVATE KEY-----");
    }

    @Test
    void secondCall_isIdempotent_andReturnsTheSamePublicKey(@TempDir Path dir) {
        Path privPath = dir.resolve("enablebanking-private.pem");
        EnableBankingKeyPairService svc = new EnableBankingKeyPairService(privPath.toString());

        String first = svc.getOrGeneratePublicPem();
        String second = svc.getOrGeneratePublicPem();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void privateKeyFile_hasOwnerOnlyPermissions_onPosixSystems(@TempDir Path dir) throws Exception {
        Path privPath = dir.resolve("enablebanking-private.pem");
        EnableBankingKeyPairService svc = new EnableBankingKeyPairService(privPath.toString());
        svc.getOrGeneratePublicPem();

        if (!Files.getFileStore(privPath).supportsFileAttributeView("posix")) {
            return; // Skip on Windows/FAT — service makes best effort only.
        }
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(privPath);
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        );
    }

    @Test
    void write_leavesNoTempFileBehind_andCreatesTheDirectoryOwnerOnly(@TempDir Path dir) throws Exception {
        Path keysDir = dir.resolve("keys");
        Path privPath = keysDir.resolve("enablebanking-private.pem");
        new EnableBankingKeyPairService(privPath.toString()).getOrGeneratePublicPem();

        // Written via a 0600 temp file + atomic move: only the final PEM may remain.
        try (var entries = Files.list(keysDir)) {
            assertThat(entries).containsExactly(privPath);
        }
        if (Files.getFileStore(keysDir).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(keysDir)).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            );
        }
    }

    @Test
    void importPrivateKey_validPkcs8_replacesTheExistingFile_andKeepsOwnerOnlyPermissions(@TempDir Path dir) throws Exception {
        Path privPath = dir.resolve("enablebanking-private.pem");
        EnableBankingKeyPairService svc = new EnableBankingKeyPairService(privPath.toString());
        String generatedPublic = svc.getOrGeneratePublicPem();

        // A second, independently generated key is what an operator imports from Enable Banking.
        Path otherDir = dir.resolve("other");
        EnableBankingKeyPairService other = new EnableBankingKeyPairService(
            otherDir.resolve("k.pem").toString());
        String importedPublic = other.getOrGeneratePublicPem();
        String importedPrivatePem = Files.readString(otherDir.resolve("k.pem"));

        String derived = svc.importPrivateKey(importedPrivatePem);

        assertThat(derived).isEqualTo(importedPublic).isNotEqualTo(generatedPublic);
        // importPrivateKey persists the stripped PEM (no trailing newline) — unchanged behaviour.
        assertThat(Files.readString(privPath)).isEqualTo(importedPrivatePem.strip());
        if (Files.getFileStore(privPath).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(privPath)).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            );
        }
    }

    @Test
    void importPrivateKey_rejectsPkcs1_withInvalidKeyMaterial(@TempDir Path dir) {
        EnableBankingKeyPairService svc = new EnableBankingKeyPairService(
            dir.resolve("enablebanking-private.pem").toString());

        assertThatThrownBy(() -> svc.importPrivateKey("-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----"))
            .isInstanceOf(InvalidKeyMaterialException.class)
            .hasMessageContaining("PKCS#1");
        assertThat(svc.exists()).isFalse();
    }

    @Test
    void importPrivateKey_unparsableBody_failsWithAFixedMessage_andNoJdkTextLeaks(@TempDir Path dir) {
        EnableBankingKeyPairService svc = new EnableBankingKeyPairService(
            dir.resolve("enablebanking-private.pem").toString());
        String garbage = "-----BEGIN PRIVATE KEY-----\n"
            + java.util.Base64.getEncoder().encodeToString("not a DER key".getBytes())
            + "\n-----END PRIVATE KEY-----";

        assertThatThrownBy(() -> svc.importPrivateKey(garbage))
            .isInstanceOf(InvalidKeyMaterialException.class)
            .hasMessageContaining("could not be parsed")
            // The JDK's "java.security.spec.InvalidKeySpecException: …" stays in the cause,
            // where the handler logs it — the frontend would hide any message containing it.
            .message().doesNotContain("Exception").doesNotContain("java.");
        assertThat(svc.exists()).isFalse();
    }

    @Test
    void importPrivateKey_blank_isRejectedBeforeTouchingDisk(@TempDir Path dir) {
        EnableBankingKeyPairService svc = new EnableBankingKeyPairService(
            dir.resolve("enablebanking-private.pem").toString());

        assertThatThrownBy(() -> svc.importPrivateKey("   "))
            .isInstanceOf(InvalidKeyMaterialException.class);
        assertThat(svc.exists()).isFalse();
    }
}
