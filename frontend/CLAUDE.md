# Frontend — CLAUDE.md

## Commands

```bash
bun run dev          # Dev server :5173, proxies /api/* → localhost:8080
bun run build        # tsc + vite build (fails on type errors)
bun run typecheck    # TypeScript only
bun run lint         # ESLint (zero-warnings)
bunx vitest run      # Unit tests
bun run test:e2e     # Playwright E2E tests
```

## Architecture (quick ref)

```
src/
  app/              providers.tsx, routes.tsx (lazy routing + guards)
  pages/            One dir per route (lazy-loaded)
  features/         Domain slices: api.ts + hooks.ts per feature
  components/
    layout/         AppSidebar, AppLayout
    ui/             shadcn/ui — DO NOT EDIT
    shared/         App-specific reusable (PageHeader, etc.)
  stores/           Zustand (auth-store, app-store)
  lib/              api-client.ts, utils.ts, constants.ts, query-client.ts
  types/            api.ts (mirrors backend DTOs), app.ts (frontend-only; locale codes come from i18n/locales.ts)
  demo/             Demo mode interceptor + mock data
  i18n/             react-i18next (FR/EN/DE/ES) — SUPPORTED_LOCALES registry in locales.ts
```

## Key patterns

**API layer (`frontend/src/lib/api-client.ts`):** Single Axios instance, `withCredentials: true`. Response interceptor silently refreshes on 401 (queues concurrent retries behind one refresh; a failed refresh rejects every queued request, logs out and redirects to `/login`), and self-heals a stale admin impersonation target (GET 403 carrying `?memberId=activeMemberId` → reset the profile store, replay once without it). Auth calls excluded to avoid loops. GET-shaped mutations opt out of the global 5xx redirect with `skipGlobalErrorRedirect`. Feature-specific API functions live in `features/*/api.ts`. Covered by `src/lib/api-client.test.ts`.

**Server state:** TanStack Query via hooks in `features/*/hooks.ts`. No Redux, no Context for server data. Query keys co-located in hook files. Stale times in `frontend/src/lib/constants.ts`. Any mutation that moves a balance (sync, transaction, snapshot, import, account deletion) calls `invalidateWealthQueries()` from `features/accounts/hooks.ts` so the dashboard total, chart, P&L and intraday series refetch together.

**Auth guard:** `RequireAuth` in `frontend/src/features/auth/guards.tsx` checks `sessionStorage.getItem('picsou_user')` (JS-readable signal; the actual session cookies are HttpOnly). sessionStorage doesn't survive a tab/browser close, so when it's empty `RequireAuth` probes the cookie-backed session once via `useSessionProbe()` (`frontend/src/features/auth/hooks.ts`, calls `POST /auth/refresh`) before redirecting to `/login` — this is what makes "Remember Me" (`persistent_token`, 90 days) actually keep users signed in across restarts instead of only within a single tab session. Because the probe's success is cached, **always log out via `useLogout()`** (never the raw `useAuthStore().logout()` action directly) — it's the only path that also revokes the session server-side and clears the query cache (`resetClientState`), so a later probe can't silently resurrect a session the UI claims is logged out.

**Routing:** React Router v7 in `frontend/src/app/routes.tsx`. Lazy code-splitting per page. `/sync/callback` and `/sync` share SyncPage (OAuth redirect target).

## Conventions

Full conventions (styling, icons, i18n, charts, state management details): see [`docs/conventions/frontend.md`](../docs/conventions/frontend.md)
