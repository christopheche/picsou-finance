package com.picsou.service;

import com.picsou.dto.ReAuthDto;
import com.picsou.model.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReAuthServiceTest {

    @Mock private MfaService mfaService;
    @Mock private PasswordEncoder passwordEncoder;
    @InjectMocks private ReAuthService service;

    private AppUser user;

    @BeforeEach
    void setUp() {
        user = new AppUser();
        user.setPasswordHash("$2a$hash");
    }

    @Test
    void verify_withMfaEnabled_acceptsValidTotp() {
        when(mfaService.isEnabled(user)).thenReturn(true);
        when(mfaService.verifyTotp(user, "123456")).thenReturn(true);
        service.verify(user, new ReAuthDto(null, "123456"));
    }

    @Test
    void verify_withMfaEnabled_rejectsWrongTotp() {
        when(mfaService.isEnabled(user)).thenReturn(true);
        when(mfaService.verifyTotp(user, "000000")).thenReturn(false);
        assertThatThrownBy(() -> service.verify(user, new ReAuthDto(null, "000000")))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class)
            .hasMessage("The verification code is incorrect.");
    }

    @Test
    void verify_withMfaEnabled_rejectsMissingTotp() {
        when(mfaService.isEnabled(user)).thenReturn(true);
        assertThatThrownBy(() -> service.verify(user, new ReAuthDto("password", null)))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
    }

    @Test
    void verify_withoutMfa_acceptsValidPassword() {
        when(mfaService.isEnabled(user)).thenReturn(false);
        when(passwordEncoder.matches("hunter2", "$2a$hash")).thenReturn(true);
        service.verify(user, new ReAuthDto("hunter2", null));
    }

    @Test
    void verify_withoutMfa_rejectsWrongPassword() {
        when(mfaService.isEnabled(user)).thenReturn(false);
        when(passwordEncoder.matches("wrong", "$2a$hash")).thenReturn(false);
        assertThatThrownBy(() -> service.verify(user, new ReAuthDto("wrong", null)))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class)
            // A user-facing sentence, not an internal phrase ("invalid password").
            .hasMessage("Your password is incorrect.");
    }

    @Test
    void verify_withoutMfa_rejectsMissingPassword() {
        when(mfaService.isEnabled(user)).thenReturn(false);
        assertThatThrownBy(() -> service.verify(user, new ReAuthDto(null, "123456")))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
    }

    @Test
    void verify_rejectsNullPayload() {
        assertThatThrownBy(() -> service.verify(user, null))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
    }
}
