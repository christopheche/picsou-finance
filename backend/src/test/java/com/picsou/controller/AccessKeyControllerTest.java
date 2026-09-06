package com.picsou.controller;

import com.picsou.dto.AccessKeyCreateRequest;
import com.picsou.dto.AccessKeyCreatedResponse;
import com.picsou.dto.AccessKeyResponse;
import com.picsou.mcp.AccessKeyService;
import com.picsou.mcp.AccessKeyService.GeneratedKey;
import com.picsou.model.AccessKey;
import com.picsou.model.AppUser;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito controller test (no Spring context, no MockMvc) — mirrors {@code SetupControllerTest}.
 * Pins the self-service management contract: a member lists/creates/revokes ONLY their own keys, the
 * raw secret is returned exactly once on create, creation is rate-limited per member, an unknown scope
 * bubbles as {@link IllegalArgumentException} (→ 400 via the global handler), and revoking a key that
 * isn't the caller's yields 404. Every operation resolves {@link UserContext#ownMemberId()} — the
 * admin impersonation override never applies to keys, which are bound to the login that created them.
 */
@ExtendWith(MockitoExtension.class)
class AccessKeyControllerTest {

    private static final Long MEMBER_ID = 7L;

    @Mock AccessKeyService accessKeyService;
    @Mock UserContext userContext;

    private Map<Long, Bucket> createBuckets;
    private AccessKeyController controller;

    @BeforeEach
    void setUp() {
        createBuckets = new ConcurrentHashMap<>();
        controller = new AccessKeyController(accessKeyService, userContext, createBuckets);
    }

    @Test
    void list_returnsCurrentMembersKeysMappedToSafeResponses() {
        when(userContext.ownMemberId()).thenReturn(MEMBER_ID);
        Instant created = Instant.parse("2026-06-01T10:00:00Z");
        Instant used = Instant.parse("2026-06-02T08:30:00Z");
        AccessKey key = sampleKey(42L, "Claude Desktop",
            new LinkedHashSet<>(List.of("goals:write", "accounts:read")), created, used, null, null);
        when(accessKeyService.list(MEMBER_ID)).thenReturn(List.of(key));

        List<AccessKeyResponse> result = controller.list();

        assertThat(result).hasSize(1);
        AccessKeyResponse r = result.get(0);
        assertThat(r.id()).isEqualTo(42L);
        assertThat(r.name()).isEqualTo("Claude Desktop");
        assertThat(r.keyPrefix()).isEqualTo("psk_abcd1234");
        // Scopes are sorted for stable UI rendering, regardless of stored order.
        assertThat(r.scopes()).containsExactly("accounts:read", "goals:write");
        assertThat(r.createdAt()).isEqualTo(created);
        assertThat(r.lastUsedAt()).isEqualTo(used);
        assertThat(r.expiresAt()).isNull();
        assertThat(r.revokedAt()).isNull();
    }

    @Test
    void create_returnsSecretOnceWithMetadataAnd201() {
        when(userContext.ownMemberId()).thenReturn(MEMBER_ID);
        AppUser owner = AppUser.builder().build();
        when(userContext.currentUser()).thenReturn(owner);
        Set<String> scopes = new LinkedHashSet<>(List.of("goals:read", "accounts:read"));
        AccessKey saved = sampleKey(1L, "My Key", scopes,
            Instant.parse("2026-06-04T00:00:00Z"), null, null, null);
        when(accessKeyService.create(eq(owner), eq("My Key"), eq(scopes), isNull()))
            .thenReturn(new GeneratedKey(saved, "psk_THISisTheOneTimeSecretValue01"));

        ResponseEntity<?> response =
            controller.create(new AccessKeyCreateRequest("My Key", scopes, null));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isInstanceOf(AccessKeyCreatedResponse.class);
        AccessKeyCreatedResponse body = (AccessKeyCreatedResponse) response.getBody();
        assertThat(body.secret()).isEqualTo("psk_THISisTheOneTimeSecretValue01");
        assertThat(body.key().id()).isEqualTo(1L);
        assertThat(body.key().name()).isEqualTo("My Key");
        assertThat(body.key().keyPrefix()).isEqualTo("psk_abcd1234");
    }

    @Test
    void create_passesOptionalExpiryThroughToService() {
        when(userContext.ownMemberId()).thenReturn(MEMBER_ID);
        AppUser owner = AppUser.builder().build();
        when(userContext.currentUser()).thenReturn(owner);
        Instant expiry = Instant.parse("2026-12-31T23:59:59Z");
        Set<String> scopes = Set.of("dashboard:read");
        AccessKey saved = sampleKey(2L, "Expiring", new LinkedHashSet<>(scopes),
            Instant.parse("2026-06-04T00:00:00Z"), null, expiry, null);
        when(accessKeyService.create(eq(owner), eq("Expiring"), eq(scopes), eq(expiry)))
            .thenReturn(new GeneratedKey(saved, "psk_secretValueForExpiringKey00x"));

        controller.create(new AccessKeyCreateRequest("Expiring", scopes, expiry));

        verify(accessKeyService).create(owner, "Expiring", scopes, expiry);
    }

    @Test
    void create_returns429_whenPerMemberRateLimitIsDrained() {
        when(userContext.ownMemberId()).thenReturn(MEMBER_ID);
        Bucket drained = Bucket.builder()
            .addLimit(Bandwidth.builder().capacity(1).refillIntervally(1, Duration.ofHours(1)).build())
            .build();
        drained.tryConsume(1);
        createBuckets.put(MEMBER_ID, drained);

        ResponseEntity<?> response =
            controller.create(new AccessKeyCreateRequest("Too many", Set.of("goals:read"), null));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        verify(accessKeyService, never()).create(any(), any(), any(), any());
    }

    @Test
    void create_propagatesUnknownScopeAsIllegalArgumentForA400() {
        when(userContext.ownMemberId()).thenReturn(MEMBER_ID);
        AppUser owner = AppUser.builder().build();
        when(userContext.currentUser()).thenReturn(owner);
        Set<String> scopes = Set.of("bogus:scope");
        when(accessKeyService.create(eq(owner), eq("Bad"), eq(scopes), isNull()))
            .thenThrow(new IllegalArgumentException("Unknown scope: bogus:scope"));

        assertThatThrownBy(() ->
            controller.create(new AccessKeyCreateRequest("Bad", scopes, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown scope");
    }

    @Test
    void delete_revokesAndReturns204_whenKeyBelongsToCaller() {
        when(userContext.ownMemberId()).thenReturn(MEMBER_ID);
        when(accessKeyService.revoke(5L, MEMBER_ID)).thenReturn(true);

        ResponseEntity<Void> response = controller.delete(5L);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(accessKeyService).revoke(5L, MEMBER_ID);
    }

    @Test
    void delete_returns404_whenKeyIsNotTheCallers() {
        when(userContext.ownMemberId()).thenReturn(MEMBER_ID);
        when(accessKeyService.revoke(99L, MEMBER_ID)).thenReturn(false);

        assertThatThrownBy(() -> controller.delete(99L))
            .isInstanceOf(ResponseStatusException.class)
            .matches(ex -> ((ResponseStatusException) ex).getStatusCode() == HttpStatus.NOT_FOUND);
    }

    // ─── impersonation never applies to keys ─────────────────────────────────

    /**
     * An admin viewing a managed profile (?memberId=X) still manages their OWN keys: create binds
     * the key to the admin's AppUser, so list/throttle/revoke must resolve the same non-overridable
     * identity — otherwise the key just created is invisible and cannot be revoked from that view.
     */
    @Test
    void listCreateDelete_useOwnMemberId_neverTheImpersonationOverride() {
        when(userContext.ownMemberId()).thenReturn(MEMBER_ID);
        AppUser owner = AppUser.builder().build();
        when(userContext.currentUser()).thenReturn(owner);
        Set<String> scopes = Set.of("goals:read");
        AccessKey saved = sampleKey(3L, "Own", new LinkedHashSet<>(scopes),
            Instant.parse("2026-06-04T00:00:00Z"), null, null, null);
        when(accessKeyService.create(eq(owner), eq("Own"), eq(scopes), isNull()))
            .thenReturn(new GeneratedKey(saved, "psk_secretValueForOwnKey0000000x"));
        when(accessKeyService.list(MEMBER_ID)).thenReturn(List.of(saved));
        when(accessKeyService.revoke(3L, MEMBER_ID)).thenReturn(true);

        controller.create(new AccessKeyCreateRequest("Own", scopes, null));
        List<AccessKeyResponse> listed = controller.list();
        ResponseEntity<Void> deleted = controller.delete(3L);

        assertThat(listed).extracting(AccessKeyResponse::id).containsExactly(3L);
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);
        assertThat(createBuckets).containsOnlyKeys(MEMBER_ID);
        verify(accessKeyService).list(MEMBER_ID);
        verify(accessKeyService).revoke(3L, MEMBER_ID);
        verify(userContext, never()).currentMemberId();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static AccessKey sampleKey(Long id, String name, Set<String> scopes,
                                       Instant createdAt, Instant lastUsedAt,
                                       Instant expiresAt, Instant revokedAt) {
        AccessKey key = AccessKey.builder()
            .id(id)
            .name(name)
            .keyPrefix("psk_abcd1234")
            .scopes(scopes)
            .lastUsedAt(lastUsedAt)
            .expiresAt(expiresAt)
            .revokedAt(revokedAt)
            .build();
        // createdAt lives on AuditableEntity (set by JPA auditing in prod); inject it for the test.
        ReflectionTestUtils.setField(key, "createdAt", createdAt);
        return key;
    }
}
