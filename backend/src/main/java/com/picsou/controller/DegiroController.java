package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.service.DegiroSyncService;
import com.picsou.service.DegiroSyncService.AuthInitResponse;
import com.picsou.service.DegiroSyncService.SessionStatusResponse;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/degiro")
public class DegiroController {

    private final DegiroSyncService   degiroService;
    private final UserContext         userContext;
    private final Map<String, Bucket> degiroAuthBuckets;
    private final Map<String, Bucket> syncBuckets;

    public DegiroController(
        DegiroSyncService degiroService,
        UserContext userContext,
        @org.springframework.beans.factory.annotation.Qualifier("degiroAuthBuckets") Map<String, Bucket> degiroAuthBuckets,
        @org.springframework.beans.factory.annotation.Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.degiroService     = degiroService;
        this.userContext       = userContext;
        this.degiroAuthBuckets = degiroAuthBuckets;
        this.syncBuckets       = syncBuckets;
    }

    /**
     * Step 1: Authenticate with DEGIRO.
     * - No TOTP required: session is stored immediately, returns {totpRequired: false}.
     * - TOTP required (the common case): returns {totpRequired: true, processId}.
     */
    @PostMapping("/auth/initiate")
    public ResponseEntity<?> initiateAuth(
        @Valid @RequestBody InitiateAuthRequest req,
        HttpServletRequest request
    ) {
        if (!checkRateLimit(request)) {
            return rateLimited();
        }

        AuthInitResponse init = degiroService.initiateAuth(req.username(), req.password(), userContext.currentMemberId());
        return ResponseEntity.ok(init);
    }

    /**
     * Step 2 (TOTP only): submit the current 6-digit authenticator code.
     * Throttled like {@code /auth/initiate}: the TOTP space is only 1M, so an
     * unthrottled verify endpoint is brute-forceable once a processId is known.
     */
    @PostMapping("/auth/complete")
    public ResponseEntity<?> completeAuth(
        @Valid @RequestBody CompleteAuthRequest req,
        HttpServletRequest request
    ) {
        if (!checkRateLimit(request)) {
            return rateLimited();
        }

        return ResponseEntity.ok(
            degiroService.completeAuth(req.processId(), req.code(), userContext.currentMemberId())
        );
    }

    /**
     * Manually trigger a sync using the stored session. There is no scheduled
     * equivalent for DEGIRO — see {@link DegiroSyncService} Javadoc.
     *
     * <p>Throttled like every other sync entry point: each call decrypts the stored session
     * and performs a live portfolio fetch from the instance's IP, which DEGIRO itself
     * throttles — nothing else stops a caller re-issuing it the moment one finishes.
     */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        if (!checkSyncRateLimit(request)) {
            return rateLimited("Too many DEGIRO synchronization requests. Please wait before retrying.");
        }
        return ResponseEntity.ok(degiroService.sync(userContext.currentMemberId()));
    }

    /** Return session status (active / needs reconnect, last sync time). */
    @GetMapping("/status")
    public SessionStatusResponse getStatus() {
        return degiroService.getSessionStatus(userContext.currentMemberId());
    }

    /** Clear the stored session (forces re-authentication). */
    @DeleteMapping("/session")
    public ResponseEntity<Void> clearSession() {
        degiroService.clearSession(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    // ─── Rate limiting ────────────────────────────────────────────────────────

    private boolean checkRateLimit(HttpServletRequest request) {
        String ip = ClientIp.resolve(request);
        Bucket bucket = degiroAuthBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createDegiroAuthBucket());
        return bucket.tryConsume(1);
    }

    private boolean checkSyncRateLimit(HttpServletRequest request) {
        String ip = ClientIp.resolve(request);
        Bucket bucket = syncBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createSyncBucket());
        return bucket.tryConsume(1);
    }

    private ResponseEntity<ProblemDetail> rateLimited() {
        return rateLimited("Too many DEGIRO authentication attempts. Please wait before retrying.");
    }

    private ResponseEntity<ProblemDetail> rateLimited(String message) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail(message);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }

    record InitiateAuthRequest(
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Size(max = 100) String password
    ) {}

    record CompleteAuthRequest(
        @NotBlank @Size(max = 100) String processId,
        @NotBlank @Pattern(regexp = "\\d{6}") String code
    ) {}
}
