# Feature: Add Account Modal

> Last updated: 2026-09-06

## Context

Creating a new account or connecting a sync provider required two separate entry points: a simple `AccountForm` dialog for manual accounts, and the `/sync` page for all provider connections. This unified both flows into a single modal accessible from the Accounts page "Add account" button.

## How it works

The `AddAccountModal` is a state-machine dialog with two levels:

1. **Selector screen** — buttons in a grid (Banks, Exchanges, Wallets, Trade Republic, BoursoBank, Bourse Direct, DEGIRO, Interactive Brokers, Amundi, Finary, Property, Manual). Each sync button enters its wizard or panel; the Manual button opens the existing `AccountForm` in a separate dialog.
2. **Wizard screens** — Each sync type has its own compact wizard with a back button. Each wizard manages its own loading and error state inline.

The `step` resets to the selector when the dialog **closes** (`handleDialogChange(false)`), not
when it opens: `AccountsPage` opens the modal by flipping the `open` prop, and Radix never calls
`onOpenChange(true)` for that, so a reset-on-open never ran and Escape from a wizard used to
reopen the dialog straight onto that wizard.

### Key files

- `frontend/src/components/shared/AddAccountModal.tsx` — main component (contains all sub-wizards; `SOURCES` array drives the selector grid)
- `frontend/src/pages/accounts/AccountsPage.tsx` — wires `AddAccountModal` for create, keeps `AccountForm` for edit
- `frontend/src/features/sync/hooks.ts` — all sync mutation hooks reused by the wizards
- `frontend/src/components/ui/input-otp.tsx` — shadcn InputOTP component (installed for TR PIN and verification code)
- `frontend/src/components/sync/IbkrPanel.tsx` — extracted IBKR connection panel (source of truth shared with `IbkrTab`)

### Flow

```
AccountsPage → "Add account" button
  └─ AddAccountModal (step = "selector")
       ├─ Banks → BankWizard
       │    └─ search institutions → select → initiate OAuth → redirect
       ├─ Exchanges → ExchangeWizard
       │    └─ pick type → API key + secret → add → success
       ├─ Wallets → WalletWizard
       │    └─ pick chain → address + label → add → success
       ├─ DEGIRO → DegiroPanel (onConnected → handleDone)
       ├─ Interactive Brokers → IbkrPanel (onConnected → handleDone)
       ├─ Amundi → AmundiPanel (onConnected → handleDone)
       ├─ Trade Republic → TradeRepublicWizard
       │    └─ phone + PIN (InputOTP 4-digit) → verification code (InputOTP 4-digit) → success
       ├─ Finary → FinaryWizard (3-step)
       │    └─ login/upload → account mapping → results
       └─ Manual → AccountForm (separate dialog)
```

### Error handling

Each wizard owns its error state as a local `useState<string | null>`. Errors are shown in a dismissible red banner inside the wizard, and cleared on the next attempt.

The Finary wizard formats every failure (login, TOTP check, API preview, file preview, execute)
through a local `formatFinaryError(err, fallbackKey)`: a 502 becomes
`sync.finary.serviceUnavailable`, everything else goes through `formatApiError` with a
step-specific fallback (`sync.finary.authFailed` for login, `sync.finary.syncFailed` for the API
path, `sync.finary.importFailed` for the file path). It never renders `err.message` (axios
boilerplate) and never uses the `common.retry` button label as a message. A login failure is
shown too — it used to only stop the spinner. The bank, exchange and wallet wizards still read
the backend `detail` first and fall back to a translated key.

The Finary API path passes `apiSync: true` explicitly to `executeWithMappings` from the preview
callback: the `isApiSync` state it sets in the same tick is not visible to that closure yet, and
reading it sent the API `syncToken` to `POST /finary/import`, whose cache had never seen it.
`FinaryTab` (`pages/sync/FinaryTab.tsx`) carries the same closure and is not fixed here.

The Trade Republic wizard follows the same state rules as the dedicated Sync
page: initiation errors keep the phone/PIN form visible and clear any stale
process id; verification-code errors keep the code form visible, clear the typed
code, and retain the current process id for retry.

Bank institution search is also handled inline. The `/sync/institutions` GET is
marked with `skipGlobalErrorRedirect` so connector failures such as Enable
Banking misconfiguration stay in the modal instead of sending the whole app to
`/error/500?code=502`.

**Previously**, a global `isPending` overlay in the parent replaced all wizard content with a spinner during mutations. This caused a React unmounting bug: when the mutation completed with an error, `setError(...)` was called on the unmounted (old) wizard instance → no-op → error silently swallowed. That mechanism was removed. Each wizard's button is now disabled via `mutation.isPending` instead.

### Currency field (validation & display resilience)

The manual `AccountForm` currency field is a **curated `<select>`** (not free text), sourced from
`SUPPORTED_CURRENCIES` in `frontend/src/lib/constants.ts`. Option labels are rendered live with
`Intl.DisplayNames` (locale-aware, e.g. "EUR — Euro"), so the list stays codes-only. When editing an
account whose code isn't in the curated list (a legacy or previously-invalid value), that code is
prepended as an extra option so opening the form never silently rewrites the currency.

Validation is layered:

- **Frontend input** — the dropdown makes an invalid code unselectable; zod requires a non-empty string.
- **Backend** — `AccountRequest.currency` carries `@ValidCurrency` (`com.picsou.validation`), which checks
  the code against `java.util.Currency.getInstance(...)`. An unknown code now returns **400** instead of
  persisting. Applies to both create and update (shared DTO).
- **Display** — `formatCurrency` (`frontend/src/lib/utils.ts`) wraps `Intl.NumberFormat` in try/catch and
  degrades to `"<amount> <code>"` on an unknown code, so a bad value can never blank the app again.

This closed issue #9: a free-text code like `AMAT` used to throw a `RangeError` from
`Intl.NumberFormat`, bubble to the root `ErrorBoundary`, and make the account unreachable/undeletable.

### Manual form (`AccountForm`)

`AccountForm` is a Dialog shell around `AccountFormBody`, which is mounted only while `open`
(the key-remount pattern from `docs/conventions/frontend.md`): the fields seed from
`defaultValues` once, on mount, with no reset-on-open effect. A parent re-render with a fresh
`defaultValues` object no longer resets the form under the user.

- **Validation is visible.** The zod schema stores i18n keys as its messages
  (`common.validation.required` / `tooLong` / `invalidNumber` / `nonNegative` / `percentage`)
  and the body renders `formState.errors` under each field with `aria-invalid`. A negative
  balance, a lone `-` in a `NumericInput` (NaN) or an over-long name now say why Save did nothing.
- **Icon-only controls carry an accessible name.** The error banners' dismiss control is an
  `<X />` icon with `aria-label={t('common.close')}` (it used to be the literal character `x`),
  and the Finary mapping step's colour swatches get `aria-label`/`title` = the colour and
  `aria-pressed` for the selected one, like `ColorPicker`. See
  `docs/conventions/frontend.md` § Accessible names and states.
- **A rejected `onSubmit` is shown.** `handleFormSubmit` awaits `onSubmit` and renders
  `formatApiError(err, t)` above the footer (`role="alert"`); the dialog stays open. Callers
  should let the mutation reject rather than swallow it.
- **Properties and loans are submitted as manual.** The checkbox is hidden for `REAL_ESTATE`
  and `LOAN`, so `handleFormSubmit` sets `isManual: true` for them. The previous
  `<input type="hidden" value="true">` was a no-op: react-hook-form submits its own values, not
  the DOM's.
- **Loan create is two requests.** `AddAccountModal.handleManualSubmit` creates the account, then
  saves the debt metadata. It keeps `createdAccountId` in state so a retry after the second
  request failed reuses the account instead of creating a twin (the guard `AddPropertyModal`
  already had); the id is cleared on success and when the form closes.

### Bank field (manual form)

The manual `AccountForm`'s provider field is a `BankPicker`: free text that also searches the
institution catalog as you type. Picking a bank sets the field to the institution's name and
sends its catalog id alongside, which is what lets the backend resolve a logo for an account no
connector syncs — see [bank-logos.md](./bank-logos.md#the-bank-a-manual-account-names). A loan's
lender field is the same control on the same form value: a loan's provider *is* its bank.

It never blocks on the search. An unconfigured or failing catalog simply shows no suggestions,
and the typed name is saved as before.

### Account type labels

`ACCOUNT_TYPES` and `accountTypeLabelKey()` (`frontend/src/lib/constants.ts`) are the only
list of account types and the only way to get one's translation key. Five call sites used to
carry their own copy — the two type dropdowns (this modal's manual form and its Finary mapping
step), `AccountTypeBadge`, `HoldingsCard`, `PortfolioView` and `HoldingDetailModal` — and three
of them derived the key from the type name (`type.toLowerCase()`, with special cases bolted on
for `COMPTE_TITRES` and `REAL_ESTATE`).

That derivation only held while every key was the lowercased value. Adding `LIVRET_A` broke it
immediately: the badge beside the account name rendered the literal string
`accountTypes.livret_a`. `constants.test.ts` now pins every type to a key that exists in all
four locales, and the partial maps are gone.

### SyncPage integration

`SyncPage` reads `?tab=` from the URL query params to set the initial tab. This was added for forward-compatibility; the modal does not redirect there — all wizards are inline.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Single file with sub-components | All wizards share the same imports, hooks, and patterns | Separate file per wizard |
| `InputOTP` for TR PIN and verification code | shadcn component, consistent UX for digit-only inputs | Regular password input |
| `AccountForm` reused for manual | Already existed, handles validation and color picking | Inline form in the modal |
| Per-wizard error state (no global overlay) | Global `isPending` unmounts the wizard, losing error state (React no-op on unmounted setter) | Global `onPending` callback |
| `mutation.isPending` on buttons for loading | Keeps the wizard mounted throughout; spinner is inline on the submit button | Parent-level overlay |

## Gotchas / Pitfalls

- **Never replace wizard content with a parent-level overlay during mutations.** When the wizard unmounts and remounts after an error, any `setError(...)` called on the old instance is silently ignored by React 18. Each wizard must stay mounted while its mutation is in flight.
- **`input-otp` package must be in root `node_modules`** — Vite resolves from the project root. If installed only in `frontend/`, it fails at runtime with "error loading dynamically imported module".
- **Trade Republic PIN and verification code are 4 digits** — `maxLength` on `InputOTP` controls this.
- **Trade Republic initiation errors stay on credentials** — never move the
  wizard to the verification-code step unless `/tr/auth/initiate` returned a
  process id. TAN completion errors are the only errors that keep the code step
  visible for retry.
- **Bank OAuth is fire-and-forget** — `window.location.href = data.authLink` redirects the entire page. The modal does not reach a success state; the redirect carries the user away. Error handling (e.g. `REDIRECT_URI_NOT_ALLOWED`) surfaces as a banner before the redirect happens.
- **`ENABLEBANKING_REDIRECT_URI` must match the EB portal** — see [bank-sync.md](./bank-sync.md).
- **Finary wizard is the only multi-step wizard** (3 steps: login/upload → mapping → results). All others are single-step.
- **Edit flow is unchanged** — `AccountsPage` uses `AccountForm` for editing. The modal is create-only.

## Tests

- `frontend/src/lib/utils.test.ts` — `formatCurrency` regression case: an invalid code does not throw
  and the raw code appears in the output (issue #9).
- `frontend/src/components/shared/AddAccountModal.test.tsx` — Trade Republic wizard regression cases; Bourse Direct, Amundi, and IBKR wizard flow tests (mock panel → `onOpenChange(false)`); Finary wizard (auto-mapped API sync hits the API endpoint, login 422/502 and preview 500 messages); dismissing a wizard returns to the selector; manual loan retry reuses the created account.
- `frontend/src/components/shared/AccountForm.test.tsx` — bank field submission; visible zod errors (negative balance, empty name) with no submit; a rejected `onSubmit` rendered as an alert; a loan submitted with `isManual: true`; seeding from `defaultValues` on open without losing edits on re-render.
- `backend/src/test/java/com/picsou/validation/CurrencyValidatorTest.java` — accepts valid ISO 4217
  codes, rejects unknown ones, leaves null/blank to `@NotBlank`.

## Links

- i18n keys: `addAccount.*`, sync keys reused from `sync.*` namespace in `en.json` / `fr.json`
- Related: [Finary import](./finary-import.md), [Trade Republic](./trade-republic.md), [Bank sync](./bank-sync.md), [Crypto tracking](./crypto-tracking.md)
