package com.picsou.service;

import com.picsou.dto.GoalProgressResponse;
import com.picsou.dto.GoalRequest;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.Goal;
import com.picsou.model.GoalManualContribution;
import com.picsou.model.GoalMonthOverride;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.GoalManualContributionRepository;
import com.picsou.repository.GoalMonthOverrideRepository;
import com.picsou.repository.GoalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoalServiceTest {

    @Mock GoalRepository goalRepository;
    @Mock AccountRepository accountRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock AccountService accountService;
    @Mock GoalMonthOverrideRepository overrideRepository;
    @Mock GoalManualContributionRepository manualContributionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock HistoryService historyService;

    @Mock AccountAccessResolver accessResolver;

    /** Goals are member-scoped in the schema; fixtures must reflect that. */
    private static final FamilyMember GOAL_OWNER = FamilyMember.builder().id(1L).displayName("Owner").build();

    /**
     * The service reads "today" from an injected clock, pinned here to a mid-month day so that
     * {@code plusMonths} never clamps and {@code ChronoUnit.MONTHS.between} is exact. Every
     * fixture derives its dates from TODAY, never from the wall clock: the on-track tests used
     * to fail on month-end days (Jan 31 + 3 months = Apr 30 → only 2 whole months left).
     */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);
    private static final Clock CLOCK = Clock.fixed(
        TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    GoalService goalService;

    @BeforeEach
    void wire() {
        goalService = new GoalService(goalRepository, accountRepository, snapshotRepository,
            accountService, overrideRepository, manualContributionRepository,
            familyMemberRepository, historyService, accessResolver, CLOCK);
    }

    @BeforeEach
    void stubOwnershipShares() {
        // No fixture splits an account, so every one resolves to the owning member's 100%.
        // Tests that care about co-ownership override this.
        // Mirrors the batch resolver: every fixture account is wholly owned, so weighting is the
        // identity and these tests keep measuring what they were written to measure.
        lenient().when(accessResolver.sharesFor(any(), any())).thenAnswer(inv -> {
            java.util.Collection<com.picsou.model.Account> accounts = inv.getArgument(0);
            java.util.Map<Long, java.math.BigDecimal> shares = new java.util.HashMap<>();
            for (com.picsou.model.Account a : accounts) {
                shares.put(a.getId(), new java.math.BigDecimal("100"));
            }
            return shares;
        });
    }

    @Test
    void progressCalculation_onTrack() {
        Account account = Account.builder()
            .id(1L)
            .name("LEP")
            .type(AccountType.LEP)
            .currency("EUR")
            .currentBalance(new BigDecimal("5000"))
            .color("#6366f1")
            .build();

        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L)
            .name("Apport immobilier")
            .targetAmount(new BigDecimal("20000"))
            .deadline(TODAY.plusMonths(6))
            .accounts(List.of(account))
            .build();

        when(accountService.toResponse(account)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "LEP", AccountType.LEP, null, "EUR",
                new BigDecimal("5000"), new BigDecimal("5000"),
                null, null, true, "#6366f1", null, null, null, null, null, null, null, null
            )
        );
        when(accountService.signedLiveBalanceEur(account)).thenReturn(new BigDecimal("5000"));
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.currentTotal()).isEqualByComparingTo("5000");
        assertThat(progress.targetAmount()).isEqualByComparingTo("20000");

        // The clock is fixed on the 15th, so `TODAY + 6 months` is exactly 6 whole months
        // away (no day-of-month clamping) and monthlyNeeded = needed / 6.
        long monthsLeft = progress.monthsLeft();
        assertThat(monthsLeft).isEqualTo(6L);
        assertThat(progress.monthlyNeeded()).isEqualByComparingTo(
            new BigDecimal("15000").divide(BigDecimal.valueOf(monthsLeft), 2, RoundingMode.HALF_UP));
        assertThat(progress.percentComplete()).isEqualByComparingTo("25.0000");
    }

    @Test
    void progressCalculation_resolvesSharesInOneQuery() {
        // toProgressResponse runs for every goal on the page, so a per-account share lookup
        // multiplies out across the list. Two accounts, still one query.
        Account lep = Account.builder().id(1L).name("LEP").type(AccountType.LEP)
            .currency("EUR").currentBalance(new BigDecimal("5000")).color("#6366f1").build();
        Account livret = Account.builder().id(2L).name("Livret").type(AccountType.SAVINGS)
            .currency("EUR").currentBalance(new BigDecimal("3000")).color("#22c55e").build();
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L)
            .name("Apport immobilier")
            .targetAmount(new BigDecimal("20000"))
            .deadline(TODAY.plusMonths(6))
            .accounts(List.of(lep, livret))
            .build();

        when(accountService.signedLiveBalanceEur(lep)).thenReturn(new BigDecimal("5000"));
        when(accountService.signedLiveBalanceEur(livret)).thenReturn(new BigDecimal("3000"));

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.currentTotal()).isEqualByComparingTo("8000");
        verify(accessResolver, times(1)).sharesFor(any(), any());
        verify(accessResolver, never()).shareFor(any(), any());
    }

    @Test
    void progressCalculation_linkedLoan_countsNegatively() {
        Account asset = Account.builder()
            .id(1L)
            .name("LEP")
            .type(AccountType.LEP)
            .currency("EUR")
            .currentBalance(new BigDecimal("5000"))
            .color("#6366f1")
            .build();
        Account loan = Account.builder()
            .id(2L)
            .name("Prêt")
            .type(AccountType.LOAN)
            .currency("EUR")
            .currentBalance(new BigDecimal("2000"))
            .color("#ef4444")
            .build();

        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L)
            .name("Apport net")
            .targetAmount(new BigDecimal("20000"))
            .deadline(TODAY.plusMonths(6))
            .accounts(List.of(asset, loan))
            .build();

        when(accountService.toResponse(asset)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "LEP", AccountType.LEP, null, "EUR",
                new BigDecimal("5000"), new BigDecimal("5000"),
                null, null, true, "#6366f1", null, null, null, null, null, null, null, null
            )
        );
        when(accountService.toResponse(loan)).thenReturn(
            new com.picsou.dto.AccountResponse(
                2L, "Prêt", AccountType.LOAN, null, "EUR",
                new BigDecimal("2000"), new BigDecimal("2000"),
                null, null, true, "#ef4444", null, null, null, null, null, null, null, null
            )
        );
        when(accountService.signedLiveBalanceEur(asset)).thenReturn(new BigDecimal("5000"));
        // LOAN: the signed helper returns the outstanding debt as a NEGATIVE value.
        when(accountService.signedLiveBalanceEur(loan)).thenReturn(new BigDecimal("-2000"));
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        // 5000 − 2000: the linked loan reduces goal progress.
        assertThat(progress.currentTotal()).isEqualByComparingTo("3000");
        long monthsLeft = progress.monthsLeft();
        assertThat(monthsLeft).isEqualTo(6L);
        // monthlyNeeded derives from the netted total: (20000 − 3000) / monthsLeft.
        assertThat(progress.monthlyNeeded()).isEqualByComparingTo(
            new BigDecimal("17000").divide(BigDecimal.valueOf(monthsLeft), 2, RoundingMode.HALF_UP));
    }

    // ─── IDOR regression (GHSA security audit 2026-06-27) ──────────────────────
    // A member must not attach (and thereby read the live balance of) another
    // member's account. The account lookup must be member-scoped, never the
    // inherited, unscoped findAllById.

    @Test
    void create_isMemberScoped_andRejectsForeignAccounts() {
        FamilyMember member = FamilyMember.builder().id(42L).build();
        GoalRequest req = new GoalRequest(
            "Trip", new BigDecimal("1000"), TODAY.plusMonths(3), List.of(1L, 2L));
        // Account 2 belongs to someone else → the member-scoped finder returns only the owned one.
        Account owned = Account.builder()
            .id(1L).name("LEP").type(AccountType.LEP).currency("EUR")
            .currentBalance(BigDecimal.ZERO).build();
        when(accountRepository.findByIdInAndMemberId(List.of(1L, 2L), 42L))
            .thenReturn(List.of(owned));

        assertThatThrownBy(() -> goalService.create(req, member))
            .isInstanceOf(IllegalArgumentException.class);

        verify(accountRepository).findByIdInAndMemberId(List.of(1L, 2L), 42L);
        verify(accountRepository, never()).findAllById(any());
        verify(goalRepository, never()).save(any());
    }

    @Test
    void update_isMemberScoped_andRejectsForeignAccounts() {
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(5L).name("Trip").targetAmount(new BigDecimal("1000"))
            .deadline(TODAY.plusMonths(3))
            .accounts(new java.util.ArrayList<>()).build();
        when(goalRepository.findByIdAndMemberId(5L, 42L)).thenReturn(java.util.Optional.of(goal));
        GoalRequest req = new GoalRequest(
            "Trip", new BigDecimal("1000"), TODAY.plusMonths(3), List.of(1L, 2L));
        Account owned = Account.builder()
            .id(1L).name("LEP").type(AccountType.LEP).currency("EUR")
            .currentBalance(BigDecimal.ZERO).build();
        when(accountRepository.findByIdInAndMemberId(List.of(1L, 2L), 42L))
            .thenReturn(List.of(owned));

        assertThatThrownBy(() -> goalService.update(5L, req, 42L))
            .isInstanceOf(IllegalArgumentException.class);

        verify(accountRepository).findByIdInAndMemberId(List.of(1L, 2L), 42L);
        verify(accountRepository, never()).findAllById(any());
        verify(goalRepository, never()).save(any());
    }

    @Test
    void deleteMonthOverride_foreignGoal_doesNotDelete() {
        when(goalRepository.findByIdAndMemberId(99L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goalService.deleteMonthOverride(99L, "2026-06", 42L))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(overrideRepository, never()).findByGoalIdAndYearMonth(any(), any());
        verify(overrideRepository, never()).delete(any());
    }

    @Test
    void deleteManualContribution_foreignGoal_doesNotDelete() {
        when(goalRepository.findByIdAndMemberId(99L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goalService.deleteManualContribution(99L, "2026-06", 42L))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(manualContributionRepository, never()).findByGoalIdAndYearMonth(any(), any());
        verify(manualContributionRepository, never()).delete(any());
    }

    // ─── month key validation: rejected before any lookup or write ──────────
    // The path variable is persisted as the row key, so a loose value used to be saved and then
    // fail in YearMonth.parse — a rolled-back transaction surfacing as a 500 rather than a 400.

    @Test
    void setMonthOverride_malformedMonth_rejectsBeforeAnyWrite() {
        assertThatThrownBy(() -> goalService.setMonthOverride(99L, "2025-3", new BigDecimal("100"), 42L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("YYYY-MM");

        verify(goalRepository, never()).findByIdAndMemberId(any(), any());
        verify(overrideRepository, never()).save(any());
    }

    @Test
    void setManualContribution_malformedMonth_rejectsBeforeAnyWrite() {
        assertThatThrownBy(() -> goalService.setManualContribution(99L, "foo", new BigDecimal("100"), 42L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("YYYY-MM");

        verify(goalRepository, never()).findByIdAndMemberId(any(), any());
        verify(manualContributionRepository, never()).save(any());
    }

    @Test
    void deleteMonthOverride_malformedMonth_rejectsBeforeAnyLookup() {
        assertThatThrownBy(() -> goalService.deleteMonthOverride(99L, "2026-13", 42L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("YYYY-MM");

        verify(goalRepository, never()).findByIdAndMemberId(any(), any());
        verify(overrideRepository, never()).delete(any());
    }

    @Test
    void deleteManualContribution_malformedMonth_rejectsBeforeAnyLookup() {
        assertThatThrownBy(() -> goalService.deleteManualContribution(99L, "2026-06-01", 42L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("YYYY-MM");

        verify(goalRepository, never()).findByIdAndMemberId(any(), any());
        verify(manualContributionRepository, never()).delete(any());
    }

    @Test
    void setMonthOverride_wellFormedMonth_isAccepted() {
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(99L)
            .name("Trip")
            .targetAmount(new BigDecimal("1200"))
            .deadline(TODAY.plusMonths(6))
            .accounts(List.of())
            .build();
        when(goalRepository.findByIdAndMemberId(99L, 42L)).thenReturn(Optional.of(goal));
        when(overrideRepository.findByGoalIdAndYearMonth(99L, "2026-06")).thenReturn(Optional.empty());
        when(manualContributionRepository.findByGoalIdAndYearMonth(99L, "2026-06")).thenReturn(Optional.empty());

        var response = goalService.setMonthOverride(99L, "2026-06", new BigDecimal("150"), 42L);

        verify(overrideRepository).save(any(GoalMonthOverride.class));
        assertThat(response.yearMonth()).isEqualTo("2026-06");
        assertThat(response.override()).isEqualByComparingTo("150");
    }

    @Test
    void deleteMonthOverride_ownedGoal_deletesEntry() {
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(99L)
            .name("Trip")
            .targetAmount(new BigDecimal("1200"))
            .deadline(TODAY.plusMonths(6))
            .accounts(List.of())
            .build();
        GoalMonthOverride override = new GoalMonthOverride();
        override.setGoal(goal);
        override.setYearMonth("2026-06");
        override.setAmount(new BigDecimal("150"));
        when(goalRepository.findByIdAndMemberId(99L, 42L)).thenReturn(Optional.of(goal));
        when(overrideRepository.findByGoalIdAndYearMonth(99L, "2026-06"))
            .thenReturn(Optional.of(override));

        var response = goalService.deleteMonthOverride(99L, "2026-06", 42L);

        verify(overrideRepository).delete(override);
        assertThat(response.yearMonth()).isEqualTo("2026-06");
        assertThat(response.override()).isNull();
    }

    @Test
    void deleteManualContribution_ownedGoal_deletesEntry() {
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(99L)
            .name("Trip")
            .targetAmount(new BigDecimal("1200"))
            .deadline(TODAY.plusMonths(6))
            .accounts(List.of())
            .build();
        GoalManualContribution contribution = new GoalManualContribution();
        contribution.setGoal(goal);
        contribution.setYearMonth("2026-06");
        contribution.setAmount(new BigDecimal("150"));
        when(goalRepository.findByIdAndMemberId(99L, 42L)).thenReturn(Optional.of(goal));
        when(manualContributionRepository.findByGoalIdAndYearMonth(99L, "2026-06"))
            .thenReturn(Optional.of(contribution));

        var response = goalService.deleteManualContribution(99L, "2026-06", 42L);

        verify(manualContributionRepository).delete(contribution);
        assertThat(response.yearMonth()).isEqualTo("2026-06");
        assertThat(response.manualActual()).isNull();
    }

    @Test
    void isOnTrack_false_whenPastEffectivesBelowPastObjectives() {
        // 3 past months, each with snapshot delta = 1000€.
        // Target 12000, current 0, deadline +3 months → monthlyNeeded = 4000.
        // sumObjectivePast = 3 * 4000 = 12000 ; sumEffectivePast = 3 * 1000 = 3000 → behind.
        Account account = Account.builder()
            .id(1L).name("Livret").type(AccountType.SAVINGS)
            .currency("EUR").currentBalance(BigDecimal.ZERO)
            .color("#000").build();

        java.time.Instant created = TODAY.minusMonths(3).withDayOfMonth(1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L).name("Test").targetAmount(new BigDecimal("12000"))
            .deadline(TODAY.plusMonths(3))
            .accounts(List.of(account))
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "createdAt", created);

        when(accountService.toResponse(account)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "Livret", AccountType.SAVINGS, null, "EUR",
                BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, true, "#000", null, null, null, null, null, null, null, null
            )
        );
        when(accountService.signedLiveBalanceEur(account)).thenReturn(BigDecimal.ZERO);
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        // Per-month-end balances so each past month delta = 1000.
        // M-4 end = 0, M-3 end = 1000, M-2 end = 2000, M-1 end = 3000.
        java.time.YearMonth now = java.time.YearMonth.from(TODAY);
        for (int i = 1; i <= 3; i++) {
            java.time.YearMonth past = now.minusMonths(i);
            LocalDate prevEnd = past.minusMonths(1).atEndOfMonth();
            LocalDate thisEnd = past.atEndOfMonth();
            BigDecimal prevBalance = new BigDecimal(String.valueOf((4 - i - 1) * 1000));
            BigDecimal thisBalance = new BigDecimal(String.valueOf((4 - i) * 1000));
            lenient().when(snapshotRepository
                .findFirstByAccountIdAndDateLessThanEqualOrderByDateDesc(1L, prevEnd))
                .thenReturn(java.util.Optional.of(
                    com.picsou.model.BalanceSnapshot.builder()
                        .balance(prevBalance).date(prevEnd).build()));
            lenient().when(snapshotRepository
                .findFirstByAccountIdAndDateLessThanEqualOrderByDateDesc(1L, thisEnd))
                .thenReturn(java.util.Optional.of(
                    com.picsou.model.BalanceSnapshot.builder()
                        .balance(thisBalance).date(thisEnd).build()));
        }
        lenient().when(overrideRepository.findByGoalId(1L)).thenReturn(List.of());
        lenient().when(manualContributionRepository.findByGoalId(1L)).thenReturn(List.of());

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.isOnTrack()).isFalse();
    }

    @Test
    void avgMonthlyContribution_loanPaydown_countsAsPositiveProgress() {
        // Outstanding debt shrinks 12000 → 9000 over 3 months: the raw snapshot delta
        // is −1000/month, but paying down a linked loan is positive progress.
        Account loan = Account.builder()
            .id(1L).name("Mortgage").type(AccountType.LOAN)
            .currency("EUR").currentBalance(new BigDecimal("9000"))
            .color("#ef4444").build();

        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L).name("Rembourser").targetAmount(new BigDecimal("12000"))
            .deadline(TODAY.plusMonths(12))
            .accounts(List.of(loan))
            .build();

        when(accountService.toResponse(loan)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "Mortgage", AccountType.LOAN, null, "EUR",
                new BigDecimal("9000"), new BigDecimal("9000"),
                null, null, true, "#ef4444", null, null, null, null, null, null, null, null
            )
        );
        when(accountService.signedLiveBalanceEur(loan)).thenReturn(new BigDecimal("-9000"));
        LocalDate threeMonthsAgo = TODAY.minusMonths(3);
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of(
            com.picsou.model.BalanceSnapshot.builder()
                .balance(new BigDecimal("12000")).date(threeMonthsAgo).build(),
            com.picsou.model.BalanceSnapshot.builder()
                .balance(new BigDecimal("9000")).date(TODAY).build()
        ));
        lenient().when(overrideRepository.findByGoalId(1L)).thenReturn(List.of());
        lenient().when(manualContributionRepository.findByGoalId(1L)).thenReturn(List.of());

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        // (12000 − 9000) / 3 months, sign flipped for the LOAN account.
        assertThat(progress.avgMonthlyContribution()).isEqualByComparingTo("1000");
    }

    @Test
    void isOnTrack_true_whenManualContributionCoversShortfall() {
        // Same setup as the "behind" test but user declares 4000€ manual contribution
        // for each of the 3 past months → effective matches objective → on track.
        // Relies on monthlyNeeded being exactly 12000 / 3 = 4000, which holds only because
        // the clock is fixed mid-month (asserted below): on a month-end day the deadline
        // would clamp to 2 whole months away and the objective would jump to 6000.
        Account account = Account.builder()
            .id(1L).name("Livret").type(AccountType.SAVINGS)
            .currency("EUR").currentBalance(BigDecimal.ZERO)
            .color("#000").build();

        java.time.Instant created = TODAY.minusMonths(3).withDayOfMonth(1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L).name("Test").targetAmount(new BigDecimal("12000"))
            .deadline(TODAY.plusMonths(3))
            .accounts(List.of(account))
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "createdAt", created);

        when(accountService.toResponse(account)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "Livret", AccountType.SAVINGS, null, "EUR",
                BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, true, "#000", null, null, null, null, null, null, null, null
            )
        );
        when(accountService.signedLiveBalanceEur(account)).thenReturn(BigDecimal.ZERO);
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        when(overrideRepository.findByGoalId(1L)).thenReturn(List.of());

        java.time.YearMonth now = java.time.YearMonth.from(TODAY);
        java.util.List<com.picsou.model.GoalManualContribution> manuals = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            com.picsou.model.GoalManualContribution m = new com.picsou.model.GoalManualContribution();
            m.setGoal(goal);
            m.setYearMonth(now.minusMonths(i).toString());
            m.setAmount(new BigDecimal("4000"));
            manuals.add(m);
        }
        when(manualContributionRepository.findByGoalId(1L)).thenReturn(manuals);

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.monthsLeft()).isEqualTo(3L);
        assertThat(progress.monthlyNeeded()).isEqualByComparingTo("4000");
        assertThat(progress.isOnTrack()).isTrue();
    }

    @Test
    void isOnTrack_true_whenNoPastMonthHasData() {
        // Goal created this month → no past months → benefit of the doubt.
        Account account = Account.builder()
            .id(1L).name("LEP").type(AccountType.LEP)
            .currency("EUR").currentBalance(BigDecimal.ZERO)
            .color("#000").build();

        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L).name("Tout neuf").targetAmount(new BigDecimal("10000"))
            .deadline(TODAY.plusMonths(6))
            .accounts(List.of(account))
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "createdAt", CLOCK.instant());

        when(accountService.toResponse(account)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "LEP", AccountType.LEP, null, "EUR",
                BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, true, "#000", null, null, null, null, null, null, null, null
            )
        );
        when(accountService.signedLiveBalanceEur(account)).thenReturn(BigDecimal.ZERO);
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.isOnTrack()).isTrue();
    }

    // ─── Backfill history ───────────────────────────────────────────────────

    private Goal backfillGoal(String historyStartMonth) {
        java.time.Instant created = TODAY.withDayOfMonth(1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L).name("Backfill").targetAmount(new BigDecimal("1000"))
            .deadline(TODAY.plusMonths(2))
            .accounts(List.of())
            .historyStartMonth(historyStartMonth)
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "createdAt", created);
        return goal;
    }

    @Test
    void getMonthlyEntries_usesHistoryStartWhenEarlierThanCreatedAt() {
        String historyStart = java.time.YearMonth.from(TODAY).minusYears(1).toString();
        Goal goal = backfillGoal(historyStart);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));

        var entries = goalService.getMonthlyEntries(1L, 1L);

        assertThat(entries).isNotEmpty();
        assertThat(entries.get(0).yearMonth()).isEqualTo(historyStart);
        // No accounts and no manual/override data → backfilled month is empty.
        assertThat(entries.get(0).effective()).isNull();
    }

    @Test
    void getMonthlyEntries_ignoresHistoryStartWhenLaterThanCreatedAt() {
        String historyStart = java.time.YearMonth.from(TODAY).plusMonths(2).toString();
        Goal goal = backfillGoal(historyStart);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));

        var entries = goalService.getMonthlyEntries(1L, 1L);

        assertThat(entries.get(0).yearMonth()).isEqualTo(java.time.YearMonth.from(TODAY).toString());
    }

    @Test
    void extendHistory_decrementsByOneYearFromCreatedAt() {
        Goal goal = backfillGoal(null);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));
        when(goalRepository.save(goal)).thenReturn(goal);

        GoalProgressResponse progress = goalService.extendHistory(1L, 1L);

        String expected = java.time.YearMonth.from(TODAY).minusYears(1).toString();
        assertThat(goal.getHistoryStartMonth()).isEqualTo(expected);
        assertThat(progress.historyStartMonth()).isEqualTo(expected);
    }

    @Test
    void extendHistory_decrementsFromExistingHistoryStart() {
        String existing = java.time.YearMonth.from(TODAY).minusYears(1).toString();
        Goal goal = backfillGoal(existing);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));
        when(goalRepository.save(goal)).thenReturn(goal);

        goalService.extendHistory(1L, 1L);

        assertThat(goal.getHistoryStartMonth())
            .isEqualTo(java.time.YearMonth.from(TODAY).minusYears(2).toString());
    }

    @Test
    void extendHistoryByMonth_decrementsByOneMonthFromCreatedAt() {
        Goal goal = backfillGoal(null);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));
        when(goalRepository.save(goal)).thenReturn(goal);

        GoalProgressResponse progress = goalService.extendHistoryByMonth(1L, 1L);

        String expected = java.time.YearMonth.from(TODAY).minusMonths(1).toString();
        assertThat(goal.getHistoryStartMonth()).isEqualTo(expected);
        assertThat(progress.historyStartMonth()).isEqualTo(expected);
    }

    @Test
    void extendHistoryByMonth_decrementsFromExistingHistoryStart() {
        String existing = java.time.YearMonth.from(TODAY).minusMonths(1).toString();
        Goal goal = backfillGoal(existing);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));
        when(goalRepository.save(goal)).thenReturn(goal);

        goalService.extendHistoryByMonth(1L, 1L);

        assertThat(goal.getHistoryStartMonth())
            .isEqualTo(java.time.YearMonth.from(TODAY).minusMonths(2).toString());
    }

    @Test
    void isOnTrack_unaffectedByHistoryStart() {
        // History extended far back with no data → on-track stays anchored to createdAt
        // (created this month → no past months → benefit of the doubt).
        Goal goal = backfillGoal(java.time.YearMonth.from(TODAY).minusYears(5).toString());
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));

        GoalProgressResponse progress = goalService.findById(1L, 1L);

        assertThat(progress.isOnTrack()).isTrue();
    }

    // ─── avgMonthlyContribution: the goal moves by the SUM of its accounts' paces ────

    @Test
    void avgMonthlyContribution_multiAccount_sumsPerAccountPaces() {
        // Livret A and PEA each grow 300/month over 3 months. The goal's pace is 600/month
        // (what `surplus` and the "at current pace" projection are compared against), not
        // the 300/month mean of a single account.
        Account livret = Account.builder().id(1L).name("Livret A").type(AccountType.SAVINGS)
            .currency("EUR").currentBalance(new BigDecimal("900")).color("#22c55e").build();
        Account pea = Account.builder().id(2L).name("PEA").type(AccountType.PEA)
            .currency("EUR").currentBalance(new BigDecimal("900")).color("#6366f1").build();

        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L).name("Apport").targetAmount(new BigDecimal("10000"))
            .deadline(TODAY.plusMonths(10))
            .accounts(List.of(livret, pea))
            .build();

        when(accountService.signedLiveBalanceEur(livret)).thenReturn(new BigDecimal("900"));
        when(accountService.signedLiveBalanceEur(pea)).thenReturn(new BigDecimal("900"));
        LocalDate threeMonthsAgo = TODAY.minusMonths(3);
        for (long id : new long[] {1L, 2L}) {
            when(snapshotRepository.findRecentByAccountId(
                org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.any()
            )).thenReturn(List.of(
                com.picsou.model.BalanceSnapshot.builder()
                    .balance(new BigDecimal("0")).date(threeMonthsAgo).build(),
                com.picsou.model.BalanceSnapshot.builder()
                    .balance(new BigDecimal("900")).date(TODAY).build()
            ));
        }

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.avgMonthlyContribution()).isEqualByComparingTo("600");
        // target 10000 − current 1800 = 8200 over 10 months → 820/month; surplus = 600 − 820.
        assertThat(progress.monthlyNeeded()).isEqualByComparingTo("820");
        assertThat(progress.surplus()).isEqualByComparingTo("-220");
    }

    // ─── Month override = objective, never the month's effective savings ────────────
    // The calendar divides `effective` by the (overridden) objective; folding the override
    // into `effective` showed the planned amount as if it had been saved.

    /** A goal created three months ago with no linked account, so every actual is null. */
    private Goal manualGoal() {
        java.time.Instant created = TODAY.minusMonths(3).withDayOfMonth(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant();
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L).name("Manual").targetAmount(new BigDecimal("6000"))
            .deadline(TODAY.plusMonths(3))
            .accounts(List.of())
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "createdAt", created);
        return goal;
    }

    private static GoalMonthOverride override(Goal goal, String yearMonth, String amount) {
        GoalMonthOverride o = new GoalMonthOverride();
        o.setGoal(goal);
        o.setYearMonth(yearMonth);
        o.setAmount(new BigDecimal(amount));
        return o;
    }

    private static GoalManualContribution manual(Goal goal, String yearMonth, String amount) {
        GoalManualContribution m = new GoalManualContribution();
        m.setGoal(goal);
        m.setMember(GOAL_OWNER);
        m.setYearMonth(yearMonth);
        m.setAmount(new BigDecimal(amount));
        return m;
    }

    @Test
    void getMonthlyEntries_overrideDoesNotReplaceEffective() {
        // July: the user planned to save 1000 (override) but declared 200 (manual).
        // The entry must report 200 saved against an objective of 1000, not "1000 saved".
        Goal goal = manualGoal();
        String july = java.time.YearMonth.from(TODAY).minusMonths(2).toString();
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(goal));
        when(overrideRepository.findByGoalId(1L)).thenReturn(List.of(override(goal, july, "1000")));
        when(manualContributionRepository.findByGoalId(1L)).thenReturn(List.of(manual(goal, july, "200")));

        var entries = goalService.getMonthlyEntries(1L, 1L);

        var entry = entries.stream().filter(e -> e.yearMonth().equals(july)).findFirst().orElseThrow();
        assertThat(entry.override()).isEqualByComparingTo("1000");
        assertThat(entry.manualActual()).isEqualByComparingTo("200");
        assertThat(entry.effective()).isEqualByComparingTo("200");
        // The computed objective is untouched by the override: 6000 / 3 months left.
        assertThat(entry.objective()).isEqualByComparingTo("2000");
    }

    @Test
    void getMonthlyEntries_overrideWithoutSavings_leavesEffectiveNull() {
        // A holiday month with objective overridden to 0 and nothing recorded stays "no data",
        // rather than rendering as 0 saved.
        Goal goal = manualGoal();
        String july = java.time.YearMonth.from(TODAY).minusMonths(2).toString();
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(goal));
        when(overrideRepository.findByGoalId(1L)).thenReturn(List.of(override(goal, july, "0")));
        when(manualContributionRepository.findByGoalId(1L)).thenReturn(List.of());

        var entries = goalService.getMonthlyEntries(1L, 1L);

        var entry = entries.stream().filter(e -> e.yearMonth().equals(july)).findFirst().orElseThrow();
        assertThat(entry.override()).isEqualByComparingTo("0");
        assertThat(entry.effective()).isNull();
    }

    @Test
    void setMonthOverride_returnsManualOrActualAsEffective() {
        Goal goal = manualGoal();
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(goal));
        when(overrideRepository.findByGoalIdAndYearMonth(1L, "2026-07")).thenReturn(Optional.empty());
        when(manualContributionRepository.findByGoalIdAndYearMonth(1L, "2026-07"))
            .thenReturn(Optional.of(manual(goal, "2026-07", "200")));

        var response = goalService.setMonthOverride(1L, "2026-07", new BigDecimal("1000"), 1L);

        assertThat(response.override()).isEqualByComparingTo("1000");
        assertThat(response.manualActual()).isEqualByComparingTo("200");
        assertThat(response.effective()).isEqualByComparingTo("200");
    }

    @Test
    void setManualContribution_withExistingOverride_effectiveIsManual() {
        Goal goal = manualGoal();
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(goal));
        when(manualContributionRepository.findByGoalIdAndYearMonth(1L, "2026-07")).thenReturn(Optional.empty());
        when(overrideRepository.findByGoalIdAndYearMonth(1L, "2026-07"))
            .thenReturn(Optional.of(override(goal, "2026-07", "1000")));

        var response = goalService.setManualContribution(1L, "2026-07", new BigDecimal("200"), 1L);

        assertThat(response.override()).isEqualByComparingTo("1000");
        assertThat(response.manualActual()).isEqualByComparingTo("200");
        assertThat(response.effective()).isEqualByComparingTo("200");
    }

    @Test
    void deleteManualContribution_withExistingOverride_effectiveFallsBackToActual() {
        Goal goal = manualGoal();
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(goal));
        when(manualContributionRepository.findByGoalIdAndYearMonth(1L, "2026-07"))
            .thenReturn(Optional.of(manual(goal, "2026-07", "200")));
        when(overrideRepository.findByGoalIdAndYearMonth(1L, "2026-07"))
            .thenReturn(Optional.of(override(goal, "2026-07", "1000")));

        var response = goalService.deleteManualContribution(1L, "2026-07", 1L);

        assertThat(response.override()).isEqualByComparingTo("1000");
        assertThat(response.manualActual()).isNull();
        // No linked account → no snapshot delta → nothing saved is known, not "1000 saved".
        assertThat(response.effective()).isNull();
    }

    // ─── Writers are member-scoped and upsert ───────────────────────────────────────

    @Test
    void setMonthOverride_foreignGoal_throws_andSavesNothing() {
        when(goalRepository.findByIdAndMemberId(99L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goalService.setMonthOverride(99L, "2026-06", new BigDecimal("100"), 42L))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(overrideRepository, never()).findByGoalIdAndYearMonth(any(), any());
        verify(overrideRepository, never()).save(any());
    }

    @Test
    void setManualContribution_foreignGoal_throws_andSavesNothing() {
        when(goalRepository.findByIdAndMemberId(99L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goalService.setManualContribution(99L, "2026-06", new BigDecimal("100"), 42L))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(manualContributionRepository, never()).findByGoalIdAndYearMonth(any(), any());
        verify(manualContributionRepository, never()).save(any());
    }

    @Test
    void setMonthOverride_ownedGoal_upsertsExistingEntry() {
        Goal goal = manualGoal();
        GoalMonthOverride existing = override(goal, "2026-07", "500");
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(goal));
        when(overrideRepository.findByGoalIdAndYearMonth(1L, "2026-07")).thenReturn(Optional.of(existing));
        when(manualContributionRepository.findByGoalIdAndYearMonth(1L, "2026-07")).thenReturn(Optional.empty());

        goalService.setMonthOverride(1L, "2026-07", new BigDecimal("1000"), 1L);

        ArgumentCaptor<GoalMonthOverride> saved = ArgumentCaptor.forClass(GoalMonthOverride.class);
        verify(overrideRepository).save(saved.capture());
        // The same row is updated in place rather than a second one inserted for the month.
        assertThat(saved.getValue()).isSameAs(existing);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("1000");
        assertThat(saved.getValue().getYearMonth()).isEqualTo("2026-07");
    }

    @Test
    void setManualContribution_setsMemberOnNewEntry() {
        Goal goal = manualGoal();
        FamilyMember ownerRef = FamilyMember.builder().id(1L).build();
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(goal));
        when(manualContributionRepository.findByGoalIdAndYearMonth(1L, "2026-07")).thenReturn(Optional.empty());
        when(familyMemberRepository.getReferenceById(1L)).thenReturn(ownerRef);
        when(overrideRepository.findByGoalIdAndYearMonth(1L, "2026-07")).thenReturn(Optional.empty());

        goalService.setManualContribution(1L, "2026-07", new BigDecimal("200"), 1L);

        ArgumentCaptor<GoalManualContribution> saved = ArgumentCaptor.forClass(GoalManualContribution.class);
        verify(manualContributionRepository).save(saved.capture());
        // member_id is NOT NULL (V22): a fresh row must carry the caller as its member.
        assertThat(saved.getValue().getMember()).isSameAs(ownerRef);
        assertThat(saved.getValue().getGoal()).isSameAs(goal);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("200");
    }

    // ─── Snapshot-derived actual: a month with no snapshot inside it is unknown ─────

    @Test
    void getMonthlyEntries_monthWithoutSnapshot_hasNullActual_notZero() {
        // Snapshots on Jun 30 (500) and Jul 31 (1000), then the instance was offline.
        // July has a real delta; August has no snapshot at all, so its actual is unknown —
        // reporting 0 would count a full objective against nothing in isOnTrack.
        Account account = Account.builder()
            .id(1L).name("Livret").type(AccountType.SAVINGS)
            .currency("EUR").currentBalance(new BigDecimal("1000"))
            .color("#000").build();
        java.time.Instant created = LocalDate.of(2026, 7, 1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant();
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L).name("Test").targetAmount(new BigDecimal("6000"))
            .deadline(TODAY.plusMonths(3))
            .accounts(List.of(account))
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "createdAt", created);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(goal));
        when(accountService.signedLiveBalanceEur(account)).thenReturn(new BigDecimal("1000"));
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        List<com.picsou.model.BalanceSnapshot> snapshots = List.of(
            com.picsou.model.BalanceSnapshot.builder()
                .balance(new BigDecimal("500")).date(LocalDate.of(2026, 6, 30)).build(),
            com.picsou.model.BalanceSnapshot.builder()
                .balance(new BigDecimal("1000")).date(LocalDate.of(2026, 7, 31)).build()
        );
        // "Latest snapshot on or before the date" — exactly what the repository query does.
        when(snapshotRepository.findFirstByAccountIdAndDateLessThanEqualOrderByDateDesc(
            org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any()
        )).thenAnswer(inv -> {
            LocalDate cutoff = inv.getArgument(1);
            return snapshots.stream()
                .filter(sn -> !sn.getDate().isAfter(cutoff))
                .reduce((a, b) -> b);
        });

        var entries = goalService.getMonthlyEntries(1L, 1L);

        var july = entries.stream().filter(e -> e.yearMonth().equals("2026-07")).findFirst().orElseThrow();
        var august = entries.stream().filter(e -> e.yearMonth().equals("2026-08")).findFirst().orElseThrow();
        assertThat(july.actual()).isEqualByComparingTo("500");
        assertThat(july.effective()).isEqualByComparingTo("500");
        assertThat(august.actual()).isNull();
        assertThat(august.effective()).isNull();
    }
}
