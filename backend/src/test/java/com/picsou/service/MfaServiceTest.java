package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.MfaException;
import com.picsou.model.AppUser;
import com.picsou.model.UserMfa;
import com.picsou.model.UserMfaRecoveryCode;
import com.picsou.repository.UserMfaRecoveryCodeRepository;
import com.picsou.repository.UserMfaRepository;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MfaServiceTest {

    private static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"; // 32 chars base32
    private static final String SECRET_ENC = "encrypted-secret";
    private static final long FIXED_TIME = 1_700_000_000L;
    private static final long FIXED_STEP = FIXED_TIME / 30;

    @Mock UserMfaRepository userMfaRepository;
    @Mock UserMfaRecoveryCodeRepository recoveryCodeRepository;
    @Mock CryptoEncryption cryptoEncryption;
    @Mock PasswordEncoder passwordEncoder;
    @Mock SecretGenerator secretGenerator;
    @Mock CodeGenerator codeGenerator;
    @Mock TimeProvider timeProvider;
    @Mock QrGenerator qrGenerator;

    MfaService service;

    AppUser user;

    @BeforeEach
    void setUp() {
        service = new MfaService(
            userMfaRepository, recoveryCodeRepository, cryptoEncryption,
            passwordEncoder, secretGenerator, codeGenerator, timeProvider, qrGenerator,
            "Picsou"
        );
        user = AppUser.builder().id(42L).username("alice").passwordHash("hashed").build();
    }

    // ─── isEnabled / status ────────────────────────────────────────────────

    @Test
    void isEnabled_returnsTrueWhenRowExistsAndEnabled() {
        when(userMfaRepository.existsByUserIdAndEnabledTrue(42L)).thenReturn(true);
        assertThat(service.isEnabled(user)).isTrue();
    }

    @Test
    void isEnabled_returnsFalseWhenNoRow() {
        when(userMfaRepository.existsByUserIdAndEnabledTrue(42L)).thenReturn(false);
        assertThat(service.isEnabled(user)).isFalse();
    }

    // ─── beginEnrollment ───────────────────────────────────────────────────

    @Test
    void beginEnrollment_storesEncryptedSecretAndReturnsQr() throws Exception {
        when(userMfaRepository.findByUserId(42L)).thenReturn(Optional.empty());
        when(secretGenerator.generate()).thenReturn(SECRET);
        when(cryptoEncryption.encrypt(SECRET)).thenReturn(SECRET_ENC);
        when(qrGenerator.generate(any())).thenReturn(new byte[]{1, 2, 3});
        when(qrGenerator.getImageMimeType()).thenReturn("image/png");

        MfaService.EnrollmentSecret out = service.beginEnrollment(user);

        assertThat(out.base32Secret()).isEqualTo(SECRET);
        assertThat(out.qrCodeDataUri()).startsWith("data:image/png;base64,");

        ArgumentCaptor<UserMfa> captor = ArgumentCaptor.forClass(UserMfa.class);
        verify(userMfaRepository).save(captor.capture());
        UserMfa saved = captor.getValue();
        assertThat(saved.getTotpSecretEnc()).isEqualTo(SECRET_ENC);
        assertThat(saved.isEnabled()).isFalse();
        assertThat(saved.getEnrolledAt()).isNull();
    }

    @Test
    void beginEnrollment_rejectsIfAlreadyEnabled() {
        UserMfa existing = UserMfa.builder().user(user).enabled(true).totpSecretEnc("x").build();
        when(userMfaRepository.findByUserId(42L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.beginEnrollment(user))
            .isInstanceOf(MfaException.class)
            .hasMessageContaining("already enabled");
        verify(userMfaRepository, never()).save(any());
    }

    // ─── completeEnrollment ────────────────────────────────────────────────

    @Test
    void completeEnrollment_validCode_setsEnabledAndReturnsRecoveryCodes() throws Exception {
        UserMfa mfa = UserMfa.builder().id(7L).user(user).enabled(false).totpSecretEnc(SECRET_ENC).build();
        when(userMfaRepository.findByUserId(42L)).thenReturn(Optional.of(mfa));
        when(cryptoEncryption.decrypt(SECRET_ENC)).thenReturn(SECRET);
        when(timeProvider.getTime()).thenReturn(FIXED_TIME);
        when(codeGenerator.generate(eq(SECRET), eq(FIXED_STEP))).thenReturn("123456");
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "h:" + inv.getArgument(0));

        List<String> codes = service.completeEnrollment(user, "123456");

        assertThat(codes).hasSize(10);
        assertThat(codes).allMatch(c -> c.matches("\\d{8}"));
        assertThat(codes).doesNotHaveDuplicates();
        assertThat(mfa.isEnabled()).isTrue();
        assertThat(mfa.getEnrolledAt()).isNotNull();
        assertThat(mfa.getLastUsedStep()).isEqualTo(FIXED_STEP);
        verify(recoveryCodeRepository, times(10)).save(any(UserMfaRecoveryCode.class));
    }

    @Test
    void completeEnrollment_invalidCode_throws() throws Exception {
        UserMfa mfa = UserMfa.builder().id(7L).user(user).enabled(false).totpSecretEnc(SECRET_ENC).build();
        when(userMfaRepository.findByUserId(42L)).thenReturn(Optional.of(mfa));
        when(cryptoEncryption.decrypt(SECRET_ENC)).thenReturn(SECRET);
        when(timeProvider.getTime()).thenReturn(FIXED_TIME);
        when(codeGenerator.generate(eq(SECRET), anyLong())).thenReturn("999999");

        assertThatThrownBy(() -> service.completeEnrollment(user, "123456"))
            .isInstanceOf(MfaException.class)
            .hasMessageContaining("Invalid");
        assertThat(mfa.isEnabled()).isFalse();
    }

    @Test
    void completeEnrollment_noEnrollmentStarted_throws() {
        when(userMfaRepository.findByUserId(42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.completeEnrollment(user, "123456"))
            .isInstanceOf(MfaException.class)
            .hasMessageContaining("not started");
    }

    // ─── verifyTotp + anti-replay ──────────────────────────────────────────

    @Test
    void verifyTotp_validCode_returnsTrueAndUpdatesLastUsedStep() throws Exception {
        UserMfa mfa = UserMfa.builder().id(7L).user(user).enabled(true).totpSecretEnc(SECRET_ENC).build();
        when(userMfaRepository.findByUserId(42L)).thenReturn(Optional.of(mfa));
        when(cryptoEncryption.decrypt(SECRET_ENC)).thenReturn(SECRET);
        when(timeProvider.getTime()).thenReturn(FIXED_TIME);
        when(codeGenerator.generate(eq(SECRET), eq(FIXED_STEP))).thenReturn("424242");

        boolean ok = service.verifyTotp(user, "424242");

        assertThat(ok).isTrue();
        assertThat(mfa.getLastUsedStep()).isEqualTo(FIXED_STEP);
        verify(userMfaRepository, atLeastOnce()).save(mfa);
    }

    @Test
    void verifyTotp_replayedStep_returnsFalse() throws Exception {
        UserMfa mfa = UserMfa.builder().id(7L).user(user).enabled(true)
            .totpSecretEnc(SECRET_ENC).lastUsedStep(FIXED_STEP).build();
        when(userMfaRepository.findByUserId(42L)).thenReturn(Optional.of(mfa));
        when(cryptoEncryption.decrypt(SECRET_ENC)).thenReturn(SECRET);
        when(timeProvider.getTime()).thenReturn(FIXED_TIME);
        // Only step+1 is candidate (steps <= lastUsedStep are skipped); make it not match.
        when(codeGenerator.generate(eq(SECRET), eq(FIXED_STEP + 1))).thenReturn("999999");

        boolean ok = service.verifyTotp(user, "424242");

        assertThat(ok).isFalse();
        assertThat(mfa.getLastUsedStep()).isEqualTo(FIXED_STEP);
    }

    @Test
    void verifyTotp_acceptsNeighborStepWithinWindow() throws Exception {
        UserMfa mfa = UserMfa.builder().id(7L).user(user).enabled(true).totpSecretEnc(SECRET_ENC).build();
        when(userMfaRepository.findByUserId(42L)).thenReturn(Optional.of(mfa));
        when(cryptoEncryption.decrypt(SECRET_ENC)).thenReturn(SECRET);
        when(timeProvider.getTime()).thenReturn(FIXED_TIME);
        // Order: current first (no match), then -1 (match).
        when(codeGenerator.generate(eq(SECRET), eq(FIXED_STEP))).thenReturn("000000");
        when(codeGenerator.generate(eq(SECRET), eq(FIXED_STEP - 1))).thenReturn("111111");

        boolean ok = service.verifyTotp(user, "111111");

        assertThat(ok).isTrue();
        assertThat(mfa.getLastUsedStep()).isEqualTo(FIXED_STEP - 1);
    }

    @Test
    void verifyTotp_disabledMfa_returnsFalse() {
        when(userMfaRepository.findByUserId(42L)).thenReturn(Optional.empty());
        assertThat(service.verifyTotp(user, "123456")).isFalse();
    }

    // ─── verifyRecoveryCode ────────────────────────────────────────────────

    @Test
    void verifyRecoveryCode_matches_marksUsed() {
        UserMfa mfa = UserMfa.builder().id(7L).user(user).enabled(true).totpSecretEnc(SECRET_ENC).build();
        UserMfaRecoveryCode rc1 = UserMfaRecoveryCode.builder().id(101L).userMfa(mfa).codeHash("h1").build();
        UserMfaRecoveryCode rc2 = UserMfaRecoveryCode.builder().id(102L).userMfa(mfa).codeHash("h2").build();
        when(userMfaRepository.findByUserId(42L)).thenReturn(Optional.of(mfa));
        when(recoveryCodeRepository.findByUserMfaIdAndUsedAtIsNull(7L)).thenReturn(List.of(rc1, rc2));
        when(passwordEncoder.matches("12345678", "h1")).thenReturn(false);
        when(passwordEncoder.matches("12345678", "h2")).thenReturn(true);

        boolean ok = service.verifyRecoveryCode(user, "12345678");

        assertThat(ok).isTrue();
        assertThat(rc2.getUsedAt()).isNotNull();
        verify(recoveryCodeRepository).save(rc2);
    }

    @Test
    void verifyRecoveryCode_noMatch_returnsFalse() {
        UserMfa mfa = UserMfa.builder().id(7L).user(user).enabled(true).totpSecretEnc(SECRET_ENC).build();
        UserMfaRecoveryCode rc1 = UserMfaRecoveryCode.builder().id(101L).userMfa(mfa).codeHash("h1").build();
        when(userMfaRepository.findByUserId(42L)).thenReturn(Optional.of(mfa));
        when(recoveryCodeRepository.findByUserMfaIdAndUsedAtIsNull(7L)).thenReturn(List.of(rc1));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThat(service.verifyRecoveryCode(user, "12345678")).isFalse();
        verify(recoveryCodeRepository, never()).save(any());
    }

    // ─── disable ───────────────────────────────────────────────────────────

    @Test
    void disable_deletesMfaAndCodes() {
        UserMfa mfa = UserMfa.builder().id(7L).user(user).enabled(true).totpSecretEnc(SECRET_ENC).build();
        when(userMfaRepository.findByUserId(42L)).thenReturn(Optional.of(mfa));

        service.disable(user);

        verify(recoveryCodeRepository).deleteByUserMfaId(7L);
        verify(userMfaRepository).delete(mfa);
    }

    @Test
    void disable_noOpIfMfaDoesNotExist() {
        when(userMfaRepository.findByUserId(42L)).thenReturn(Optional.empty());
        service.disable(user);
        verify(userMfaRepository, never()).delete(any());
        verify(recoveryCodeRepository, never()).deleteByUserMfaId(anyLong());
    }

    // ─── regenerateRecoveryCodes ──────────────────────────────────────────

    @Test
    void regenerateRecoveryCodes_wipesAndReturnsNewSet() {
        UserMfa mfa = UserMfa.builder().id(7L).user(user).enabled(true).totpSecretEnc(SECRET_ENC).build();
        when(userMfaRepository.findByUserId(42L)).thenReturn(Optional.of(mfa));
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "h:" + inv.getArgument(0));

        List<String> codes = service.regenerateRecoveryCodes(user);

        verify(recoveryCodeRepository).deleteByUserMfaId(7L);
        assertThat(codes).hasSize(10).doesNotHaveDuplicates();
        verify(recoveryCodeRepository, times(10)).save(any(UserMfaRecoveryCode.class));
    }

    @Test
    void regenerateRecoveryCodes_rejectsWhenMfaNotEnabled() {
        when(userMfaRepository.findByUserId(42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.regenerateRecoveryCodes(user))
            .isInstanceOf(MfaException.class)
            .hasMessageContaining("not enabled");
    }

    // ─── reauth ────────────────────────────────────────────────────────────

    @Test
    void requireReauth_throwsOnBadPassword() {
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);
        assertThatThrownBy(() -> service.requireReauth(user, "wrong"))
            .isInstanceOf(MfaException.class);
    }

    @Test
    void requireReauth_passesOnGoodPassword() {
        when(passwordEncoder.matches("right", "hashed")).thenReturn(true);

        assertThatCode(() -> service.requireReauth(user, "right")).doesNotThrowAnyException();

        verify(passwordEncoder).matches("right", "hashed");
    }
}
