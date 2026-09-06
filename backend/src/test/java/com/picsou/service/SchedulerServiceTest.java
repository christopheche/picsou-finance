package com.picsou.service;

import com.picsou.dto.FinaryAutoSyncResponse;
import com.picsou.finary.FinaryApiSyncService;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.FamilyMember;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The whole design of the scheduler is failure isolation: one connector, one member or one
 * account failing must never cost the others their sync or their snapshot. These tests pin
 * that contract at the orchestration point, where the guards actually live.
 */
@ExtendWith(MockitoExtension.class)
class SchedulerServiceTest {

    private static final FamilyMember ALICE = FamilyMember.builder().id(1L).displayName("Alice").build();
    private static final FamilyMember BOB = FamilyMember.builder().id(2L).displayName("Bob").build();

    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock SyncService syncService;
    @Mock TradeRepublicSyncService trSyncService;
    @Mock BoursoSyncService boursoSyncService;
    @Mock BourseDirectSyncService bourseDirectSyncService;
    @Mock AmundiSyncService amundiSyncService;
    @Mock PriceService priceService;
    @Mock CryptoExchangeSyncService cryptoExchangeSyncService;
    @Mock WalletSyncService walletSyncService;
    @Mock FinaryApiSyncService finaryApiSyncService;
    @Mock IbkrSyncService ibkrSyncService;
    @Mock PropertyValuationService propertyValuationService;

    @InjectMocks SchedulerService scheduler;

    private static Account account(Long id, FamilyMember member) {
        return Account.builder()
            .id(id)
            .member(member)
            .name("Account " + id)
            .type(AccountType.CHECKING)
            .currency("EUR")
            .currentBalance(new BigDecimal("100"))
            .color("#6366f1")
            .build();
    }

    // ─── dailyBankSync ───────────────────────────────────────────────────────

    /**
     * Each connector swallows its own sync failures, but a class-level {@code @Transactional}
     * service (Trade Republic, IBKR) can still throw {@code UnexpectedRollbackException} at the
     * proxy exit, and a bug anywhere can throw anything. None of it may reach the member loop.
     */
    @Test
    void dailyBankSync_connectorFailureDoesNotStopOtherConnectorsOrMembers() {
        when(familyMemberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(ALICE, BOB));
        when(walletSyncService.resyncAll(anyLong()))
            .thenReturn(new WalletSyncService.ResyncSummary(0, 0, List.of()));
        when(finaryApiSyncService.autoSync(anyLong()))
            .thenReturn(new FinaryAutoSyncResponse("NOT_CONNECTED", 0, 0));

        doThrow(new RuntimeException("EB down")).when(syncService).resyncAll(1L);
        doThrow(new UnexpectedRollbackException("Transaction silently rolled back"))
            .when(trSyncService).resyncIfSessionActive(1L);
        doThrow(new UnexpectedRollbackException("Transaction silently rolled back"))
            .when(ibkrSyncService).resyncIfConnected(2L);
        doThrow(new IllegalStateException("bug")).when(cryptoExchangeSyncService).resyncAll(2L);

        scheduler.dailyBankSync();

        for (Long memberId : List.of(1L, 2L)) {
            verify(syncService).resyncAll(memberId);
            verify(syncService).retryAllFailed(memberId);
            verify(trSyncService).resyncIfSessionActive(memberId);
            verify(boursoSyncService).resyncIfSessionActive(memberId);
            verify(bourseDirectSyncService).resyncIfSessionActive(memberId);
            verify(amundiSyncService).resyncIfSessionActive(memberId);
            verify(ibkrSyncService).resyncIfConnected(memberId);
            verify(cryptoExchangeSyncService).resyncAll(memberId);
            verify(walletSyncService).resyncAll(memberId);
            verify(finaryApiSyncService).autoSync(memberId);
        }
    }

    // ─── dailySnapshots ──────────────────────────────────────────────────────

    @Test
    void dailySnapshots_writesLiveAndInvestedEur() {
        Account account = account(10L, ALICE);
        when(familyMemberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(ALICE));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(account));
        when(snapshotRepository.findByAccountIdAndDate(10L, LocalDate.now())).thenReturn(Optional.empty());
        when(accountService.valuation(account)).thenReturn(new AccountService.Valuation(
            new BigDecimal("1234.50"), new BigDecimal("1000"), true, true, false));

        scheduler.dailySnapshots();

        ArgumentCaptor<BalanceSnapshot> captor = ArgumentCaptor.forClass(BalanceSnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        BalanceSnapshot written = captor.getValue();
        assertThat(written.getAccount()).isSameAs(account);
        assertThat(written.getDate()).isEqualTo(LocalDate.now());
        assertThat(written.getBalance()).isEqualByComparingTo("1234.50");
        assertThat(written.getInvestedAmount()).isEqualByComparingTo("1000");
    }

    /**
     * Nothing priced is a blank balance, not a small one: writing it stamps a permanent dip
     * into the net-worth chart for what is usually a transient provider outage (PR #79).
     */
    @Test
    void dailySnapshots_skipsAccountWhenNothingIsPriced() {
        Account account = account(10L, ALICE);
        when(familyMemberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(ALICE));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(account));
        when(snapshotRepository.findByAccountIdAndDate(10L, LocalDate.now())).thenReturn(Optional.empty());
        when(accountService.valuation(account)).thenReturn(new AccountService.Valuation(
            BigDecimal.ZERO, new BigDecimal("448.24"), false, false, false));

        scheduler.dailySnapshots();

        verify(snapshotRepository, never()).save(any(BalanceSnapshot.class));
    }

    /** A past date is never rewritten: a row already there (a sync wrote it) is left alone. */
    @Test
    void dailySnapshots_skipsWhenSnapshotAlreadyExistsToday() {
        Account account = account(10L, ALICE);
        when(familyMemberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(ALICE));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(account));
        when(snapshotRepository.findByAccountIdAndDate(10L, LocalDate.now()))
            .thenReturn(Optional.of(BalanceSnapshot.builder().account(account).date(LocalDate.now())
                .balance(new BigDecimal("99")).build()));

        scheduler.dailySnapshots();

        verify(accountService, never()).valuation(any(Account.class));
        verify(snapshotRepository, never()).save(any(BalanceSnapshot.class));
    }

    @Test
    void dailySnapshots_valuationFailureDoesNotSkipLaterAccountsOrMembers() {
        Account broken = account(10L, ALICE);
        Account fine = account(11L, ALICE);
        Account bobs = account(20L, BOB);
        when(familyMemberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(ALICE, BOB));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(broken, fine));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(2L)).thenReturn(List.of(bobs));
        when(snapshotRepository.findByAccountIdAndDate(anyLong(), any(LocalDate.class))).thenReturn(Optional.empty());
        when(accountService.valuation(broken)).thenThrow(new NullPointerException("price adapter bug"));
        AccountService.Valuation priced = new AccountService.Valuation(
            new BigDecimal("50"), new BigDecimal("40"), true, true, false);
        when(accountService.valuation(fine)).thenReturn(priced);
        when(accountService.valuation(bobs)).thenReturn(priced);

        scheduler.dailySnapshots();

        ArgumentCaptor<BalanceSnapshot> captor = ArgumentCaptor.forClass(BalanceSnapshot.class);
        verify(snapshotRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(s -> s.getAccount().getId()).containsExactly(11L, 20L);
    }

    /**
     * The 08:00 sync jobs write today's row on their own executors; one landing between the
     * existence check and the save trips the (account_id, date) constraint. The day is recorded
     * either way, so the run must go on — and, since each save commits on its own, the rows
     * already written stay written.
     */
    @Test
    void dailySnapshots_concurrentSnapshotWriteIsToleratedAndTheRunGoesOn() {
        Account raced = account(10L, ALICE);
        Account next = account(11L, ALICE);
        when(familyMemberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(ALICE));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(raced, next));
        when(snapshotRepository.findByAccountIdAndDate(anyLong(), any(LocalDate.class))).thenReturn(Optional.empty());
        when(accountService.valuation(any(Account.class))).thenReturn(new AccountService.Valuation(
            new BigDecimal("50"), new BigDecimal("40"), true, true, false));
        when(snapshotRepository.save(any(BalanceSnapshot.class))).thenAnswer(inv -> {
            BalanceSnapshot s = inv.getArgument(0);
            if (s.getAccount().getId() == 10L) {
                throw new DataIntegrityViolationException("duplicate key value violates unique constraint");
            }
            return s;
        });

        scheduler.dailySnapshots();

        ArgumentCaptor<BalanceSnapshot> captor = ArgumentCaptor.forClass(BalanceSnapshot.class);
        verify(snapshotRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(s -> s.getAccount().getId()).containsExactly(10L, 11L);
    }

    /**
     * The per-account guard only means what it says without a transaction spanning the run:
     * a repository failure inside one marks it rollback-only through the repository's own
     * proxy, and the commit at method exit then discards every snapshot of the day. This is
     * structural, so it is pinned structurally.
     */
    @Test
    void dailySnapshots_isNotOneTransaction() throws NoSuchMethodException {
        Method method = SchedulerService.class.getMethod("dailySnapshots");

        assertThat(method.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(SchedulerService.class.isAnnotationPresent(Transactional.class)).isFalse();
    }

    // ─── refreshPrices ───────────────────────────────────────────────────────

    /**
     * A coin sharing its symbol with a listed equity (SUI, ATOM, TIA) must never reach the
     * Yahoo Finance branch, which records what it fetches in price_snapshot.
     */
    @Test
    void refreshPrices_sendsCryptoTickersOnlyToCoinGecko() {
        when(holdingRepository.findDistinctTickersByAccountType(AccountType.CRYPTO)).thenReturn(Set.of("BTC", "SUI"));
        when(accountRepository.findDistinctTickersByType(AccountType.CRYPTO)).thenReturn(Set.of("ETH"));
        when(accountRepository.findDistinctTickersExcludingType(AccountType.CRYPTO)).thenReturn(Set.of("AAPL"));
        when(holdingRepository.findDistinctTickers()).thenReturn(Set.of("BTC", "SUI", "MC.PA"));

        scheduler.refreshPrices();

        verify(priceService).refreshCryptoPrices(Set.of("BTC", "ETH", "SUI"));
        verify(priceService).refreshPrices(Set.of("AAPL", "MC.PA"));
    }

    /**
     * A fixedDelay task fires at context refresh, before the application runners — which is
     * exactly when StartupSyncService and PriceBackfillRunner are already hammering the same
     * providers from the main thread (the 2026-08-01 rate-limit incident).
     */
    @Test
    void refreshPrices_isDeferredPastTheStartupRunners() throws NoSuchMethodException {
        Scheduled schedule = SchedulerService.class.getMethod("refreshPrices").getAnnotation(Scheduled.class);

        assertThat(schedule).isNotNull();
        assertThat(schedule.initialDelay()).isGreaterThanOrEqualTo(60_000L);
    }
}
