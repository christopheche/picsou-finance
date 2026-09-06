package com.picsou.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for storing Finary credentials.
 *
 * <p>Both fields are required: {@code FinaryApiSyncService.login} encrypts them and writes them
 * to {@code finary_session.email} / {@code .password}, both {@code NOT NULL} — a missing field
 * would surface as a 500 from the constraint violation instead of a 422 naming it.
 *
 * <p>The {@code @Size} bounds are on the <em>plaintext</em> while the columns hold the AES-GCM
 * ciphertext: Base64 turns n bytes into roughly {@code 4/3 * (n + 28)} characters, so 254 &rarr;
 * ~376 and 200 &rarr; ~304, both inside {@code varchar(500)}. Raising either bound without
 * widening the column turns a long-but-valid credential into a 500 at INSERT.
 */
public record FinaryLoginRequest(
    @NotBlank @Size(max = 254) String email,
    @NotBlank @Size(max = 200) String password
) {}
