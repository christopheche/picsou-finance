package com.picsou.dto;

import com.picsou.model.TransactionType;
import com.picsou.validation.ValidCurrency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Body of the manual-transaction endpoints ({@code POST}/{@code PUT}
 * {@code /api/accounts/{id}/transactions}).
 *
 * <p>The {@code @Size} bounds mirror the {@code transaction} columns exactly — description
 * {@code varchar(255)}, ticker {@code varchar(30)}, name {@code varchar(100)}, native_currency
 * {@code varchar(10)} — because {@code ManualTransactionService} copies the fields verbatim, so
 * anything longer used to fail at INSERT and surface as a generic 500 instead of a 422 naming
 * the field. Changing a column's width means changing the bound here too.
 *
 * <p>{@code amount} is deliberately unbounded in sign: an expense is negative. {@code quantity}
 * is not — {@code HoldingComputeService} negates it itself on a SELL, so a negative quantity
 * there would add to the position instead of removing from it.
 */
public record TransactionRequest(
    @NotNull LocalDate date,
    @NotBlank @Size(max = 255) String description,
    @NotNull BigDecimal amount,
    TransactionType txType,
    @Size(max = 30) String ticker,
    @Size(max = 100) String name,
    @DecimalMin("0") BigDecimal quantity,
    BigDecimal pricePerUnit,
    @Size(max = 10) @ValidCurrency String currency,
    BigDecimal fees
) {
    /** Backwards-compatible constructor for callers that do not specify per-trade fees. */
    public TransactionRequest(
        LocalDate date, String description, BigDecimal amount, TransactionType txType,
        String ticker, String name, BigDecimal quantity, BigDecimal pricePerUnit, String currency) {
        this(date, description, amount, txType, ticker, name, quantity, pricePerUnit, currency, null);
    }
}
