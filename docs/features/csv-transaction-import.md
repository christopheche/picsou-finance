# Feature: CSV transaction import (investment accounts)

> Last updated: 2026-09-06

## Context

Users want to seed a PEA/CTO (or crypto) account with their broker's trade history without
re-keying every line ([issue #38](https://github.com/Zoeille/picsou-finance/issues/38)). This
adds a two-phase CSV importer for **manual investment accounts**. It accepts any broker layout
because the user maps the columns. It writes manual BUY/SELL transactions and folds per-trade fees
into the cost basis.

## How it works

Two phases, modelled on the Finary XLSX importer but mapping **columns** into an
**already-selected account** (not mapping accounts):

1. **Preview** — the raw file is uploaded, the dialect (delimiter / decimal / date format) is
   sniffed, a best-guess column mapping is built from the header names, and the raw file is cached
   under a `fileToken` **bound to the target account** (30-min TTL). The response returns the
   detected columns, a sample of rows, and the guesses — all overridable in the wizard.
2. **Execute** — the client echoes the `fileToken` plus the (possibly user-adjusted) mapping and
   dialect. The raw file is **re-parsed** with the confirmed dialect, each row is mapped to a
   manual BUY/SELL transaction, valid rows are bulk-inserted (`is_manual = true`), and holdings are
   recomputed **once**. Invalid rows are reported per-row rather than failing the whole file.

### Key files

- `backend/src/main/java/com/picsou/imports/csv/` — `CsvReader` (RFC-4180, configurable delimiter),
  `CsvDialectDetector` (delimiter/decimal/date sniffing), `CsvValueParser` (`BigDecimal`/`LocalDate`
  parsing), `CsvDialect` + `DecimalStyle`. Dependency-free; symmetric with the GDPR `CsvWriter`.
- `backend/src/main/java/com/picsou/imports/TransactionRowMapper.java` — one row → unsaved
  `Transaction`; reuses `InstrumentFieldResolver` (ISIN→ticker) and `TransactionAmountCalculator`
  (signed amount incl. fees).
- `backend/src/main/java/com/picsou/service/TransactionImportService.java` — preview/execute + the
  `fileToken` cache and its `@Scheduled` TTL sweep.
- `backend/src/main/java/com/picsou/controller/TransactionImportController.java` —
  `POST /api/accounts/{id}/transactions/import/preview` (multipart) and
  `POST /api/accounts/{id}/transactions/import` (JSON, `201`). Member-scoped + `syncBuckets` throttle.
- `frontend/src/components/shared/ImportTransactionsModal.tsx` — the 3-step wizard.
- `frontend/src/features/accounts/{api,hooks}.ts` — `importPreview` / `importExecute` + hooks.

### Flow

```
upload CSV ─► preview() ─► detect dialect + guess mapping ─► cache raw file @token(account)
                                                                     │
user adjusts mapping/dialect ◄───────────────────────────────────────┘
        │
        ▼
execute(token, mapping, dialect) ─► re-parse ─► map rows ─► saveAll(is_manual) ─► recomputeHoldings()
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Hand-rolled `CsvReader` (no lib) | Matches the existing hand-rolled `CsvWriter`; the hard part (delimiter/decimal/date sniffing) isn't solved by a lib anyway | opencsv / Commons-CSV |
| Column-mapping wizard | Works for any broker export without a per-broker parser | Fixed template · per-broker native parsers |
| Cache the **raw file**, re-parse on execute | The user can change the delimiter after preview; caching parsed rows would be stale | Cache parsed rows |
| Token bound to the account | A preview cannot be replayed against another account | Bare token |
| Tolerant per-row errors | One bad line shouldn't sink a multi-year import | All-or-nothing transaction |

## Gotchas / Pitfalls

- **French broker exports** commonly use `;` delimiter and `,` decimals (`1 234,56`). The detector
  handles both; the user can override delimiter, decimal style, and date format in the wizard.
- **ISINs must be resolved to tickers at import time** (`InstrumentFieldResolver`) or Yahoo pricing
  never populates and an ISIN row won't merge with the equivalent ticker row.
- **Amount signing lives in one place** (`TransactionAmountCalculator`, mirrored in the TS modal):
  BUY `= −(qty·price + fees)`, SELL `= +(qty·price − fees)`. Fees also fold into the PMP — see
  [manual-transactions.md](manual-transactions.md).
- The importer only accepts **manual investment** accounts (PEA, COMPTE_TITRES, and CRYPTO). Synced
  investment accounts are rejected before preview data is cached or transactions are saved, so a
  CSV cannot replace provider-owned positions.
- Multipart limit was raised to **10 MB** (`application.yml`) for multi-year histories; the endpoint
  is member-scoped and throttled. Both nginx configs (`docker/nginx.conf`, `frontend/nginx.conf`)
  set `client_max_body_size 10m` on `/api` to match — nginx's 1 MB default would otherwise answer
  an HTML 413 before the upload reaches the backend.
- **Re-importing is idempotent per trade**: `executeImport` loads the account's manual trades once and
  skips any row whose (date, side, ticker, quantity, unit price, fees) already exists, reporting it
  as a per-row error ("Already imported -- ...") so it shows in the wizard's skipped list. Matching is a
  multiset: two identical fills inside one file are both kept, and a re-import of that file skips both.
  Without this, importing an updated broker export that overlaps the previous one duplicated every
  trade and `HoldingComputeService` doubled the position and cost basis.
- **`sideValueMap` targets are validated once, before any row is parsed**: only `BUY`/`SELL`
  (case-insensitive) are accepted; anything else (a localised typo such as `ACHAT`, or `DIVIDEND`) is a
  400 for the whole request. The mapper enforces the same rule per row, with a user-safe message
  rather than `Enum.valueOf`'s class-name error. Non-trade types would otherwise be saved with a
  quantity that never reaches the position, since holdings only read BUY/SELL.
- Demo mode returns `{}` for unhandled endpoints — UI consumers must guard accordingly.

## Tests

- `CsvReaderTest`, `CsvDialectDetectorTest`, `CsvValueParserTest` — parsing / sniffing.
- `TransactionRowMapperTest` — sign+fees, ISIN resolution, amount-derived price, bad rows,
  `sideValueMap` target outside BUY/SELL.
- `TransactionImportServiceTest` — happy path, expired token, **token↔account binding**,
  non-investment or synced investment account rejection (400), foreign account (404), per-row
  error reporting, invalid `sideValueMap` rejected up front, **duplicate rows skipped on re-import**
  while identical rows within one file are kept.
- `ImportTransactionsModal.test.tsx` — preview → mapping → import request → result.

## Links

- Sibling importer: [finary-import.md](finary-import.md) · [trade-republic.md](trade-republic.md)
- [manual-transactions.md](manual-transactions.md) · [ISIN_TO_TICKER_CONVERSION.md](ISIN_TO_TICKER_CONVERSION.md)
- Ticket: [issue #38](https://github.com/Zoeille/picsou-finance/issues/38)
