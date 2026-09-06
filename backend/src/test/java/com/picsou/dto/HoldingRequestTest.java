package com.picsou.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean-validation contract of {@code PUT /api/accounts/{id}/holdings/{ticker}}.
 *
 * <p>{@code AccountService.updateHolding} stores both figures verbatim and clears the provider
 * valuation, so a negative one is not a short position — the model has none — it is a phantom
 * amount that propagates into the account balance, the dashboard net worth and the P&amp;L.
 */
class HoldingRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<String> violatedFields(Object body) {
        Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(body);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void positiveQuantityAndBuyIn_passes() {
        assertThat(violatedFields(new HoldingRequest(new BigDecimal("12.5"), new BigDecimal("180"))))
            .isEmpty();
    }

    @Test
    void nullBuyIn_passes_serviceLeavesTheStoredValue() {
        assertThat(violatedFields(new HoldingRequest(new BigDecimal("12.5"), null))).isEmpty();
    }

    @Test
    void zeroQuantity_passes_aClosedPositionIsLegitimate() {
        assertThat(violatedFields(new HoldingRequest(BigDecimal.ZERO, null))).isEmpty();
    }

    @Test
    void nullQuantity_isRejected() {
        assertThat(violatedFields(new HoldingRequest(null, null))).containsExactly("quantity");
    }

    @Test
    void negativeQuantityAndBuyIn_areRejected() {
        assertThat(violatedFields(new HoldingRequest(new BigDecimal("-1000"), new BigDecimal("-5"))))
            .containsExactlyInAnyOrder("quantity", "averageBuyIn");
    }
}
