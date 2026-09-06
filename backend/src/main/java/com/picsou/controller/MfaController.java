package com.picsou.controller;

import com.picsou.config.ClientIp;
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
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth/mfa")
public class MfaController {

    private final MfaService mfaService;
    private final PersistentSessionService persistentSessionService;
    private final Map<String, Bucket> mfaEnrollBuckets;
    private final Map<String, Bucket> reauthBuckets;

    public MfaController(
        MfaService mfaService,
        PersistentSessionService persistentSessionService,
        @Qualifier("mfaEnrollBuckets") Map<String, Bucket> mfaEnrollBuckets,
        @Qualifier("reauthBuckets") Map<String, Bucket> reauthBuckets
    ) {
        this.mfaService = mfaService;
        this.persistentSessionService = persistentSessionService;
        this.mfaEnrollBuckets = mfaEnrollBuckets;
        this.reauthBuckets = reauthBuckets;
    }

    @GetMapping("/status")
    public ResponseEntity<MfaStatusResponse> status(@AuthenticationPrincipal AppUser user) {
        MfaService.MfaStatus s = mfaService.getStatus(user);
        return ResponseEntity.ok(new MfaStatusResponse(
            s.enabled(), s.enrolledAt(), s.remainingRecoveryCodes()
        ));
    }

    @PostMapping("/enroll/init")
    public ResponseEntity<EnrollInitResponse> enrollInit(
        @AuthenticationPrincipal AppUser user,
        @Valid @RequestBody EnrollInitRequest req,
        HttpServletRequest httpReq
    ) {
        String ip = ClientIp.resolve(httpReq);
        Bucket bucket = mfaEnrollBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createMfaEnrollBucket());
        if (!bucket.tryConsume(1)) {
            return rateLimited("Too many enrollment attempts. Try again in an hour.");
        }
        mfaService.requireReauth(user, req.currentPassword());
        MfaService.EnrollmentSecret secret = mfaService.beginEnrollment(user);
        return ResponseEntity.ok(new EnrollInitResponse(
            secret.qrCodeDataUri(), secret.base32Secret()
        ));
    }

    @PostMapping("/enroll/verify")
    public ResponseEntity<RecoveryCodesResponse> enrollVerify(
        @AuthenticationPrincipal AppUser user,
        @Valid @RequestBody EnrollVerifyRequest req
    ) {
        List<String> codes = mfaService.completeEnrollment(user, req.code());
        // Enabling MFA invalidates pre-MFA "remembered" devices: their persistent
        // cookies were issued without a second factor and no longer satisfy policy.
        persistentSessionService.revokeAllForUser(user.getId());
        return ResponseEntity.ok(new RecoveryCodesResponse(codes));
    }

    @PostMapping("/disable")
    public ResponseEntity<Void> disable(
        @AuthenticationPrincipal AppUser user,
        @Valid @RequestBody DisableMfaRequest req
    ) {
        // Step-up password check from an already-authenticated session: throttle it per
        // user like /login, or a hijacked session cookie becomes a bcrypt-speed password
        // oracle (see RateLimitConfig#reauthBuckets). Consumed BEFORE the password is
        // looked at so a wrong password and a wrong code cost the same budget.
        if (!consumeReauthToken(user)) {
            return rateLimited(REAUTH_RATE_LIMITED);
        }
        mfaService.requireReauth(user, req.currentPassword());

        boolean isRecovery = Boolean.TRUE.equals(req.isRecoveryCode());
        if (!mfaService.verifyTotpOrRecovery(user, req.code(), isRecovery)) {
            throw new MfaException("Invalid verification code");
        }

        mfaService.disable(user);
        // Disabling MFA wipes "trusted" devices too — symmetric with enable.
        persistentSessionService.revokeAllForUser(user.getId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/recovery-codes/regenerate")
    public ResponseEntity<RecoveryCodesResponse> regenerateRecoveryCodes(
        @AuthenticationPrincipal AppUser user,
        @Valid @RequestBody RegenerateCodesRequest req
    ) {
        if (!consumeReauthToken(user)) {
            return rateLimited(REAUTH_RATE_LIMITED);
        }
        mfaService.requireReauth(user, req.currentPassword());

        // Recovery-code path explicitly disallowed here: regenerating codes from a
        // recovery code would let one consumed code spawn 10 fresh ones, breaking
        // the "single-use list" promise. Only TOTP is accepted.
        if (!mfaService.verifyTotp(user, req.code())) {
            throw new MfaException("Invalid verification code");
        }

        List<String> codes = mfaService.regenerateRecoveryCodes(user);
        return ResponseEntity.ok(new RecoveryCodesResponse(codes));
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private static final String REAUTH_RATE_LIMITED = "Too many password attempts. Try again in 15 minutes.";

    /** One step-up password check against {@code user}'s reauth budget; false once it is exhausted. */
    private boolean consumeReauthToken(AppUser user) {
        Bucket bucket = reauthBuckets.computeIfAbsent(
            String.valueOf(user.getId()), k -> RateLimitConfig.createReauthBucket());
        return bucket.tryConsume(1);
    }

    /**
     * 429 as RFC 7807 ProblemDetail, like every other error (api-rest.md "Error format").
     * Generic so it slots into the typed {@code ResponseEntity<...>} signatures above; the
     * body is a ProblemDetail either way, exactly as the {@code ResponseEntity<?>} endpoints
     * of {@code AuthController} return it.
     */
    @SuppressWarnings("unchecked")
    private static <T> ResponseEntity<T> rateLimited(String message) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail(message);
        return (ResponseEntity<T>) ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }
}
