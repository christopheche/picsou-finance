package com.picsou.controller;

import com.picsou.dto.FamilyMemberRequest;
import com.picsou.dto.FamilyMemberResponse;
import com.picsou.dto.FamilyMemberUpdateRequest;
import com.picsou.dto.SharingSettingsRequest;
import com.picsou.dto.SharingSettingsResponse;
import com.picsou.model.SharingLevel;
import com.picsou.service.FamilyService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito controller test (no Spring context, no MockMvc). {@code /api/family/**} has no path
 * rule in {@code SecurityConfig} (only {@code /api/admin/**} is role-gated), so
 * {@code FamilyController.requireAdmin()} is the sole guard between a regular member and the
 * endpoints that list the family, create logins, and mint activation/reset tokens that set another
 * member's password. Each admin-only endpoint is pinned twice: a MEMBER gets 403 and the service is
 * never reached; an ADMIN reaches the service with the expected arguments.
 */
@ExtendWith(MockitoExtension.class)
class FamilyControllerTest {

    @Mock FamilyService familyService;
    @Mock UserContext userContext;

    @InjectMocks FamilyController controller;

    private static void assertForbidden(ThrowingCall call) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @FunctionalInterface
    interface ThrowingCall { void run(); }

    // ─── MEMBER: every admin-only endpoint is refused before the service ──────

    @Test
    void listMembers_member_isForbidden() {
        when(userContext.isAdmin()).thenReturn(false);

        assertForbidden(() -> controller.listMembers());
        verifyNoInteractions(familyService);
    }

    @Test
    void createMember_member_isForbidden() {
        when(userContext.isAdmin()).thenReturn(false);

        assertForbidden(() -> controller.createMember(new FamilyMemberRequest("Kid", null)));
        verifyNoInteractions(familyService);
    }

    @Test
    void updateMember_member_isForbidden() {
        when(userContext.isAdmin()).thenReturn(false);

        assertForbidden(() -> controller.updateMember(3L, new FamilyMemberUpdateRequest("Renamed")));
        verifyNoInteractions(familyService);
    }

    @Test
    void deleteMember_member_isForbidden() {
        when(userContext.isAdmin()).thenReturn(false);

        assertForbidden(() -> controller.deleteMember(3L));
        verifyNoInteractions(familyService);
    }

    @Test
    void generateActivationLink_member_isForbidden() {
        when(userContext.isAdmin()).thenReturn(false);

        assertForbidden(() -> controller.generateActivationLink(3L));
        verifyNoInteractions(familyService);
    }

    @Test
    void generateResetPasswordLink_member_isForbidden() {
        when(userContext.isAdmin()).thenReturn(false);

        assertForbidden(() -> controller.generateResetPasswordLink(3L));
        verifyNoInteractions(familyService);
    }

    // ─── ADMIN: delegation ────────────────────────────────────────────────────

    @Test
    void listMembers_admin_delegates() {
        FamilyMemberResponse m = mock(FamilyMemberResponse.class);
        when(userContext.isAdmin()).thenReturn(true);
        when(familyService.listMembers()).thenReturn(List.of(m));

        assertThat(controller.listMembers()).containsExactly(m);
    }

    @Test
    void createMember_admin_delegates() {
        FamilyMemberRequest req = new FamilyMemberRequest("Kid", "#abcdef");
        FamilyMemberResponse created = mock(FamilyMemberResponse.class);
        when(userContext.isAdmin()).thenReturn(true);
        when(familyService.createManagedProfile(req)).thenReturn(created);

        assertThat(controller.createMember(req)).isSameAs(created);
    }

    @Test
    void updateMember_admin_delegatesTheValidatedDisplayName() {
        FamilyMemberResponse updated = mock(FamilyMemberResponse.class);
        when(userContext.isAdmin()).thenReturn(true);
        when(familyService.updateDisplayName(3L, "Renamed")).thenReturn(updated);

        assertThat(controller.updateMember(3L, new FamilyMemberUpdateRequest("Renamed"))).isSameAs(updated);
    }

    @Test
    void deleteMember_admin_passesCurrentMemberIdAsRequester() {
        when(userContext.isAdmin()).thenReturn(true);
        when(userContext.currentMemberId()).thenReturn(1L);

        controller.deleteMember(3L);

        verify(familyService).deleteMember(3L, 1L);
    }

    @Test
    void generateActivationLink_admin_returnsActivationPath() {
        when(userContext.isAdmin()).thenReturn(true);
        when(familyService.generateActivationToken(3L)).thenReturn("tok3n");

        ResponseEntity<Map<String, String>> res = controller.generateActivationLink(3L);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("activationLink", "/activate/tok3n");
    }

    @Test
    void generateResetPasswordLink_admin_returnsResetPath() {
        when(userContext.isAdmin()).thenReturn(true);
        when(familyService.resetPasswordToken(3L)).thenReturn("r3set");

        ResponseEntity<Map<String, String>> res = controller.generateResetPasswordLink(3L);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("resetLink", "/activate/r3set");
    }

    // ─── Sharing settings: any member, scoped to themselves ──────────────────

    @Test
    void sharingSettings_anyMember_scopedToCurrentMember_withoutAdminCheck() {
        SharingSettingsResponse settings = mock(SharingSettingsResponse.class);
        SharingSettingsRequest req = new SharingSettingsRequest("GOAL", SharingLevel.ALL, List.of());
        when(userContext.currentMemberId()).thenReturn(5L);
        when(familyService.getSharingSettings(5L, "GOAL")).thenReturn(settings);

        assertThat(controller.getSharingSettings("GOAL")).isSameAs(settings);
        assertThat(controller.updateSharingSettings(req).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(familyService).updateSharingSettings(5L, req);
        verify(userContext, never()).isAdmin();
    }
}
