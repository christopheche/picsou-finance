package com.picsou.controller;

import com.picsou.config.AuthCookieWriter;
import com.picsou.dto.SessionResponse;
import com.picsou.model.AppUser;
import com.picsou.model.PersistentSession;
import com.picsou.model.UserRole;
import com.picsou.service.PersistentSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    @Mock PersistentSessionService persistentSessionService;
    SessionController controller;

    AppUser user;
    MockHttpServletRequest httpReq;

    @BeforeEach
    void setUp() {
        controller = new SessionController(persistentSessionService);
        user = AppUser.builder()
            .id(7L).username("alice").role(UserRole.MEMBER).activated(true)
            .build();
        httpReq = new MockHttpServletRequest();
    }

    // ─── GET /sessions ───────────────────────────────────────────────────

    @Test
    void list_returnsActiveSessions_andMarksCurrent() {
        UUID seriesA = UUID.randomUUID();
        UUID seriesB = UUID.randomUUID();
        PersistentSession a = sessionWith(1L, seriesA, "Mac/Chrome", "10.0.0.", false);
        PersistentSession b = sessionWith(2L, seriesB, "iPhone/Safari", "192.168.1.", true);
        when(persistentSessionService.listActiveForUser(user)).thenReturn(List.of(a, b));

        // Cookie matches session B → it's flagged "current".
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "value-for-b"));
        when(persistentSessionService.seriesFromCookie("value-for-b"))
            .thenReturn(Optional.of(seriesB));

        List<SessionResponse> res = controller.list(user, httpReq);

        assertThat(res).hasSize(2);
        assertThat(res.get(0).id()).isEqualTo(1L);
        assertThat(res.get(0).current()).isFalse();
        assertThat(res.get(0).userAgent()).isEqualTo("Mac/Chrome");
        assertThat(res.get(0).ipPrefix()).isEqualTo("10.0.0.");
        assertThat(res.get(0).trustedFor2fa()).isFalse();

        assertThat(res.get(1).id()).isEqualTo(2L);
        assertThat(res.get(1).current()).isTrue();
        assertThat(res.get(1).trustedFor2fa()).isTrue();
    }

    @Test
    void list_returnsEmpty_andSkipsCookieLookup_whenNoCookie() {
        when(persistentSessionService.listActiveForUser(user)).thenReturn(List.of());

        List<SessionResponse> res = controller.list(user, httpReq);

        assertThat(res).isEmpty();
        verify(persistentSessionService, never()).seriesFromCookie(any());
    }

    @Test
    void list_doesNotMarkCurrent_whenCookieIsUnparseable() {
        UUID series = UUID.randomUUID();
        PersistentSession s = sessionWith(1L, series, "ua", "10.", false);
        when(persistentSessionService.listActiveForUser(user)).thenReturn(List.of(s));

        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "garbage"));
        when(persistentSessionService.seriesFromCookie("garbage")).thenReturn(Optional.empty());

        List<SessionResponse> res = controller.list(user, httpReq);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).current()).isFalse();
    }

    // ─── DELETE /sessions/{id} ───────────────────────────────────────────

    @Test
    void revoke_returns204_onSuccess() {
        when(persistentSessionService.revoke(42L, user)).thenReturn(true);

        ResponseEntity<?> res = controller.revoke(user, 42L);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void revoke_returns404_whenServiceReturnsFalse() {
        when(persistentSessionService.revoke(99L, user)).thenReturn(false);

        ResponseEntity<?> res = controller.revoke(user, 99L);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── DELETE /sessions (all-except-current) ───────────────────────────

    @Test
    void revokeAll_excludesCurrentSession_whenCookieMatches() {
        UUID seriesA = UUID.randomUUID();
        UUID seriesB = UUID.randomUUID();
        PersistentSession a = sessionWith(1L, seriesA, "ua", "10.", false);
        PersistentSession b = sessionWith(2L, seriesB, "ua", "10.", false);
        when(persistentSessionService.listActiveForUser(user)).thenReturn(List.of(a, b));

        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "v"));
        when(persistentSessionService.seriesFromCookie("v")).thenReturn(Optional.of(seriesB));

        ResponseEntity<Void> res = controller.revokeAllExceptCurrent(user, httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(persistentSessionService).revokeAllForUserExcept(7L, 2L);
        verify(persistentSessionService, never()).revokeAllForUser(anyLong());
    }

    @Test
    void revokeAll_revokesAll_whenNoCookieOrNoMatch() {
        // No cookie at all.
        ResponseEntity<Void> res = controller.revokeAllExceptCurrent(user, httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(persistentSessionService).revokeAllForUser(7L);
        verify(persistentSessionService, never()).revokeAllForUserExcept(anyLong(), anyLong());
    }

    @Test
    void revokeAll_revokesAll_whenCookieDoesntMatchAnyActiveSession() {
        UUID seriesA = UUID.randomUUID();
        UUID seriesUnknown = UUID.randomUUID();
        PersistentSession a = sessionWith(1L, seriesA, "ua", "10.", false);
        when(persistentSessionService.listActiveForUser(user)).thenReturn(List.of(a));

        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "v"));
        when(persistentSessionService.seriesFromCookie("v")).thenReturn(Optional.of(seriesUnknown));

        controller.revokeAllExceptCurrent(user, httpReq);

        verify(persistentSessionService).revokeAllForUser(7L);
        verify(persistentSessionService, never()).revokeAllForUserExcept(anyLong(), anyLong());
    }

    // ─── helper ──────────────────────────────────────────────────────────

    private PersistentSession sessionWith(Long id, UUID series, String ua, String ipPrefix, boolean trusted) {
        Instant now = Instant.now();
        return PersistentSession.builder()
            .id(id)
            .seriesId(series)
            .user(user)
            .tokenHash("h")
            .userAgent(ua)
            .ipPrefix(ipPrefix)
            .trustedFor2fa(trusted)
            .createdAt(now.minus(2, ChronoUnit.DAYS))
            .lastUsedAt(now)
            .expiresAt(now.plus(80, ChronoUnit.DAYS))
            .build();
    }
}
