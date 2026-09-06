package com.picsou.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record FamilyMemberRequest(
    @NotBlank @Size(max = 100) String displayName,
    // Stored in a NOT NULL VARCHAR(7) and rendered as an avatar background; same rule as
    // SetupAdminRequest. Null keeps the service default.
    @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "Avatar color must be a hex color like #6366f1")
    String avatarColor
) {}
