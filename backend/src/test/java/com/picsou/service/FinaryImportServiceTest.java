package com.picsou.service;

import com.picsou.dto.FinaryAccountPreview;
import com.picsou.dto.FinaryPreviewResponse;
import com.picsou.finary.FinaryPersistenceHelper;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
