package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.service.TradeRepublicSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/tr")
public class TradeRepublicController {

    private final TradeRepublicSyncService trService;
    private final UserContext userContext;
    private final Map<String, Bucket>      trAuthBuckets;
    private final Map<String, Bucket>      trTanBuckets;
    private final Map<String, Bucket>      syncBuckets;

    public TradeRepublicController(
        TradeRepublicSyncService trService,
        UserContext userContext,
        @org.springframework.beans.factory.annotation.Qualifier("trAuthBuckets") Map<String, Bucket> trAuthBuckets,
        @org.springframework.beans.factory.annotation.Qualifier("trTanBuckets") Map<String, Bucket> trTanBuckets,
        @org.springframework.beans.factory.annotation.Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.trService     = trService;
        this.userContext   = userContext;
        this.trAuthBuckets = trAuthBuckets;
        this.trTanBuckets  = trTanBuckets;
        this.syncBuckets   = syncBuckets;
    }

    /** Step 1: Sends phone+PIN to TR, triggers 2FA SMS. Credentials are never stored. */
    @PostMapping("/auth/initiate")
    public ResponseEntity<?> initiateAuth(
        @Valid @RequestBody InitiateAuthRequest req,
        HttpServletRequest request
    ) {
        if (!checkAuthRateLimit(request)) {
            return rateLimited("Too many authentication attempts. Please wait before trying again.");
        }
        return ResponseEntity.ok(trService.initiateAuth(req.phoneNumber(), req.pin()));
    }

    /**
     * Step 2: Exchange 2FA code -> session stored, sync runs in background.
     *
     * <p>Throttled on its own bucket (not the SMS one): the TAN space is only 10,000, so an
     * unthrottled verify endpoint is brute-forceable once a processId is known.
     */
    @PostMapping("/auth/complete")
    public ResponseEntity<?> completeAuth(
        @Valid @RequestBody CompleteAuthRequest req,
        HttpServletRequest request
    ) {
        if (!checkTanRateLimit(request)) {
            return rateLimited("Too many verification attempts. Please wait before trying again.");
        }
        return ResponseEntity.ok(
            trService.completeAuth(req.processId(), req.tan(), userContext.currentMemberId())
        );
    }

    /**
     * Manual sync using the stored session. Throttled like every other sync entry point:
     * each call decrypts the stored session and drives a sidecar round-trip that may refresh
     * the TR token, and nothing else stops a caller re-issuing it the moment one finishes.
     */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        if (!checkSyncRateLimit(request)) {
            return rateLimited("Too many sync requests. Please wait a moment.");
        }
        return ResponseEntity.ok(trService.sync(userContext.currentMemberId()));
    }

    /** Session status: is there an active session, and when does it expire? */
    @GetMapping("/status")
    public TradeRepublicSyncService.SessionStatusResponse getStatus() {
        return trService.getSessionStatus(userContext.currentMemberId());
    }

    /** CSV fallback import. Throttled like the other upload endpoints — the 10 MB multipart
     *  ceiling is global, so without a bucket a member can keep the backend parsing uploads. */
    @PostMapping("/import")
    public ResponseEntity<?> importCsv(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        if (!checkSyncRateLimit(request)) {
            return rateLimited("Too many import requests. Please wait a moment.");
        }
        return ResponseEntity.ok(trService.importCsv(file, userContext.currentMemberId()));
    }

    /** Clear stored session token (forces re-authentication). */
    @DeleteMapping("/session")
    public ResponseEntity<Void> clearSession() {
        trService.clearSession(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    // --- Rate limiting ---

    private boolean checkAuthRateLimit(HttpServletRequest request) {
        String ip = ClientIp.resolve(request);
        Bucket bucket = trAuthBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createTrAuthBucket());
        return bucket.tryConsume(1);
    }

    private boolean checkTanRateLimit(HttpServletRequest request) {
        String ip = ClientIp.resolve(request);
        Bucket bucket = trTanBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createTrTanBucket());
        return bucket.tryConsume(1);
    }

    private boolean checkSyncRateLimit(HttpServletRequest request) {
        String ip = ClientIp.resolve(request);
        Bucket bucket = syncBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createSyncBucket());
        return bucket.tryConsume(1);
    }

    private static ResponseEntity<ProblemDetail> rateLimited(String message) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail(message);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }

    /**
     * {@code phoneNumber} and {@code pin} are relayed to the sidecar through
     * {@code Map.of(...)}, which throws {@link NullPointerException} on a null value — a
     * missing field would surface as a 500 instead of a 422 naming it. {@code @Size} keeps a
     * pasted wall of text from reaching the sidecar at all.
     */
    record InitiateAuthRequest(
        @NotBlank @Size(max = 100) String phoneNumber,
        @NotBlank @Size(max = 100) String pin
    ) {}

    record CompleteAuthRequest(
        @NotBlank @Size(max = 100) String processId,
        @NotBlank @Size(max = 100) String tan
    ) {}
}
