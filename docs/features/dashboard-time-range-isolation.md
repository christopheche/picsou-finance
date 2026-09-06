# Feature: Dashboard Time Range Isolation

> Last updated: 2026-09-06

## Context

The Dashboard displays net worth history, account distribution, and goals. The time range selector (1D, 7D, 1M, etc.) controls both the chart data and the net worth trend displayed in the hero card. The `range` state lives in `DashboardPage` and is passed to `NetWorthChart` as a prop.

## How it works

`DashboardPage` owns the `range` state and three hooks read it:

- `useDashboard(range)` sends `?range=` and keys its query on it, so **every range click refetches the whole dashboard payload** (distribution, liabilities, goals, `netWorthHistory`). `DashboardController` accepts the parameter and `DashboardService` turns it into a start date through `TimeRange.fromString(range).fromDate()`.
- `useHistory(chartAccountIds, historyMonths)` fetches the series the chart actually draws; `historyMonths` is derived from the range in the page.
- `usePnl(investmentAccountIds, pnlFromDate)` supplies the hero trend (`rangePnl`, falling back to the live `pnl` on `ALL`).

`NetWorthChart` still filters the series it is given client-side, through `filterByRange()`, so the visible window is the range even when the fetched window is wider.

Note that `DashboardData.netWorthHistory` — the series `useDashboard` refetches per range — has no consumer in the frontend today; the chart draws `useHistory`'s series.

### Key files

- `frontend/src/pages/dashboard/DashboardPage.tsx` — Page layout, owns `range` state, derives `historyMonths` and `pnlFromDate` from it, passes `range`/`onRangeChange` to chart
- `frontend/src/components/shared/NetWorthChart.tsx` — Chart with `range`/`onRangeChange` props
- `frontend/src/components/shared/chart-range.ts` — `filterByRange()` client-side filter, shared with `AccountsStackedChart` (unit-tested west of UTC in `chart-range.test.ts`)
- `frontend/src/components/shared/TimeRangeSelector.tsx` — Time range button controls (1D, 7D, 1M, 3M, YTD, 1Y, ALL)
- `frontend/src/features/dashboard/hooks.ts` — `useDashboard(range)`, query key `['dashboard', range]`
- `backend/src/main/java/com/picsou/controller/DashboardController.java` — `GET /api/dashboard?range=`
- `backend/src/main/java/com/picsou/service/DashboardService.java` — maps `range` to a start date via `TimeRange` and calls `historyService.buildHistory(ids, from, false, memberId)`
- `backend/src/main/java/com/picsou/dto/TimeRange.java` — the one place a range literal becomes a date

### Flow

```
DashboardPage mounts, owns range state (default: '1Y')
  ↓
useDashboard(range) fetches /api/dashboard?range=<range>
  ↓
Backend: TimeRange.fromString(range).fromDate() → buildHistory(ids, from, ...)
         + distribution + liabilities + goals
  ↓
useHistory(chartAccountIds, historyMonths) fetches the chart series
useNetWorthIntraday(...) instead when range === '24H'
usePnl(investmentAccountIds, pnlFromDate) supplies the hero trend
  ↓
NetWorthChart filters the series client-side (filterByRange) and
receives range + onRangeChange as props
  ↓
User clicks "3M" → DashboardPage.setRange('3M')
  ↓
All four queries re-key on the new range: dashboard, history, intraday, pnl
```

## Trend calculation

The dashboard hero shows the P&L over the selected range, computed server-side by
`HistoryService.buildPnl(accountIds, memberId, fromDate)` and read through `usePnl`:

```typescript
const pnl = pnlData?.rangePnl != null ? pnlData.rangePnl : (pnlData?.pnl ?? 0)
const pnlPct = pnlData?.rangePnlPercent ?? pnlData?.pnlPercent
```

`pnlFromDate` is derived from the range in `DashboardPage` (`ALL` sends none, which
asks for the live P&L). The earlier `last − first` over the filtered history array is
gone; the chart still filters its own series client-side for what it draws.

## Optional invested data

`DashboardData.netWorthHistory` items have an optional `invested` field. When present, the chart renders:
- A dashed `invested` line alongside the `total` area
- A gain/loss section in the tooltip (`total - invested`)
- A custom legend with solid (total) and dashed (invested) indicators

When `invested` is absent or undefined, the chart and tooltip show only the `total` line. The `NetWorthTooltip` component uses a `hasInvested` flag to conditionally render invested-related sections.

## Dashboard chart row layout

The dashboard chart row uses fixed-height cards (`420px`) for the PnL chart and
the distribution/allocation card. `DistributionPie` keeps both tab panels inside
the same bounded content area: the pie legend scrolls if needed, and the
allocation treemap fills the available height. This prevents the whole grid row
from growing when the user switches between "Distribution" and "Allocation".

Each treemap tile is a `<button>` carrying `aria-label="<account> <share>"`, and focus drives the
same highlight and tooltip as hover: the per-account share used to be hover-only, so keyboard and
screen-reader users could never reach it. Shares are rendered through `formatPercent`, in the app
locale rather than a bare `` `${percentage}%` ``.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Client-side filtering in `NetWorthChart` | The fetched window can be wider than the range (`ALL` reaches back to the epoch); filtering on the client keeps the drawn window exactly the range without a second request | Trusting the server window alone — the same series feeds several ranges |
| One place turns a range literal into a date (`TimeRange`) | `YTD` means "since 1 January", which a month count anchored on today cannot express | A `switch` on the raw string in `DashboardService` — it returned today-minus-N-months for YTD |
| `range` state in `DashboardPage` (lifted from `NetWorthChart`) | Both hero trend and chart must react to range changes — single source of truth | State inside `NetWorthChart` only — trend was disconnected from range selection |
| Default range `'1Y'` | A year of history is the useful default on open. | `'ALL'` — a much wider query on every dashboard load |
| Responsive `TimeRangeSelector` buttons | Smaller padding/font on mobile (`px-1.5 text-[11px]`), larger on `sm:` breakpoint. `flex-wrap` for overflow. | Fixed size — overflows on small screens |
| Hero trend from `usePnl`'s `rangePnl` | The range P&L is computed over holdings priced on both sides of the window, which a `last − first` over the history array cannot express (cash movements and unpriced positions leak into it) | Derive it from the filtered history array — the earlier approach |
| Fixed-height chart cards | Tab content has different natural heights; a fixed row prevents card resizing on tab switches | Let the grid auto-size each tab panel — causes the PnL card to jump too |

## Gotchas / Pitfalls

- **Each range click is a full dashboard refetch**: `useDashboard(range)` keys on `range`, so switching 1M → 3M → 1Y issues three `GET /dashboard` (each recomputing live balances server-side) on top of the history and P&L calls. Only `netWorthHistory` in that payload depends on the range, and nothing reads it — dropping `range` from `useDashboard` would be a pure win if the payload's history is never wired up.

- **`range` is a real backend parameter**: `DashboardController.getDashboard(@RequestParam String range)` → `TimeRange.fromString(range).fromDate()`. It is not ignored, and removing it from the frontend call silently narrows every range to the `1Y` fallback.

- **`TimeRange.fromString` is the only parser**: it accepts both spellings (`1D`/`YTD`) and falls back to `_1Y`. Its old `valueOf("_" + value)` threw on `YTD` and `ALL` — the two alphabetic ranges the UI actually sends — and answered both with a one-year window.

- **`filterByRange()` uses `new Date()` at filter time**: The cutoff date is computed on each range change relative to "now". If the page stays open across midnight, the filtered window shifts accordingly.

- **Points are `LocalDate`s and go through `parseApiDate`**: both the filter and the chart's time-scale `dateMs` anchor a date at *local* midnight. Parsed as an instant (`new Date(p.date)`) a point sits at UTC midnight, so west of UTC it was labelled with the previous day and a point dated exactly on the range start was dropped.

- **`NetWorthChart` is used elsewhere**: It's a shared component in `components/shared/`. The `TimeRangeSelector` is now always rendered inside it. If another page uses `NetWorthChart`, it will also show the range selector.

- **`useMemo` must be before the conditional return**: in `DashboardPage`, `chartAccountIds`, `investmentAccountIds`, `historyMonths`, `pnlFromDate` and `wealthValue` are all computed before the `if (isLoading || !data) return` guard. React requires hooks in the same order on every render; placing one after an early return causes error #310.

- **`invested` is optional in history items**: Some data sources (e.g., bank sync) don't provide invested amounts. The chart and tooltip handle the absence gracefully. Don't assume `invested` exists without checking.

## Tests

- `DashboardServiceTest.getDashboard_rangeSwitch_mapsToTheRangeStartDate` and `..._ytd_startsOnJanuaryFirst_notTodayMinusNMonths`
- `TimeRangeTest` — every wire value, including `YTD` and `ALL`
- `frontend/src/components/shared/chart-range.test.ts` — `filterByRange()`

Manual verification:

1. Open Dashboard
2. Click range buttons in the chart → chart, hero P&L and the dashboard payload all refetch
3. Toggle Distribution / Allocation → chart row card heights stay fixed
4. Verify range buttons are usable on mobile viewport (no overflow)
5. Refresh page → chart defaults to 1Y

## Links

- Related ADR: [Component-local state for UI filters](../decisions/2026-04-05-component-local-state-for-ui-filters.md)
