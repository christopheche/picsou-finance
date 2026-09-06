package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.adapter.OpenFigiIsinConverter.TickerResult;
import com.picsou.config.CryptoEncryption;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.TradeRepublicSession;
import com.picsou.port.TradeRepublicPort;
import com.picsou.port.TradeRepublicPort.TrAccountData;
import com.picsou.port.TradeRepublicPort.TrPosition;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TradeRepublicSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeRepublicSyncServiceTest {

    @Mock TradeRepublicPort trPort;
    @Mock TradeRepublicSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock OpenFigiIsinConverter isinConverter;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TradeRepublicSessionWriter sessionWriter;

    @InjectMocks TradeRepublicSyncService service;

    /**
     * When two ISINs resolve to the same ticker, the saved holding's averageBuyIn
     * must be the VWAP -- not whichever position HashMap iteration happens to yield first.
     *
     * Scenario: ISIN_A (qty=2, avg=10) and ISIN_B (qty=3, avg=20) both resolve to "RKLB".
     * Expected merged holding: quantity=5, averageBuyIn = (2*10 + 3*20)/5 = 16,
     * provider value = 2*100 + 3*110 = 530.
     */
    @Test
    void sync_mergesDuplicateTickersWithVwap() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");

        TrPosition pos1 = new TrPosition("IE00ISIN_A", bd("2"), bd("10"), bd("100"));
        TrPosition pos2 = new TrPosition("IE00ISIN_B", bd("3"), bd("20"), bd("110"));
        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("530"), List.of(pos1, pos2));
        when(trPort.fetchAccounts("plain-session")).thenReturn(List.of(accountData));

        when(isinConverter.resolve("IE00ISIN_A")).thenReturn(new TickerResult("RKLB", "Rocket Lab"));
        when(isinConverter.resolve("IE00ISIN_B")).thenReturn(new TickerResult("RKLB", "Rocket Lab"));

        when(accountRepository.findByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("530")));

        service.sync(memberId);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());

        AccountHolding saved = captor.getValue();
        assertThat(saved.getTicker()).isEqualTo("RKLB");
        assertThat(saved.getQuantity()).isEqualByComparingTo("5");
        // VWAP: (2*10 + 3*20) / 5 = 16  -- scale-8 representation 16.00000000
        assertThat(saved.getAverageBuyIn()).isEqualByComparingTo("16.00000000");
        assertThat(saved.getProviderValueEur()).isEqualByComparingTo("530");
    }

    @Test
    void sync_storesTheBrokerPositionValueInEur() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");

        TrPosition unpriceable = new TrPosition("IE000BI8OT95", bd("10"), bd("80"), bd("84"));
        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("840"), List.of(unpriceable));
        when(trPort.fetchAccounts("plain-session")).thenReturn(List.of(accountData));
        when(isinConverter.resolve("IE000BI8OT95"))
            .thenReturn(new TickerResult("MWRDF", "Amundi Core MSCI World"));

        when(accountRepository.findByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("840")));

        service.sync(memberId);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());

        AccountHolding saved = captor.getValue();
        assertThat(saved.getQuoteCurrency()).isEqualTo("EUR");
        assertThat(saved.getProviderValueEur()).isEqualByComparingTo("840"); // 10 × 84
    }

    @Test
    void sync_fallsBackToAverageBuyIn_whenTradeRepublicHasNoLivePrice() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");

        TrPosition noPrice = new TrPosition("IE000BI8OT95", bd("10"), bd("80"), bd("0"));
        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("800"), List.of(noPrice));
        when(trPort.fetchAccounts("plain-session")).thenReturn(List.of(accountData));
        when(isinConverter.resolve("IE000BI8OT95"))
            .thenReturn(new TickerResult("MWRDF", "Amundi Core MSCI World"));

        when(accountRepository.findByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("800")));

        service.sync(memberId);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());

        assertThat(captor.getValue().getProviderValueEur()).isEqualByComparingTo("800"); // 10 × 80
    }

    @Test
    void sync_deletesOldHoldingsWhenPortfolioReturnsEmpty() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");

        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("0"), List.of());
        when(trPort.fetchAccounts("plain-session")).thenReturn(List.of(accountData));

        Account existingAccount = Account.builder()
            .id(42L)
            .member(member)
            .name("TR Titres")
            .type(AccountType.COMPTE_TITRES)
            .provider("Trade Republic")
            .currency("EUR")
            .currentBalance(bd("1000"))
            .externalAccountId("tr_cto")
            .isManual(false)
            .build();
        when(accountRepository.findByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(Optional.of(existingAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("0")));

        service.sync(memberId);

        verify(holdingRepository).deleteByAccountId(42L);
        verify(holdingRepository).flush();
        verify(holdingRepository, never()).save(any(AccountHolding.class));
    }

    // --- Session lifecycle: refresh instead of dying at the 2h heuristic ---

    @Test
    void resync_attemptsRefreshWhenExpired() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .refreshToken("enc-refresh")
            .expiresAt(java.time.Instant.now().minusSeconds(3600)) // past the heuristic window
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");
        when(encryption.decrypt("enc-refresh")).thenReturn("plain-refresh");
        when(encryption.encrypt(any(String.class))).thenAnswer(inv -> "enc:" + inv.getArgument(0));

        when(trPort.fetchAccounts("plain-session"))
            .thenThrow(new com.picsou.exception.SyncException("SESSION_EXPIRED"));
        when(trPort.refreshSession("plain-refresh"))
            .thenReturn(new TradeRepublicPort.TrTokens("new-session", "new-refresh"));
        when(trPort.fetchAccounts("new-session")).thenReturn(List.of());

        service.resyncIfSessionActive(memberId);

        verify(trPort).refreshSession("plain-refresh");
        verify(trPort).fetchAccounts("new-session");
        // Through the REQUIRES_NEW writer, and never by mutating the managed entity: on the
        // manual path a failure on the retry rethrows out of the @Transactional class, and a
        // plain save of the rotated tokens was rolled back with it.
        verify(sessionWriter).storeRefreshedTokens(eq(memberId), eq("enc:new-session"), eq("enc:new-refresh"), any());
        verify(sessionWriter, never()).clear(any());
        verify(sessionRepository, never()).save(any(TradeRepublicSession.class));
        verify(sessionRepository, never()).delete(any(TradeRepublicSession.class));
    }

    @Test
    void refreshSucceedsButRetryFailsTransiently_keepsTheRotatedTokens() {
        // TR rotates the refresh token on refresh. If the retry then times out, the old token
        // must not come back: TR has already invalidated it, and the next sync would clear the
        // session for good over what was a transient failure.
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member).sessionToken("enc-session").refreshToken("enc-refresh")
            .expiresAt(java.time.Instant.now().minusSeconds(3600)).build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");
        when(encryption.decrypt("enc-refresh")).thenReturn("plain-refresh");
        when(encryption.encrypt(any(String.class))).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(trPort.fetchAccounts("plain-session"))
            .thenThrow(new com.picsou.exception.SyncException("SESSION_EXPIRED"));
        when(trPort.refreshSession("plain-refresh"))
            .thenReturn(new TradeRepublicPort.TrTokens("new-session", "new-refresh"));
        when(trPort.fetchAccounts("new-session"))
            .thenThrow(new com.picsou.exception.SyncException("Trade Republic WebSocket timed out"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sync(memberId))
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("timed out");

        verify(sessionWriter).storeRefreshedTokens(eq(memberId), eq("enc:new-session"), eq("enc:new-refresh"), any());
        verify(sessionWriter, never()).clear(any());
    }

    @Test
    void retryRejectedAfterRefresh_clearsTheSessionThroughTheWriter() {
        // The refresh went through but TR rejects the new session token outright: there is no
        // second refresh, and the session is cleared so the UI offers re-authentication.
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member).sessionToken("enc-session").refreshToken("enc-refresh")
            .expiresAt(java.time.Instant.now().minusSeconds(3600)).build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");
        when(encryption.decrypt("enc-refresh")).thenReturn("plain-refresh");
        when(encryption.encrypt(any(String.class))).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(trPort.fetchAccounts("plain-session"))
            .thenThrow(new com.picsou.exception.SyncException("SESSION_EXPIRED"));
        when(trPort.refreshSession("plain-refresh"))
            .thenReturn(new TradeRepublicPort.TrTokens("new-session", null));
        when(trPort.fetchAccounts("new-session"))
            .thenThrow(new com.picsou.exception.SyncException("SESSION_EXPIRED"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sync(memberId))
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("expired");

        verify(sessionWriter).storeRefreshedTokens(eq(memberId), eq("enc:new-session"), isNull(), any());
        verify(sessionWriter).clear(memberId);
        verify(sessionRepository, never()).delete(any(TradeRepublicSession.class));
    }

    @Test
    void refreshFailure_transient_keepsSession() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .refreshToken("enc-refresh")
            .expiresAt(java.time.Instant.now().minusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");
        when(encryption.decrypt("enc-refresh")).thenReturn("plain-refresh");

        when(trPort.fetchAccounts("plain-session"))
            .thenThrow(new com.picsou.exception.SyncException("SESSION_EXPIRED"));
        when(trPort.refreshSession("plain-refresh"))
            .thenThrow(new com.picsou.exception.SyncException(
                "Trade Republic authentication service is unavailable. Please make sure tr-auth is running on port 8001."));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sync(memberId))
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("unavailable");

        verify(sessionRepository, never()).delete(any(TradeRepublicSession.class));
        verify(sessionWriter, never()).clear(any());
    }

    @Test
    void refreshFailure_expired_clearsSession() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .refreshToken("enc-refresh")
            .expiresAt(java.time.Instant.now().minusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");
        when(encryption.decrypt("enc-refresh")).thenReturn("plain-refresh");

        when(trPort.fetchAccounts("plain-session"))
            .thenThrow(new com.picsou.exception.SyncException("SESSION_EXPIRED"));
        when(trPort.refreshSession("plain-refresh"))
            .thenThrow(new com.picsou.exception.SyncException("SESSION_EXPIRED"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sync(memberId))
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("reconnect");

        // Through the REQUIRES_NEW writer: sync() rethrows out of a @Transactional boundary,
        // so a plain delete here was rolled back and getSessionStatus kept saying active.
        verify(sessionWriter).clear(memberId);
        verify(sessionRepository, never()).delete(any(TradeRepublicSession.class));
    }

    // --- completeAuth: the background sync must only see a committed session ---

    @Test
    void completeAuth_handsTheBackgroundSyncOverOnlyAfterCommit() {
        // With a transaction open (the controller path), the tr-sync thread must not start
        // before the session row is committed: it opens its own transaction and a findById
        // that runs first sees no session, which the expiry path answers by deleting the one
        // just stored.
        Long memberId = 7L;
        arrangeCompleteAuth(memberId);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.completeAuth("proc", "123456", memberId);

            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            verify(txTemplate, never()).executeWithoutResult(any());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

            verify(txTemplate, timeout(2000)).executeWithoutResult(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void completeAuth_runsTheBackgroundSyncInline_whenNoTransactionIsActive() {
        Long memberId = 7L;
        arrangeCompleteAuth(memberId);

        TradeRepublicSyncService.SessionStatusResponse status = service.completeAuth("proc", "123456", memberId);

        assertThat(status.isActive()).isTrue();
        verify(txTemplate, timeout(2000)).executeWithoutResult(any());
    }

    private void arrangeCompleteAuth(Long memberId) {
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        when(trPort.completeAuth("proc", "123456"))
            .thenReturn(new TradeRepublicPort.TrTokens("session", "refresh"));
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.empty());
        when(encryption.encrypt(any(String.class))).thenAnswer(inv -> "enc:" + inv.getArgument(0));
    }

    // --- CSV import fallback ---

    @Test
    void importCsv_parsesRowsAndUpsertsWithAStableExternalId() {
        Long memberId = 42L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), eq(memberId))).thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(any(), eq(memberId)))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), BigDecimal.ZERO));

        List<com.picsou.dto.AccountResponse> result = service.importCsv(csv(
            "name,type,balance",
            "Livret A,savings,1234.56",
            "CTO Trade Republic,COMPTE_TITRES,5000.00"), memberId);

        assertThat(result).hasSize(2);
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        Account livret = captor.getAllValues().get(0);
        // The slug is what deduplicates a re-import: changing it doubles every balance.
        assertThat(livret.getExternalAccountId()).isEqualTo("tr_csv_livret_a");
        assertThat(livret.getName()).isEqualTo("Livret A");
        assertThat(livret.getType()).isEqualTo(AccountType.SAVINGS);
        assertThat(livret.getCurrentBalance()).isEqualByComparingTo("1234.56");
        assertThat(livret.getProvider()).isEqualTo("Trade Republic");
        assertThat(livret.isManual()).isFalse();
        assertThat(captor.getAllValues().get(1).getExternalAccountId()).isEqualTo("tr_csv_cto_trade_republic");
        verify(accountService).upsertSnapshot(eq(livret), eq(bd("1234.56")), any());
        // Balances only: a CSV import never touches holdings.
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void importCsv_skipsMalformedAndNonNumericRows() {
        Long memberId = 42L;
        arrangeCsvUpsert(memberId);

        List<com.picsou.dto.AccountResponse> result = service.importCsv(csv(
            "name,type,balance",
            "only two,fields",
            "Bad balance,PEA,twelve",
            "",
            "PEA TR,PEA,100"), memberId);

        assertThat(result).hasSize(1);
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getExternalAccountId()).isEqualTo("tr_csv_pea_tr");
    }

    @Test
    void importCsv_unknownType_fallsBackToOther() {
        Long memberId = 42L;
        arrangeCsvUpsert(memberId);

        service.importCsv(csv("name,type,balance", "Weird,PLAN_EPARGNE_MARS,10"), memberId);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(AccountType.OTHER);
    }

    @Test
    void importCsv_sameNameTwice_updatesTheOneAccount() {
        Long memberId = 42L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        java.util.Map<String, Account> store = new java.util.HashMap<>();
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), eq(memberId)))
            .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(any(), eq(memberId)))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            store.put(a.getExternalAccountId(), a);
            return a;
        });
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), BigDecimal.ZERO));

        service.importCsv(csv("name,type,balance", "Livret A,SAVINGS,100", "livret a,SAVINGS,250"), memberId);

        assertThat(store).hasSize(1);
        assertThat(store.get("tr_csv_livret_a").getCurrentBalance()).isEqualByComparingTo("250");
    }

    @Test
    void importCsv_ioError_wrapsInSyncExceptionWithTheCause_andWithoutItsMessage() throws java.io.IOException {
        MultipartFile broken = org.mockito.Mockito.mock(MultipartFile.class);
        java.io.IOException disk = new java.io.IOException("Stream closed: /tmp/upload_1234.tmp");
        when(broken.getInputStream()).thenThrow(disk);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.importCsv(broken, 42L))
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("Could not read the CSV file")
            // The 422 detail is user-facing: the internal message stays in the cause.
            .hasMessageNotContaining("upload_1234")
            .hasCause(disk);
    }

    @Test
    void importCsv_letsABusinessFailurePropagateUnchanged() {
        // A missing member (or a constraint violation) is not a parse error: relabelling it
        // as one turned a 404 into a 422 whose detail carried the internal message.
        Long memberId = 42L;
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), eq(memberId))).thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(any(), eq(memberId)))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.importCsv(csv("name,type,balance", "Livret A,SAVINGS,100"), memberId))
            .isInstanceOf(com.picsou.exception.ResourceNotFoundException.class);
    }

    private void arrangeCsvUpsert(Long memberId) {
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), eq(memberId))).thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(any(), eq(memberId)))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), BigDecimal.ZERO));
    }

    private static MultipartFile csv(String... lines) {
        return new MockMultipartFile("file", "tr.csv", "text/csv",
            String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void getSessionStatus_activeWhenRefreshTokenPresent() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession expiredWithRefresh = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .refreshToken("enc-refresh")
            .expiresAt(java.time.Instant.now().minusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(expiredWithRefresh));
        assertThat(service.getSessionStatus(memberId).isActive()).isTrue();

        TradeRepublicSession expiredNoRefresh = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().minusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(expiredNoRefresh));
        assertThat(service.getSessionStatus(memberId).isActive()).isFalse();
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
