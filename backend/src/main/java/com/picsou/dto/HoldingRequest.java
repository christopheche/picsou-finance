package com.picsou.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Body of {@code PUT /api/accounts/{id}/holdings/{ticker}}.
 *
 * <p>Both figures are bounded at zero like every other money-bearing request record. There is no
 * short-selling anywhere in the model — {@code HoldingComputeService} tracks a net long position
 * and negates the quantity itself on a SELL — so a negative value here is not a short, it is a
 * phantom amount that {@code AccountService.updateHolding} stores verbatim and that then flows
 * into the account balance, the dashboard net worth and the P&amp;L.
 */
public record HoldingRequest(
    @NotNull @DecimalMin("0") BigDecimal quantity,
    @DecimalMin("0") BigDecimal averageBuyIn
) {}
