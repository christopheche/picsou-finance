package com.picsou.controller;

import com.picsou.config.RateLimitConfig;
import com.picsou.dto.MfaDtos.DisableMfaRequest;
import com.picsou.dto.MfaDtos.EnrollInitRequest;
import com.picsou.dto.MfaDtos.EnrollInitResponse;
import com.picsou.dto.MfaDtos.EnrollVerifyRequest;
import com.picsou.dto.MfaDtos.MfaStatusResponse;
import com.picsou.dto.MfaDtos.RecoveryCodesResponse;
import com.picsou.dto.MfaDtos.RegenerateCodesRequest;
import com.picsou.exception.MfaException;
import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MfaControllerTest {

    @Mock MfaService mfaService;
    @Mock PersistentSessionService persistentSessionService;

    Map<String, Bucket> mfaEnrollBuckets;
    Map<String, Bucket> reauthBuckets;
    MfaController controller;
    AppUser user;
    MockHttpServletRequest httpReq;

    @BeforeEach
    void setUp() {
        mfaEnrollBuckets = new HashMap<>();
        reauthBuckets = new HashMap<>();
        controller = new MfaController(mfaService, persistentSessionService, mfaEnrollBuckets, reauthBuckets);
        user = AppUser.builder()
            .id(7L).username("alice")
            .role(UserRole.MEMBER).activated(true)
            .passwordHash("$2a$12$hash")
            .build();
        httpReq = new MockHttpServletRequest();
        httpReq.setRemoteAddr("10.0.0.5");
    }

    // ─── /status ─────────────────────────────────────────────────────────

    @Test
    void status_returnsServiceState() {
        Instant enrolled = Instant.parse("2026-04-26T10:00:00Z");
        when(mfaService.getStatus(user))
            .thenReturn(new MfaService.MfaStatus(true, enrolled, 8));

        ResponseEntity<MfaStatusResponse> res = controller.status(user);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().enabled()).isTrue();
        assertThat(res.getBody().enrolledAt()).isEqualTo(enrolled);
        assertThat(res.getBody().remainingRecoveryCodes()).isEqualTo(8);
    }

    @Test
    void status_returnsDisabledWhenNotEnrolled() {
        when(mfaService.getStatus(user))
            .thenReturn(new MfaService.MfaStatus(false, null, 0));

        ResponseEntity<MfaStatusResponse> res = controller.status(user);

        assertThat(res.getBody().enabled()).isFalse();
        assertThat(res.getBody().enrolledAt()).isNull();
        assertThat(res.getBody().remainingRecoveryCodes()).isZero();
    }

    // ─── /enroll/init ────────────────────────────────────────────────────

    @Test
    void enrollInit_returnsQrAndSecret_onValidPassword() {
        when(mfaService.beginEnrollment(user))
            .thenReturn(new MfaService.EnrollmentSecret("data:image/png;base64,abc", "JBSWY3DPEHPK3PXP"));

        ResponseEntity<EnrollInitResponse> res =
            controller.enrollInit(user, new EnrollInitRequest("pw"), httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().qrCodeDataUri()).startsWith("data:image/png;base64,");
        assertThat(res.getBody().secret()).isEqualTo("JBSWY3DPEHPK3PXP");
        verify(mfaService).requireReauth(user, "pw");
    }

    @Test
    void enrollInit_failsWithMfaException_whenPasswordWrong() {
        org.mockito.Mockito.doThrow(new MfaException("Current password is incorrect"))
            .when(mfaService).requireReauth(user, "bad");

        assertThatThrownBy(() ->
            controller.enrollInit(user, new EnrollInitRequest("bad"), httpReq)
        ).isInstanceOf(MfaException.class);

        verify(mfaService, never()).beginEnrollment(any());
    }

    @Test
    void enrollInit_returns429_whenRateLimitExhausted() {
        // Drain the per-IP enroll bucket.
        Bucket bucket = RateLimitConfig.createMfaEnrollBucket();
        while (bucket.tryConsume(1)) { /* drain */ }
        mfaEnrollBuckets.put("10.0.0.5", bucket);

        ResponseEntity<EnrollInitResponse> res =
            controller.enrollInit(user, new EnrollInitRequest("pw"), httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // RFC 7807 like every other error, so the client can show *why* (api-rest.md).
        assertThat((Object) res.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(((ProblemDetail) (Object) res.getBody()).getDetail()).containsIgnoringCase("try again");
        verifyNoInteractions(mfaService);
    }

    /** A per-user reauth bucket with no attempts left. */
    private void drainReauthBucketFor(long userId) {
        Bucket bucket = RateLimitConfig.createReauthBucket();
        while (bucket.tryConsume(1)) { /* drain */ }
        reauthBuckets.put(String.valueOf(userId), bucket);
    }

    // ─── /enroll/verify ──────────────────────────────────────────────────

    @Test
    void enrollVerify_returnsCodesAndRevokesSessions_onValidCode() {
        List<String> codes = List.of("12345678", "23456789");
        when(mfaService.completeEnrollment(user, "123456")).thenReturn(codes);

        ResponseEntity<RecoveryCodesResponse> res =
            controller.enrollVerify(user, new EnrollVerifyRequest("123456"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().recoveryCodes()).isEqualTo(codes);
        verify(persistentSessionService).revokeAllForUser(7L);
    }

    @Test
    void enrollVerify_propagatesMfaException_andSkipsRevoke() {
        when(mfaService.completeEnrollment(user, "000000"))
            .thenThrow(new MfaException("Invalid verification code"));

        assertThatThrownBy(() ->
            controller.enrollVerify(user, new EnrollVerifyRequest("000000"))
        ).isInstanceOf(MfaException.class);

        verify(persistentSessionService, never()).revokeAllForUser(any());
    }

    // ─── /disable ────────────────────────────────────────────────────────

    @Test
    void disable_succeeds_withTotp_andRevokesSessions() {
        when(mfaService.verifyTotpOrRecovery(user, "123456", false)).thenReturn(true);

        ResponseEntity<Void> res = controller.disable(user,
            new DisableMfaRequest("pw", "123456", false));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(mfaService).requireReauth(user, "pw");
        verify(mfaService).disable(user);
        verify(persistentSessionService).revokeAllForUser(7L);
    }

    @Test
    void disable_succeeds_withRecoveryCode() {
        when(mfaService.verifyTotpOrRecovery(user, "abcd1234", true)).thenReturn(true);

        ResponseEntity<Void> res = controller.disable(user,
            new DisableMfaRequest("pw", "abcd1234", true));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(mfaService).disable(user);
    }

    @Test
    void disable_treatsNullIsRecoveryAsFalse() {
        when(mfaService.verifyTotpOrRecovery(user, "123456", false)).thenReturn(true);

        controller.disable(user, new DisableMfaRequest("pw", "123456", null));

        verify(mfaService).verifyTotpOrRecovery(user, "123456", false);
    }

    @Test
    void disable_throwsMfaException_whenCodeInvalid() {
        when(mfaService.verifyTotpOrRecovery(user, "000000", false)).thenReturn(false);

        assertThatThrownBy(() -> controller.disable(user,
            new DisableMfaRequest("pw", "000000", false))
        ).isInstanceOf(MfaException.class)
            .hasMessageContaining("Invalid verification code");

        verify(mfaService, never()).disable(any());
        verify(persistentSessionService, never()).revokeAllForUser(any());
    }

    @Test
    void disable_returns429ProblemDetail_beforeCheckingPassword_whenReauthBucketExhausted() {
        // The step-up password check must not be a bcrypt-speed oracle for whoever holds a
        // hijacked session cookie: same per-user budget as /login, consumed before anything
        // (password OR code) is looked at.
        drainReauthBucketFor(7L);

        ResponseEntity<Void> res = controller.disable(user,
            new DisableMfaRequest("guess", "123456", false));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat((Object) res.getBody()).isInstanceOf(ProblemDetail.class);
        verifyNoInteractions(mfaService, persistentSessionService);
    }

    @Test
    void disable_reauthBudget_isKeyedByUserId_andSharedWithRegenerate() {
        when(mfaService.verifyTotpOrRecovery(user, "123456", false)).thenReturn(true);
        when(mfaService.verifyTotp(user, "123456")).thenReturn(true);
        when(mfaService.regenerateRecoveryCodes(user)).thenReturn(List.of("11111111"));

        controller.disable(user, new DisableMfaRequest("pw", "123456", false));
        controller.regenerateRecoveryCodes(user, new RegenerateCodesRequest("pw", "123456"));

        // One bucket for the account (5 / 15 min), two step-up checks consumed from it.
        assertThat(reauthBuckets).containsOnlyKeys("7");
        assertThat(reauthBuckets.get("7").getAvailableTokens()).isEqualTo(3L);
    }

    @Test
    void disable_throwsBeforeVerifyingCode_whenPasswordWrong() {
        org.mockito.Mockito.doThrow(new MfaException("Current password is incorrect"))
            .when(mfaService).requireReauth(user, "bad");

        assertThatThrownBy(() -> controller.disable(user,
            new DisableMfaRequest("bad", "123456", false))
        ).isInstanceOf(MfaException.class);

        verify(mfaService, never()).verifyTotpOrRecovery(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(mfaService, never()).disable(any());
    }

    // ─── /recovery-codes/regenerate ──────────────────────────────────────

    @Test
    void regenerate_returnsFreshCodes_onValidTotp() {
        when(mfaService.verifyTotp(user, "123456")).thenReturn(true);
        List<String> fresh = List.of("11111111", "22222222");
        when(mfaService.regenerateRecoveryCodes(user)).thenReturn(fresh);

        ResponseEntity<RecoveryCodesResponse> res =
            controller.regenerateRecoveryCodes(user, new RegenerateCodesRequest("pw", "123456"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().recoveryCodes()).isEqualTo(fresh);
        verify(mfaService).requireReauth(user, "pw");
        // Critically: regenerate does NOT revoke persistent sessions — user keeps them.
        verifyNoInteractions(persistentSessionService);
    }

    @Test
    void regenerate_returns429ProblemDetail_whenReauthBucketExhausted() {
        drainReauthBucketFor(7L);

        ResponseEntity<RecoveryCodesResponse> res =
            controller.regenerateRecoveryCodes(user, new RegenerateCodesRequest("guess", "123456"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat((Object) res.getBody()).isInstanceOf(ProblemDetail.class);
        verifyNoInteractions(mfaService);
    }

    @Test
    void regenerate_throwsMfaException_whenTotpInvalid() {
        when(mfaService.verifyTotp(user, "000000")).thenReturn(false);

        assertThatThrownBy(() -> controller.regenerateRecoveryCodes(user,
            new RegenerateCodesRequest("pw", "000000"))
        ).isInstanceOf(MfaException.class)
            .hasMessageContaining("Invalid verification code");

        verify(mfaService, never()).regenerateRecoveryCodes(any());
    }
}
