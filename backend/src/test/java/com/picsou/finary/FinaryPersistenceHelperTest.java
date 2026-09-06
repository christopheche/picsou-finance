package com.picsou.finary;

import com.picsou.finary.FinaryPersistenceHelper.ParsedFinaryAccount;
import com.picsou.finary.FinaryPersistenceHelper.ParsedFinaryTransaction;
import com.picsou.model.Account;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.Transaction;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinaryPersistenceHelperTest {

    @Mock BalanceSnapshotRepository balanceSnapshotRepository;
    @Mock TransactionRepository transactionRepository;

    @InjectMocks FinaryPersistenceHelper helper;

    private final Account account = Account.builder().id(5L).name("Livret").currency("EUR")
        .currentBalance(new BigDecimal("100")).build();

    private static ParsedFinaryTransaction tx(LocalDate date, String amount) {
        return new ParsedFinaryTransaction("Livret", date, "flow", new BigDecimal(amount), "", "", "EUR");
    }

    private Map<LocalDate, BigDecimal> savedSnapshots() {
        ArgumentCaptor<BalanceSnapshot> captor = ArgumentCaptor.forClass(BalanceSnapshot.class);
        verify(balanceSnapshotRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream()
            .collect(Collectors.toMap(BalanceSnapshot::getDate, BalanceSnapshot::getBalance));
    }

    // ---------------------------------------------------------------------------
    // Snapshot dating: a row dated D is the end-of-day balance, so D's own flows are
    // already in it (the scheduler's daily row has the same meaning).
    // ---------------------------------------------------------------------------

    @Test
    void reconstructSnapshots_datesBalanceAfterTheDaysOwnFlows() {
        LocalDate deposit = LocalDate.now().minusDays(30);
        ParsedFinaryAccount finaryAcc = new ParsedFinaryAccount("Livret", "Bank", "Savings", new BigDecimal("100"), "EUR");
        when(balanceSnapshotRepository.findRecentByAccountId(eq(5L), any())).thenReturn(List.of());

        int count = helper.reconstructSnapshots(account, finaryAcc, List.of(tx(deposit, "50")));

        Map<LocalDate, BigDecimal> snapshots = savedSnapshots();
        // Balance today 100, one +50 deposit on D: 100 on D itself, 50 the day before, 100 today.
        assertThat(snapshots.get(deposit)).isEqualByComparingTo("100");
        assertThat(snapshots.get(deposit.minusDays(1))).isEqualByComparingTo("50");
        assertThat(snapshots.get(LocalDate.now())).isEqualByComparingTo("100");
        assertThat(count).isEqualTo(3);
    }

    @Test
    void reconstructSnapshots_severalFlowsOnOneDay_recordEndOfDayOnce() {
        LocalDate day = LocalDate.now().minusDays(10);
        LocalDate earlier = LocalDate.now().minusDays(20);
        ParsedFinaryAccount finaryAcc = new ParsedFinaryAccount("Livret", "Bank", "Savings", new BigDecimal("100"), "EUR");
        when(balanceSnapshotRepository.findRecentByAccountId(eq(5L), any())).thenReturn(List.of());

        helper.reconstructSnapshots(account, finaryAcc,
            List.of(tx(day, "30"), tx(earlier, "20"), tx(day, "-10")));

        Map<LocalDate, BigDecimal> snapshots = savedSnapshots();
        assertThat(snapshots.get(day)).isEqualByComparingTo("100");           // after +30 and -10
        assertThat(snapshots.get(earlier)).isEqualByComparingTo("80");        // 100 - 30 + 10
        assertThat(snapshots.get(earlier.minusDays(1))).isEqualByComparingTo("60");
    }

    @Test
    void reconstructSnapshots_flowDatedToday_doesNotDuplicateTheAnchor() {
        LocalDate today = LocalDate.now();
        ParsedFinaryAccount finaryAcc = new ParsedFinaryAccount("Livret", "Bank", "Savings", new BigDecimal("100"), "EUR");
        when(balanceSnapshotRepository.findRecentByAccountId(eq(5L), any())).thenReturn(List.of());

        int count = helper.reconstructSnapshots(account, finaryAcc, List.of(tx(today, "25")));

        Map<LocalDate, BigDecimal> snapshots = savedSnapshots();
        assertThat(snapshots.get(today)).isEqualByComparingTo("100");
        assertThat(snapshots.get(today.minusDays(1))).isEqualByComparingTo("75");
        assertThat(count).isEqualTo(2);
    }

    @Test
    void reconstructSnapshotsFromDb_appliesTheSameEndOfDayRule() {
        LocalDate deposit = LocalDate.now().minusDays(30);
        when(transactionRepository.findByAccountIdOrderByDateDesc(5L)).thenReturn(List.of(
            Transaction.builder().account(account).date(deposit).amount(new BigDecimal("50")).description("d").build()));
        when(balanceSnapshotRepository.findRecentByAccountId(eq(5L), any())).thenReturn(List.of());

        helper.reconstructSnapshotsFromDb(account);

        Map<LocalDate, BigDecimal> snapshots = savedSnapshots();
        assertThat(snapshots.get(deposit)).isEqualByComparingTo("100");
        assertThat(snapshots.get(deposit.minusDays(1))).isEqualByComparingTo("50");
        assertThat(snapshots.get(LocalDate.now())).isEqualByComparingTo("100");
    }

    // ---------------------------------------------------------------------------
    // MAP_EXISTING eligibility
    // ---------------------------------------------------------------------------

    @Test
    void isMappable_acceptsManualAccounts_unboundOrFinaryBound() {
        assertThat(FinaryPersistenceHelper.isMappable(Account.builder().isManual(true).build())).isTrue();
        assertThat(FinaryPersistenceHelper.isMappable(
            Account.builder().isManual(true).externalAccountId("finary_savings_livret_a").build())).isTrue();
    }

    @Test
    void isMappable_rejectsProviderSyncedAccounts() {
        assertThat(FinaryPersistenceHelper.isMappable(
            Account.builder().isManual(false).externalAccountId("tr_pea").build())).isFalse();
        assertThat(FinaryPersistenceHelper.isMappable(
            Account.builder().isManual(true).externalAccountId("ibkr_U123").build())).isFalse();
    }
}
