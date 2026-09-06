package com.picsou.controller;

import com.picsou.config.RateLimitConfig;
import com.picsou.dto.AccessKeyCreateRequest;
import com.picsou.dto.AccessKeyCreatedResponse;
import com.picsou.dto.AccessKeyResponse;
import com.picsou.mcp.AccessKeyService;
import com.picsou.mcp.AccessKeyService.GeneratedKey;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Self-service management of a member's MCP access-keys.
 *
 * <p>Lives under {@code /api/**}, so it is cookie-authenticated and structurally unreachable by a
 * {@code psk_} key — {@code AccessKeyAuthFilter} only authenticates {@code /mcp/**} (Property A). It is
 * deliberately NOT under {@code /api/admin/**}: every logged-in member manages their OWN keys. The
 * service scopes every read/revoke by the caller's member id, so one member can never see or revoke
 * another's keys, and {@code create} binds the new key to the caller's own member.
 *
 * <p>Every operation resolves {@link UserContext#ownMemberId()} rather than {@code currentMemberId()}:
 * a key is bound to the {@code AppUser} that created it (a login-less managed profile cannot own one,
 * see the access-key ADR), so an admin impersonating a managed profile keeps listing, throttling and
 * revoking their own keys — the ones {@code create} would bind to anyway.
 */
@RestController
@RequestMapping("/api/access-keys")
public class AccessKeyController {

    private final AccessKeyService accessKeyService;
    private final UserContext userContext;
    private final Map<Long, Bucket> createBuckets;

    public AccessKeyController(AccessKeyService accessKeyService,
                              UserContext userContext,
                              @Qualifier("accessKeyCreateBuckets") Map<Long, Bucket> createBuckets) {
        this.accessKeyService = accessKeyService;
        this.userContext = userContext;
        this.createBuckets = createBuckets;
    }

    @GetMapping
    public List<AccessKeyResponse> list() {
        return accessKeyService.list(userContext.ownMemberId()).stream()
            .map(AccessKeyResponse::from)
            .toList();
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody AccessKeyCreateRequest request) {
        Long memberId = userContext.ownMemberId();
        if (!consumeCreateToken(memberId)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many access-key creations. Try again later.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }
        // An unknown scope throws IllegalArgumentException here → 400 via GlobalExceptionHandler.
        GeneratedKey generated = accessKeyService.create(
            userContext.currentUser(),
            request.name(),
            request.scopes(),
            request.expiresAt()
        );
        AccessKeyCreatedResponse body = new AccessKeyCreatedResponse(
            generated.rawSecret(),
            AccessKeyResponse.from(generated.accessKey())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        boolean revoked = accessKeyService.revoke(id, userContext.ownMemberId());
        if (!revoked) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Access key not found");
        }
        return ResponseEntity.noContent().build();
    }

    private boolean consumeCreateToken(Long memberId) {
        Bucket bucket = createBuckets.computeIfAbsent(
            memberId, k -> RateLimitConfig.createAccessKeyCreateBucket());
        return bucket.tryConsume(1);
    }
}
