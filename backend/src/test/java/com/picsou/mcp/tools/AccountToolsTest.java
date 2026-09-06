package com.picsou.mcp.tools;

import com.picsou.dto.AccountRequest;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.HoldingResponse;
import com.picsou.dto.SnapshotRequest;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.FamilyMember;
import com.picsou.service.AccountConnectionService;
import com.picsou.service.AccountService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Every tool must resolve {@link UserContext#currentMemberId()} (or {@code currentMember()}) and
 * delegate to the already member-scoped {@link AccountService} — never reaching across members.
 * These tests pin that delegation; member isolation itself is enforced (and tested) in the service.
 * They also pin the documented {@code accounts:write} contract: every write tool other than create
 * refuses a synced account before touching the service.
 */
@ExtendWith(MockitoExtension.class)
class AccountToolsTest {

    private static final long MID = 7L;

    @Mock AccountService accountService;
    @Mock AccountConnectionService accountConnectionService;
    @Mock UserContext userContext;
    @InjectMocks AccountTools tools;

    /** The member-scoped lookup every write tool performs first; {@code manual} drives the guard. */
    private void stubAccount(boolean manual) {
        AccountResponse account = mock(AccountResponse.class);
        when(account.isManual()).thenReturn(manual);
        when(accountService.findById(5L, MID)).thenReturn(account);
    }

    @Test
    void listAccounts_delegatesScopedToCurrentMember() {
        AccountResponse r = mock(AccountResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.findAll(MID)).thenReturn(List.of(r));

        assertThat(tools.listAccounts()).containsExactly(r);
    }

    @Test
    void getAccount_delegatesScopedToCurrentMember() {
        AccountResponse r = mock(AccountResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.findById(5L, MID)).thenReturn(r);

        assertThat(tools.getAccount(5L)).isSameAs(r);
    }

    @Test
    void getAccountHoldings_delegatesScopedToCurrentMember() {
        HoldingResponse h = mock(HoldingResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.getHoldings(5L, MID)).thenReturn(List.of(h));

        assertThat(tools.getAccountHoldings(5L)).containsExactly(h);
    }

    @Test
    void getAccountBalanceHistory_delegatesScopedToCurrentMember() {
        BalanceSnapshot s = mock(BalanceSnapshot.class);
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 1);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.getHistory(5L, MID, from, to)).thenReturn(List.of(s));

        assertThat(tools.getAccountBalanceHistory(5L, from, to)).containsExactly(s);
    }

    @Test
    void createManualAccount_forcesManualFlagAndDelegatesWithCurrentMember() {
        FamilyMember member = FamilyMember.builder().id(MID).build();
        AccountResponse created = mock(AccountResponse.class);
        when(userContext.currentMember()).thenReturn(member);
        when(accountService.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(member)))
            .thenReturn(created);

        AccountResponse out = tools.createManualAccount(
            "Livret", AccountType.SAVINGS, "EUR", new BigDecimal("100.00"), "#abcdef", null);

        assertThat(out).isSameAs(created);
        ArgumentCaptor<AccountRequest> captor = ArgumentCaptor.forClass(AccountRequest.class);
        verify(accountService).create(captor.capture(), org.mockito.ArgumentMatchers.eq(member));
        AccountRequest req = captor.getValue();
        assertThat(req.isManual()).isTrue();              // MCP can only create *manual* accounts
        assertThat(req.name()).isEqualTo("Livret");
        assertThat(req.type()).isEqualTo(AccountType.SAVINGS);
        assertThat(req.currency()).isEqualTo("EUR");
        assertThat(req.currentBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void updateAccount_delegatesScopedToCurrentMember() {
        AccountResponse updated = mock(AccountResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        stubAccount(true);
        when(accountService.update(org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(MID))).thenReturn(updated);

        AccountResponse out = tools.updateAccount(
            5L, "Renamed", AccountType.CHECKING, "EUR", null, null, null);

        assertThat(out).isSameAs(updated);
        verify(accountService).update(org.mockito.ArgumentMatchers.eq(5L),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(MID));
    }

    /**
     * Same delegate as {@code AccountController.delete}: the connection behind the account is removed
     * with it once no live account is left (account-deletion ADR). {@code AccountService.delete} only
     * flips {@code deletedAt} and would leave the connection syncing forever.
     */
    @Test
    void deleteAccount_goesThroughAccountConnectionService_scopedToCurrentMember() {
        when(userContext.currentMemberId()).thenReturn(MID);
        stubAccount(true);

        tools.deleteAccount(5L);

        verify(accountConnectionService).deleteAccount(5L, MID);
        verify(accountService, never()).delete(anyLong(), anyLong());
    }

    @Test
    void addBalanceSnapshot_delegatesScopedToCurrentMember() {
        BalanceSnapshot saved = mock(BalanceSnapshot.class);
        LocalDate date = LocalDate.of(2026, 6, 4);
        when(userContext.currentMemberId()).thenReturn(MID);
        stubAccount(true);
        when(accountService.addManualSnapshot(org.mockito.ArgumentMatchers.eq(5L),
            org.mockito.ArgumentMatchers.eq(MID), org.mockito.ArgumentMatchers.any(SnapshotRequest.class)))
            .thenReturn(saved);

        BalanceSnapshot out = tools.addBalanceSnapshot(5L, new BigDecimal("250.50"), date);

        assertThat(out).isSameAs(saved);
        ArgumentCaptor<SnapshotRequest> captor = ArgumentCaptor.forClass(SnapshotRequest.class);
        verify(accountService).addManualSnapshot(org.mockito.ArgumentMatchers.eq(5L),
            org.mockito.ArgumentMatchers.eq(MID), captor.capture());
        assertThat(captor.getValue().balance()).isEqualByComparingTo("250.50");
        assertThat(captor.getValue().date()).isEqualTo(date);
    }

    @Test
    void upsertHolding_upsertsThenReturnsHoldingsDto_neverTheEntity() {
        HoldingResponse h = mock(HoldingResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        stubAccount(true);
        when(accountService.getHoldings(5L, MID)).thenReturn(List.of(h));

        List<HoldingResponse> out = tools.upsertHolding(5L, "AAPL", "Apple", new BigDecimal("3"), new BigDecimal("180"));

        assertThat(out).containsExactly(h);
        verify(accountService).upsertHolding(5L, MID, "AAPL", "Apple", new BigDecimal("3"), new BigDecimal("180"));
        verify(accountService).getHoldings(5L, MID);
    }

    @Test
    void deleteHolding_delegatesScopedToCurrentMember() {
        when(userContext.currentMemberId()).thenReturn(MID);
        stubAccount(true);

        tools.deleteHolding(5L, "AAPL");

        verify(accountService).deleteHolding(5L, MID, "AAPL");
    }

    // ─── accounts:write reaches manual accounts only ──────────────────────────
    // A leaked key must not be able to rename/retype a synced account, rewrite its balance
    // history, or add/remove its positions; those belong to the sync that produced them.

    @Test
    void updateAccount_syncedAccount_isRefusedBeforeAnyWrite() {
        when(userContext.currentMemberId()).thenReturn(MID);
        stubAccount(false);

        assertThatThrownBy(() -> tools.updateAccount(5L, "Renamed", AccountType.CHECKING, "EUR", null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("manual");
        verify(accountService, never()).update(anyLong(), any(), anyLong());
    }

    @Test
    void deleteAccount_syncedAccount_isRefusedBeforeAnyWrite() {
        when(userContext.currentMemberId()).thenReturn(MID);
        stubAccount(false);

        assertThatThrownBy(() -> tools.deleteAccount(5L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("manual");
        verifyNoInteractions(accountConnectionService);
        verify(accountService, never()).delete(anyLong(), anyLong());
    }

    @Test
    void addBalanceSnapshot_syncedAccount_isRefusedBeforeAnyWrite() {
        when(userContext.currentMemberId()).thenReturn(MID);
        stubAccount(false);

        assertThatThrownBy(() -> tools.addBalanceSnapshot(5L, BigDecimal.ZERO, LocalDate.of(2026, 6, 4)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("manual");
        verify(accountService, never()).addManualSnapshot(anyLong(), anyLong(), any());
    }

    @Test
    void upsertHolding_syncedAccount_isRefusedBeforeAnyWrite() {
        when(userContext.currentMemberId()).thenReturn(MID);
        stubAccount(false);

        assertThatThrownBy(() -> tools.upsertHolding(5L, "AAPL", "Apple", BigDecimal.ONE, BigDecimal.TEN))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("manual");
        verify(accountService, never()).upsertHolding(anyLong(), anyLong(), anyString(), anyString(), any(), any());
        verify(accountService, never()).getHoldings(anyLong(), anyLong());
    }

    @Test
    void deleteHolding_syncedAccount_isRefusedBeforeAnyWrite() {
        when(userContext.currentMemberId()).thenReturn(MID);
        stubAccount(false);

        assertThatThrownBy(() -> tools.deleteHolding(5L, "AAPL"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("manual");
        verify(accountService, never()).deleteHolding(anyLong(), anyLong(), anyString());
    }
}
