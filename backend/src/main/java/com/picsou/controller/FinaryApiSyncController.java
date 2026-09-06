package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.FinaryApiSyncExecuteRequest;
import com.picsou.dto.FinaryApiSyncPreviewRequest;
import com.picsou.dto.FinaryConnectionStatusResponse;
import com.picsou.dto.FinaryImportResultResponse;
import com.picsou.dto.FinaryLoginRequest;
import com.picsou.finary.FinaryApiSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller for Finary API direct sync (two-phase: preview + execute)
 *
 * <p>Every endpoint that runs a Clerk sign-in with the stored credentials
 * ({@code /check-totp}, {@code /api-sync/preview}, {@code /api-sync/auto}) is IP-throttled on
 * the shared Finary auth bucket: the second factor is a 6-digit code and Clerk sees all of this
 * traffic as one IP, so an unthrottled loop is both a brute-force oracle and a way to get the
 * instance blocked upstream. {@code /api-sync/execute} works off the cached preview and
 * authenticates nothing, so it is not on the bucket.
 */
@RestController
@RequestMapping("/api/finary")
public class FinaryApiSyncController {

    private final FinaryApiSyncService finaryApiSyncService;
    private final UserContext userContext;
    private final Map<String, Bucket> finaryAuthBuckets;

    public FinaryApiSyncController(
        FinaryApiSyncService finaryApiSyncService,
        UserContext userContext,
        @Qualifier("finaryAuthBuckets") Map<String, Bucket> finaryAuthBuckets
    ) {
        this.finaryApiSyncService = finaryApiSyncService;
        this.userContext = userContext;
        this.finaryAuthBuckets = finaryAuthBuckets;
    }

    /**
     * Get current Finary connection status
     */
    @GetMapping("/status")
    public FinaryConnectionStatusResponse getStatus() {
        return finaryApiSyncService.getConnectionStatus(userContext.currentMemberId());
    }

    /**
     * Store Finary credentials (encrypted)
     */
    @PostMapping("/login")
    public void login(@Valid @RequestBody FinaryLoginRequest request) {
        finaryApiSyncService.login(request.email(), request.password(), userContext.currentMemberId());
    }

    /**
     * Check whether stored Finary credentials require TOTP to authenticate.
     * Must be called after /login has stored credentials.
     */
    @PostMapping("/check-totp")
    public ResponseEntity<?> checkTotp(HttpServletRequest request) {
        if (!consumeAuthToken(request)) {
            return rateLimited();
        }
        return ResponseEntity.ok(finaryApiSyncService.checkTotp(userContext.currentMemberId()));
    }

    /**
     * Delete stored Finary session
     */
    @DeleteMapping("/session")
    public ResponseEntity<Void> deleteSession() {
        finaryApiSyncService.deleteSession(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Preview phase: authenticate, fetch accounts + transactions, return preview for mapping.
     *
     * <p>The body is optional: the first attempt carries no second factor, and only the retry
     * after a {@code TotpRequiredException} sends {@code {"totp": "123456"}}.
     */
    @PostMapping("/api-sync/preview")
    public ResponseEntity<?> apiSyncPreview(
        @Valid @RequestBody(required = false) FinaryApiSyncPreviewRequest body,
        HttpServletRequest request
    ) {
        if (!consumeAuthToken(request)) {
            return rateLimited();
        }
        String totp = body != null ? body.totp() : null;
        return ResponseEntity.ok(finaryApiSyncService.preview(totp, userContext.currentMemberId()));
    }

    /**
     * Execute phase: apply user mappings and import accounts + transactions
     */
    @PostMapping("/api-sync/execute")
    public FinaryImportResultResponse apiSyncExecute(@RequestBody FinaryApiSyncExecuteRequest request) {
        return finaryApiSyncService.execute(request.syncToken(), request.mappings(), userContext.currentMemberId());
    }

    /**
     * Auto-sync: runs preview + execute in one step if all accounts are already mapped.
     * Returns NEEDS_MAPPING if new accounts are discovered (user must go through mapping UI).
     */
    @PostMapping("/api-sync/auto")
    public ResponseEntity<?> apiSyncAuto(HttpServletRequest request) {
        if (!consumeAuthToken(request)) {
            return rateLimited();
        }
        return ResponseEntity.ok(finaryApiSyncService.autoSync(userContext.currentMemberId()));
    }

    private boolean consumeAuthToken(HttpServletRequest request) {
        return finaryAuthBuckets.computeIfAbsent(
            ClientIp.resolve(request),
            key -> RateLimitConfig.createFinaryAuthBucket()
        )
            .tryConsume(1);
    }

    private static ResponseEntity<ProblemDetail> rateLimited() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail("Too many Finary authentication attempts. Please wait before retrying.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }
}
