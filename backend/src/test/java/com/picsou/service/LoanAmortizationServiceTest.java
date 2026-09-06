package com.picsou.service;

import com.picsou.model.Debt;
import com.picsou.service.LoanAmortizationService.LoanScheduleResponse;
import com.picsou.service.LoanAmortizationService.LoanSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LoanAmortizationServiceTest {

    private final LoanAmortizationService service = new LoanAmortizationService();

    @Test
    void zeroRate_splitEvenlyAcrossInstallments() {
        Debt debt = debt("12000", "0", "100", "2025-01-01", "2026-01-01");

        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2025-01-01"));

        assertThat(out.summary().totalInstallments()).isEqualTo(12);
        assertThat(out.summary().monthlyPayment()).isEqualByComparingTo("100.00");
        assertThat(out.summary().totalInterestCost()).isEqualByComparingTo("0.00");
        assertThat(out.schedule()).hasSize(12);
        assertThat(out.schedule().get(0).capital()).isEqualByComparingTo("100.00");
        assertThat(out.schedule().get(11).remainingBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void monthlyPayment_isComputedWhenNotProvided() {
        // 100k borrowed at 3% over 20y → standard French mortgage
        Debt debt = Debt.builder()
            .borrowedAmount(new BigDecimal("100000"))
            .interestRate(new BigDecimal("0.03"))
            .startDate(LocalDate.parse("2025-01-01"))
            .endDate(LocalDate.parse("2045-01-01"))
            .build();

        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2025-01-01"));

        assertThat(out.summary().totalInstallments()).isEqualTo(240);
        assertThat(out.summary().monthlyPayment().doubleValue())
            .isCloseTo(554.60, within(1.0));
    }

    @Test
    void paidInstallments_computedFromAsOfDate() {
        Debt debt = debt("23649", "0.015", null, "2024-01-01", "2029-01-01");

        // 28 months in (2026-05-01) — matches the user mockup
        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2026-05-01"));

        LoanSummary s = out.summary();
        assertThat(s.totalInstallments()).isEqualTo(60);
        assertThat(s.paidInstallments()).isEqualTo(28);
        assertThat(s.remainingInstallments()).isEqualTo(32);
        assertThat(s.endDate()).isEqualTo(LocalDate.parse("2029-01-01"));
    }

    @Test
    void capitalRepaidPct_isProportionalToCapitalPaid() {
        Debt debt = debt("10000", "0", "1000", "2025-01-01", "2025-11-01");

        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2025-06-01"));

        // 5 months paid out of 10 → 50% of capital
        assertThat(out.summary().paidInstallments()).isEqualTo(5);
        assertThat(out.summary().capitalRepaid()).isEqualByComparingTo("5000.00");
        assertThat(out.summary().capitalRepaidPct()).isEqualByComparingTo("50.00");
        assertThat(out.summary().remainingBalance()).isEqualByComparingTo("5000.00");
    }

    @Test
    void insurance_isSplitOutOfCapital() {
        Debt debt = Debt.builder()
            .borrowedAmount(new BigDecimal("12000"))
            .interestRate(new BigDecimal("0"))
            .monthlyPayment(new BigDecimal("110"))
            .insuranceMonthly(new BigDecimal("10"))
            .startDate(LocalDate.parse("2025-01-01"))
            .endDate(LocalDate.parse("2026-01-01"))
            .build();

        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2025-01-01"));

        assertThat(out.summary().monthlyPayment()).isEqualByComparingTo("110.00");
        assertThat(out.summary().monthlyInsurance()).isEqualByComparingTo("10.00");
        assertThat(out.summary().totalInsuranceCost()).isEqualByComparingTo("120.00");
        // Capital portion of each installment = 110 - 10 (insurance) - 0 (interest) = 100
        assertThat(out.schedule().get(0).capital()).isEqualByComparingTo("100.00");
    }

    @Test
    void totalCost_includesFileFees() {
        Debt debt = Debt.builder()
            .borrowedAmount(new BigDecimal("12000"))
            .interestRate(new BigDecimal("0"))
            .monthlyPayment(new BigDecimal("100"))
            .fileFees(new BigDecimal("500"))
            .startDate(LocalDate.parse("2025-01-01"))
            .endDate(LocalDate.parse("2026-01-01"))
            .build();

        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2025-01-01"));

        // 12000 + 0 (interest) + 0 (insurance) + 500 (fees) = 12500
        assertThat(out.summary().totalCost()).isEqualByComparingTo("12500.00");
        assertThat(out.summary().fileFees()).isEqualByComparingTo("500.00");
    }

    @Test
    void loanFinished_remainingBalanceIsZero() {
        Debt debt = debt("1200", "0", "100", "2024-01-01", "2025-01-01");

        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2030-01-01"));

        assertThat(out.summary().paidInstallments()).isEqualTo(12);
        assertThat(out.summary().remainingInstallments()).isZero();
        assertThat(out.summary().remainingBalance()).isEqualByComparingTo("0.00");
        assertThat(out.summary().capitalRepaidPct()).isEqualByComparingTo("100.00");
    }

    @Test
    void loanNotStartedYet_remainingBalanceIsPrincipal() {
        Debt debt = debt("12000", "0", "100", "2030-01-01", "2031-01-01");

        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2026-01-01"));

        assertThat(out.summary().paidInstallments()).isZero();
        assertThat(out.summary().remainingBalance()).isEqualByComparingTo("12000");
    }

    @Test
    void computeRemainingBalance_returnsPositiveCapitalRemaining() {
        Debt debt = debt("12000", "0", "100", "2025-01-01", "2026-01-01");

        BigDecimal remaining = service.computeRemainingBalance(debt, LocalDate.parse("2025-04-01"));

        // 3 months × 100€ paid → 300 paid, 11700 left
        assertThat(remaining).isEqualByComparingTo("11700.00");
    }

    @Test
    void paidInstallments_countedOnTheSchedulesOwnDates_notOnCalendarMonths() {
        // Installment i is dated startDate.plusMonths(i), so a loan starting on the 20th has
        // nothing due on the 1st of the next month. Counting calendar months marked installment
        // #1 (dated 2026-02-20) as paid on 2026-02-05, nineteen days early, and stepped
        // remainingBalance down with it.
        Debt debt = debt("12000", "0", "1000", "2026-01-20", "2027-01-20");

        LoanScheduleResponse before = service.compute(debt, LocalDate.parse("2026-02-05"));
        assertThat(before.summary().paidInstallments()).isZero();
        assertThat(before.summary().remainingBalance()).isEqualByComparingTo("12000");

        LoanScheduleResponse onDueDate = service.compute(debt, LocalDate.parse("2026-02-20"));
        assertThat(onDueDate.summary().paidInstallments()).isEqualTo(1);
        assertThat(onDueDate.schedule().getFirst().date()).isEqualTo(LocalDate.parse("2026-02-20"));
        assertThat(onDueDate.summary().remainingBalance()).isEqualByComparingTo("11000.00");
    }

    @Test
    void paidInstallments_monthEndStart_countsTheInstallmentDatedOnTheClampedDay() {
        // 31 January + 1 month is 28 February: the schedule dates installment #1 there, so it is
        // due that day even though ChronoUnit.MONTHS.between reports an incomplete month.
        Debt debt = debt("12000", "0", "1000", "2026-01-31", "2027-01-31");

        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2026-02-28"));

        assertThat(out.schedule().getFirst().date()).isEqualTo(LocalDate.parse("2026-02-28"));
        assertThat(out.summary().paidInstallments()).isEqualTo(1);
    }

    private static Debt debt(String borrowed, String rate, String monthly, String start, String end) {
        return Debt.builder()
            .borrowedAmount(new BigDecimal(borrowed))
            .interestRate(rate == null ? null : new BigDecimal(rate))
            .monthlyPayment(monthly == null ? null : new BigDecimal(monthly))
            .startDate(LocalDate.parse(start))
            .endDate(LocalDate.parse(end))
            .build();
    }
}
