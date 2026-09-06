import '@testing-library/jest-dom'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { institutionSearch } = vi.hoisted(() => ({
  institutionSearch: { current: { data: undefined as unknown } },
}))

vi.mock('@/features/sync/hooks', () => ({
  useSearchInstitutions: () => institutionSearch.current,
  useBankCountries: () => ({ data: ['FR'] }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'fr' } }),
}))

// jsdom lacks matchMedia, which DateInput (loan dates) probes for the touch/native date picker.
vi.stubGlobal('matchMedia', (query: string) => ({
  matches: false, media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {},
  addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
}))
vi.stubGlobal('ResizeObserver', class {
  observe() {}
  unobserve() {}
  disconnect() {}
})
Object.defineProperty(document, 'elementFromPoint', {
  configurable: true,
  value: vi.fn(() => document.body),
})

const { AccountForm } = await import('./AccountForm')

const CREDIT_AGRICOLE = {
  id: 'Crédit Agricole::FR::personal',
  name: 'Crédit Agricole',
  bic: null,
  logoUrl: 'https://cdn.example/ca.png',
  country: 'FR',
  psuType: 'personal',
}

describe('AccountForm bank field', () => {
  /**
   * `provider` is written by BankPicker through setValue rather than being registered by the
   * input itself, so this is what proves the picked bank actually reaches the submitted
   * request — and that its catalog id rides along for the server-side logo lookup.
   */
  it('submits the picked bank and its institution id', async () => {
    institutionSearch.current = { data: [CREDIT_AGRICOLE] }
    const onSubmit = vi.fn()
    render(<AccountForm open onOpenChange={vi.fn()} onSubmit={onSubmit} />)

    fireEvent.change(screen.getByLabelText('accounts.accountName'), {
      target: { value: 'Compte joint' },
    })
    fireEvent.change(screen.getByLabelText('accounts.provider'), { target: { value: 'crédit a' } })
    fireEvent.click(screen.getByRole('button', { name: /Crédit Agricole/ }))
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      name: 'Compte joint',
      provider: 'Crédit Agricole',
      institutionId: 'Crédit Agricole::FR::personal',
    })
  })

  it('submits a hand-typed bank with no institution id', async () => {
    // Nothing picked from the catalog: the backend falls back to matching on the name alone.
    institutionSearch.current = { data: undefined }
    const onSubmit = vi.fn()
    render(<AccountForm open onOpenChange={vi.fn()} onSubmit={onSubmit} />)

    fireEvent.change(screen.getByLabelText('accounts.accountName'), {
      target: { value: 'Livret' },
    })
    fireEvent.change(screen.getByLabelText('accounts.provider'), {
      target: { value: 'Ma banque locale' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      provider: 'Ma banque locale',
      institutionId: undefined,
    })
  })

  it('offers Livret A among the account types', () => {
    institutionSearch.current = { data: undefined }
    render(<AccountForm open onOpenChange={vi.fn()} onSubmit={vi.fn()} />)

    expect(screen.getByRole('option', { name: 'accountTypes.livretA' })).toBeInTheDocument()
  })
})

describe('AccountForm validation and submit errors', () => {
  beforeEach(() => {
    institutionSearch.current = { data: undefined }
  })

  /** The schema always rejected a negative balance; the user just never saw why Save did nothing. */
  it('shows the field error and does not submit a negative balance', async () => {
    const onSubmit = vi.fn()
    render(<AccountForm open onOpenChange={vi.fn()} onSubmit={onSubmit} />)

    fireEvent.change(screen.getByLabelText('accounts.accountName'), { target: { value: 'Livret' } })
    fireEvent.change(screen.getByLabelText('accounts.balance'), { target: { value: '-500' } })
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))

    expect(await screen.findByText('common.validation.nonNegative')).toBeInTheDocument()
    expect(screen.getByLabelText('accounts.balance')).toHaveAttribute('aria-invalid', 'true')
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('shows the required-name error instead of silently ignoring Save', async () => {
    const onSubmit = vi.fn()
    render(<AccountForm open onOpenChange={vi.fn()} onSubmit={onSubmit} />)

    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))

    expect(await screen.findByText('common.validation.required')).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('renders a rejected onSubmit as an error and keeps the form open', async () => {
    const onOpenChange = vi.fn()
    const onSubmit = vi.fn().mockRejectedValue({
      response: { status: 409, data: { detail: 'An account with this name already exists' } },
    })
    render(<AccountForm open onOpenChange={onOpenChange} onSubmit={onSubmit} />)

    fireEvent.change(screen.getByLabelText('accounts.accountName'), { target: { value: 'Livret' } })
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('An account with this name already exists')
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
    expect(screen.getByLabelText('accounts.accountName')).toHaveValue('Livret')
  })

  /**
   * The old `<input type="hidden" value="true">` never reached react-hook-form's values, so a
   * loan was submitted with whatever the hidden checkbox last held.
   */
  it('submits a loan as manual even though the checkbox is not offered', async () => {
    const onSubmit = vi.fn()
    render(<AccountForm open onOpenChange={vi.fn()} onSubmit={onSubmit} />)

    fireEvent.change(screen.getByLabelText('accounts.accountName'), { target: { value: 'Prêt' } })
    fireEvent.change(screen.getByLabelText('accounts.accountType'), { target: { value: 'LOAN' } })
    expect(screen.queryByLabelText('accounts.manual')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ type: 'LOAN', isManual: true })
  })
})

describe('AccountForm seeding', () => {
  it('seeds from defaultValues when the parent flips open, and keeps edits across re-renders', () => {
    const { rerender } = render(
      <AccountForm open={false} onOpenChange={vi.fn()} onSubmit={vi.fn()} defaultValues={{ name: 'Livret A' }} />,
    )
    expect(screen.queryByLabelText('accounts.accountName')).not.toBeInTheDocument()

    rerender(<AccountForm open onOpenChange={vi.fn()} onSubmit={vi.fn()} defaultValues={{ name: 'Livret A' }} />)
    expect(screen.getByLabelText('accounts.accountName')).toHaveValue('Livret A')

    // A parent re-render with a fresh (inline) defaultValues object must not wipe typing.
    fireEvent.change(screen.getByLabelText('accounts.accountName'), { target: { value: 'Livret B' } })
    rerender(<AccountForm open onOpenChange={vi.fn()} onSubmit={vi.fn()} defaultValues={{ name: 'Livret A' }} />)
    expect(screen.getByLabelText('accounts.accountName')).toHaveValue('Livret B')
  })
})
