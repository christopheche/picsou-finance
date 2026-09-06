import { useMemo, useState } from 'react'
import { useForm, useWatch, Controller } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useTranslation } from 'react-i18next'
import type { Account } from '@/types/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NumericInput } from '@/components/shared/NumericInput'
import { DateInput } from '@/components/shared/DateInput'
import { Label } from '@/components/ui/label'
import { ColorPicker } from '@/components/shared/ColorPicker'
import { LogoPicker } from '@/components/shared/LogoPicker'
import { BankPicker } from '@/components/shared/BankPicker'
import { parseAmount, getLocale } from '@/lib/utils'
import { formatApiError } from '@/lib/errors'
import { ACCOUNT_TYPES, SUPPORTED_CURRENCIES } from '@/lib/constants'

/** RHF setValueAs: empty → undefined (optional), else comma-tolerant number. */
const toOptionalNumber = (v: unknown): number | undefined =>
  v === '' || v == null ? undefined : parseAmount(String(v))
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

// Every constraint names its message as an i18n key: zod's own wording is English and
// the field errors below are rendered through `t`. A lone "-" or "." in a NumericInput
// parses to NaN, which is the `error` (invalid type) case rather than a range one.
const optionalAmount = () =>
  z.number({ error: 'common.validation.invalidNumber' }).min(0, 'common.validation.nonNegative').optional()

const accountSchema = z.object({
  name: z.string().min(1, 'common.validation.required').max(100, 'common.validation.tooLong'),
  type: z.enum([
    'LEP', 'LIVRET_A', 'LDDS', 'LIVRET_JEUNE', 'PEL', 'CEL',
    'PEA', 'COMPTE_TITRES', 'CRYPTO', 'CHECKING', 'SAVINGS',
    'REAL_ESTATE', 'LOAN', 'EMPLOYEE_SAVINGS', 'OTHER',
  ]),
  provider: z.string().max(100, 'common.validation.tooLong').optional(),
  currency: z.string().min(1, 'common.validation.required'),
  currentBalance: optionalAmount(),
  isManual: z.boolean(),
  color: z.string(),
  ticker: z.string().max(20, 'common.validation.tooLong').optional(),
  logoKey: z.string().optional(),
  // Not an account field: the id of the bank picked in BankPicker, forwarded to the backend
  // once so it can resolve that bank's logo server-side. Undefined for a hand-typed name.
  institutionId: z.string().optional(),
  // Loan-only fields (validated as numbers but optional at the form level — required-ness is enforced at submit when type=LOAN)
  borrowedAmount: optionalAmount(),
  interestRatePct: z.number({ error: 'common.validation.invalidNumber' })
    .min(0, 'common.validation.nonNegative').max(100, 'common.validation.percentage').optional(),
  monthlyPayment: optionalAmount(),
  insuranceMonthly: optionalAmount(),
  fileFees: optionalAmount(),
  startDate: z.string().optional(),
  endDate: z.string().optional(),
  // Ties a mortgage to the property it finances, which is what makes gross vs net
  // property equity computable.
  linkedAccountId: z.number().optional(),
})

type AccountFormData = z.infer<typeof accountSchema>

interface AccountFormProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** A rejected promise is caught and shown above the footer; the dialog stays open. */
  onSubmit: (data: AccountFormData) => void | Promise<void>
  defaultValues?: Partial<AccountFormData>
  title?: string
  loading?: boolean
  /**
   * The caller's account list, used to offer a property to link a loan to.
   *
   * Passed in rather than fetched here: this form is also rendered by AddAccountModal, and a
   * shared presentational component that issues its own query forces every consumer (and
   * every test) to provide a QueryClient. The bank field is the one exception — searching a
   * catalog as the user types cannot be answered by a prop — and it keeps its query inside
   * BankPicker rather than lifting it here, so only the field that needs it pays for it.
   */
  accounts?: Account[]
}

const EMPTY_DEFAULTS: AccountFormData = {
  name: '',
  type: 'CHECKING',
  provider: '',
  currency: 'EUR',
  currentBalance: undefined,
  isManual: false,
  color: '#6366f1',
  ticker: '',
  logoKey: '',
  institutionId: undefined,
  borrowedAmount: undefined,
  interestRatePct: undefined,
  monthlyPayment: undefined,
  insuranceMonthly: undefined,
  fileFees: undefined,
  startDate: '',
  endDate: '',
}

const selectControlClassName = "flex h-10 w-full rounded-xl border border-input bg-background text-foreground px-4 text-sm outline-none [color-scheme:light] dark:[color-scheme:dark]"

/** The form hides the manual checkbox for a property or a loan, so what it submits must say what the UI implies. */
function isAlwaysManual(type: AccountFormData['type']): boolean {
  return type === 'REAL_ESTATE' || type === 'LOAN'
}

export function AccountForm({ open, onOpenChange, onSubmit, defaultValues, title, loading, accounts = [] }: AccountFormProps) {
  const { t } = useTranslation()

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{title ?? t('accounts.addAccount')}</DialogTitle>
          <DialogDescription />
        </DialogHeader>
        {/* Mounted only while open so the fields seed from `defaultValues` once, on mount: the
            parent opens this dialog by flipping `open` (Radix never calls onOpenChange for
            that), and a reset-on-open effect would also re-run on every new `defaultValues`
            identity — wiping in-progress edits under a caller that builds them inline. */}
        {open && (
          <AccountFormBody
            onOpenChange={onOpenChange}
            onSubmit={onSubmit}
            defaultValues={defaultValues}
            loading={loading}
            accounts={accounts}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

type AccountFormBodyProps = Omit<AccountFormProps, 'open' | 'title'>

function AccountFormBody({ onOpenChange, onSubmit, defaultValues, loading, accounts = [] }: AccountFormBodyProps) {
  const { t } = useTranslation()
  const propertyAccounts = accounts.filter(a => a.type === 'REAL_ESTATE')
  const { register, handleSubmit, setValue, control, formState: { errors } } = useForm<AccountFormData>({
    resolver: zodResolver(accountSchema),
    defaultValues: { ...EMPTY_DEFAULTS, ...defaultValues },
  })
  const [submitError, setSubmitError] = useState<string | null>(null)

  const selectedColor = useWatch({ control, name: 'color' })
  // Doubles as the "does this account get a logo choice at all" test: only an on-chain wallet
  // is created with a key (WalletSyncService), and the picker only ever swaps one key for
  // another, so an account that has none never grows one here.
  const selectedLogoKey = useWatch({ control, name: 'logoKey' })
  const selectedType = useWatch({ control, name: 'type' })
  const selectedProvider = useWatch({ control, name: 'provider' })
  const selectedCurrency = useWatch({ control, name: 'currency' })

  // Build the currency dropdown options. Labels are resolved live via Intl.DisplayNames
  // (locale-aware, e.g. "EUR — Euro"). If the account being edited carries a code not in
  // the curated list (a legacy or previously-invalid value), prepend it so opening the
  // form for edit never silently rewrites the account's currency — issue #9.
  const currencyOptions = useMemo(() => {
    const codes =
      selectedCurrency && !(SUPPORTED_CURRENCIES as readonly string[]).includes(selectedCurrency)
        ? [selectedCurrency, ...SUPPORTED_CURRENCIES]
        : [...SUPPORTED_CURRENCIES]
    const display = new Intl.DisplayNames([getLocale()], { type: 'currency' })
    return codes.map((code) => {
      let name: string | undefined
      try {
        name = display.of(code)
      } catch {
        name = undefined
      }
      return { code, label: name && name !== code ? `${code} — ${name}` : code }
    })
  }, [selectedCurrency])

  // The lender field and the provider field are the same form value: a loan's provider IS its
  // bank, and it gets a logo on the same terms as any other account.
  function handleBankChange(bankName: string, institutionId?: string) {
    setValue('provider', bankName)
    setValue('institutionId', institutionId)
  }

  async function handleFormSubmit(data: AccountFormData) {
    setSubmitError(null)
    // Not a hidden input: react-hook-form submits its own values, not the DOM's, so a
    // `<input type="hidden" value="true">` never reached the request.
    const submitted = isAlwaysManual(data.type) ? { ...data, isManual: true } : data
    try {
      await onSubmit(submitted)
    } catch (err) {
      setSubmitError(formatApiError(err, t))
    }
  }

  /** Field-level zod message, translated; the schema stores i18n keys as messages. */
  function fieldError(name: keyof AccountFormData) {
    const message = errors[name]?.message
    if (!message) return null
    return <p className="text-sm text-destructive">{t(message)}</p>
  }

  return (
    <form onSubmit={handleSubmit(handleFormSubmit)} className="space-y-4">
      <div className="space-y-2">
        <Label htmlFor="name">{t('accounts.accountName')}</Label>
        <Input id="name" {...register('name')} aria-invalid={!!errors.name} placeholder={t('accounts.accountNamePlaceholder')} />
        {fieldError('name')}
      </div>

      <div className="space-y-2">
        <Label htmlFor="type">{t('accounts.accountType')}</Label>
        <select
          id="type"
          {...register('type')}
          className={selectControlClassName}
        >
          {ACCOUNT_TYPES.map((at) => (
            <option key={at.value} value={at.value}>
              {t(at.labelKey)}
            </option>
          ))}
        </select>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-2">
          <Label htmlFor="currency">{t('common.currency')}</Label>
          <select
            id="currency"
            {...register('currency')}
            className={selectControlClassName}
          >
            {currencyOptions.map((c) => (
              <option key={c.code} value={c.code}>
                {c.label}
              </option>
            ))}
          </select>
          {fieldError('currency')}
        </div>
        <div className="space-y-2">
          <Label htmlFor="balance">
            {selectedType === 'LOAN' ? t('debt.remaining') : t('accounts.balance')}
          </Label>
          <NumericInput id="balance" {...register('currentBalance', { setValueAs: toOptionalNumber })} aria-invalid={!!errors.currentBalance} />
          {fieldError('currentBalance')}
        </div>
      </div>

      {selectedType !== 'REAL_ESTATE' && selectedType !== 'LOAN' && (
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="provider">{t('accounts.provider')}</Label>
            <BankPicker
              id="provider"
              value={selectedProvider ?? ''}
              placeholder={t('accounts.providerPlaceholder')}
              onChange={handleBankChange}
            />
            {fieldError('provider')}
          </div>
          <div className="space-y-2">
            <Label htmlFor="ticker">{t('accounts.ticker')}</Label>
            <Input id="ticker" {...register('ticker')} aria-invalid={!!errors.ticker} placeholder={t('accounts.tickerPlaceholder')} />
            {fieldError('ticker')}
          </div>
        </div>
      )}

      {selectedType === 'LOAN' && (
        <>
          <div className="space-y-2">
            <Label htmlFor="provider">{t('debt.lenderName')}</Label>
            <BankPicker
              id="provider"
              value={selectedProvider ?? ''}
              placeholder={t('debt.lenderName')}
              onChange={handleBankChange}
            />
            {fieldError('provider')}
          </div>
          {propertyAccounts.length > 0 && (
            <div className="space-y-2">
              <Label htmlFor="linkedAccountId">{t('debt.linkedAccount')}</Label>
              <select
                id="linkedAccountId"
                className={selectControlClassName}
                {...register('linkedAccountId', {
                  setValueAs: v => (v === '' || v == null ? undefined : Number(v)),
                })}
              >
                <option value="">{t('debt.noLinkedAsset')}</option>
                {propertyAccounts.map(property => (
                  <option key={property.id} value={property.id}>{property.name}</option>
                ))}
              </select>
              <p className="text-xs text-muted-foreground">{t('debt.linkedAssetHint')}</p>
            </div>
          )}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="borrowedAmount">{t('debt.borrowedAmount')}</Label>
              <NumericInput
                id="borrowedAmount"
                {...register('borrowedAmount', { setValueAs: toOptionalNumber })}
                aria-invalid={!!errors.borrowedAmount}
                placeholder="100000"
              />
              {fieldError('borrowedAmount')}
            </div>
            <div className="space-y-2">
              <Label htmlFor="interestRatePct">{t('debt.interestRate')} (%)</Label>
              <NumericInput
                id="interestRatePct"
                {...register('interestRatePct', { setValueAs: toOptionalNumber })}
                aria-invalid={!!errors.interestRatePct}
                placeholder="1.5"
              />
              {fieldError('interestRatePct')}
            </div>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="monthlyPayment">{t('debt.monthlyPayment')}</Label>
              <NumericInput
                id="monthlyPayment"
                {...register('monthlyPayment', { setValueAs: toOptionalNumber })}
                aria-invalid={!!errors.monthlyPayment}
                placeholder="394.40"
              />
              {fieldError('monthlyPayment')}
            </div>
            <div className="space-y-2">
              <Label htmlFor="insuranceMonthly">{t('debt.insuranceMonthly')}</Label>
              <NumericInput
                id="insuranceMonthly"
                {...register('insuranceMonthly', { setValueAs: toOptionalNumber })}
                aria-invalid={!!errors.insuranceMonthly}
                placeholder="0"
              />
              {fieldError('insuranceMonthly')}
            </div>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="fileFees">{t('debt.fileFees')}</Label>
              <NumericInput
                id="fileFees"
                {...register('fileFees', { setValueAs: toOptionalNumber })}
                aria-invalid={!!errors.fileFees}
                placeholder="0"
              />
              {fieldError('fileFees')}
            </div>
            <div className="space-y-2">
              {/* spacer to keep grid aligned */}
            </div>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="startDate">{t('debt.startDate')}</Label>
              <Controller
                control={control}
                name="startDate"
                render={({ field }) => (
                  <DateInput id="startDate" value={field.value ?? ''} onChange={field.onChange} />
                )}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="endDate">{t('debt.endDate')}</Label>
              <Controller
                control={control}
                name="endDate"
                render={({ field }) => (
                  <DateInput id="endDate" value={field.value ?? ''} onChange={field.onChange} />
                )}
              />
            </div>
          </div>
        </>
      )}

      <div className="space-y-2">
        <Label>{t('accounts.color')}</Label>
        <ColorPicker value={selectedColor} onChange={(c) => setValue('color', c)} />
      </div>

      {/* Also gated on the type: AccountService keeps a key only on a crypto account that
          already stores one, so the picker has to disappear the moment the type changes
          rather than offer a choice the save is about to discard. */}
      {selectedLogoKey && selectedType === 'CRYPTO' && (
        <div className="space-y-2">
          <Label>{t('accounts.logo')}</Label>
          <LogoPicker value={selectedLogoKey} onChange={(k) => setValue('logoKey', k)} />
        </div>
      )}

      {!isAlwaysManual(selectedType) && (
        <div className="flex min-h-10 items-center gap-2">
          <input id="isManual" type="checkbox" {...register('isManual')} className="h-5 w-5 rounded accent-primary" />
          <Label htmlFor="isManual">{t('accounts.manual')}</Label>
        </div>
      )}

      {submitError && (
        <p role="alert" className="text-sm text-destructive">{submitError}</p>
      )}

      <DialogFooter>
        <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={loading}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" disabled={loading}>
          {loading ? t('common.loading') : t('common.save')}
        </Button>
      </DialogFooter>
    </form>
  )
}
