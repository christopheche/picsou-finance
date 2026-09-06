package com.picsou.service;

import com.picsou.config.AccessKeyAuthentication;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Privacy boundary: an admin may impersonate (?memberId=X) only members who have
 * NOT activated their own login. Once a member has set their own password
 * (activated = true), the override is refused with 403.
 */
@ExtendWith(MockitoExtension.class)
class UserContextTest {

    @Mock AppUserRepository userRepository;
    @Mock FamilyMemberRepository memberRepository;

    UserContext userContext;
    MockHttpServletRequest request;

    static final long ADMIN_MEMBER_ID = 1L;

    @BeforeEach
    void setUp() {
        userContext = new UserContext(userRepository, memberRepository);
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private void authenticate(AppUser user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null));
    }

    private AppUser member(long memberId, UserRole role, boolean activated) {
        FamilyMember m = FamilyMember.builder().id(memberId).managed(role != UserRole.ADMIN).build();
        return AppUser.builder()
            .id(memberId)
            .username("user" + memberId)
            .role(role)
            .activated(activated)
            .member(m)
            .build();
    }

    @Test
    void adminOverrideToActivatedMember_isForbidden() {
        authenticate(member(ADMIN_MEMBER_ID, UserRole.ADMIN, true));
        request.setParameter("memberId", "2");
        when(userRepository.findByMemberId(2L))
            .thenReturn(Optional.of(member(2L, UserRole.MEMBER, true)));

        assertThatThrownBy(() -> userContext.currentMemberId())
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminOverrideToManagedNotYetActivatedMember_isAllowed() {
        authenticate(member(ADMIN_MEMBER_ID, UserRole.ADMIN, true));
        request.setParameter("memberId", "3");
        when(userRepository.findByMemberId(3L))
            .thenReturn(Optional.of(member(3L, UserRole.MEMBER, false)));

        assertThat(userContext.currentMemberId()).isEqualTo(3L);
    }

    @Test
    void adminOverrideToManagedProfileWithNoLogin_isAllowed() {
        authenticate(member(ADMIN_MEMBER_ID, UserRole.ADMIN, true));
        request.setParameter("memberId", "4");
        when(userRepository.findByMemberId(4L)).thenReturn(Optional.empty());

        assertThat(userContext.currentMemberId()).isEqualTo(4L);
    }

    @Test
    void adminOverrideToSelf_isAllowed_withoutRepoLookup() {
        authenticate(member(ADMIN_MEMBER_ID, UserRole.ADMIN, true));
        request.setParameter("memberId", String.valueOf(ADMIN_MEMBER_ID));

        assertThat(userContext.currentMemberId()).isEqualTo(ADMIN_MEMBER_ID);
    }

    @Test
    void nonAdminMemberIdParam_isIgnored() {
        authenticate(member(5L, UserRole.MEMBER, true));
        request.setParameter("memberId", "2");

        assertThat(userContext.currentMemberId()).isEqualTo(5L);
    }

    // ─── currentMember() honours the same override as currentMemberId() ──────────
    // Create paths hand the entity to the service; if it ignored ?memberId=, an account or
    // goal added while impersonating would land under the admin's own member.

    @Test
    void currentMember_adminWithOverride_returnsOverriddenMember() {
        authenticate(member(ADMIN_MEMBER_ID, UserRole.ADMIN, true));
        request.setParameter("memberId", "3");
        when(userRepository.findByMemberId(3L)).thenReturn(Optional.empty());
        FamilyMember managed = FamilyMember.builder().id(3L).managed(true).build();
        when(memberRepository.findById(3L)).thenReturn(Optional.of(managed));

        assertThat(userContext.currentMember()).isSameAs(managed);
        assertThat(userContext.currentMemberId()).isEqualTo(3L);
    }

    @Test
    void currentMember_adminOverrideToActivatedMember_isForbidden() {
        authenticate(member(ADMIN_MEMBER_ID, UserRole.ADMIN, true));
        request.setParameter("memberId", "2");
        when(userRepository.findByMemberId(2L))
            .thenReturn(Optional.of(member(2L, UserRole.MEMBER, true)));

        assertThatThrownBy(() -> userContext.currentMember())
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(memberRepository);
    }

    @Test
    void currentMember_adminOverrideToUnknownMember_isNotFound() {
        authenticate(member(ADMIN_MEMBER_ID, UserRole.ADMIN, true));
        request.setParameter("memberId", "42");
        when(userRepository.findByMemberId(42L)).thenReturn(Optional.empty());
        when(memberRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userContext.currentMember())
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void currentMember_withoutOverride_returnsOwnMember_withoutRepoLookup() {
        AppUser admin = member(ADMIN_MEMBER_ID, UserRole.ADMIN, true);
        authenticate(admin);

        assertThat(userContext.currentMember()).isSameAs(admin.getMember());
        verifyNoInteractions(memberRepository);
    }

    @Test
    void currentMember_overrideToSelf_returnsOwnMember_withoutRepoLookup() {
        AppUser admin = member(ADMIN_MEMBER_ID, UserRole.ADMIN, true);
        authenticate(admin);
        request.setParameter("memberId", String.valueOf(ADMIN_MEMBER_ID));

        assertThat(userContext.currentMember()).isSameAs(admin.getMember());
        verifyNoInteractions(memberRepository);
    }

    @Test
    void currentMember_nonAdminMemberIdParam_isIgnored() {
        AppUser user = member(5L, UserRole.MEMBER, true);
        authenticate(user);
        request.setParameter("memberId", "2");

        assertThat(userContext.currentMember()).isSameAs(user.getMember());
        verifyNoInteractions(memberRepository);
    }

    // ─── ownMemberId() never honours the override ─────────────────────────────

    @Test
    void ownMemberId_adminWithOverride_stillReturnsOwnMember() {
        authenticate(member(ADMIN_MEMBER_ID, UserRole.ADMIN, true));
        request.setParameter("memberId", "3");

        assertThat(userContext.ownMemberId()).isEqualTo(ADMIN_MEMBER_ID);
        verifyNoInteractions(userRepository, memberRepository);
    }

    // ─── Property B: an access-key never impersonates, even if its owner is an admin ──

    @Test
    void accessKey_ignoresMemberIdOverride_evenWhenOwnerIsAdmin() {
        // Same principal a cookie-admin would use, but presented as an access-key.
        // For a cookie-admin, ?memberId=3 (a managed, not-yet-activated profile) is a
        // permitted override; for a key it must be silently refused so the key only ever
        // touches its own member's data — and the repo is never even consulted.
        AppUser adminOwner = member(ADMIN_MEMBER_ID, UserRole.ADMIN, true);
        SecurityContextHolder.getContext().setAuthentication(
            new AccessKeyAuthentication(adminOwner, List.of(), 99L));
        request.setParameter("memberId", "3");

        assertThat(userContext.currentMemberId()).isEqualTo(ADMIN_MEMBER_ID);
        assertThat(userContext.currentMember()).isSameAs(adminOwner.getMember());
        verifyNoInteractions(memberRepository);
    }
}
