package com.picsou.config;

import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The auth choke point: every authenticated request goes through this filter, and it is the
 * only place the {@code tv} claim is checked against {@code AppUser.tokenVersion}, the only
 * place a deactivated account is refused, and the only place a non-access token (refresh /
 * mfa_challenge) presented as {@code access_token} is rejected. Each gate is pinned here so a
 * refactor cannot silently drop one and keep the suite green.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock JwtUtil jwtUtil;
    @Mock AppUserRepository userRepository;
    @Mock FilterChain chain;

    JwtAuthenticationFilter filter;
    MockHttpServletRequest request;
    MockHttpServletResponse response;
    AppUser user;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtUtil, userRepository);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        user = AppUser.builder()
            .id(7L).username("alice")
            .role(UserRole.ADMIN)
            .activated(true)
            .tokenVersion(2L)
            .build();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** Plumbs a parseable {@code access_token} cookie whose claims carry uid 7 and the given tv. */
    private Claims accessTokenFor(long uid, Long tv, boolean isAccess) {
        request.setCookies(new Cookie(AuthCookieWriter.ACCESS_COOKIE, "jwt"));
        Claims claims = mock(Claims.class);
        when(jwtUtil.validateAndParse("jwt")).thenReturn(claims);
        when(jwtUtil.isAccessToken(claims)).thenReturn(isAccess);
        if (isAccess) {
            when(claims.get("uid", Long.class)).thenReturn(uid);
            when(jwtUtil.getTokenVersion(claims)).thenReturn(tv);
        }
        return claims;
    }

    private static Authentication currentAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    // ─── happy path ──────────────────────────────────────────────────────

    @Test
    void validAccessToken_matchingTokenVersion_activatedUser_setsAuthenticationWithRole() throws Exception {
        accessTokenFor(7L, 2L, true);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(user));

        filter.doFilter(request, response, chain);

        assertThat(currentAuth()).isNotNull();
        assertThat(currentAuth().getPrincipal()).isSameAs(user);
        assertThat(currentAuth().getAuthorities())
            .extracting(Object::toString).containsExactly("ROLE_ADMIN");
        verify(chain).doFilter(request, response);
    }

    @Test
    void memberRole_isMappedToRoleMemberAuthority() throws Exception {
        user.setRole(UserRole.MEMBER);
        accessTokenFor(7L, 2L, true);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(user));

        filter.doFilter(request, response, chain);

        assertThat(currentAuth().getAuthorities())
            .extracting(Object::toString).containsExactly("ROLE_MEMBER");
    }

    // ─── the three gates ─────────────────────────────────────────────────

    @Test
    void tokenVersionMismatch_leavesContextEmpty() throws Exception {
        // A password change / admin reset bumps tokenVersion: every JWT minted before it
        // carries a stale tv and must be refused, even though its signature is fine.
        accessTokenFor(7L, 1L, true); // token says tv=1, user is at tv=2
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(user));

        filter.doFilter(request, response, chain);

        assertThat(currentAuth()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void missingTokenVersionClaim_leavesContextEmpty() throws Exception {
        accessTokenFor(7L, null, true);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(user));

        filter.doFilter(request, response, chain);

        assertThat(currentAuth()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void deactivatedUser_leavesContextEmpty() throws Exception {
        // Admin recovery / pending activation: the account is unusable until re-activated,
        // whatever tokens it still holds.
        user.setActivated(false);
        accessTokenFor(7L, 2L, true);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(user));

        filter.doFilter(request, response, chain);

        assertThat(currentAuth()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void refreshOrChallengeToken_presentedAsAccessToken_isNotAccepted() throws Exception {
        // A refresh or mfa_challenge JWT is signed with the same key; only the `type` claim
        // tells them apart, so this gate is what keeps a 7-day refresh_token (or a 5-minute
        // pre-MFA challenge) from doubling as an access token.
        accessTokenFor(7L, 2L, false);

        filter.doFilter(request, response, chain);

        assertThat(currentAuth()).isNull();
        verifyNoInteractions(userRepository);
        verify(chain).doFilter(request, response);
    }

    // ─── degraded inputs ─────────────────────────────────────────────────

    @Test
    void unknownUser_leavesContextEmpty() throws Exception {
        accessTokenFor(7L, 2L, true);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        assertThat(currentAuth()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void invalidJwt_continuesChain_unauthenticated() throws Exception {
        // Expired / tampered token: the normal prelude to a refresh, never a short-circuit.
        request.setCookies(new Cookie(AuthCookieWriter.ACCESS_COOKIE, "bad"));
        when(jwtUtil.validateAndParse("bad")).thenThrow(new JwtException("expired"));

        filter.doFilter(request, response, chain);

        assertThat(currentAuth()).isNull();
        verifyNoInteractions(userRepository);
        verify(chain).doFilter(request, response);
    }

    @Test
    void noAccessTokenCookie_skipsJwtUtil_andContinuesChain() throws Exception {
        request.setCookies(new Cookie("some_other_cookie", "x"));

        filter.doFilter(request, response, chain);

        assertThat(currentAuth()).isNull();
        verifyNoInteractions(jwtUtil, userRepository);
        verify(chain).doFilter(request, response);
    }

    @Test
    void noCookiesAtAll_skipsJwtUtil_andContinuesChain() throws Exception {
        filter.doFilter(request, response, chain);

        assertThat(currentAuth()).isNull();
        verify(jwtUtil, never()).validateAndParse(any());
        verify(chain).doFilter(request, response);
    }
}
