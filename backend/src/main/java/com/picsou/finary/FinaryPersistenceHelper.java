package com.picsou.finary;

import com.picsou.model.*;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Shared persistence logic for Finary imports (both xlsx and API).
 * Handles account snapshots and transaction imports.
 */
@Component
public class FinaryPersistenceHelper {

    private static final Logger log = LoggerFactory.getLogger(FinaryPersistenceHelper.class);

    private final BalanceSnapshotRepository balanceSnapshotRepository;
    private final TransactionRepository transactionRepository;

    public FinaryPersistenceHelper(BalanceSnapshotRepository balanceSnapshotRepository,
                                   TransactionRepository transactionRepository) {
        this.balanceSnapshotRepository = balanceSnapshotRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Record types for parsed Finary data (used by both xlsx and API import paths)
     */
    public record ParsedFinaryAccount(
        String name,
        String institution,
        String category,
        BigDecimal balance,
        String currency
    ) {}

    public record ParsedFinaryTransaction(
        String accountName,
        LocalDate date,
        String description,
        BigDecimal amount,
        String type,
        String category,
        String currency
    ) {}

    /**
     * External id prefix shared by both Finary paths ({@code finary_<category>_<id>}).
     */
    public static final String EXTERNAL_ID_PREFIX = "finary_";

    /**
     * Whether a Picsou account may be the target of a {@code MAP_EXISTING} mapping.
     *
     * <p>Mapping rebinds {@code externalAccountId}, replaces the non-manual transactions and
     * rebuilds the whole snapshot history from Finary flows. On a provider-synced account
     * (Trade Republic, IBKR, Enable Banking...) that would orphan the connector's account --
     * it recreates a duplicate on its next sync -- and erase the daily valuation history, so
     * only manual accounts that are unbound or already bound to Finary qualify.
     */
    public static boolean isMappable(Account account) {
        return account.isManual()
            && (account.getExternalAccountId() == null
                || account.getExternalAccountId().startsWith(EXTERNAL_ID_PREFIX));
    }

    /**
     * Reconstruct balance snapshots by walking backwards from current balance
     */
    public int reconstructSnapshots(Account account, ParsedFinaryAccount finaryAcc,
                                    List<ParsedFinaryTransaction> allTransactions) {
        // Filter transactions for this account
        List<ParsedFinaryTransaction> accountTx = allTransactions.stream()
            .filter(tx -> tx.accountName().equalsIgnoreCase(finaryAcc.name()))
            .sorted(Comparator.comparing(ParsedFinaryTransaction::date).reversed())
            .toList();

        if (accountTx.isEmpty()) {
            // No transactions; just create a snapshot for today
            LocalDate today = LocalDate.now();
            balanceSnapshotRepository.findByAccountIdAndDate(account.getId(), today)
                .ifPresent(balanceSnapshotRepository::delete);
            balanceSnapshotRepository.flush();
            balanceSnapshotRepository.save(BalanceSnapshot.builder()
                .account(account)
                .date(today)
                .balance(finaryAcc.balance())
                .investedAmount(finaryAcc.balance())
                .build());
            return 1;
        }

        // Delete existing snapshots for this account (batch delete executes immediately)
        List<BalanceSnapshot> existing = balanceSnapshotRepository.findRecentByAccountId(account.getId(), LocalDate.of(2000, 1, 1));
        balanceSnapshotRepository.deleteAllInBatch(existing);

        int count = 0;
        BigDecimal runningBalance = finaryAcc.balance();

        // Anchor point: today with current balance
        LocalDate today = LocalDate.now();
        balanceSnapshotRepository.save(BalanceSnapshot.builder()
            .account(account)
            .date(today)
            .balance(runningBalance)
            .investedAmount(runningBalance)
            .build());
        count++;

        // Walk backwards through transactions (date-desc, so a day's rows are contiguous).
        // A snapshot dated D is the end-of-day balance, like the scheduler's daily row: record
        // it before subtracting D's own flows, then carry the pre-history balance to the day
        // before the earliest transaction so the first flow still reads as a step.
        Map<LocalDate, BigDecimal> snapshots = new LinkedHashMap<>();
        LocalDate earliest = null;
        for (ParsedFinaryTransaction tx : accountTx) {
            snapshots.putIfAbsent(tx.date(), runningBalance);
            runningBalance = runningBalance.subtract(tx.amount());
            earliest = tx.date();
        }
        snapshots.putIfAbsent(earliest.minusDays(1), runningBalance);

        // Remove today to avoid duplicate with the anchor point above
        snapshots.remove(today);

        // Save snapshots
        for (Map.Entry<LocalDate, BigDecimal> entry : snapshots.entrySet()) {
            balanceSnapshotRepository.save(BalanceSnapshot.builder()
                .account(account)
                .date(entry.getKey())
                .balance(entry.getValue())
                .investedAmount(entry.getValue())
                .build());
            count++;
        }

        return count;
    }

    /**
     * Import transactions for an account
     */
    public int importTransactions(Account account, ParsedFinaryAccount finaryAcc,
                                  List<ParsedFinaryTransaction> allTransactions) {
        // Delete existing transactions for this account
        transactionRepository.deleteByAccountIdAndIsManualFalse(account.getId());

        List<ParsedFinaryTransaction> accountTx = allTransactions.stream()
            .filter(tx -> tx.accountName().equalsIgnoreCase(finaryAcc.name()))
            .toList();

        if (accountTx.isEmpty()) {
            return 0;
        }

        List<Transaction> toInsert = accountTx.stream()
            .map(tx -> Transaction.builder()
                .account(account)
                .date(tx.date())
                .description(tx.description())
                .amount(tx.amount())
                .type(tx.type())
                .category(tx.category())
                .nativeCurrency(tx.currency())
                .build())
            .collect(Collectors.toList());

        transactionRepository.saveAll(toInsert);
        return toInsert.size();
    }

    /**
     * Reconstruct balance snapshots from already-persisted Transaction entities.
     * Same algorithm as reconstructSnapshots() but reads from DB instead of parsed DTOs.
     */
    public int reconstructSnapshotsFromDb(Account account) {
        List<Transaction> accountTx = transactionRepository.findByAccountIdOrderByDateDesc(account.getId());
        BigDecimal currentBalance = account.getCurrentBalance();

        if (accountTx.isEmpty()) {
            LocalDate today = LocalDate.now();
            balanceSnapshotRepository.findByAccountIdAndDate(account.getId(), today)
                .ifPresent(balanceSnapshotRepository::delete);
            balanceSnapshotRepository.flush();
            balanceSnapshotRepository.save(BalanceSnapshot.builder()
                .account(account)
                .date(today)
                .balance(currentBalance)
                .investedAmount(currentBalance)
                .build());
            return 1;
        }

        // Delete existing snapshots for this account (batch delete executes immediately)
        List<BalanceSnapshot> existing = balanceSnapshotRepository.findRecentByAccountId(account.getId(), LocalDate.of(2000, 1, 1));
        balanceSnapshotRepository.deleteAllInBatch(existing);

        int count = 0;
        BigDecimal runningBalance = currentBalance;

        // Anchor: today with current balance
        LocalDate today = LocalDate.now();
        balanceSnapshotRepository.save(BalanceSnapshot.builder()
            .account(account)
            .date(today)
            .balance(runningBalance)
            .investedAmount(runningBalance)
            .build());
        count++;

        // Walk backwards through transactions -- same end-of-day rule as reconstructSnapshots()
        Map<LocalDate, BigDecimal> snapshots = new LinkedHashMap<>();
        LocalDate earliest = null;
        for (Transaction tx : accountTx) {
            snapshots.putIfAbsent(tx.getDate(), runningBalance);
            runningBalance = runningBalance.subtract(tx.getAmount());
            earliest = tx.getDate();
        }
        snapshots.putIfAbsent(earliest.minusDays(1), runningBalance);

        // Remove today to avoid duplicate with anchor
        snapshots.remove(today);

        for (Map.Entry<LocalDate, BigDecimal> entry : snapshots.entrySet()) {
            balanceSnapshotRepository.save(BalanceSnapshot.builder()
                .account(account)
                .date(entry.getKey())
                .balance(entry.getValue())
                .investedAmount(entry.getValue())
                .build());
            count++;
        }

        return count;
    }

    /**
     * Suggest AccountType based on Finary category.
     * This method handles display names from xlsx import ("Checkings", "Savings", etc).
     */
    public static AccountType suggestTypeFromDisplayCategory(String finaryCategory) {
        return switch (finaryCategory) {
            case "Checkings" -> AccountType.CHECKING;
            case "Savings" -> AccountType.SAVINGS;
            case "Investments" -> AccountType.COMPTE_TITRES;
            case "Cryptos" -> AccountType.CRYPTO;
            case "Credits" -> AccountType.OTHER;
            default -> AccountType.OTHER;
        };
    }

    /**
     * Suggest AccountType based on Finary API category (snake_case URL segments).
     * Used by API sync path.
     */
    public static AccountType suggestTypeFromApiCategory(String apiCategory) {
        return switch (apiCategory) {
            case "checkings" -> AccountType.CHECKING;
            case "savings" -> AccountType.SAVINGS;
            case "investments" -> AccountType.COMPTE_TITRES;
            case "cryptos" -> AccountType.CRYPTO;
            case "fonds_euro" -> AccountType.SAVINGS;
            case "loans" -> AccountType.LOAN;
            case "credits" -> AccountType.OTHER;
            case "real_estates", "commodities", "other_assets", "startups" -> AccountType.OTHER;
            default -> AccountType.OTHER;
        };
    }

    /**
     * Default color for account type
     */
    public static String defaultColorForType(AccountType type) {
        return switch (type) {
            case PEA -> "#10b981";
            case COMPTE_TITRES -> "#3b82f6";
            case CRYPTO -> "#f59e0b";
            case SAVINGS -> "#8b5cf6";
            default -> "#6366f1";
        };
    }
}
