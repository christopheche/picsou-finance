package com.picsou.service;

import com.picsou.dto.ReAuthDto;
import com.picsou.model.AppUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class ReAuthService {

    public static class ReAuthFailedException extends RuntimeException {
        public ReAuthFailedException(String message) {
            super(message);
        }
    }

    private final MfaService mfaService;
    private final PasswordEncoder passwordEncoder;

    public ReAuthService(MfaService mfaService, PasswordEncoder passwordEncoder) {
        this.mfaService = mfaService;
        this.passwordEncoder = passwordEncoder;
    }

    public void verify(AppUser user, ReAuthDto reAuth) {
        // Messages are user-facing sentences (error-handling.md "Backend message language");
        // the machine-readable marker is the REAUTH_FAILED code set by GlobalExceptionHandler.
        if (reAuth == null) {
            throw new ReAuthFailedException("Please confirm your identity to continue.");
        }
        if (mfaService.isEnabled(user)) {
            if (reAuth.totpCode() == null || reAuth.totpCode().isBlank()) {
                throw new ReAuthFailedException("A verification code is required.");
            }
            if (!mfaService.verifyTotp(user, reAuth.totpCode())) {
                throw new ReAuthFailedException("The verification code is incorrect.");
            }
        } else {
            if (reAuth.password() == null || reAuth.password().isBlank()) {
                throw new ReAuthFailedException("Your password is required.");
            }
            if (!passwordEncoder.matches(reAuth.password(), user.getPasswordHash())) {
                throw new ReAuthFailedException("Your password is incorrect.");
            }
        }
    }
}
