package com.picsou.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean-validation contract of the family-member bodies, checked with the standalone validator (no
 * Spring context): what the controller's {@code @Valid} turns into a 422 rather than a database
 * constraint violation at INSERT/UPDATE time.
 */
class FamilyMemberRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<String> violatedFields(Object body) {
        Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(body);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    // ─── FamilyMemberRequest (POST /api/family/members) ──────────────────────

    @Test
    void create_validNameAndHexColour_passes() {
        assertThat(violatedFields(new FamilyMemberRequest("Kid", "#6366F1"))).isEmpty();
    }

    @Test
    void create_nullColour_passes_serviceAppliesDefault() {
        assertThat(violatedFields(new FamilyMemberRequest("Kid", null))).isEmpty();
    }

    @Test
    void create_colourLongerThanColumn_isRejected() {
        // family_member.avatar_color is VARCHAR(7): this used to be a 500 at INSERT.
        assertThat(violatedFields(new FamilyMemberRequest("Kid", "#12345678"))).containsExactly("avatarColor");
    }

    @Test
    void create_nonHexColour_isRejected() {
        assertThat(violatedFields(new FamilyMemberRequest("Kid", "red"))).containsExactly("avatarColor");
    }

    @Test
    void create_nameOver100Chars_isRejected() {
        assertThat(violatedFields(new FamilyMemberRequest("x".repeat(101), null))).containsExactly("displayName");
    }

    // ─── FamilyMemberUpdateRequest (PUT /api/family/members/{id}) ────────────

    @Test
    void update_validName_passes() {
        assertThat(violatedFields(new FamilyMemberUpdateRequest("Renamed"))).isEmpty();
    }

    @Test
    void update_blankName_isRejected() {
        assertThat(violatedFields(new FamilyMemberUpdateRequest("  "))).containsExactly("displayName");
    }

    @Test
    void update_nameOver100Chars_isRejected() {
        // family_member.display_name is VARCHAR(100): this used to be a 500 at UPDATE.
        assertThat(violatedFields(new FamilyMemberUpdateRequest("x".repeat(101)))).containsExactly("displayName");
    }
}
