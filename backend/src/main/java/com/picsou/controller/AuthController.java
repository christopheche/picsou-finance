package com.picsou.controller;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.ClientIp;
import com.picsou.config.JwtUtil;
import com.picsou.config.PersistentTokenAuthFilter;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.ActivationRequest;
import com.picsou.dto.LoginRequest;
import com.picsou.dto.MfaDtos;
import com.picsou.model.AppUser;
import com.picsou.model.PersistentSession;
import com.picsou.repository.AppUserRepository;
import com.picsou.model.UserRole;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import com.picsou.service.SetupAuditService;
import io.github.bucket4j.Bucket;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final Map<String, Bucket> loginBuckets;
    private final Map<String, Bucket> mfaVerifyBuckets;
    private final Map<String, Bucket> reauthBuckets;
    private final AuthCookieWriter cookieWriter;
    private final MfaService mfaService;
    private final PersistentSessionService persistentSessionService;
    private final SetupAuditService auditService;
    private final boolean adminRecoveryEnabled;

    /**
     * A throwaway hash with the same cost factor as {@link #passwordEncoder}.
     * When a login request ask an unknown user we still run passwordEncoder.matches
     * against this hash so the "no such user" path costs the same time as the
     * "wrong password" path.
     */
    private final String dummyPasswordHash;

    public AuthController(
        AppUserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtUtil jwtUtil,
        @org.springframework.beans.factory.annotation.Qualifier("loginBuckets") Map<String, Bucket> loginBuckets,
        @org.springframework.beans.factory.annotation.Qualifier("mfaVerifyBuckets") Map<String, Bucket> mfaVerifyBuckets,
        @org.springframework.beans.factory.annotation.Qualifier("reauthBuckets") Map<String, Bucket> reauthBuckets,
        AuthCookieWriter cookieWriter,
        MfaService mfaService,
        PersistentSessionService persistentSessionService,
        SetupAuditService auditService,
        @org.springframework.beans.factory.annotation.Value("${app.admin-recovery.enabled:false}") boolean adminRecoveryEnabled
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.loginBuckets = loginBuckets;
        this.mfaVerifyBuckets = mfaVerifyBuckets;
        this.reauthBuckets = reauthBuckets;
        this.cookieWriter = cookieWriter;
        this.mfaService = mfaService;
        this.persistentSessionService = persistentSessionService;
        this.auditService = auditService;
        this.adminRecoveryEnabled = adminRecoveryEnabled;
        this.dummyPasswordHash = passwordEncoder.encode("login-timing-equalizer");
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
        @Valid @RequestBody LoginRequest req,
        HttpServletRequest httpReq,
        HttpServletResponse httpRes
    ) {
        String ip = getClientIp(httpReq);
        Bucket bucket = loginBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createLoginBucket());

        if (!bucket.tryConsume(1)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many login attempts. Try again in 15 minutes.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }

        AppUser user = userRepository.findByUsernameWithMember(req.username()).orElse(null);
        if (user == null) {
            // Equalize timing with the password-check path below: run a bcrypt comparison
            // against a dummy hash so a non-existent username can't be distinguished from a
            // wrong password by response latency. The response is already identical (same
            // BadCredentialsException -> 401), so this closes the enumeration oracle.
            // The result is discarded on purpose — we only want the constant-time work.
            boolean ignored = passwordEncoder.matches(req.password(), dummyPasswordHash);
            throw new BadCredentialsException("Invalid credentials");
        }

        // Break-glass recovery in progress: the admin has been deactivated and a
        // reset link was printed to the server console. Point the operator there
        // even if they mistype the (now-irrelevant) old password, and remind them
        // to turn the flag off so the next restart doesn't deactivate the account
        // again. The link itself is NOT echoed — only its location — so nothing
        // secret leaves over HTTP. Returning before the password check also means
        // the response is identical for right and wrong passwords (no oracle).
        if (adminRecoveryEnabled && user.getRole() == UserRole.ADMIN && !user.isActivated()) {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
            problem.setDetail("Admin recovery mode is active: the password reset link was "
                + "printed to the server console/logs — open it to set a new password. "
                + "Remember to set ADMIN_RECOVERY_ENABLED=false before the next restart.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
        }

        // A managed member that has only been issued an activation link still has a
        // blank password hash (""). passwordEncoder.matches(pw, "") returns false
        // *immediately* without running bcrypt, so this path would be measurably
        // faster than both the unknown-user and the wrong-password paths — a timing
        // oracle that lets an attacker distinguish "pending-activation profile" from
        // "no such user" (CWE-208). Run the same dummy-hash bcrypt round and fail
        // exactly like a wrong password so all three failing paths cost the same.
        String storedHash = user.getPasswordHash();
        if (storedHash == null || storedHash.isBlank()) {
            boolean ignored = passwordEncoder.matches(req.password(), dummyPasswordHash);
            throw new BadCredentialsException("Invalid credentials");
        }

        if (!passwordEncoder.matches(req.password(), storedHash)) {
            throw new BadCredentialsException("Invalid credentials");
        }

        // A deactivated account with the recovery flag off (or a non-admin pending
        // activation) must not get a session either: JwtAuthenticationFilter
        // requires isActivated(), so issuing tokens here would only feed an endless
        // refresh→401 loop. Fail fast with a clear message.
        if (!user.isActivated()) {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
            problem.setDetail("Account not activated. Use your activation link to set a password.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
        }

        // MFA gate: if 2FA is on AND this device is not already a trusted one,
        // we hand back a short-lived mfa_challenge cookie and demand the user
        // complete /api/auth/mfa/verify before access/refresh are issued.
        boolean trustedDevice = false;
        if (mfaService.isEnabled(user)) {
            String existingPersistent = extractCookie(httpReq, AuthCookieWriter.PERSISTENT_COOKIE);
            trustedDevice = existingPersistent != null && !existingPersistent.isBlank()
                && isHashValidatedTrustedDevice(user, existingPersistent, httpReq, httpRes);

            if (!trustedDevice) {
                // Password is correct but the second factor is still outstanding, so
                // the caller is NOT authenticated yet. Drop any pre-existing session
                // cookies first: on a shared/reused browser they may belong to a
                // DIFFERENT identity (e.g. a left-over no-MFA admin "Remember Me"
                // session), which would otherwise silently re-authenticate that other
                // user while this challenge is pending or abandoned.
                cookieWriter.clearSessionCookies(httpRes);
                String challenge = jwtUtil.generateMfaChallengeToken(user, req.rememberMe());
                cookieWriter.setMfaChallenge(httpRes, challenge);
                return ResponseEntity.ok(Map.of(
                    "mfaRequired", true,
                    "username", user.getUsername()
                ));
            }
            // Trusted device — fall through to issue access/refresh.
        }

        if (trustedDevice) {
            // The device KEEPS its existing trusted series: isHashValidatedTrustedDevice has
            // already rotated it (or PersistentTokenAuthFilter did, on this same request),
            // so no new PersistentSession is issued even if Remember Me was re-ticked.
            // Minting a fresh series here would (a) overwrite the trusted cookie with an
            // untrusted one — trust is granted only by a successful TOTP verify, never
            // inherited — which the filter then discards at first use, and (b) orphan the
            // trusted row in Settings → Sessions. Access/refresh are written with Remember-Me
            // TTLs and the refresh token stays bound to the series (sid) so "log out this
            // device" still cuts its chain.
            setTokenCookies(httpRes,
                jwtUtil.generateAccessToken(user),
                rotatedRefreshToken(user, true, seriesToBind(httpReq, null)),
                true);
        } else {
            completeAuthenticatedSession(user, req.rememberMe(), false, httpReq, httpRes);
        }
        return ResponseEntity.ok(userPayload(user));
    }

    @PostMapping("/mfa/verify")
    public ResponseEntity<?> mfaVerify(
        @Valid @RequestBody MfaDtos.MfaVerifyRequest req,
        HttpServletRequest httpReq,
        HttpServletResponse httpRes
    ) {
        String challengeCookie = extractCookie(httpReq, AuthCookieWriter.MFA_CHALLENGE_COOKIE);
        if (challengeCookie == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "No MFA challenge in progress"));
        }

        Claims claims;
        try {
            claims = jwtUtil.validateAndParse(challengeCookie);
        } catch (JwtException ex) {
            cookieWriter.clearMfaChallenge(httpRes);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid MFA challenge"));
        }
        if (!jwtUtil.isMfaChallengeToken(claims)) {
            cookieWriter.clearMfaChallenge(httpRes);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid MFA challenge"));
        }

        // Throttle per ACCOUNT (the challenge's uid), not per IP: the 6-digit space being
        // brute-forced belongs to one account, and a family behind a single NAT must not
        // lock each other out — five bad codes from one member would otherwise clear every
        // pending challenge behind that IP. Only a signed challenge names an account, so the
        // bucket is consumed after the (cheap, HMAC-only, no DB) signature check above.
        Long userId = claims.get("uid", Long.class);
        Bucket bucket = mfaVerifyBuckets.computeIfAbsent(
            String.valueOf(userId), k -> RateLimitConfig.createMfaVerifyBucket());
        if (!bucket.tryConsume(1)) {
            cookieWriter.clearMfaChallenge(httpRes);
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many verification attempts. Please log in again in 15 minutes.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }

        AppUser user = userRepository.findByIdWithMember(userId)
            .orElseThrow(() -> new BadCredentialsException("User not found"));

        boolean isRecovery = Boolean.TRUE.equals(req.isRecoveryCode());
        if (!mfaService.verifyTotpOrRecovery(user, req.code(), isRecovery)) {
            // 400, not 401: the challenge cookie is still valid — only the code is
            // wrong. The frontend treats 401 as "challenge gone, re-login" and
            // bounces to /login; a bad code must keep the user on the page to retry.
            // The challenge cookie is intentionally left intact here.
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid verification code"));
        }

        // For a 2FA-enabled user (the only kind that reaches this endpoint) a persistent
        // session is issued ONLY together with "Trust this device": PersistentTokenAuthFilter
        // refuses to re-mint a session from an untrusted persistent_token when MFA is on
        // (the cookie alone must not bypass the second factor), so a Remember-Me-without-
        // trust series would be rotated once, discarded at first use and left as a phantom
        // "active session". Remember Me without trust therefore yields a normal session-
        // scoped login; the rememberMe claim carried by the challenge is deliberately not
        // consulted on its own.
        boolean trustDevice = Boolean.TRUE.equals(req.trustDevice());
        completeAuthenticatedSession(user, trustDevice, trustDevice, httpReq, httpRes);
        cookieWriter.clearMfaChallenge(httpRes);
        return ResponseEntity.ok(userPayload(user));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
        @AuthenticationPrincipal AppUser persistentPrincipal,
        HttpServletRequest httpReq, HttpServletResponse httpRes
    ) {
        String refreshToken = extractCookie(httpReq, "refresh_token");

        // If the request still carries a persistent_token whose series has been revoked
        // ("log out this device" / "log out everywhere else") or has passed its 90-day cap,
        // refuse decisively -- a still-valid access_token or refresh_token on the SAME device
        // must not silently re-establish a session the user has explicitly killed.
        UUID presentedSeries = currentSeriesId(httpReq).orElse(null);
        if (presentedSeries != null && !persistentSessionService.isSeriesActive(presentedSeries)) {
            clearTokenCookies(httpRes);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Session revoked"));
        }

        if (refreshToken != null) {
            try {
                var claims = jwtUtil.validateAndParse(refreshToken);
                if (jwtUtil.isRefreshToken(claims)) {
                    AppUser user = userRepository.findByUsernameWithMember(claims.getSubject()).orElse(null);
                    // Reject if the user is gone, the token version was bumped (credential
                    // change / recovery), or the account has since been deactivated.
                    Long tv = jwtUtil.getTokenVersion(claims);
                    if (user != null && tv != null && tv == user.getTokenVersion() && user.isActivated()) {
                        // A refresh_token bound to a "Remember Me" series (sid) is only good
                        // while that series is live. Revoking the device breaks the chain even
                        // though the JWT itself hasn't expired. Decisive 401 -- do NOT fall
                        // through to the access-principal re-mint below, which would re-establish
                        // it from a still-valid access_token on the same device.
                        UUID boundSeries = jwtUtil.getSeriesId(claims);
                        if (boundSeries != null && !persistentSessionService.isSeriesActive(boundSeries)) {
                            clearTokenCookies(httpRes);
                            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Session revoked"));
                        }
                        boolean persistent = isPersistentDevice(httpReq, user);
                        setTokenCookies(httpRes,
                            jwtUtil.generateAccessToken(user),
                            rotatedRefreshToken(user, persistent, seriesToBind(httpReq, boundSeries)),
                            persistent);
                        return ResponseEntity.ok(userPayload(user));
                    }
                }
            } catch (RuntimeException ex) {
                // Falls through: an invalid/expired refresh_token doesn't necessarily mean
                // "logged out" -- see the persistentPrincipal branch below. Logged at DEBUG so
                // a refresh loop can still be traced to the token that caused it.
                log.debug("Refresh token rejected, falling through to persistent token: {}", ex.toString());
            }
        }

        // No usable refresh_token: honour a persistent-token ("Remember Me") restoration
        // for THIS SAME request -- and only that. PersistentTokenAuthFilter runs before this
        // controller and, on a valid persistent_token, rotates the series, sets the
        // SecurityContext principal AND stamps VALIDATED_SERIES_ATTR on the request. A
        // principal WITHOUT that stamp came from JwtAuthenticationFilter, i.e. from a bare
        // access_token: it must not be upgraded into a fresh 7-day refresh_token here,
        // otherwise the access token's 15-minute TTL would contain nothing at all. On the
        // stamped path we still (re)mint below rather than trusting what the filter already
        // wrote: the endpoint's contract is "200 = fresh cookies were issued", never a
        // phantom win with zero Set-Cookie.
        if (persistentPrincipal != null
            && httpReq.getAttribute(PersistentTokenAuthFilter.VALIDATED_SERIES_ATTR) != null) {
            boolean persistent = isPersistentDevice(httpReq, persistentPrincipal);
            setTokenCookies(httpRes,
                jwtUtil.generateAccessToken(persistentPrincipal),
                rotatedRefreshToken(persistentPrincipal, persistent, seriesToBind(httpReq, null)),
                persistent);
            return ResponseEntity.ok(userPayload(persistentPrincipal));
        }

        // Genuinely no valid session of any kind. Note we deliberately do NOT call
        // clearTokenCookies() in the branches above: PersistentTokenAuthFilter may have
        // just rotated persistent_token on this very response, and clearing it here
        // (Max-Age=0, added after the filter's Set-Cookie) would wipe that rotation and
        // needlessly destroy Remember Me for a request that was otherwise fine.
        clearTokenCookies(httpRes);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "No refresh token"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpReq, HttpServletResponse httpRes) {
        // Best-effort revoke the persistent series so the cookie can't be replayed even if
        // the browser failed to honour Set-Cookie Max-Age=0 -- but only for a series this
        // request has PROVEN to hold (token hash compared in constant time). The series id
        // alone is not a secret, so revoking on it would let any caller log a device out
        // with a stale copy of the cookie.
        String persistent = extractCookie(httpReq, AuthCookieWriter.PERSISTENT_COOKIE);
        if (persistent != null && !persistent.isBlank()) {
            if (httpReq.getAttribute(PersistentTokenAuthFilter.VALIDATED_SERIES_ATTR) instanceof UUID validated) {
                // PersistentTokenAuthFilter validated (and rotated) this cookie on the way in.
                persistentSessionService.revokeBySeriesId(validated);
            } else {
                // A valid access_token authenticated the request, so the filter skipped the
                // cookie: check the hash here. A mismatch is left to validateAndRotate's own
                // theft detection (series wiped + WARN), the designed fail-safe.
                persistentSessionService.validateAndRotate(persistent)
                    .ifPresent(v -> persistentSessionService.revokeBySeriesId(v.session().getSeriesId()));
            }
        }
        clearTokenCookies(httpRes);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/username")
    public ResponseEntity<?> changeUsername(
        @AuthenticationPrincipal AppUser user,
        @Valid @RequestBody ChangeUsernameRequest req,
        HttpServletRequest httpReq,
        HttpServletResponse httpRes
    ) {
        String newUsername = req.newUsername().trim();
        if (newUsername.equals(user.getUsername())) {
            return ResponseEntity.ok(Map.of("username", user.getUsername()));
        }
        if (userRepository.existsByUsername(newUsername)) {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            problem.setDetail("Username already taken");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }
        // Preserve whatever persistence state this browser already had -- a Remember-Me
        // user renaming their account shouldn't be silently downgraded to a session cookie.
        boolean persistent = isPersistentDevice(httpReq, user);
        user.setUsername(newUsername);
        userRepository.save(user);
        String newAccess = jwtUtil.generateAccessToken(user);
        String newRefresh = rotatedRefreshToken(user, persistent, seriesToBind(httpReq, null));
        setTokenCookies(httpRes, newAccess, newRefresh, persistent);
        return ResponseEntity.ok(Map.of("username", newUsername));
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
        @AuthenticationPrincipal AppUser user,
        @Valid @RequestBody ChangePasswordRequest req,
        HttpServletRequest httpReq,
        HttpServletResponse httpRes
    ) {
        // Step-up password check from an already-authenticated session: throttle it like
        // /login, per user, or a hijacked session cookie becomes a bcrypt-speed oracle for
        // the account password (and from there full takeover). See RateLimitConfig#reauthBuckets.
        if (!consumeReauthToken(user)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(reauthRateLimited());
        }

        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        // Invalidate every outstanding access/refresh JWT across all devices.
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);

        // Kick every Remember-Me browser. The caller's persistent cookie is
        // dropped along with the rest — they will need to log back in on this
        // browser too if they ticked Remember-Me previously.
        persistentSessionService.revokeAllForUser(user.getId());

        // Re-issue access+refresh for the calling browser so the user does
        // not get logged out by their own action. The new cookies carry the
        // bumped tokenVersion; old cookies on this browser are overwritten.
        // Always session-scoped (persistent=false): persistent_token was just
        // revoked/cleared above, so nothing should outlive this browser tab.
        String newAccess = jwtUtil.generateAccessToken(user);
        String newRefresh = jwtUtil.generateRefreshToken(user);
        setTokenCookies(httpRes, newAccess, newRefresh, false);
        cookieWriter.clearPersistent(httpRes);

        return ResponseEntity.ok(Map.of("message", "Password updated successfully"));
    }

    @PostMapping("/activate/{token}")
    public ResponseEntity<?> activate(
        @PathVariable String token,
        @Valid @RequestBody ActivationRequest req,
        HttpServletRequest httpReq
    ) {
        AppUser user = userRepository.findByActivationToken(token)
            .orElseThrow(() -> new BadCredentialsException("Invalid activation token"));

        // ProblemDetail, not an ad-hoc {"error": …} map: the frontend reads `detail`
        // (error-handling.md "Frontend display"), so anything else shows as a generic error.
        if (user.getActivationTokenExpires() != null &&
            user.getActivationTokenExpires().isBefore(Instant.now())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "This activation link has expired. Ask your administrator for a new one."));
        }

        if (!req.acknowledgedWarning()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "You must acknowledge the data access warning."));
        }

        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setActivationToken(null);
        user.setActivationTokenExpires(null);
        user.setActivated(true);
        user.setAcknowledgedWarning(true);
        // This endpoint is the shared sink for new-member activation AND
        // admin-initiated password resets (FamilyService.resetPasswordToken issues
        // the token; the member then activates here) AND admin-recovery completion.
        // A reset may be a response to a compromise, so — exactly like the
        // self-service change-password flow — invalidate every outstanding session:
        // bump tokenVersion to revoke all access/refresh JWTs, and wipe the user's
        // Remember-Me persistent sessions. Without this, an attacker's pre-existing
        // tokens/cookies would survive an admin password reset (CWE-613/640).
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);

        persistentSessionService.revokeAllForUser(user.getId());

        if (user.getRole() == UserRole.ADMIN) {
            auditService.record("admin.recovery.completed", user.getUsername(), httpReq, null);
        }

        return ResponseEntity.ok(Map.of("message", "Account activated successfully"));
    }

    // ─── Session helpers ──────────────────────────────────────────────────────
    // Cookie attributes (HttpOnly/SameSite/Secure) live in AuthCookieWriter so
    // every emitter (controller, MFA flow, persistent-token filter) stays aligned.

    /**
     * Issues access + refresh cookies and, when {@code issuePersistent} is true,
     * also creates a fresh PersistentSession in DB and writes the persistent_token
     * cookie. Use {@code trustedFor2fa} to mark the device as MFA-trusted for the
     * silent-bypass flow.
     */
    private void completeAuthenticatedSession(
        AppUser user,
        boolean issuePersistent,
        boolean trustedFor2fa,
        HttpServletRequest httpReq,
        HttpServletResponse httpRes
    ) {
        if (issuePersistent) {
            PersistentSessionService.IssueResult issued = persistentSessionService.issue(
                user,
                trustedFor2fa,
                httpReq.getHeader("User-Agent"),
                getClientIp(httpReq)
            );
            PersistentSession session = issued.session();
            // Bind the refresh token to the freshly-issued series (sid) so revoking this
            // device later cuts its refresh chain -- see /auth/refresh.
            cookieWriter.setAccessAndRefresh(httpRes,
                jwtUtil.generateAccessToken(user),
                jwtUtil.generateRefreshToken(user, session.getSeriesId()),
                true);
            cookieWriter.setPersistent(httpRes, issued.cookieValue(), secondsUntilExpiry(session));
        } else {
            cookieWriter.setAccessAndRefresh(httpRes,
                jwtUtil.generateAccessToken(user),
                jwtUtil.generateRefreshToken(user),
                false);
            // Not remembering this device. If a "Remember Me" cookie from a DIFFERENT
            // identity is still on this browser (shared/reused machine), drop it so it
            // can't silently re-mint that other user's session via the persistent-token
            // filter once this access token lapses. A persistent cookie that belongs to
            // THIS user — a trusted device logging in without re-ticking Remember Me —
            // is left intact so we don't needlessly un-trust the device.
            String existingPersistent = extractCookie(httpReq, AuthCookieWriter.PERSISTENT_COOKIE);
            if (existingPersistent != null && !existingPersistent.isBlank()
                && persistentSessionService.ownerUserId(existingPersistent)
                    .filter(ownerId -> ownerId.equals(user.getId()))
                    .isEmpty()) {
                cookieWriter.clearPersistent(httpRes);
            }
        }
    }

    /**
     * Whether the {@code persistent_token} on this request PROVES a device that {@code user}
     * marked as trusted for 2FA. "Proves" means the cookie's token hash was compared in constant
     * time on this very request -- a series-id-only lookup ({@link PersistentSessionService#isTrustedDeviceFor})
     * would accept {@code <victim-series>:<anything>}, and the series id is no secret: it survives
     * rotation and sits in every stale copy of the cookie and in refresh JWTs' {@code sid} claim.
     * Combined with a valid access_token of ANY account (which makes {@link PersistentTokenAuthFilter}
     * bail out before validating) and a phished password, that lookup alone would skip the
     * second factor without ever tripping theft detection.
     *
     * <p>Two ways to satisfy the proof:
     * <ol>
     *   <li>{@link PersistentTokenAuthFilter} validated + rotated this cookie and stamped
     *       {@code VALIDATED_SERIES_ATTR} with its series. It has already written the rotated
     *       cookie; we must NOT validate again -- the request still carries the pre-rotation
     *       token, which would only pass via the grace window and emit a second, conflicting
     *       {@code Set-Cookie: persistent_token}.</li>
     *   <li>The filter bailed out (a valid access_token authenticated the request), so nothing
     *       has checked the hash yet: run {@code validateAndRotate} here -- constant-time compare,
     *       rotation, theft detection -- and write the rotated value ourselves. A forged or stale
     *       token then wipes the series and logs a WARN instead of granting a session.</li>
     * </ol>
     */
    private boolean isHashValidatedTrustedDevice(
        AppUser user,
        String cookie,
        HttpServletRequest httpReq,
        HttpServletResponse httpRes
    ) {
        if (httpReq.getAttribute(PersistentTokenAuthFilter.VALIDATED_SERIES_ATTR) instanceof UUID validated) {
            return persistentSessionService.seriesFromCookie(cookie).filter(validated::equals).isPresent()
                && persistentSessionService.isTrustedDeviceFor(user, cookie);
        }
        Optional<PersistentSessionService.ValidationResult> result = persistentSessionService.validateAndRotate(cookie);
        if (result.isEmpty()) return false;
        PersistentSession session = result.get().session();
        if (!session.getUser().getId().equals(user.getId()) || !session.isTrustedFor2fa()) {
            // Rotated but not trusted for THIS user: the MFA-required branch clears the
            // cookie anyway, so the rotated value is deliberately not written.
            return false;
        }
        cookieWriter.setPersistent(httpRes, result.get().rotatedCookieValue(), secondsUntilExpiry(session));
        return true;
    }

    /** Remaining lifetime of a persistent session, for the persistent_token cookie's Max-Age. */
    private static long secondsUntilExpiry(PersistentSession session) {
        return Math.max(ChronoUnit.SECONDS.between(Instant.now(), session.getExpiresAt()), 0);
    }

    /** One step-up password check against {@code user}'s reauth budget; false once it is exhausted. */
    private boolean consumeReauthToken(AppUser user) {
        Bucket bucket = reauthBuckets.computeIfAbsent(
            String.valueOf(user.getId()), k -> RateLimitConfig.createReauthBucket());
        return bucket.tryConsume(1);
    }

    private static ProblemDetail reauthRateLimited() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail("Too many password attempts. Try again in 15 minutes.");
        return detail;
    }

    private Map<String, Object> userPayload(AppUser user) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", user.getUsername());
        body.put("role", user.getRole().name());
        body.put("memberId", user.getMember().getId());
        body.put("displayName", user.getMember().getDisplayName());
        return body;
    }

    private void setTokenCookies(HttpServletResponse response, String accessToken, String refreshToken, boolean persistent) {
        cookieWriter.setAccessAndRefresh(response, accessToken, refreshToken, persistent);
    }

    /** Whether {@code httpReq} carries a persistent_token ("Remember Me") cookie owned by {@code user}. */
    private boolean isPersistentDevice(HttpServletRequest httpReq, AppUser user) {
        String cookie = extractCookie(httpReq, AuthCookieWriter.PERSISTENT_COOKIE);
        if (cookie == null || cookie.isBlank()) return false;
        return persistentSessionService.ownerUserId(cookie).filter(id -> id.equals(user.getId())).isPresent();
    }

    /**
     * A rotated refresh token that keeps its "Remember Me" series binding: bound to
     * {@code series} (sid claim) when this is a persistent device with a known series,
     * otherwise a plain session-scoped token. Falling back to the unbound overload when
     * there is no series keeps a non-"Remember Me" rotation identical to before.
     */
    private String rotatedRefreshToken(AppUser user, boolean persistent, UUID series) {
        return persistent && series != null
            ? jwtUtil.generateRefreshToken(user, series)
            : jwtUtil.generateRefreshToken(user);
    }

    /** The series to bind a rotated refresh token to: the token's own binding wins, else the persistent_token cookie's series. */
    private UUID seriesToBind(HttpServletRequest httpReq, UUID boundSeries) {
        return boundSeries != null ? boundSeries : currentSeriesId(httpReq).orElse(null);
    }

    /** The persistent-session series id carried by this request's persistent_token cookie, if any. */
    private Optional<UUID> currentSeriesId(HttpServletRequest httpReq) {
        String cookie = extractCookie(httpReq, AuthCookieWriter.PERSISTENT_COOKIE);
        if (cookie == null || cookie.isBlank()) return Optional.empty();
        return persistentSessionService.seriesFromCookie(cookie);
    }

    private void clearTokenCookies(HttpServletResponse response) {
        cookieWriter.clearAuthCookies(response);
    }

    private String extractCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (var cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private String getClientIp(HttpServletRequest request) {
        // Never trust X-Forwarded-For from the client — it is user-controllable and
        // would allow rate-limit bypass by spoofing IPs. request.getRemoteAddr() is NOT
        // a safe substitute either: under server.forward-headers-strategy=framework
        // (see application.yml), Spring's ForwardedHeaderFilter rewrites it from the
        // leftmost X-Forwarded-For entry, which nginx's proxy_add_x_forwarded_for only
        // ever appends to (never replaces) — so it inherits the same spoofability.
        // ClientIp.resolve() uses X-Real-IP instead, which our nginx always overwrites
        // with $remote_addr and a client cannot inject through the proxy.
        return ClientIp.resolve(request);
    }

    record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8, max = 128) String newPassword
    ) {}

    record ChangeUsernameRequest(
        @NotBlank @Size(min = 3, max = 50)
        @jakarta.validation.constraints.Pattern(regexp = "[a-zA-Z0-9._-]+", message = "Username may only contain letters, digits, dots, underscores and hyphens")
        String newUsername
    ) {}
}
