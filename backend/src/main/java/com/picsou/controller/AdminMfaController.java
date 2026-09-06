package com.picsou.controller;

import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.AppUser;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only force-disable of another member's 2FA. Anchored under
 * {@code /api/admin/**} so {@code SecurityConfig.hasRole("ADMIN")} guards it
 * via the URL pattern; no extra check needed in the method body for that.
 *
 * <p>The {@code target == self} guard prevents an admin from accidentally
 * locking themselves out of their own MFA via this endpoint — they must go
 * through the regular {@code /api/auth/mfa/disable} flow with their own
 * password and current code, like any other user.
 */
@RestController
@RequestMapping("/api/admin/members")
public class AdminMfaController {

    private final AppUserRepository userRepository;
    private final MfaService mfaService;
    private final PersistentSessionService persistentSessionService;

    public AdminMfaController(
        AppUserRepository userRepository,
        MfaService mfaService,
        PersistentSessionService persistentSessionService
    ) {
        this.userRepository = userRepository;
        this.mfaService = mfaService;
        this.persistentSessionService = persistentSessionService;
    }

    @DeleteMapping("/{memberId}/mfa")
    public ResponseEntity<?> forceDisable(
        @AuthenticationPrincipal AppUser admin,
        @PathVariable Long memberId
    ) {
        AppUser target = userRepository.findByMemberId(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Member not found"));

        if (target.getId().equals(admin.getId())) {
            // ProblemDetail like every other error (api-rest.md): the client shows `detail`.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Use Settings > Security to disable your own two-factor authentication"));
        }

        mfaService.disable(target);
        // Same blast radius as user-initiated disable: any "trusted" devices
        // were issued under the now-revoked MFA policy, so they go too.
        persistentSessionService.revokeAllForUser(target.getId());
        return ResponseEntity.noContent().build();
    }
}
