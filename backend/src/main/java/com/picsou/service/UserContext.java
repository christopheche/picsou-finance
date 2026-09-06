package com.picsou.service;

import com.picsou.config.AccessKeyAuthentication;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * Request-scoped helper to access the current authenticated user and their family member.
 * Admins can override the memberId via query param to act on behalf of a managed profile.
 *
 * <p>{@link #currentMember()} and {@link #currentMemberId()} both honour that override, so a
 * create path (which needs the entity) and a read/update path (which needs the id) scope to the
 * same member within one request. {@link #ownMemberId()} is the one accessor that never does —
 * for the few resources bound to the login itself rather than to the profile being viewed.
 */
@Component
public class UserContext {

    private final AppUserRepository userRepository;
    private final FamilyMemberRepository memberRepository;

    public UserContext(AppUserRepository userRepository, FamilyMemberRepository memberRepository) {
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
    }

    public AppUser currentUser() {
        return (AppUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    /**
     * The member the request acts on: the admin's impersonation target when {@code ?memberId=} is
     * honoured, otherwise the caller's own member. Same resolution as {@link #currentMemberId()},
     * so an account or goal created while impersonating lands under the impersonated profile.
     */
    public FamilyMember currentMember() {
        Long override = getMemberIdOverride();
        FamilyMember own = currentUser().getMember();
        if (override == null || override.equals(own.getId())) {
            return own;
        }
        return memberRepository.findById(override)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
    }

    public Long currentMemberId() {
        Long override = getMemberIdOverride();
        return override != null ? override : ownMemberId();
    }

    /**
     * The caller's own member id, never the impersonation override. For resources bound to the
     * login rather than to the profile being viewed (access-keys: a managed profile has no login
     * and cannot own one, so the admin's keys stay the admin's while impersonating).
     */
    public Long ownMemberId() {
        return currentUser().getMember().getId();
    }

    public boolean isAdmin() {
        return currentUser().getRole() == UserRole.ADMIN;
    }

    /**
     * If the current user is an admin and a memberId query param is present, return it.
     * Otherwise return null (use own member).
     *
     * <p>Privacy boundary: an admin may impersonate a member only while that member
     * has not taken ownership of their own login. Once a member is activated (has set
     * their own password), their data is private — the override is refused with 403.
     * Overriding to the admin's own member id is always allowed (no-op).
     */
    private Long getMemberIdOverride() {
        // Property B: an access-key acts ONLY on its owner's own data. Even when the owner is an
        // admin, a key must never honour ?memberId= — short-circuit before isAdmin() opens the
        // override, so a key can never reach another member's data via impersonation.
        if (SecurityContextHolder.getContext().getAuthentication() instanceof AccessKeyAuthentication) {
            return null;
        }
        if (!isAdmin()) return null;
        ServletRequestAttributes attrs =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return null;
        HttpServletRequest request = attrs.getRequest();
        String param = request.getParameter("memberId");
        if (param == null || param.isBlank()) return null;
        Long memberId;
        try {
            memberId = Long.parseLong(param);
        } catch (NumberFormatException e) {
            return null;
        }
        if (memberId.equals(ownMemberId())) return memberId;
        boolean independent = userRepository.findByMemberId(memberId)
            .map(AppUser::isActivated)
            .orElse(false);
        if (independent) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Cannot access an independent member's data");
        }
        return memberId;
    }
}
