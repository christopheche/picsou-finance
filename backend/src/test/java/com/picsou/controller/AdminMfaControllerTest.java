package com.picsou.controller;

import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMfaControllerTest {

    @Mock AppUserRepository userRepository;
    @Mock MfaService mfaService;
    @Mock PersistentSessionService persistentSessionService;

    AdminMfaController controller;
    AppUser admin;

    @BeforeEach
    void setUp() {
        controller = new AdminMfaController(userRepository, mfaService, persistentSessionService);
        admin = AppUser.builder()
            .id(1L).username("root").role(UserRole.ADMIN).activated(true)
            .build();
    }

    @Test
    void forceDisable_disablesAndRevokesSessions_forOtherMember() {
        AppUser target = AppUser.builder()
            .id(7L).username("alice").role(UserRole.MEMBER).activated(true)
            .build();
        when(userRepository.findByMemberId(42L)).thenReturn(Optional.of(target));

        ResponseEntity<?> res = controller.forceDisable(admin, 42L);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(mfaService).disable(target);
        verify(persistentSessionService).revokeAllForUser(7L);
    }

    @Test
    void forceDisable_returns404_whenMemberNotFound() {
        when(userRepository.findByMemberId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.forceDisable(admin, 99L))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(mfaService, never()).disable(any());
        verify(persistentSessionService, never()).revokeAllForUser(anyLong());
    }

    @Test
    void forceDisable_returns403_whenAdminTargetsThemselves() {
        // Admin's own AppUser as target — same id => self.
        when(userRepository.findByMemberId(1L)).thenReturn(Optional.of(admin));

        ResponseEntity<?> res = controller.forceDisable(admin, 1L);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(mfaService, never()).disable(any());
        verify(persistentSessionService, never()).revokeAllForUser(anyLong());
    }
}
