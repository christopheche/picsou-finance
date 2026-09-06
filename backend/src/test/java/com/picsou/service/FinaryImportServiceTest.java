package com.picsou.service;

import com.picsou.dto.FinaryAccountMapping;
import com.picsou.dto.FinaryAccountPreview;
import com.picsou.dto.FinaryImportRequest;
import com.picsou.dto.FinaryImportResultResponse;
import com.picsou.dto.FinaryMappingAction;
import com.picsou.dto.FinaryPreviewResponse;
import com.picsou.dto.NewAccountDetails;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the real Apache POI code path on an in-memory workbook, so a POI
 * upgrade (the pin exists for CVE-2025-31672) that changed workbook detection
 * or cell reading would surface here rather than on a user's first import.
 */
@ExtendWith(MockitoExtension.class)
class FinaryImportServiceTest {

    private static final long MEMBER_ID = 7L;

    @Mock private AccountRepository accountRepository;
    @Mock private BalanceSnapshotRepository balanceSnapshotRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private FamilyMemberRepository familyMemberRepository;
    @Mock private FinaryPersistenceHelper persistenceHelper;

    private FinaryImportService service;

    @BeforeEach
    void setUp() {
        service = new FinaryImportService(
            accountRepository, balanceSnapshotRepository, transactionRepository,
            familyMemberRepository, persistenceHelper);
    }

    @Test
    void preview_parsesAccountSheetsAndTransactionsFromXlsx() throws IOException {
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of());

        byte[] xlsx = workbook(wb -> {
            Sheet checkings = wb.createSheet("Checkings");
            header(checkings, "Name", "Institution", "Balance", "Currency");
            Row row = checkings.createRow(1);
            row.createCell(0).setCellValue("Compte courant");
            row.createCell(1).setCellValue("BoursoBank");
            row.createCell(2).setCellValue(1234.5);
            row.createCell(3).setCellValue("EUR");

            Sheet tx = wb.createSheet("Transactions");
            header(tx, "Category", "Date", "Name", "Amount", "Type", "Account", "Institution", "Currency");
            Row t1 = tx.createRow(1);
            t1.createCell(0).setCellValue("Checkings");
            t1.createCell(1).setCellValue("2026-01-15");
            t1.createCell(2).setCellValue("Salaire");
            t1.createCell(3).setCellValue(2500.0);
            t1.createCell(4).setCellValue("income");
            t1.createCell(5).setCellValue("Compte courant");
            t1.createCell(6).setCellValue("BoursoBank");
            t1.createCell(7).setCellValue("EUR");
            Row t2 = tx.createRow(2);
            t2.createCell(0).setCellValue("Checkings");
            t2.createCell(1).setCellValue("not-a-date");
            t2.createCell(2).setCellValue("Skipped");
            t2.createCell(3).setCellValue(1.0);
            t2.createCell(5).setCellValue("Compte courant");
        });

        FinaryPreviewResponse preview = service.preview(
            new MockMultipartFile("file", "finary.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx),
            MEMBER_ID);

        assertThat(preview.accounts()).hasSize(1);
        FinaryAccountPreview account = preview.accounts().get(0);
        assertThat(account.finaryName()).isEqualTo("Compte courant");
        assertThat(account.finaryInstitution()).isEqualTo("BoursoBank");
        assertThat(account.finaryCategory()).isEqualTo("Checkings");
        assertThat(account.currentBalance()).isEqualTo(1234.5);
        assertThat(account.nativeCurrency()).isEqualTo("EUR");
        assertThat(account.transactionCount()).isEqualTo(1);
        assertThat(preview.totalTransactionCount()).isEqualTo(1);
        assertThat(preview.fileToken()).isNotBlank();
        assertThat(preview.existingPicsouAccounts()).isEmpty();
    }

    @Test
    void preview_rejectsNonSpreadsheetPayloadAsInvalidExcelFile() {
        MockMultipartFile notXlsx = new MockMultipartFile("file", "finary.xlsx",
            "text/plain", "name;balance\nfoo;12".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.preview(notXlsx, MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("valid Excel spreadsheet");
    }

    // ---------------------------------------------------------------------------
    // executeImport
    // ---------------------------------------------------------------------------

    /** One "Livret A" savings account at 5 250 EUR with a single +250 deposit. */
    private byte[] livretWorkbook() throws IOException {
        return workbook(wb -> {
            Sheet savings = wb.createSheet("Savings");
            header(savings, "Name", "Institution", "Balance", "Currency");
            Row row = savings.createRow(1);
            row.createCell(0).setCellValue("Livret A");
            row.createCell(1).setCellValue("BoursoBank");
            row.createCell(2).setCellValue(5250.0);
            row.createCell(3).setCellValue("EUR");

            Sheet tx = wb.createSheet("Transactions");
            header(tx, "Category", "Date", "Name", "Amount", "Type", "Account", "Institution", "Currency");
            Row t1 = tx.createRow(1);
            t1.createCell(0).setCellValue("Savings");
            t1.createCell(1).setCellValue("2026-03-01");
            t1.createCell(2).setCellValue("Versement");
            t1.createCell(3).setCellValue(250.0);
            t1.createCell(4).setCellValue("deposit");
            t1.createCell(5).setCellValue("Livret A");
            t1.createCell(6).setCellValue("BoursoBank");
            t1.createCell(7).setCellValue("EUR");
        });
    }

    private String previewLivret() throws IOException {
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of());
        return service.preview(new MockMultipartFile("file", "finary.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", livretWorkbook()), MEMBER_ID)
            .fileToken();
    }

    private static FinaryAccountMapping mapExisting(Long targetAccountId) {
        return new FinaryAccountMapping("savings_livret_a", "Livret A", "Savings",
            FinaryMappingAction.MAP_EXISTING, targetAccountId, null);
    }

    @Test
    void executeImport_mapExisting_updatesBalanceAndBindsFinaryId_onMemberScopedAccount() throws IOException {
        String token = previewLivret();
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(FamilyMember.builder().id(MEMBER_ID).build()));
        Account livret = Account.builder().id(9L).name("Livret A").type(AccountType.SAVINGS).currency("EUR")
            .currentBalance(new java.math.BigDecimal("5000")).isManual(true).build();
        when(accountRepository.findByIdAndMemberId(9L, MEMBER_ID)).thenReturn(Optional.of(livret));
        when(persistenceHelper.reconstructSnapshots(any(), any(), any())).thenReturn(3);
        when(persistenceHelper.importTransactions(any(), any(), any())).thenReturn(1);

        FinaryImportResultResponse result = service.executeImport(
            new FinaryImportRequest(List.of(mapExisting(9L)), token), MEMBER_ID);

        assertThat(result.accountsMapped()).isEqualTo(1);
        assertThat(result.accountsCreated()).isZero();
        assertThat(result.snapshotsCreated()).isEqualTo(3);
        assertThat(result.transactionsImported()).isEqualTo(1);
        // The account takes the Finary figure and is bound to the Finary id (doc: "update balance,
        // set externalAccountId"), so the result and today's snapshot agree.
        assertThat(livret.getCurrentBalance()).isEqualByComparingTo("5250");
        assertThat(livret.getExternalAccountId()).isEqualTo("finary_Savings_livret_a");
        assertThat(livret.getLastSyncedAt()).isNotNull();
        assertThat(result.importedAccounts().get(0).currentBalance()).isEqualTo(5250.0);
        verify(accountRepository).save(livret);
        verify(persistenceHelper).reconstructSnapshots(any(), any(), any());
    }

    @Test
    void executeImport_mapExisting_foreignAccount_throwsAndWritesNothing() throws IOException {
        String token = previewLivret();
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(FamilyMember.builder().id(MEMBER_ID).build()));
        when(accountRepository.findByIdAndMemberId(9L, MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.executeImport(new FinaryImportRequest(List.of(mapExisting(9L)), token), MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class);

        verify(persistenceHelper, never()).reconstructSnapshots(any(), any(), any());
        verify(persistenceHelper, never()).importTransactions(any(), any(), any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void executeImport_mapExisting_providerSyncedAccount_isRejected() throws IOException {
        String token = previewLivret();
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(FamilyMember.builder().id(MEMBER_ID).build()));
        Account trPea = Account.builder().id(9L).name("TR PEA").type(AccountType.PEA).currency("EUR")
            .isManual(false).externalAccountId("tr_pea").build();
        when(accountRepository.findByIdAndMemberId(9L, MEMBER_ID)).thenReturn(Optional.of(trPea));

        // Mapping would rebind the external id (orphaning the TR connector), delete its
        // transactions and wipe its snapshot history -- refused before anything is written.
        assertThatThrownBy(() -> service.executeImport(new FinaryImportRequest(List.of(mapExisting(9L)), token), MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("manual");

        assertThat(trPea.getExternalAccountId()).isEqualTo("tr_pea");
        verify(persistenceHelper, never()).reconstructSnapshots(any(), any(), any());
        verify(persistenceHelper, never()).importTransactions(any(), any(), any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void executeImport_createNew_setsMemberBalanceAndExternalIdFromFile() throws IOException {
        String token = previewLivret();
        FamilyMember member = FamilyMember.builder().id(MEMBER_ID).build();
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        FinaryAccountMapping createNew = new FinaryAccountMapping("savings_livret_a", "Livret A", "Savings",
            FinaryMappingAction.CREATE_NEW, null,
            new NewAccountDetails("Livret A", AccountType.SAVINGS, "BoursoBank", "EUR", null));

        FinaryImportResultResponse result = service.executeImport(new FinaryImportRequest(List.of(createNew), token), MEMBER_ID);

        assertThat(result.accountsCreated()).isEqualTo(1);
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        Account saved = captor.getValue();
        assertThat(saved.getMember()).isSameAs(member);
        assertThat(saved.getCurrentBalance()).isEqualByComparingTo("5250");
        assertThat(saved.getExternalAccountId()).isEqualTo("finary_Savings_livret_a");
        assertThat(saved.isManual()).isTrue();
        assertThat(saved.getColor()).isEqualTo(FinaryPersistenceHelper.defaultColorForType(AccountType.SAVINGS));
    }

    @Test
    void executeImport_skip_countsSkippedAndWritesNothing() throws IOException {
        String token = previewLivret();
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(FamilyMember.builder().id(MEMBER_ID).build()));
        FinaryAccountMapping skip = new FinaryAccountMapping("savings_livret_a", "Livret A", "Savings",
            FinaryMappingAction.SKIP, null, null);

        FinaryImportResultResponse result = service.executeImport(new FinaryImportRequest(List.of(skip), token), MEMBER_ID);

        assertThat(result.accountsSkipped()).isEqualTo(1);
        assertThat(result.importedAccounts()).isEmpty();
        verify(accountRepository, never()).save(any());
        verify(persistenceHelper, never()).reconstructSnapshots(any(), any(), any());
    }

    @Test
    void executeImport_expiredToken_throws() {
        assertThatThrownBy(() -> service.executeImport(new FinaryImportRequest(List.of(), "nope"), MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expired");
    }

    @Test
    void executeImport_tokenPreviewedByAnotherMember_throws() throws IOException {
        String token = previewLivret(); // previewed by MEMBER_ID

        assertThatThrownBy(() -> service.executeImport(new FinaryImportRequest(List.of(), token), 99L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not belong");
        verify(familyMemberRepository, never()).findById(any());
    }

    @Test
    void executeImport_removesTokenAfterUse() throws IOException {
        String token = previewLivret();
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(FamilyMember.builder().id(MEMBER_ID).build()));

        service.executeImport(new FinaryImportRequest(List.of(), token), MEMBER_ID);

        assertThatThrownBy(() -> service.executeImport(new FinaryImportRequest(List.of(), token), MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expired");
    }

    @Test
    void preview_offersOnlyMappableAccountsAsTargets() throws IOException {
        Account manual = Account.builder().id(1L).name("Livret").type(AccountType.SAVINGS).currency("EUR").isManual(true).build();
        Account trPea = Account.builder().id(2L).name("TR PEA").type(AccountType.PEA).currency("EUR")
            .isManual(false).externalAccountId("tr_pea").build();
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of(manual, trPea));

        FinaryPreviewResponse preview = service.preview(new MockMultipartFile("file", "finary.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", livretWorkbook()), MEMBER_ID);

        assertThat(preview.existingPicsouAccounts()).extracting(a -> a.id()).containsExactly(1L);
    }

    private static void header(Sheet sheet, String... titles) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < titles.length; i++) {
            row.createCell(i).setCellValue(titles[i]);
        }
    }

    private static byte[] workbook(java.util.function.Consumer<XSSFWorkbook> fill) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fill.accept(wb);
            wb.write(out);
            return out.toByteArray();
        }
    }
}
