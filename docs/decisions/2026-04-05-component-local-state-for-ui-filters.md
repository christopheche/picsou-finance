# ADR: Component-local state for UI filters, not global state

> Date: 2026-04-05
> Status: ✅ Active

## Context

The Dashboard displays net worth history, account distribution, and goals. Users can click time range buttons (1D, 7D, 1M, etc.) to filter the net worth chart to different date ranges.

When a UI filter (like time range) is applied, the question arises: should the filter state live in **global state** (Zustand, Redux, Context) or as **local state** in the component that owns that data?

Choosing global state would cause:
- The entire Dashboard page to re-render when the user clicks a range button
- Unrelated sections (distribution pie, goals) to re-fetch unnecessarily
- Coupling of unrelated data (net worth history, account distribution, goals)

This decision clarifies the project's approach to UI filter state.

## Decision

**UI filter state (time range, date ranges, sorting, pagination) lives in component-local state via `useState`, not in global state.**

Exceptions: Filters that are genuinely app-wide (e.g., user locale, theme) remain in global state.

## Alternatives considered

### Global state (Zustand/Redux/Context)

- **Pros**:
  - All state in one place, visible from DevTools
  - Can persist filter state across page reloads
  - Can pre-populate from URL params easily
  - Easier to implement in heavily interconnected UIs
  
- **Cons**:
  - Causes entire page to re-render when a filter changes
  - Couples unrelated components (net worth history re-fetch + distribution re-fetch)
  - Adds boilerplate for simple UI state
  - Harder to reason about which component "owns" the filter

### Component-local state (`useState`)

- **Pros**:
  - Scope isolation — only the affected component re-renders
  - Clear ownership — the component that uses the filter state manages it
  - No boilerplate, simpler code
  - Prevents accidental cross-component coupling
  - Each page can have independent filter state without interfering with others

- **Cons**:
  - Filter state is lost on page reload (unless persisted to localStorage)
  - Harder to debug from Redux DevTools (not visible globally)
  - Requires explicit prop drilling if deeply nested

## Reasoning

UI filters (time range, pagination, sorting, search) control what data is **displayed**, not app-wide state. The affected component should own its own display state.

**Key principle**: Scope isolation prevents unnecessary re-renders.

In the Dashboard:
- Net worth time range affects only the net worth chart (NetWorthCard)
- Clicking "7D" should NOT cause the distribution pie to re-render
- Component-local state ensures this

If in the future we need to:
- Persist filter state across reloads → use `localStorage` in the component
- Sync filter state to URL params → use `useSearchParams()` in the component
- Share filters across multiple pages → lift state UP to a shared parent component, don't jump to global state

## Trade-offs accepted

- **No global filter visibility**: Filter state is not visible in Redux DevTools or similar. Acceptable because UI filters are ephemeral.
- **Lost on reload**: User's selected time range resets to default ('1Y') on page reload. Acceptable as it matches standard web app behavior.
- **No cross-page filter sync**: If we later need "time range follows you across pages," we'd lift state to a shared ancestor. Not adopting global state preemptively.

## Consequences

### For this feature

- `NetWorthCard` owns `range` state via `useState<TimeRange>('1Y')`
- Time range selector and net worth chart live in the same component
- ~~Distribution and goals never re-fetch when time range changes~~ — no longer true since the
  `range` argument was added to `useDashboard`: the hook keys its query on it, so a range click
  refetches the whole dashboard payload, distribution and goals included. The decision itself
  (component-local state, no global store) still holds; only this consequence lapsed. See the
  gotchas in [dashboard-time-range-isolation.md](../features/dashboard-time-range-isolation.md).

### For future filters

- Any new time range, pagination, sorting, search filter should start with `useState`
- Only lift to Context/global if the filter affects multiple unrelated components
- Use the Dashboard and NetWorthCard as the reference pattern

### Code patterns to adopt

```typescript
// Pattern 1: Simple filter in a single component
export function ChartCard() {
  const [range, setRange] = useState<TimeRange>('1Y')
  const { data } = useChartData(range)
  return <ChartDisplay data={data} onRangeChange={setRange} />
}

// Pattern 2: If filters are shared across multiple sibling components, lift state
export function DashboardPage() {
  const [range, setRange] = useState<TimeRange>('1Y')
  return (
    <>
      <NetWorthCard range={range} onRangeChange={setRange} />
      <DistributionCard range={range} />  {/* if it also needs range */}
    </>
  )
}

// Pattern 3: Only use global state if filter affects truly independent features
// e.g., app-wide theme, user locale, sidebar collapsed state
```

## Related features

- [Dashboard time range isolation](../features/dashboard-time-range-isolation.md) — First implementation of this pattern
