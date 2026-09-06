# Fix: Trade Republic Holding Deduplication

> Last updated: 2026-09-06

## Problem

When syncing Trade Republic accounts, a `DataIntegrityViolationException` was thrown with the error:
```
duplicate key value violates unique constraint "account_holding_account_id_ticker_key"
```

This occurred because multiple ISIN codes (securities identifiers) could convert to the same Yahoo Finance ticker symbol via the `OpenFigiIsinConverter`. When syncing positions, the code would attempt to insert multiple `AccountHolding` records with the same `(account_id, ticker)` combination, violating the database's unique constraint.

### Example scenario
- Trade Republic account has two positions:
  - ISIN: `US0378691033` (Apple Inc. - US listing)
  - ISIN: `IE00B4L5Y983` (Apple Inc. - ISIN for European fund)
- Both convert to ticker: `AAPL`
- Sync tries to insert two holdings with `(account_id=57, ticker=AAPL)`
- Constraint violation occurs

## Solution

Modified `TradeRepublicSyncService.upsertAccount()` to deduplicate holdings by ticker before persisting (IBKR and DEGIRO reuse the same helper; the provider-valued brokers — BoursoBank, Bourse Direct, Amundi — merge with their own `mergePositions`, see below):

1. **Collect and deduplicate**: Loop through positions, converting each ISIN to a ticker
2. **Aggregate via VWAP**: When multiple positions map to the same ticker, combine quantities AND compute a quantity-weighted average buy-in
3. **Save deduplicated holdings**: Insert only one holding per ticker with the aggregated quantity and weighted average

### Implementation

- Shared helper `com.picsou.service.HoldingDedup` exposes the `HoldingAgg` record and a static `vwapMerge(prev, next)` method
- Trade Republic, IBKR and DEGIRO use `Map.merge(..., HoldingDedup::vwapMerge)` so the three ISIN-converting brokers share a single canonical merge formula
- BoursoBank, Bourse Direct and Amundi do **not** use `HoldingDedup`: their lines carry a provider valuation (`providerValueEur` / `providerPnlEur`) that `HoldingAgg` has no field for, and dropping it on merge is exactly what makes an unpriceable holding read as 0 EUR downstream. Each of them has a private `mergePositions(left, right)` that sums quantities and provider value/PnL, weights buy-in and current price by quantity, and refuses to merge lines whose quote currencies differ (BoursoBank, Bourse Direct) or whose labels differ (Amundi — an ISIN-less fallback ticker colliding across two funds). Their `PreparedPosition` records are the merge unit, not `HoldingAgg`.
- Positions are deduplicated **in-memory before database writes**, avoiding constraint violations
- VWAP formula: `weightedAvg = (q1·a1 + q2·a2) / (q1 + q2)` at scale 8, `RoundingMode.HALF_UP` (matches `HoldingComputeService`); the provider-valued services use the same formula but return `null` instead of treating a missing side as zero

### Key files

- `backend/src/main/java/com/picsou/service/HoldingDedup.java` — shared VWAP merge helper
- `backend/src/main/java/com/picsou/service/TradeRepublicSyncService.java` — `upsertAccount()` dedup loop ("Deduplicate by ticker" block) over `HoldingDedup::vwapMerge`
- `backend/src/main/java/com/picsou/service/IbkrSyncService.java`, `DegiroSyncService.java` — same helper, same loop shape
- `backend/src/main/java/com/picsou/service/BoursoSyncService.java`, `BourseDirectSyncService.java`, `AmundiSyncService.java` — `preparePositions()` merges through the service's own `mergePositions()` (provider valuation preserved)

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Deduplicate in-memory before saving | Avoids constraint violations and keeps the database clean | Update existing holdings (more complex, slower) |
| Use `Map.merge()` for aggregation | Concise, handles both first occurrence and merges in one pass | Manual `if-put-get` logic (more verbose) |
| VWAP-weighted average buy-in on duplicates | Mathematically correct cost-basis; preserves the gain/loss invariant `pnl = value − cost` | "Keep first averageBuyIn" — non-deterministic (depends on HashMap iteration order) and produces wrong gain/loss percentages |
| Shared `HoldingDedup` helper for TR, IBKR & DEGIRO | One canonical formula = one place to audit/test; prevents drift between the brokers whose lines are priced by Yahoo only | Per-service private lambdas (regressed twice already) |
| Private `mergePositions` in BoursoBank / Bourse Direct / Amundi | `HoldingAgg` carries no provider valuation; a merge through `vwapMerge` would silently drop `providerValueEur` / `providerPnlEur` and zero the line once Yahoo cannot price it | Extending `HoldingAgg` with provider fields (would make TR/IBKR/DEGIRO carry nullable columns they never fill) or reusing `vwapMerge` (regressed to 0 EUR holdings — see `bourso-bank.md`) |

## Gotchas / Pitfalls

- **ISIN → Ticker conversion is not 1:1**: Multiple ISINs can map to the same ticker (e.g., different listings of the same security)
- **`HashMap` iteration order is not deterministic**: A merge lambda that picks `prev` (or `next`) for a field implicitly depends on insertion-order hashing, which can flip between syncs and JVMs. The VWAP merge is symmetric and therefore order-independent — see `HoldingDedupTest#vwapMerge_isOrderIndependent`.
- **Null averages treated as zero**: When one of the merged aggregates has a null `averageBuyIn`, the VWAP uses zero for that side. Acceptable because callers populate it from the provider's reported buy-in; null typically means "unknown / cash-equivalent".
- **Edge case**: WebSocket sync treats positions as authoritative. If TR returns an
  empty position list for a portfolio, existing holdings for that account are deleted
  so a full sale is reflected immediately. CSV imports still preserve holdings because
  they contain balances only, not position details.

## Tests

- `HoldingDedupTest` — VWAP math, null handling, order independence, zero-quantity guard, name/currentPrice fallback
- `TradeRepublicSyncServiceTest#sync_mergesDuplicateTickersWithVwap` — integration wiring: two distinct ISINs → same ticker → saved `AccountHolding.averageBuyIn` is the VWAP, not whichever position appeared first
- `TradeRepublicSyncServiceTest#sync_deletesOldHoldingsWhenPortfolioReturnsEmpty` — empty authoritative TR portfolio clears stale holdings
- `BoursoSyncServiceTest#queueSync_mergesLinesThatResolveToTheSameTicker`, `BourseDirectSyncServiceTest#duplicatePositions_areMergedWithWeightedPrices` / `#duplicatePositions_doNotInventMissingPrices`, `AmundiSyncServiceTest#theSameFundListedTwiceIsMerged` / `#twoDifferentFundsCollidingOnTheFallbackTickerAreRefusedNotFused` — the provider-valued merge path: quantities and provider value summed, prices weighted, `null` kept when one side is missing, collisions refused
- No regression in existing sync flow when the backend suite is run.

## Related

- `OpenFigiIsinConverter` — responsible for ISIN → Yahoo ticker conversion
- `AccountHolding` — database entity with unique constraint on `(account_id, ticker)`
- Sync flow: `TradeRepublicSyncService.sync()` → `upsertAccount()` → `holdingRepository.save()`
