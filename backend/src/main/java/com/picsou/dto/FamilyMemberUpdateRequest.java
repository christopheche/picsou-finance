package com.picsou.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/family/members/{id}}. Same bound as {@link FamilyMemberRequest}: the
 * column is {@code VARCHAR(100)}, so a longer name must be a 422 here, not a constraint violation
 * at UPDATE time.
 */
public record FamilyMemberUpdateRequest(
    @NotBlank @Size(max = 100) String displayName
) {}
