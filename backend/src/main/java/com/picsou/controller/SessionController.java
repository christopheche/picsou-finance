package com.picsou.controller;

import com.picsou.config.AuthCookieWriter;
import com.picsou.dto.SessionResponse;
import com.picsou.model.AppUser;
import com.picsou.model.PersistentSession;
import com.picsou.service.PersistentSessionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Self-service "Active sessions" list for the authenticated user. Each entry
 * is a {@link PersistentSession} (Remember Me cookie). Users can revoke any
 * one or "log out everywhere else"; the request's own session is identified
 * by matching the persistent_token cookie's series_id and is excluded from
 * the bulk-revoke variant.
 */
@RestController
@RequestMapping("/api/auth/sessions")
public class SessionController {

    private final PersistentSessionService persistentSessionService;

    public SessionController(PersistentSessionService persistentSessionService) {
        this.persistentSessionService = persistentSessionService;
    }

    @GetMapping
    public List<SessionResponse> list(
        @AuthenticationPrincipal AppUser user,
        HttpServletRequest httpReq
    ) {
        UUID currentSeries = currentSeriesId(httpReq).orElse(null);
        return persistentSessionService.listActiveForUser(user).stream()
            .map(s -> toResponse(s, currentSeries))
            .toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> revoke(
        @AuthenticationPrincipal AppUser user,
        @PathVariable Long id
    ) {
        // Service returns false when the session doesn't exist OR doesn't belong
        // to this user — both collapse to 404 to avoid leaking other users' ids.
        if (!persistentSessionService.revoke(id, user)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Session not found"));
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> revokeAllExceptCurrent(
        @AuthenticationPrincipal AppUser user,
        HttpServletRequest httpReq
    ) {
        UUID currentSeries = currentSeriesId(httpReq).orElse(null);

        // If we can identify the current session, exclude it; otherwise nuke
        // them all (request had no persistent cookie => user is on access_token
        // only, no in-flight session to spare).
        Long exceptId = persistentSessionService.listActiveForUser(user).stream()
            .filter(s -> currentSeries != null && currentSeries.equals(s.getSeriesId()))
            .map(PersistentSession::getId)
            .findFirst()
            .orElse(null);

        if (exceptId != null) {
            persistentSessionService.revokeAllForUserExcept(user.getId(), exceptId);
        } else {
            persistentSessionService.revokeAllForUser(user.getId());
        }
        return ResponseEntity.noContent().build();
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private Optional<UUID> currentSeriesId(HttpServletRequest httpReq) {
        if (httpReq.getCookies() == null) return Optional.empty();
        for (Cookie c : httpReq.getCookies()) {
            if (AuthCookieWriter.PERSISTENT_COOKIE.equals(c.getName())) {
                return persistentSessionService.seriesFromCookie(c.getValue());
            }
        }
        return Optional.empty();
    }

    private static SessionResponse toResponse(PersistentSession s, UUID currentSeries) {
        return new SessionResponse(
            s.getId(),
            s.getUserAgent(),
            s.getIpPrefix(),
            s.getCreatedAt(),
            s.getLastUsedAt(),
            s.getExpiresAt(),
            s.isTrustedFor2fa(),
            currentSeries != null && currentSeries.equals(s.getSeriesId())
        );
    }
}
