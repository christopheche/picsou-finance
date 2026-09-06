package com.picsou.controller;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.JwtUtil;
import com.picsou.config.PersistentTokenAuthFilter;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.ActivationRequest;
import com.picsou.dto.LoginRequest;
import com.picsou.dto.MfaDtos;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.PersistentSession;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import com.picsou.service.SetupAuditService;
import io.github.bucket4j.Bucket;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import jakarta.servlet.http.Cookie;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock AppUserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtUtil jwtUtil;
    @Mock AuthCookieWriter cookieWriter;
    @Mock MfaService mfaService;
    @Mock PersistentSessionService persistentSessionService;
    @Mock SetupAuditService auditService;

    Map<String, Bucket> loginBuckets;
    Map<String, Bucket> mfaVerifyBuckets;
    Map<String, Bucket> reauthBuckets;
    AuthController controller;
    MockHttpServletRequest httpReq;
    MockHttpServletResponse httpRes;

    @BeforeEach
    void setUp() {
        loginBuckets = new HashMap<>();
        mfaVerifyBuckets = new HashMap<>();
        reauthBuckets = new HashMap<>();
        controller = newController(false);
        httpReq = new MockHttpServletRequest();
        httpReq.setRemoteAddr("10.0.0.5");
        httpRes = new MockHttpServletResponse();
    }

    private AuthController newController(boolean adminRecoveryEnabled) {
        return new AuthController(
            userRepository, passwordEncoder, jwtUtil,
            loginBuckets, mfaVerifyBuckets, reauthBuckets, cookieWriter,
            mfaService, persistentSessionService, auditService,
            adminRecoveryEnabled
        );
    }

    private AppUser user(boolean activated) {
        FamilyMember member = FamilyMember.builder()
            .id(42L).displayName("Alice").build();
        return AppUser.builder()
            .id(7L).username("alice")
            .role(UserRole.ADMIN)
            .passwordHash("$2a$12$hash")
            .activated(activated)
            .tokenVersion(3L)
            .member(member)
            .build();
    }

    /** A persistent-session row as {@code validateAndRotate} would hand it back. */
    private PersistentSession session(long ownerId, UUID series, boolean trusted) {
        return PersistentSession.builder()
            .id(1L)
            .seriesId(series)
            .user(AppUser.builder().id(ownerId).username("owner").build())
            .tokenHash("h")
            .trustedFor2fa(trusted)
            .createdAt(Instant.now().minus(1, ChronoUnit.DAYS))
            .lastUsedAt(Instant.now())
            .expiresAt(Instant.now().plus(80, ChronoUnit.DAYS))
            .build();
    }

    /** Arranges an activated MFA-enabled "alice" whose password check passes. */
    private AppUser mfaUserWithGoodPassword() {
        AppUser active = user(true);
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);
        when(mfaService.isEnabled(active)).thenReturn(true);
        return active;
    }

    // ─── login ───────────────────────────────────────────────────────────

    @Test
    void login_returns403_andSetsNoCookies_whenAccountNotActivated() {
        AppUser deactivated = user(false);
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(deactivated));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(((ProblemDetail) res.getBody()).getDetail()).containsIgnoringCase("not activated");
        // No session must be established for a deactivated account.
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(mfaService, never()).isEnabled(any());
    }

    @Test
    void login_returns403_withConsoleHint_whenRecoveryEnabled_evenWithWrongPassword() {
        AppUser deactivatedAdmin = user(false); // ADMIN, is_activated=false
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(deactivatedAdmin));
        AuthController recoveryController = newController(true);

        ResponseEntity<?> res = recoveryController.login(
            new LoginRequest("alice", "whatever-they-typed", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ProblemDetail body = (ProblemDetail) res.getBody();
        assertThat(body.getDetail())
            .containsIgnoringCase("console")
            .contains("ADMIN_RECOVERY_ENABLED=false");
        // Hint fires before (and regardless of) the password check — no oracle, no cookies.
        verify(passwordEncoder, never()).matches(any(), any());
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void login_proceeds_whenActivated_noMfa() {
        AppUser active = user(true);
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);
        when(mfaService.isEnabled(active)).thenReturn(false);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref");

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref", false);
    }

    @Test
    void login_mfaRequired_seversLingeringSessionCookies_beforeIssuingChallenge() {
        AppUser active = user(true);
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);
        when(mfaService.isEnabled(active)).thenReturn(true);
        // A DIFFERENT identity's "Remember Me" cookie is sitting on this browser. The
        // persistent filter did not stamp the request, so the controller checks the hash
        // itself -- and a stale/foreign token fails that check.
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "stale-admin-cookie"));
        when(persistentSessionService.validateAndRotate("stale-admin-cookie")).thenReturn(Optional.empty());
        when(jwtUtil.generateMfaChallengeToken(active, false)).thenReturn("chal");

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) res.getBody()).get("mfaRequired")).isEqualTo(true);
        // The lingering session is severed and NO access/refresh is issued: the
        // caller stays unauthenticated until the second factor is verified.
        verify(cookieWriter).clearSessionCookies(httpRes);
        verify(cookieWriter).setMfaChallenge(httpRes, "chal");
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void login_noMfa_dropsForeignPersistentCookie_whenNotRemembering() {
        AppUser active = user(true); // id 7L
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);
        when(mfaService.isEnabled(active)).thenReturn(false);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref");
        // A "Remember Me" cookie owned by a DIFFERENT user (99) lingers on the browser.
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "foreign"));
        when(persistentSessionService.ownerUserId("foreign")).thenReturn(Optional.of(99L));

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref", false);
        // The foreign Remember-Me cookie is cleared so it can't re-mint user 99's session.
        verify(cookieWriter).clearPersistent(httpRes);
    }

    @Test
    void login_noMfa_keepsOwnPersistentCookie_whenNotRemembering() {
        AppUser active = user(true); // id 7L
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);
        when(mfaService.isEnabled(active)).thenReturn(false);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref");
        // The lingering cookie belongs to THIS user (a trusted device not re-ticking
        // Remember Me) — it must be preserved, not cleared.
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "mine"));
        when(persistentSessionService.ownerUserId("mine")).thenReturn(Optional.of(7L));

        controller.login(new LoginRequest("alice", "pw", false), httpReq, httpRes);

        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref", false);
        verify(cookieWriter, never()).clearPersistent(httpRes);
    }

    // ─── login: trusted-device MFA skip requires a hash-validated cookie ──

    @Test
    void login_mfa_forgedPersistentCookie_withForeignPrincipal_stillRequiresMfa() {
        // Attack: victim's password + a stale copy of her persistent cookie (series id is
        // not a secret) + ANY valid access_token (e.g. the attacker's own family login),
        // which makes PersistentTokenAuthFilter bail out before validating the hash. The
        // controller must then validate the hash itself instead of trusting the series id.
        AppUser active = mfaUserWithGoodPassword();
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "victim-series:garbage"));
        // No VALIDATED_SERIES_ATTR on the request; the hash check fails (theft detection).
        when(persistentSessionService.validateAndRotate("victim-series:garbage")).thenReturn(Optional.empty());
        when(jwtUtil.generateMfaChallengeToken(active, false)).thenReturn("chal");

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) res.getBody()).get("mfaRequired")).isEqualTo(true);
        // The series-id-only lookup is never consulted on an unvalidated cookie.
        verify(persistentSessionService, never()).isTrustedDeviceFor(any(), any());
        verify(cookieWriter).clearSessionCookies(httpRes);
        verify(cookieWriter).setMfaChallenge(httpRes, "chal");
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), anyBoolean());
    }

    @Test
    void login_mfa_validCookieOfAnotherUser_stillRequiresMfa() {
        // The cookie is genuine (hash matches) but the series belongs to user 99, not alice.
        AppUser active = mfaUserWithGoodPassword();
        UUID series = UUID.randomUUID();
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, series + ":tok"));
        when(persistentSessionService.validateAndRotate(series + ":tok")).thenReturn(Optional.of(
            new PersistentSessionService.ValidationResult(series + ":rotated", session(99L, series, true))));
        when(jwtUtil.generateMfaChallengeToken(active, false)).thenReturn("chal");

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(((Map<?, ?>) res.getBody()).get("mfaRequired")).isEqualTo(true);
        verify(cookieWriter).clearSessionCookies(httpRes);
        // The rotated value is not handed back: the foreign cookie is being cleared anyway.
        verify(cookieWriter, never()).setPersistent(any(), any(), anyLong());
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), anyBoolean());
    }

    @Test
    void login_mfa_trustedDevice_skipsMfa_whenFilterValidatedHash() {
        // Legit path 1: no access_token on the request, so PersistentTokenAuthFilter
        // validated + rotated the cookie and stamped its series on the request.
        AppUser active = mfaUserWithGoodPassword();
        UUID series = UUID.randomUUID();
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "mine"));
        httpReq.setAttribute(PersistentTokenAuthFilter.VALIDATED_SERIES_ATTR, series);
        when(persistentSessionService.seriesFromCookie("mine")).thenReturn(Optional.of(series));
        when(persistentSessionService.isTrustedDeviceFor(active, "mine")).thenReturn(true);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active, series)).thenReturn("ref");

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) res.getBody()).containsKey("mfaRequired")).isFalse();
        verify(cookieWriter, never()).setMfaChallenge(any(), any());
        verify(cookieWriter, never()).clearSessionCookies(any());
        // Remember-Me TTLs, refresh bound to the trusted series (sid).
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref", true);
        // The filter already rotated: no second validation (it would only pass via the grace
        // window and emit a conflicting Set-Cookie) and no new series.
        verify(persistentSessionService, never()).validateAndRotate(any());
        verify(persistentSessionService, never()).issue(any(), anyBoolean(), any(), any());
        verify(cookieWriter, never()).setPersistent(any(), any(), anyLong());
    }

    @Test
    void login_mfa_filterStampForAnotherSeries_doesNotTrustThisCookie() {
        // Defensive: the stamp must name THIS cookie's series, not just exist.
        AppUser active = mfaUserWithGoodPassword();
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "mine"));
        httpReq.setAttribute(PersistentTokenAuthFilter.VALIDATED_SERIES_ATTR, UUID.randomUUID());
        when(persistentSessionService.seriesFromCookie("mine")).thenReturn(Optional.of(UUID.randomUUID()));
        when(jwtUtil.generateMfaChallengeToken(active, false)).thenReturn("chal");

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(((Map<?, ?>) res.getBody()).get("mfaRequired")).isEqualTo(true);
        verify(persistentSessionService, never()).isTrustedDeviceFor(any(), any());
    }

    @Test
    void login_mfa_trustedDevice_skipsMfa_whenControllerValidatesHash_underAccessTokenPrincipal() {
        // Legit path 2: a still-valid access_token of the SAME user made the filter bail
        // out. The controller validates + rotates the cookie itself and writes the rotated
        // value, exactly as the filter would have.
        AppUser active = mfaUserWithGoodPassword(); // id 7L
        UUID series = UUID.randomUUID();
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, series + ":tok"));
        when(persistentSessionService.validateAndRotate(series + ":tok")).thenReturn(Optional.of(
            new PersistentSessionService.ValidationResult(series + ":rotated", session(7L, series, true))));
        when(persistentSessionService.seriesFromCookie(series + ":tok")).thenReturn(Optional.of(series));
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active, series)).thenReturn("ref");

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cookieWriter, never()).setMfaChallenge(any(), any());
        verify(cookieWriter).setPersistent(eq(httpRes), eq(series + ":rotated"), anyLong());
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref", true);
        verify(persistentSessionService, never()).isTrustedDeviceFor(any(), any());
        verify(persistentSessionService, never()).issue(any(), anyBoolean(), any(), any());
    }

    @Test
    void login_mfa_untrustedOwnCookie_stillRequiresMfa_evenWhenHashValid() {
        // Own cookie, valid hash, but the series was never marked trusted_for_2fa.
        AppUser active = mfaUserWithGoodPassword();
        UUID series = UUID.randomUUID();
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, series + ":tok"));
        when(persistentSessionService.validateAndRotate(series + ":tok")).thenReturn(Optional.of(
            new PersistentSessionService.ValidationResult(series + ":rotated", session(7L, series, false))));
        when(jwtUtil.generateMfaChallengeToken(active, false)).thenReturn("chal");

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(((Map<?, ?>) res.getBody()).get("mfaRequired")).isEqualTo(true);
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), anyBoolean());
    }

    @Test
    void login_trustedDevice_withRememberMe_keepsTrustedSeries() {
        // Re-ticking Remember Me on an already-trusted device must NOT mint a fresh
        // (untrusted) series: that would overwrite the trusted cookie with one the filter
        // discards at first use, and orphan the trusted row in Settings -> Sessions.
        AppUser active = mfaUserWithGoodPassword();
        UUID series = UUID.randomUUID();
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "mine"));
        httpReq.setAttribute(PersistentTokenAuthFilter.VALIDATED_SERIES_ATTR, series);
        when(persistentSessionService.seriesFromCookie("mine")).thenReturn(Optional.of(series));
        when(persistentSessionService.isTrustedDeviceFor(active, "mine")).thenReturn(true);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active, series)).thenReturn("ref");

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", true), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(persistentSessionService, never()).issue(any(), anyBoolean(), any(), any());
        verify(cookieWriter, never()).setPersistent(any(), any(), anyLong());
        verify(cookieWriter, never()).clearPersistent(any());
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref", true);
    }

    @Test
    void login_unknownUserAndWrongPassword_payIdenticalBcryptCost_soLatencyRevealsNothing() {
        // Drive the controller with a REAL bcrypt encoder (strength 12, same as prod —
        // see SecurityConfig) so the dummy-hash comparison performs genuine work, which
        // is the whole point of the timing fix. A spy lets us count the bcrypt calls and
        // inspect the hashes they ran against.
        PasswordEncoder realEncoder = spy(new BCryptPasswordEncoder(12));
        AuthController timingController = new AuthController(
            userRepository, realEncoder, jwtUtil,
            loginBuckets, mfaVerifyBuckets, reauthBuckets, cookieWriter,
            mfaService, persistentSessionService, auditService, false);

        // Path A — the username does not exist: there is no stored hash to compare,
        // yet the request must still cost a full bcrypt round against the dummy hash.
        when(userRepository.findByUsernameWithMember("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> timingController.login(
                new LoginRequest("ghost", "pw", false), httpReq, httpRes))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Invalid credentials");

        // Path B — the username exists but the password is wrong. The stored hash is a
        // real bcrypt hash of a DIFFERENT secret, so "pw" does not match.
        AppUser known = AppUser.builder()
            .id(7L).username("alice")
            .role(UserRole.ADMIN)
            .passwordHash(realEncoder.encode("the-real-password"))
            .activated(true)
            .tokenVersion(3L)
            .member(FamilyMember.builder().id(42L).displayName("Alice").build())
            .build();
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(known));
        assertThatThrownBy(() -> timingController.login(
                new LoginRequest("alice", "pw", false), httpReq, httpRes))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Invalid credentials");

        // The two failing paths are indistinguishable by wall-clock time: each ran
        // EXACTLY one bcrypt comparison, each against a real $2a$12$ hash of the SAME
        // cost factor, each fed the submitted password. Path A used the constructor's
        // dummy hash, Path B the user's stored hash — same work, no enumeration oracle.
        ArgumentCaptor<String> hashUsed = ArgumentCaptor.forClass(String.class);
        verify(realEncoder, times(2)).matches(eq("pw"), hashUsed.capture());
        assertThat(hashUsed.getAllValues())
            .hasSize(2)
            .allSatisfy(h -> assertThat(h).startsWith("$2a$12$"));

        // ...and neither failing path leaks a session.
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void login_pendingActivationMember_blankHash_paysOneRealBcryptRound_andReturns401() {
        // Same real-encoder spy pattern as the timing test above. A managed member
        // that has only been issued an activation link still has a BLANK password
        // hash; matches(pw, "") would return false instantly without bcrypt, leaking
        // "this profile exists, pending activation" by latency (CWE-208). The fix
        // runs the dummy-hash bcrypt round and fails like a wrong password instead.
        PasswordEncoder realEncoder = spy(new BCryptPasswordEncoder(12));
        AuthController timingController = new AuthController(
            userRepository, realEncoder, jwtUtil,
            loginBuckets, mfaVerifyBuckets, reauthBuckets, cookieWriter,
            mfaService, persistentSessionService, auditService, false);

        AppUser pending = AppUser.builder()
            .id(11L).username("bob")
            .role(UserRole.MEMBER)
            .passwordHash("")          // invited but not yet activated
            .activated(false)
            .tokenVersion(0L)
            .member(FamilyMember.builder().id(50L).displayName("Bob").build())
            .build();
        when(userRepository.findByUsernameWithMember("bob")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> timingController.login(
                new LoginRequest("bob", "pw", false), httpReq, httpRes))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Invalid credentials");

        // Exactly ONE real bcrypt comparison, run against the constructor's $2a$12$
        // dummy hash — same cost as the unknown-user and wrong-password paths, so a
        // pending-activation profile is indistinguishable from "no such user".
        ArgumentCaptor<String> hashUsed = ArgumentCaptor.forClass(String.class);
        verify(realEncoder, times(1)).matches(eq("pw"), hashUsed.capture());
        assertThat(hashUsed.getValue()).startsWith("$2a$12$");

        // No session is established, and a credential-less profile never reaches MFA.
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(mfaService, never()).isEnabled(any());
    }

    @Test
    void login_ratelimitKey_isXRealIp_notSpoofableXForwardedFor() {
        // Regression for the leftmost-XFF spoofing bug: request.getRemoteAddr() is tainted by
        // ForwardedHeaderFilter from whatever the client puts in X-Forwarded-For (nginx only
        // appends, never replaces), so a raw client could rotate it and get a fresh bucket every
        // request. ClientIp.resolve() must key on nginx's X-Real-IP instead, which stays constant
        // for the same real client regardless of the spoofed XFF value.
        @SuppressWarnings("unchecked")
        Map<String, Bucket> mockedLoginBuckets = mock(Map.class);
        Bucket bucket = mock(Bucket.class);
        when(bucket.tryConsume(1)).thenReturn(true);
        when(mockedLoginBuckets.computeIfAbsent(any(), any())).thenReturn(bucket);
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.empty());

        AuthController spoofTestController = new AuthController(
            userRepository, passwordEncoder, jwtUtil,
            mockedLoginBuckets, mfaVerifyBuckets, reauthBuckets, cookieWriter,
            mfaService, persistentSessionService, auditService, false);

        MockHttpServletRequest firstCall = new MockHttpServletRequest();
        firstCall.setRemoteAddr("172.18.0.2");
        firstCall.addHeader("X-Forwarded-For", "1.1.1.1"); // attacker-supplied, rotates per request
        firstCall.addHeader("X-Real-IP", "203.0.113.9");   // nginx-observed peer, stable

        MockHttpServletRequest secondCall = new MockHttpServletRequest();
        secondCall.setRemoteAddr("172.18.0.2");
        secondCall.addHeader("X-Forwarded-For", "9.9.9.9"); // rotated -- same attacker, new value
        secondCall.addHeader("X-Real-IP", "203.0.113.9");   // unchanged: same real client

        assertThatThrownBy(() -> spoofTestController.login(
                new LoginRequest("alice", "pw", false), firstCall, httpRes))
            .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> spoofTestController.login(
                new LoginRequest("alice", "pw", false), secondCall, httpRes))
            .isInstanceOf(BadCredentialsException.class);

        // Both calls looked up the SAME key, despite the different X-Forwarded-For each time.
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockedLoginBuckets, times(2)).computeIfAbsent(keyCaptor.capture(), any());
        assertThat(keyCaptor.getAllValues()).containsOnly("203.0.113.9");
    }

    // ─── activate ────────────────────────────────────────────────────────

    @Test
    void activate_bumpsTokenVersion_andRevokesPersistentSessions() {
        // M2: activate() is the shared sink for new-member activation, admin-initiated
        // password reset, and admin-recovery completion. Like change-password, it must
        // invalidate every pre-existing session so a compromised member's old JWTs and
        // Remember-Me cookies don't survive the reset (CWE-613/640).
        AppUser member = AppUser.builder()
            .id(11L).username("bob")
            .role(UserRole.MEMBER)
            .passwordHash("")
            .activated(false)
            .tokenVersion(5L)
            .activationToken("tok")
            .activationTokenExpires(Instant.now().plus(1, ChronoUnit.HOURS))
            .member(FamilyMember.builder().id(50L).displayName("Bob").build())
            .build();
        when(userRepository.findByActivationToken("tok")).thenReturn(Optional.of(member));

        ResponseEntity<?> res = controller.activate(
            "tok", new ActivationRequest("new-password-123", true), httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(member.isActivated()).isTrue();
        // tokenVersion bumped 5 -> 6 invalidates every outstanding access/refresh JWT.
        assertThat(member.getTokenVersion()).isEqualTo(6L);
        // ...and every Remember-Me persistent session for this user is wiped.
        verify(persistentSessionService).revokeAllForUser(11L);
    }

    @Test
    void activate_expiredToken_isA400ProblemDetail_withAReadableDetail() {
        // ProblemDetail, not {"error": …}: the frontend reads `detail`, so an ad-hoc map
        // would have shown the generic fallback instead of "the link expired".
        AppUser member = AppUser.builder()
            .id(11L).username("bob").role(UserRole.MEMBER).passwordHash("").activated(false)
            .activationToken("tok")
            .activationTokenExpires(Instant.now().minus(1, ChronoUnit.HOURS))
            .build();
        when(userRepository.findByActivationToken("tok")).thenReturn(Optional.of(member));

        ResponseEntity<?> res = controller.activate(
            "tok", new ActivationRequest("new-password-123", true), httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(((ProblemDetail) res.getBody()).getDetail()).containsIgnoringCase("expired");
        assertThat(member.isActivated()).isFalse();
        verify(userRepository, never()).save(any());
    }

    @Test
    void activate_withoutAcknowledgement_isA400ProblemDetail() {
        AppUser member = AppUser.builder()
            .id(11L).username("bob").role(UserRole.MEMBER).passwordHash("").activated(false)
            .activationToken("tok")
            .activationTokenExpires(Instant.now().plus(1, ChronoUnit.HOURS))
            .build();
        when(userRepository.findByActivationToken("tok")).thenReturn(Optional.of(member));

        ResponseEntity<?> res = controller.activate(
            "tok", new ActivationRequest("new-password-123", false), httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(((ProblemDetail) res.getBody()).getDetail()).containsIgnoringCase("acknowledge");
        verify(userRepository, never()).save(any());
    }

    @Test
    void activate_returns400ProblemDetail_whenTokenExpired() {
        // RFC 7807 like every other error: the frontend only reads `detail`, so an ad-hoc
        // {"error": ...} body would leave the member with axios boilerplate instead of
        // "the link expired, ask the admin for a new one".
        AppUser member = AppUser.builder()
            .id(11L).username("bob")
            .role(UserRole.MEMBER)
            .passwordHash("")
            .activated(false)
            .tokenVersion(5L)
            .activationToken("tok")
            .activationTokenExpires(Instant.now().minus(1, ChronoUnit.HOURS))
            .member(FamilyMember.builder().id(50L).displayName("Bob").build())
            .build();
        when(userRepository.findByActivationToken("tok")).thenReturn(Optional.of(member));

        ResponseEntity<?> res = controller.activate(
            "tok", new ActivationRequest("new-password-123", true), httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(((ProblemDetail) res.getBody()).getDetail()).containsIgnoringCase("expired");
        assertThat(member.isActivated()).isFalse();
        verify(userRepository, never()).save(any());
        verify(persistentSessionService, never()).revokeAllForUser(any());
    }

    // ─── mfa/verify ──────────────────────────────────────────────────────

    /** A parsed, signed mfa_challenge for user 7 sitting on the request. */
    private Claims challengeFor(long uid) {
        httpReq.setCookies(new Cookie(AuthCookieWriter.MFA_CHALLENGE_COOKIE, "challenge"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("challenge")).thenReturn(claims);
        when(jwtUtil.isMfaChallengeToken(claims)).thenReturn(true);
        when(claims.get("uid", Long.class)).thenReturn(uid);
        return claims;
    }

    @Test
    void mfaVerify_rememberMeWithoutTrust_issuesNoPersistentSession() {
        // A 2FA user who ticked Remember Me but declined "Trust this device": the persistent
        // filter would refuse (rotate, then clear) an untrusted persistent_token for an MFA
        // user, so issuing one would only leave a phantom "active session" behind. Remember
        // Me without trust is a normal session-scoped login.
        AppUser active = user(true);
        challengeFor(7L);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(active));
        when(mfaService.verifyTotpOrRecovery(active, "123456", false)).thenReturn(true);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref");

        ResponseEntity<?> res = controller.mfaVerify(
            new MfaDtos.MfaVerifyRequest("123456", false, false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(persistentSessionService, never()).issue(any(), anyBoolean(), any(), any());
        verify(cookieWriter, never()).setPersistent(any(), any(), anyLong());
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref", false);
        verify(cookieWriter).clearMfaChallenge(httpRes);
    }

    @Test
    void mfaVerify_trustDevice_issuesTrustedPersistentSession() {
        AppUser active = user(true);
        UUID series = UUID.randomUUID();
        challengeFor(7L);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(active));
        when(mfaService.verifyTotpOrRecovery(active, "123456", false)).thenReturn(true);
        when(persistentSessionService.issue(eq(active), eq(true), any(), any())).thenReturn(
            new PersistentSessionService.IssueResult(series + ":fresh", session(7L, series, true)));
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active, series)).thenReturn("ref");

        ResponseEntity<?> res = controller.mfaVerify(
            new MfaDtos.MfaVerifyRequest("123456", true, false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cookieWriter).setPersistent(eq(httpRes), eq(series + ":fresh"), anyLong());
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref", true);
    }

    @Test
    void mfaVerify_ratelimitKey_isChallengeUid_notClientIp() {
        // Two attempts for the SAME account from two different addresses share one bucket,
        // and (conversely) a family behind one NAT is not locked out by one member's typos.
        @SuppressWarnings("unchecked")
        Map<String, Bucket> mockedBuckets = mock(Map.class);
        Bucket bucket = mock(Bucket.class);
        when(bucket.tryConsume(1)).thenReturn(true);
        when(mockedBuckets.computeIfAbsent(any(), any())).thenReturn(bucket);
        AuthController keyController = new AuthController(
            userRepository, passwordEncoder, jwtUtil,
            loginBuckets, mockedBuckets, reauthBuckets, cookieWriter,
            mfaService, persistentSessionService, auditService, false);
        AppUser active = user(true);
        challengeFor(7L);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(active));
        when(mfaService.verifyTotpOrRecovery(active, "000000", false)).thenReturn(false);

        MockHttpServletRequest fromHome = new MockHttpServletRequest();
        fromHome.setRemoteAddr("10.0.0.5");
        fromHome.setCookies(httpReq.getCookies());
        MockHttpServletRequest fromPhone = new MockHttpServletRequest();
        fromPhone.setRemoteAddr("203.0.113.9");
        fromPhone.setCookies(httpReq.getCookies());

        keyController.mfaVerify(new MfaDtos.MfaVerifyRequest("000000", false, false), fromHome, httpRes);
        keyController.mfaVerify(new MfaDtos.MfaVerifyRequest("000000", false, false), fromPhone, httpRes);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockedBuckets, times(2)).computeIfAbsent(keyCaptor.capture(), any());
        assertThat(keyCaptor.getAllValues()).containsOnly("7");
    }

    @Test
    void mfaVerify_returns429_andClearsChallenge_whenAccountBucketExhausted() {
        challengeFor(7L);
        Bucket drained = RateLimitConfig.createMfaVerifyBucket();
        while (drained.tryConsume(1)) { /* drain */ }
        mfaVerifyBuckets.put("7", drained);

        ResponseEntity<?> res = controller.mfaVerify(
            new MfaDtos.MfaVerifyRequest("123456", false, false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(res.getBody()).isInstanceOf(ProblemDetail.class);
        verify(cookieWriter).clearMfaChallenge(httpRes);
        verify(mfaService, never()).verifyTotpOrRecovery(any(), any(), anyBoolean());
        verify(userRepository, never()).findByIdWithMember(any());
    }

    @Test
    void mfaVerify_returns401ProblemDetail_whenCookieIsNotAChallengeToken() {
        httpReq.setCookies(new Cookie(AuthCookieWriter.MFA_CHALLENGE_COOKIE, "an-access-token"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("an-access-token")).thenReturn(claims);
        when(jwtUtil.isMfaChallengeToken(claims)).thenReturn(false);

        ResponseEntity<?> res = controller.mfaVerify(
            new MfaDtos.MfaVerifyRequest("123456", false, false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(((ProblemDetail) res.getBody()).getDetail()).isEqualTo("Invalid MFA challenge");
        verify(cookieWriter).clearMfaChallenge(httpRes);
    }

    @Test
    void mfaVerify_returns400_andKeepsChallenge_whenCodeInvalid() {
        AppUser active = user(true);
        httpReq.setCookies(new Cookie(AuthCookieWriter.MFA_CHALLENGE_COOKIE, "challenge"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("challenge")).thenReturn(claims);
        when(jwtUtil.isMfaChallengeToken(claims)).thenReturn(true);
        when(claims.get("uid", Long.class)).thenReturn(7L);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(active));
        when(mfaService.verifyTotpOrRecovery(active, "000000", false)).thenReturn(false);

        ResponseEntity<?> res = controller.mfaVerify(
            new com.picsou.dto.MfaDtos.MfaVerifyRequest("000000", false, false), httpReq, httpRes);

        // 400, not 401: the challenge is still valid, only the code is wrong. The
        // frontend keeps the user on the page to retry instead of bouncing to /login.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(cookieWriter, never()).clearMfaChallenge(httpRes);
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void mfaVerify_returns401_whenNoChallengeCookie() {
        ResponseEntity<?> res = controller.mfaVerify(
            new com.picsou.dto.MfaDtos.MfaVerifyRequest("123456", false, false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(mfaService, never()).verifyTotpOrRecovery(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ─── refresh ─────────────────────────────────────────────────────────

    @Test
    void refresh_returns401_andClearsCookies_whenAccountNotActivated() {
        AppUser deactivated = user(false);
        httpReq.setCookies(new Cookie("refresh_token", "rt"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("rt")).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(claims.getSubject()).thenReturn("alice");
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(deactivated));
        when(jwtUtil.getTokenVersion(claims)).thenReturn(3L); // matches user.tokenVersion

        ResponseEntity<?> res = controller.refresh(null, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(cookieWriter).clearAuthCookies(httpRes);
        verify(jwtUtil, never()).generateAccessToken(any());
    }

    @Test
    void refresh_rotatesTokens_asSessionCookies_whenActivated_andTvMatches_andNoRememberMe() {
        AppUser active = user(true);
        httpReq.setCookies(new Cookie("refresh_token", "rt"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("rt")).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(claims.getSubject()).thenReturn("alice");
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(jwtUtil.getTokenVersion(claims)).thenReturn(3L);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc2");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref2");

        ResponseEntity<?> res = controller.refresh(null, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        // No persistent_token cookie on this request -> rotated as session cookies, so a
        // non-"Remember Me" login can't be silently resurrected past the browser closing.
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc2", "ref2", false);
    }

    @Test
    void refresh_rotatesTokens_asPersistentCookies_whenPersistentTokenCookiePresentAndOwned() {
        AppUser active = user(true); // id 7L
        httpReq.setCookies(
            new Cookie("refresh_token", "rt"),
            new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "mine"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("rt")).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(claims.getSubject()).thenReturn("alice");
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(jwtUtil.getTokenVersion(claims)).thenReturn(3L);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc2");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref2");
        when(persistentSessionService.ownerUserId("mine")).thenReturn(Optional.of(7L));

        ResponseEntity<?> res = controller.refresh(null, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc2", "ref2", true);
    }

    @Test
    void refresh_returns200_andMintsFreshCookies_fromPersistentPrincipal_whenNoRefreshTokenCookie() {
        // No refresh_token cookie in the request, but PersistentTokenAuthFilter already
        // re-authenticated this request from a valid persistent_token one filter earlier
        // (hash validated + rotated, request stamped with the series).
        AppUser active = user(true);
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "mine"));
        httpReq.setAttribute(PersistentTokenAuthFilter.VALIDATED_SERIES_ATTR, UUID.randomUUID());
        when(persistentSessionService.ownerUserId("mine")).thenReturn(Optional.of(active.getId()));
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc3");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref3");

        ResponseEntity<?> res = controller.refresh(active, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo(Map.of(
            "username", active.getUsername(),
            "role", active.getRole().name(),
            "memberId", active.getMember().getId(),
            "displayName", active.getMember().getDisplayName()
        ));
        // The endpoint's contract is "200 = fresh cookies were issued" -- always mint here,
        // regardless of whether PersistentTokenAuthFilter already wrote a pair moments ago,
        // rather than trusting what the filter may or may not have written.
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc3", "ref3", true);
    }

    @Test
    void refresh_returns401_whenOnlyAccessTokenPrincipal_andNoRefreshOrPersistentCookie() {
        // The principal came from JwtAuthenticationFilter (a bare access_token): no
        // VALIDATED_SERIES_ATTR stamp. A 15-minute credential must not be exchangeable for a
        // fresh 7-day refresh_token, otherwise its short TTL contains nothing.
        AppUser active = user(true);

        ResponseEntity<?> res = controller.refresh(active, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(cookieWriter).clearAuthCookies(httpRes);
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), anyBoolean());
        verify(jwtUtil, never()).generateRefreshToken(any());
        verify(jwtUtil, never()).generateRefreshToken(any(), any());
    }

    @Test
    void refresh_returns401_whenAccessTokenPrincipal_presentsOwnedButUnvalidatedPersistentCookie() {
        // Ownership of the series (a series-id-only lookup) is NOT proof of possession: the
        // persistent filter skipped the cookie because the access_token authenticated the
        // request, so nothing checked its hash. No stamp -> no re-mint.
        AppUser active = user(true);
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "mine"));

        ResponseEntity<?> res = controller.refresh(active, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), anyBoolean());
    }

    @Test
    void refresh_returns401_whenNoRefreshTokenCookie_andNoPersistentPrincipal() {
        ResponseEntity<?> res = controller.refresh(null, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(cookieWriter).clearAuthCookies(httpRes);
    }

    @Test
    void refresh_honoursPersistentPrincipal_whenRefreshTokenPresentButTokenVersionRevoked() {
        // Concrete trigger from the review: something (e.g. AdminRecoveryRunner) bumps
        // tokenVersion without revoking persistent sessions. The stale refresh_token cookie
        // is now invalid, but a still-valid persistent_token was ALSO presented on this same
        // request and PersistentTokenAuthFilter already re-authenticated it -- that must win
        // instead of a flat 401, and cookies must not be cleared out from under the filter's
        // just-rotated persistent_token.
        AppUser active = user(true);
        httpReq.setCookies(
            new Cookie("refresh_token", "stale-rt"),
            new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "mine"));
        httpReq.setAttribute(PersistentTokenAuthFilter.VALIDATED_SERIES_ATTR, UUID.randomUUID());
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("stale-rt")).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(claims.getSubject()).thenReturn("alice");
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(jwtUtil.getTokenVersion(claims)).thenReturn(1L); // stale -- user is now at tv=3
        when(persistentSessionService.ownerUserId("mine")).thenReturn(Optional.of(active.getId()));
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc4");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref4");

        ResponseEntity<?> res = controller.refresh(active, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc4", "ref4", true);
        verify(cookieWriter, never()).clearAuthCookies(any());
    }

    @Test
    void refresh_returns401_andClears_whenRefreshTokenBoundToRevokedSeries() {
        // "Log out this device": the persistent session was revoked, but the device still
        // holds a cryptographically-valid, series-bound refresh_token. It must be cut -- and
        // must NOT fall through to a re-mint from the still-valid access-token principal.
        AppUser active = user(true);
        UUID revokedSeries = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        httpReq.setCookies(new Cookie("refresh_token", "rt"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("rt")).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(claims.getSubject()).thenReturn("alice");
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(jwtUtil.getTokenVersion(claims)).thenReturn(3L);
        when(jwtUtil.getSeriesId(claims)).thenReturn(revokedSeries);
        when(persistentSessionService.isSeriesActive(revokedSeries)).thenReturn(false);

        ResponseEntity<?> res = controller.refresh(active, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(cookieWriter).clearAuthCookies(httpRes);
        verify(jwtUtil, never()).generateAccessToken(any());
    }

    @Test
    void refresh_returns401_whenPersistentCookieSeriesRevoked_evenWithValidAccessPrincipal() {
        // The device presents a persistent_token whose series was revoked ("log out
        // everywhere else"). Even a still-valid access-token principal on the same request
        // must not re-establish the session -- the front guard fires before any minting.
        AppUser active = user(true);
        UUID revokedSeries = UUID.fromString("dddddddd-eeee-ffff-0000-111111111111");
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "revoked:tok"));
        when(persistentSessionService.seriesFromCookie("revoked:tok")).thenReturn(Optional.of(revokedSeries));
        when(persistentSessionService.isSeriesActive(revokedSeries)).thenReturn(false);

        ResponseEntity<?> res = controller.refresh(active, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(cookieWriter).clearAuthCookies(httpRes);
        verify(jwtUtil, never()).generateAccessToken(any());
    }

    // ─── logout ──────────────────────────────────────────────────────────

    @Test
    void logout_revokesSeries_whenFilterValidatedTheCookie() {
        UUID series = UUID.randomUUID();
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, series + ":tok"));
        httpReq.setAttribute(PersistentTokenAuthFilter.VALIDATED_SERIES_ATTR, series);

        ResponseEntity<Void> res = controller.logout(httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(persistentSessionService).revokeBySeriesId(series);
        // Already validated + rotated by the filter: no second hash check.
        verify(persistentSessionService, never()).validateAndRotate(any());
        verify(cookieWriter).clearAuthCookies(httpRes);
    }

    @Test
    void logout_validatesHash_thenRevokes_whenAccessTokenAuthenticatedTheRequest() {
        // The usual case: a valid access_token made the persistent filter skip the cookie,
        // so logout proves possession itself before revoking.
        UUID series = UUID.randomUUID();
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, series + ":tok"));
        when(persistentSessionService.validateAndRotate(series + ":tok")).thenReturn(Optional.of(
            new PersistentSessionService.ValidationResult(series + ":rotated", session(7L, series, false))));

        controller.logout(httpReq, httpRes);

        verify(persistentSessionService).revokeBySeriesId(series);
        verify(cookieWriter).clearAuthCookies(httpRes);
    }

    @Test
    void logout_doesNotRevokeOnSeriesIdAlone_whenHashIsNotValid() {
        // A stale copy of the cookie carries the (non-secret) series id but a dead token:
        // logout must not turn it into a "log anyone out" primitive. (A mismatching hash is
        // left to validateAndRotate's own theft detection.)
        UUID series = UUID.randomUUID();
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, series + ":stale"));
        when(persistentSessionService.validateAndRotate(series + ":stale")).thenReturn(Optional.empty());

        ResponseEntity<Void> res = controller.logout(httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(persistentSessionService, never()).revokeBySeriesId(any());
        verify(persistentSessionService, never()).seriesFromCookie(any());
        verify(cookieWriter).clearAuthCookies(httpRes);
    }

    // ─── change-password ─────────────────────────────────────────────────

    @Test
    void changePassword_bumpsTokenVersion_revokesAllPersistentSessions_andIssuesSessionCookies() {
        AppUser active = user(true); // tokenVersion 3
        when(passwordEncoder.matches("old-pw", "$2a$12$hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password-123")).thenReturn("$2a$12$new");
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref");

        ResponseEntity<?> res = controller.changePassword(active,
            new AuthController.ChangePasswordRequest("old-pw", "new-password-123"), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(active.getPasswordHash()).isEqualTo("$2a$12$new");
        // tokenVersion 3 -> 4 invalidates every outstanding access/refresh JWT on every device.
        assertThat(active.getTokenVersion()).isEqualTo(4L);
        verify(userRepository).save(active);
        // ...and every Remember-Me browser is kicked (CWE-613/640).
        verify(persistentSessionService).revokeAllForUser(7L);
        // The calling browser is re-issued session-scoped cookies and loses its persistent one.
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref", false);
        verify(cookieWriter).clearPersistent(httpRes);
    }

    @Test
    void changePassword_wrongCurrentPassword_throwsBadCredentials_andChangesNothing() {
        AppUser active = user(true);
        when(passwordEncoder.matches("wrong", "$2a$12$hash")).thenReturn(false);

        assertThatThrownBy(() -> controller.changePassword(active,
                new AuthController.ChangePasswordRequest("wrong", "new-password-123"), httpReq, httpRes))
            .isInstanceOf(BadCredentialsException.class);

        assertThat(active.getPasswordHash()).isEqualTo("$2a$12$hash");
        assertThat(active.getTokenVersion()).isEqualTo(3L);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(persistentSessionService);
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), anyBoolean());
    }

    @Test
    void changePassword_returns429ProblemDetail_beforeCheckingPassword_whenReauthBucketExhausted() {
        // A hijacked session must not get unlimited bcrypt-speed guesses at the account
        // password through the step-up check; the per-user budget mirrors /login.
        AppUser active = user(true);
        Bucket drained = RateLimitConfig.createReauthBucket();
        while (drained.tryConsume(1)) { /* drain */ }
        reauthBuckets.put("7", drained);

        ResponseEntity<?> res = controller.changePassword(active,
            new AuthController.ChangePasswordRequest("guess", "new-password-123"), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(res.getBody()).isInstanceOf(ProblemDetail.class);
        // No oracle: the password was never compared.
        verify(passwordEncoder, never()).matches(any(), any());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(persistentSessionService);
    }

    @Test
    void changePassword_reauthBudget_isKeyedByUserId() {
        AppUser active = user(true);
        when(passwordEncoder.matches("wrong", "$2a$12$hash")).thenReturn(false);

        assertThatThrownBy(() -> controller.changePassword(active,
                new AuthController.ChangePasswordRequest("wrong", "new-password-123"), httpReq, httpRes))
            .isInstanceOf(BadCredentialsException.class);

        assertThat(reauthBuckets).containsOnlyKeys("7");
        assertThat(reauthBuckets.get("7").getAvailableTokens()).isEqualTo(4L);
    }

    // ─── change-username ─────────────────────────────────────────────────

    @Test
    void changeUsername_returns409ProblemDetail_onCollision_andChangesNothing() {
        AppUser active = user(true);
        when(userRepository.existsByUsername("bob")).thenReturn(true);

        ResponseEntity<?> res = controller.changeUsername(active,
            new AuthController.ChangeUsernameRequest("bob"), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(active.getUsername()).isEqualTo("alice");
        verify(userRepository, never()).save(any());
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), anyBoolean());
    }

    @Test
    void changeUsername_reissuesCookies_withNewSubject_preservingPersistence() {
        AppUser active = user(true); // id 7L
        UUID series = UUID.randomUUID();
        // A Remember-Me device renaming itself must keep Remember-Me TTLs and its sid binding.
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "mine"));
        when(persistentSessionService.ownerUserId("mine")).thenReturn(Optional.of(7L));
        when(persistentSessionService.seriesFromCookie("mine")).thenReturn(Optional.of(series));
        when(userRepository.existsByUsername("alice2")).thenReturn(false);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active, series)).thenReturn("ref");

        ResponseEntity<?> res = controller.changeUsername(active,
            new AuthController.ChangeUsernameRequest(" alice2 "), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) res.getBody()).get("username")).isEqualTo("alice2");
        assertThat(active.getUsername()).isEqualTo("alice2");
        verify(userRepository).save(active);
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref", true);
    }
}
