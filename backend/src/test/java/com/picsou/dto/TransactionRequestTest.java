package com.picsou.dto;

import com.picsou.model.TransactionType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean-validation contract of the manual-transaction body, checked with the standalone
 * validator (no Spring context): what the controller's {@code @Valid} turns into a 422 rather
 * than a {@code DataIntegrityViolationException} at INSERT time (a generic 500) or a row the
 * frontend's currency formatter cannot render.
 */
class TransactionRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<String> violatedFields(Object body) {
        Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(body);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    private static TransactionRequest valid() {
        return new TransactionRequest(
            LocalDate.of(2026, 1, 5), "Groceries", new BigDecimal("-42.10"), TransactionType.BUY,
            "AAPL", "Apple Inc.", new BigDecimal("3"), new BigDecimal("180"), "USD", BigDecimal.ONE
        );
    }

    @Test
    void fullyPopulatedRequest_passes() {
        assertThat(violatedFields(valid())).isEmpty();
    }

    @Test
    void optionalInstrumentFields_mayBeNull() {
        assertThat(violatedFields(new TransactionRequest(
            LocalDate.of(2026, 1, 5), "Rent", new BigDecimal("-900"), null,
            null, null, null, null, null, null
        ))).isEmpty();
    }

    @Test
    void descriptionLongerThanColumn_isRejected() {
        // transaction.description is VARCHAR(255): this used to be a 500 at INSERT.
        TransactionRequest req = new TransactionRequest(
            LocalDate.of(2026, 1, 5), "x".repeat(256), BigDecimal.ONE, null,
            null, null, null, null, null, null
        );
        assertThat(violatedFields(req)).containsExactly("description");
    }

    @Test
    void tickerAndNameLongerThanTheirColumns_areRejected() {
        // transaction.ticker is VARCHAR(30), transaction.name is VARCHAR(100).
        TransactionRequest req = new TransactionRequest(
            LocalDate.of(2026, 1, 5), "Buy", BigDecimal.ONE, TransactionType.BUY,
            "x".repeat(31), "y".repeat(101), null, null, null, null
        );
        assertThat(violatedFields(req)).containsExactlyInAnyOrder("ticker", "name");
    }

    @Test
    void unknownCurrencyCode_isRejected() {
        // Persisting an unknown ISO code crashes the frontend's currency formatter (issue #9).
        TransactionRequest req = new TransactionRequest(
            LocalDate.of(2026, 1, 5), "Buy", BigDecimal.ONE, TransactionType.BUY,
            null, null, null, null, "XYZ", null
        );
        assertThat(violatedFields(req)).containsExactly("currency");
    }

    @Test
    void currencyLongerThanColumn_isRejected() {
        TransactionRequest req = new TransactionRequest(
            LocalDate.of(2026, 1, 5), "Buy", BigDecimal.ONE, TransactionType.BUY,
            null, null, null, null, "NOTACURRENCY", null
        );
        assertThat(violatedFields(req)).containsExactly("currency");
    }

    @Test
    void negativeQuantity_isRejected() {
        // HoldingComputeService negates the quantity itself on a SELL, so a negative one there
        // would add to the position instead of removing from it.
        TransactionRequest req = new TransactionRequest(
            LocalDate.of(2026, 1, 5), "Sell", BigDecimal.ONE, TransactionType.SELL,
            "AAPL", null, new BigDecimal("-3"), new BigDecimal("180"), null, null
        );
        assertThat(violatedFields(req)).containsExactly("quantity");
    }

    @Test
    void negativeAmount_passes_anExpenseIsNegative() {
        TransactionRequest req = new TransactionRequest(
            LocalDate.of(2026, 1, 5), "Rent", new BigDecimal("-900"), null,
            null, null, null, null, "EUR", null
        );
        assertThat(violatedFields(req)).isEmpty();
    }
}
