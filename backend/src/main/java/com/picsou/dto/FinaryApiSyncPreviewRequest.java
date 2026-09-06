package com.picsou.dto;

import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/finary/api-sync/preview}.
 *
 * <p>{@code totp} is absent on the first attempt and only supplied when Clerk answered
 * {@code needs_second_factor}. It travels in the body, not as {@code ?totp=}, so the live
 * second factor never lands in reverse-proxy access logs or browser history — the same choice
 * every other connector (DEGIRO, Bourse Direct, Amundi) and the app's own MFA make.
 *
 * @param totp current 6-digit authenticator code, or {@code null} on the first attempt
 */
public record FinaryApiSyncPreviewRequest(
    @Pattern(regexp = "\\d{6}") String totp
) {}
