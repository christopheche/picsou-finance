# Feature: Live Prices in Holdings

> Last updated: 2026-09-06

## Context

Holdings (PEA, Compte-Titres, Crypto) display prices that are only updated during a full sync. When a user navigates to an account detail page, the displayed prices may be stale — sometimes hours old. The backend already exposes `GET /api/prices?tickers=...` via `PriceController` which refreshes the cache and returns live EUR prices. This feature integrates those live prices into the holdings table on page navigation, and propagates live portfolio values (with PnL) to Goals progress, Dashboard distribution, and the AccountDetail balance card.

## How it works

The frontend fetches holdings and live prices in parallel, then merges them client-side. The backend also computes live values server-side for Goals and Dashboard.

### Frontend data flow (holdings table)

```
User navigates to account detail
        |
        v
useHoldingsWithLivePrices(id)
        |
        +-- GET /api/accounts/{id}/holdings  (DB prices, may be stale)
        |
        +-- GET /api/prices?tickers=BTC,ETH,IWDA.AS  (live prices from providers)
        |
        v
Merge: override currentPrice with live price
Recalculate: currentValueEur, pnlEur, pnlPercent
        |
        v
HoldingsTable renders with live prices
```

If the prices API fails, the hook keeps the backend response. For Bourse Direct
holdings that response can include the last reconciled broker valuation in EUR,
even when Yahoo cannot resolve the native quote.

The ticker list is sent without any account type: the frontend cannot tell a
coin from a share. `PriceController` therefore goes through
`PriceService.refreshHeldPrices`, which routes tickers held in a `CRYPTO` account
crypto-only, so an unmapped coin (SNX, STX, APT, SEI) is simply absent from the
response — the hook keeps the backend price for it — rather than displayed, and
recorded in `price_snapshot`, at the share price of the equity trading under the
same symbol. See [price-service.md](./price-service.md).

The two failure modes are deliberately not symmetric. `usePortfolio` fetches
holdings per account in a `Promise.all`, and a rejected holdings call fails the
whole query: `PortfolioView` and `HoldingsCard` render `ErrorState` with
`formatApiError` and a retry. Swallowing it per account (the previous
`catch { return [] }`) silently dropped that account from the portfolio total,
allocation and P&L, with a "no holdings" empty state as the only hint.

### Live-price recompute formula (single source of truth)

Both `usePortfolio` (portfolio view across all accounts) and `useHoldingsWithLivePrices` (single account holdings table) share a single helper, `recomputeWithLivePrice`, in `frontend/src/features/accounts/hooks.ts`:

```
costBasisEur    = backend costBasisEur         (null if the basis is unknown)
currentValueEur = quantity * livePrice
pnlEur          = currentValueEur - costBasisEur
pnlPercent      = pnlEur / costBasisEur * 100  (null if costBasisEur == 0)
```

The backend cost basis is retained because a broker can provide an authoritative
EUR P&L for a foreign-currency position. Reconstructing it from a native price
would introduce FX errors. Value and P&L are recomputed from the same
`livePrice` snapshot, so the header badge (`pnlPercent`), Gain/Loss display
(`pnlEur`), and total value (`currentValueEur`) cannot drift out of sync.

The previous implementation in `usePortfolio` updated only `valueEur`/`pnlEur` via delta-add (`l.pnlEur + (newVal - oldVal)`) and left `pnlPercent` at the backend's stored ratio, producing badge-vs-display incoherence on every live-price refresh.

### Frontend data flow (AccountDetail balance)

The balance card renders `account.currentBalanceEur`. The backend owns that
aggregate so cash, live EUR prices and the all-or-nothing Bourse Direct fallback
are applied once rather than reconstructed differently in each frontend view:

```
displayBalance = account.currentBalanceEur
```

### Backend data flow (Goals & Dashboard)

The backend computes live balance via `AccountService.liveBalanceEur()`:

```
AccountService.liveBalanceEur(account)
        |
        +-- Load holdings for account
        |
        +-- If no holdings: return stored balance converted to EUR
        |
        +-- If holdings: for each holding:
        |       +-- priceService.getPriceEur(ticker)  (live cache, 15-min TTL — EUR-denominated)
        |       +-- if no live price: never multiply an unqualified native quote as EUR
        |       +-- accumulate qty * livePrice
        |
        v
Return live portfolio value in EUR
```

For Bourse Direct, if any position lacks a live EUR price, the method returns
the last complete broker account total instead of a partial
`cash + priced symbols` sum. `HoldingResponse` follows the same rule per
position: Yahoo EUR value when available, otherwise the broker's reconciled EUR
value. `currentPrice` remains tagged with its explicit `quoteCurrency`.

Used by:
- `GoalService.toProgressResponse()` — sums `liveBalanceEur()` across linked accounts for `currentTotal`
- `DashboardService.buildDistribution()` — uses live values from pre-loaded `holdingsByAccount` map for distribution percentages
- `DashboardService.getDashboard()` — already computed live total/invested inline (pre-dates `liveBalanceEur()`)

### Historical net-worth chart (`HistoryService.buildHistory`)

For each past date, both `total` and `invested` are read from `balance_snapshot` and forward-filled per account from the latest row on or before that date. The window query only returns rows from `from` onwards, so `buildPerAccountForwardFill` additionally seeds every account whose first in-window row comes after the chart's first date with its latest snapshot *before* the window: without it, an account the daily job could not price that morning (an expected outcome) contributed 0 to the earliest points, and since the frontend reads a range's trend as `last − first`, that dip was reported as a gain. The seed keeps its own pre-window date so `floorEntry` finds it; it never becomes a chart point of its own. Loans contribute their negative balance to `total` and zero to `invested`. Today's point is replaced with live values from `liveBalanceEur()` and `calculateInvestedAmount()` so intraday changes are visible immediately. The `invested_amount` column (added in V18, `NOT NULL`) is written by both the daily scheduler and every sync path via `AccountService.upsertSnapshot`. Both columns are EUR on every write path — the manual ones (`create`, `update`, `addManualSnapshot`) included since 2026-09-06; see the gotchas in [accounts-overview.md](./accounts-overview.md) for the cost basis of a hand-entered or backdated row.

### Key files

- `frontend/src/features/accounts/api.ts` — `prices(tickers)` API function
- `frontend/src/features/accounts/hooks.ts` — `useHoldingsWithLivePrices(id)` hook
- `frontend/src/pages/accounts/AccountDetailPage.tsx` — uses the hook; `displayBalance` for holding accounts
- `backend/src/main/java/com/picsou/service/AccountService.java` — `liveBalanceEur()` method
- `backend/src/main/java/com/picsou/service/HistoryService.java` — `buildHistory()`, `buildPnl()`, `buildIntradayHistory()`; deliberately **not** `@Transactional`
- `backend/src/main/java/com/picsou/service/GoalService.java` — uses `liveBalanceEur()` in `toProgressResponse()`
- `backend/src/main/java/com/picsou/service/DashboardService.java` — `buildDistribution()` uses live values from `holdingsByAccount`

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Two separate API calls (holdings + prices) | Prices API is reusable and navigation can refresh a cached backend value | A dedicated enriched-holdings endpoint |
| Client-side merge | Refreshes the existing currency-explicit `HoldingResponse` without another domain DTO | New dedicated endpoint returning enriched holdings |
| Graceful degradation on price failure | Better UX than showing errors for non-critical price data | Throwing error / blocking page render |
| Holdings failure propagates instead | A missing *price* leaves the position visible with a stale value; a missing *holdings* response removes the position from every total | Returning `[]` for the failed account |

## Gotchas / Pitfalls

- **The aggregated cash line has no unit price.** `usePortfolio` appends it with `isCash: true`, `ticker: 'EUR'` and `quantity: 0`; it is clickable like every other row. `HoldingDetailModal` treats a line with `isCash` or `quantity <= 0` as *not priceable*: it shows the total value only, hides the price/position toggle and the chart, and passes a `null` ticker to `usePriceHistory` and `HoldingInsightSection` — otherwise the headline read `value / 0 = ∞ €` and the modal fetched `/prices/EUR/history`. Covered by `HoldingDetailModal.test.tsx`.

- **`PortfolioView`'s "total value" is the whole portfolio.** It reduces over `lines`, not over the
  search-filtered `sorted` list — the header keeps the same label while the search box narrows the
  rows, so summing the matches turned it into an unlabelled subtotal. Covered by `PortfolioView.test.tsx`.

- **Prices are not persisted**: Live prices are only used for display. The DB `current_price` in `account_holding` is not updated — that still happens during sync.
- **`useAccountHoldings` was removed**: the old plain-holdings hook is gone. It was unused and shared the query key `['accounts', id, 'holdings']` with `useHoldingsWithLivePrices` while running a different `queryFn` — a cache-collision trap. `usePortfolio` fetches holdings for every account itself and enriches them with live prices too.
- **Prices poll every 2 minutes**: `usePortfolio` and `useHoldingsWithLivePrices` both set `refetchInterval: QUERY_STALE_TIMES.accountDetail` (2 min) on top of the matching `staleTime`, because `refetchOnWindowFocus` is globally off and an open tab would otherwise show an unbounded-age price. Each hook has one query key, so a tab polls once per key regardless of how many components read it — but every open dashboard/portfolio/account-detail tab does hit `/api/prices` every 2 min, which is the number to budget Yahoo/CoinGecko rate limits against. `PriceFreshnessDot`'s `LIVE_THRESHOLD_MS` (3 min) deliberately sits above this interval (+ latency) so the "live" dot does not flicker between refetches; lengthening the interval past 3 min would make the dot go stale on fresh data.
- **Yahoo Finance is unofficial**: See [price-service.md](./price-service.md) gotchas. A stored quote is displayed only with its explicit `quoteCurrency`; it is never multiplied as EUR when that currency is unknown.
- **Bourse Direct and Trade Republic write an EUR fallback**: `provider_value_eur` is accepted only from a broker that quotes in EUR — Bourse Direct as part of a fully reconciled snapshot, Trade Republic because its portfolio stream is EUR-denominated by construction (the same assumption already behind `TrAccountData.balanceEur`). Connectors that expose native-currency values without an explicit `quoteCurrency` still return `null`.
- **`liveBalanceEur()` falls back per holding**: when Yahoo has no live price, the holding is valued at `provider_value_eur` before being dropped. Dropping it is the last resort, and it is what makes the dashboard P&L wrong (see the asymmetry gotcha below).
- **Value and invested must cover the same holdings**: `liveBalanceEur` may drop an unpriced holding, but `DashboardService` and `AccountService.calculateInvestedAmount` still count its full cost basis. Any holding dropped from the value side while its cost basis remains understates P&L by exactly that cost basis (GH issue #76). The `provider_value_eur` fallback closes this for Bourse Direct and Trade Republic; a connector that populates neither can still hit it.
- **`AccountService.toResponse()` computes a live balance**: for Bourse Direct it falls back to the complete stored broker total if even one Yahoo price is unavailable.
- **`HistoryService` carries no class-level `@Transactional`.** Every read in it is a self-contained repository call (each already runs in its own short read-only transaction), while `buildIntradayHistory` loops over tickers doing provider HTTP calls with a 15 s timeout each. A class-level read-only transaction pinned one of the ten pooled connections for that whole loop, so a few concurrent 24H-chart loads during a slow Yahoo response exhausted the pool and failed unrelated requests with `SQLTransientConnectionException`. `HistoryServiceTest.historyService_isNotTransactional_soProviderCallsDoNotPinAConnection` pins its absence. (`DashboardService` is still transactional, so the dashboard path keeps a connection across `valuation()` — separate concern.)

- **The range P&L routes prices by account type, in batches.** `buildPnl` groups holdings by their owning account and resolves one set per route — `getCryptoQuotes` for `CRYPTO` accounts, `getQuotes` for the rest — exactly as `AccountService.quotesFor` does. It used to call `getPriceEur(ticker)` per holding, which sent an unmapped coin to Yahoo and valued it at the share price of the equity trading under the same symbol (SUI, ATOM, TIA, STX…), on the live side *and* through the ticker-keyed `price_snapshot` fallback on the historical side. A holding with no live quote is now skipped before the snapshot lookup, so that table is never reached for a coin CoinGecko cannot map.

- **`liveBalanceEur()` triggers price lookups**: Each call fetches holdings then queries `PriceService` per ticker. Don't call in tight loops. The Dashboard pre-loads holdings into a map to avoid N+1; Goals calls it per-account in the goal's account list (typically small).
- **The account detail page does not re-sum holdings**: it displays the backend's `currentBalanceEur`, avoiding disagreement with cash and broker fallback rules.

## Tests

- Manual: navigate to a PEA/CT/Crypto account, verify `/api/prices` call in network tab and live prices in table
- Manual: navigate to a checking/savings account, verify no `/api/prices` call

## Links

- Related feature: [Price Service](./price-service.md)
- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
