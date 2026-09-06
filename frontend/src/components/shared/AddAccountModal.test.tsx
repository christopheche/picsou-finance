import '@testing-library/jest-dom'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { createAccount, updateDebtMetadata } = vi.hoisted(() => ({
  createAccount: vi.fn(),
  updateDebtMetadata: vi.fn(),
}))

const { initiateTrAuth, completeTrAuth, addCryptoExchange } = vi.hoisted(() => ({
  initiateTrAuth: vi.fn(),
  completeTrAuth: vi.fn(),
  addCryptoExchange: vi.fn(),
}))

const { finaryLogin, previewFinaryApi, importFinary, executeFinaryApiSync, finaryStatus } = vi.hoisted(() => ({
  finaryLogin: vi.fn(),
  previewFinaryApi: vi.fn(),
  importFinary: vi.fn(),
  executeFinaryApiSync: vi.fn(),
  /** Mutable so a test can open the wizard on the "connected" branch. */
  finaryStatus: { current: { connected: false } as { connected: boolean; maskedEmail?: string } },
}))

/** Mutable so each test can seed the institution list the BankWizard renders. */
const { institutionSearch } = vi.hoisted(() => ({
  institutionSearch: {
    current: { data: undefined as unknown, isError: false, isLoading: false, error: null },
  },
}))

vi.mock('@/features/accounts/hooks', () => ({
  useCreateAccount: () => ({ mutateAsync: createAccount, isPending: false }),
  useUpdateDebtMetadata: () => ({ mutateAsync: updateDebtMetadata, isPending: false }),
}))

vi.mock('@/features/sync/hooks', () => ({
  useSearchInstitutions: () => institutionSearch.current,
  useBankCountries: () => ({ data: undefined }),
  useInitiateBankSync: () => ({ mutate: vi.fn(), isPending: false }),
  useInitiateTrAuth: () => ({ mutate: initiateTrAuth, isPending: false }),
  useCompleteTrAuth: () => ({ mutate: completeTrAuth, isPending: false }),
  useAddCryptoExchange: () => ({ mutate: addCryptoExchange, isPending: false }),
  useAddCryptoWallet: () => ({ mutate: vi.fn(), isPending: false }),
  useFinaryConnectionStatus: () => ({ data: finaryStatus.current }),
  useFinaryLogin: () => ({ mutate: finaryLogin, isPending: false }),
  usePreviewFinaryFile: () => ({ mutate: vi.fn(), isPending: false }),
  usePreviewFinaryApi: () => ({ mutate: previewFinaryApi, isPending: false }),
  useImportFinary: () => ({ mutate: importFinary, isPending: false }),
  useExecuteFinaryApiSync: () => ({ mutate: executeFinaryApiSync, isPending: false }),
  useCheckFinaryTotp: () => ({ mutate: vi.fn(), isPending: false }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}))

vi.mock('@/components/sync/BourseDirectPanel', () => ({
  BourseDirectPanel: ({ onConnected }: { onConnected?: () => void }) => (
    <button onClick={onConnected}>bourse-direct-wizard</button>
  ),
}))

vi.mock('@/components/sync/AmundiPanel', () => ({
  AmundiPanel: ({ onConnected }: { onConnected?: () => void }) => (
    <button onClick={onConnected}>amundi-wizard</button>
  ),
}))

vi.mock('@/components/sync/IbkrPanel', () => ({
  IbkrPanel: ({ onConnected }: { onConnected?: () => void }) => (
    <button onClick={onConnected}>ibkr-wizard</button>
  ),
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

const { AddAccountModal } = await import('./AddAccountModal')

function renderTradeRepublicWizard() {
  render(<AddAccountModal open onOpenChange={vi.fn()} />)
  fireEvent.click(screen.getByText('sync.tr.title'))
}

function fillPhoneAndPin() {
  fireEvent.change(screen.getByLabelText('sync.tr.phone'), {
    target: { value: '+33612345678' },
  })
  const pinInput = screen.getAllByRole('textbox').find((input) => input !== screen.getByLabelText('sync.tr.phone'))
  if (!pinInput) throw new Error('PIN input not found')
  fireEvent.change(pinInput, { target: { value: '1234' } })
}

describe('AddAccountModal Trade Republic wizard', () => {
  beforeEach(() => {
    // The OTP/PIN field (input-otp) schedules an internal setTimeout that it
    // does not cancel on unmount. With real timers it can fire after jsdom is
    // torn down, throwing "window is not defined" as an unhandled error that
    // fails the whole vitest run (all assertions still pass). Fake timers keep
    // that callback under our control so afterEach can drop it before teardown.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    createAccount.mockReset()
    updateDebtMetadata.mockReset()
    initiateTrAuth.mockReset()
    completeTrAuth.mockReset()
  })

  afterEach(() => {
    vi.clearAllTimers()
    vi.useRealTimers()
  })

  it('keeps the credentials form visible when initiation fails', async () => {
    initiateTrAuth.mockImplementation((_params, options: { onError: (error: unknown) => void }) => {
      options.onError({ response: { status: 500, data: { detail: 'PIN_INVALID' } } })
    })

    renderTradeRepublicWizard()
    fillPhoneAndPin()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText('sync.tr.errors.invalidPin')).toBeInTheDocument()
    expect(screen.getByLabelText('sync.tr.phone')).toBeInTheDocument()
    expect(screen.queryByText('sync.tr.tan')).not.toBeInTheDocument()
    expect(completeTrAuth).not.toHaveBeenCalled()
  })

  it('keeps the TAN form visible when completion fails', async () => {
    initiateTrAuth.mockImplementation((_params, options: { onSuccess: (data: { processId: string }) => void }) => {
      options.onSuccess({ processId: 'process-123' })
    })
    completeTrAuth.mockImplementation((_params, options: { onError: (error: unknown) => void }) => {
      options.onError({ response: { status: 500, data: { detail: 'VALIDATION_CODE_INVALID' } } })
    })

    renderTradeRepublicWizard()
    fillPhoneAndPin()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    const tanInput = await screen.findByLabelText('sync.tr.tan')
    fireEvent.change(tanInput, { target: { value: '9876' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText('sync.tr.errors.invalidTan')).toBeInTheDocument()
    expect(screen.getByText('sync.tr.tan')).toBeInTheDocument()
    await waitFor(() => expect(tanInput).toHaveValue(''))
    expect(completeTrAuth).toHaveBeenCalledWith(
      { processId: 'process-123', tan: '9876' },
      expect.any(Object),
    )
  })
})

describe('AddAccountModal exchange wizard', () => {
  beforeEach(() => {
    addCryptoExchange.mockReset()
  })

  function openExchangeWizard() {
    render(<AddAccountModal open onOpenChange={vi.fn()} />)
    fireEvent.click(screen.getByText('sync.exchanges.title'))
  }

  it('asks Meria for an API key only, and posts no secret', () => {
    // The backend rejects a stray secret for a single-key exchange with a 400, so hiding the
    // field is what keeps that error unreachable.
    openExchangeWizard()

    fireEvent.change(screen.getByLabelText('sync.exchanges.apiKey'), { target: { value: 'k' } })
    fireEvent.change(screen.getByLabelText('sync.exchanges.apiSecret'), { target: { value: 's' } })
    fireEvent.click(screen.getByRole('button', { name: 'MERIA' }))

    expect(screen.queryByLabelText('sync.exchanges.apiSecret')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'sync.exchanges.connect' }))

    expect(addCryptoExchange).toHaveBeenCalledWith(
      { type: 'MERIA', apiKey: 'k', apiSecret: undefined },
      expect.any(Object),
    )
  })

  it('still sends both credentials for Binance', () => {
    openExchangeWizard()

    fireEvent.change(screen.getByLabelText('sync.exchanges.apiKey'), { target: { value: 'k' } })
    fireEvent.change(screen.getByLabelText('sync.exchanges.apiSecret'), { target: { value: 's' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.exchanges.connect' }))

    expect(addCryptoExchange).toHaveBeenCalledWith(
      { type: 'BINANCE', apiKey: 'k', apiSecret: 's' },
      expect.any(Object),
    )
  })
})

describe('AddAccountModal bank wizard', () => {
  function openBankWizardWith(institutions: unknown[]) {
    institutionSearch.current = { data: institutions, isError: false, isLoading: false, error: null }
    render(<AddAccountModal open onOpenChange={vi.fn()} />)
    fireEvent.click(screen.getByText('sync.banks.title'))
    // The list only renders past the 2-character search gate.
    fireEvent.change(screen.getByPlaceholderText('sync.banks.searchPlaceholder'), {
      target: { value: 'swan' },
    })
  }

  afterEach(() => {
    institutionSearch.current = { data: undefined, isError: false, isLoading: false, error: null }
  })

  /** Swan and other BaaS providers are business-only; the badge warns before the OAuth redirect. */
  it('marks a business-only institution with a Pro badge', () => {
    openBankWizardWith([
      { id: 'Swan::FR::business', name: 'Swan', bic: 'SWNBFR22', logoUrl: null, country: 'FR', psuType: 'business' },
    ])

    expect(screen.getByText('Swan')).toBeInTheDocument()
    expect(screen.getByText('sync.banks.proBadge')).toBeInTheDocument()
  })

  it('leaves a retail institution unbadged', () => {
    openBankWizardWith([
      {
        id: 'BNP Paribas::FR::personal',
        name: 'BNP Paribas',
        bic: 'BNPAFRPP',
        logoUrl: null,
        country: 'FR',
        psuType: 'personal',
      },
    ])

    expect(screen.getByText('BNP Paribas')).toBeInTheDocument()
    expect(screen.queryByText('sync.banks.proBadge')).not.toBeInTheDocument()
  })

  /**
   * resolvePsuType falls through to the provider's own first value when it offers
   * neither personal nor business, and a requisition written before PSU types were
   * modelled carries none at all. The badge claims a specific thing about a bank --
   * an unrecognised value is not evidence for it, so it stays off.
   */
  it('leaves an institution with an unknown PSU type unbadged', () => {
    openBankWizardWith([
      { id: 'Swan::FR::corporate', name: 'Swan', bic: 'SWNBFR22', logoUrl: null, country: 'FR', psuType: 'corporate' },
    ])

    expect(screen.getByText('Swan')).toBeInTheDocument()
    expect(screen.queryByText('sync.banks.proBadge')).not.toBeInTheDocument()
  })
})

describe('AddAccountModal Bourse Direct wizard', () => {
  it('opens the connector and closes after authentication', () => {
    const onOpenChange = vi.fn()
    render(<AddAccountModal open onOpenChange={onOpenChange} />)

    fireEvent.click(screen.getByText('sync.bourseDirect.title'))
    fireEvent.click(screen.getByRole('button', { name: 'bourse-direct-wizard' }))

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

describe('AddAccountModal Amundi wizard', () => {
  it('opens the connector and closes after authentication', () => {
    const onOpenChange = vi.fn()
    render(<AddAccountModal open onOpenChange={onOpenChange} />)

    fireEvent.click(screen.getByText('sync.amundi.title'))
    fireEvent.click(screen.getByRole('button', { name: 'amundi-wizard' }))

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

describe('AddAccountModal IBKR wizard', () => {
  it('opens the connector and closes after authentication', () => {
    const onOpenChange = vi.fn()
    render(<AddAccountModal open onOpenChange={onOpenChange} />)

    fireEvent.click(screen.getByText('sync.ibkr.title'))
    fireEvent.click(screen.getByRole('button', { name: 'ibkr-wizard' }))

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

describe('AddAccountModal Finary wizard', () => {
  beforeEach(() => {
    finaryLogin.mockReset()
    previewFinaryApi.mockReset()
    importFinary.mockReset()
    executeFinaryApiSync.mockReset()
    finaryStatus.current = { connected: false }
  })

  function openFinaryWizard() {
    render(<AddAccountModal open onOpenChange={vi.fn()} />)
    fireEvent.click(screen.getByText('sync.finary.title'))
  }

  /**
   * The preview's onSuccess set `isApiSync` and then executed in the same tick, so the closure
   * still read the state as false and posted the API sync token to the file-import endpoint,
   * whose cache has never seen it ("Preview expired or invalid").
   */
  it('executes an auto-mapped API sync through the API endpoint, not the file import', () => {
    finaryStatus.current = { connected: true, maskedEmail: 'j***@example.com' }
    const mappings = [{ finaryId: 'f1', finaryName: 'Livret', finaryCategory: 'Savings', action: 'MAP_EXISTING', targetAccountId: 7 }]
    previewFinaryApi.mockImplementation((_totp, options: { onSuccess: (data: unknown) => void }) => {
      options.onSuccess({
        accounts: [], existingPicsouAccounts: [], totalTransactionCount: 0,
        fileToken: 'api-token', autoMapped: true, suggestedMappings: mappings,
      })
    })
    openFinaryWizard()

    fireEvent.click(screen.getByRole('button', { name: 'sync.finary.sync' }))

    expect(executeFinaryApiSync).toHaveBeenCalledWith(
      { syncToken: 'api-token', mappings },
      expect.any(Object),
    )
    expect(importFinary).not.toHaveBeenCalled()
  })

  it('shows the backend reason when the Finary login is rejected', async () => {
    finaryLogin.mockImplementation((_creds, options: { onError: (error: unknown) => void }) => {
      options.onError({
        response: { status: 422, data: { detail: 'Finary sign-in failed. Please check your credentials and try again.' } },
      })
    })
    openFinaryWizard()

    fireEvent.change(screen.getByLabelText('sync.finary.email'), { target: { value: 'j@example.com' } })
    fireEvent.change(screen.getByLabelText('sync.finary.password'), { target: { value: 'secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.finary.login' }))

    expect(await screen.findByText('Finary sign-in failed. Please check your credentials and try again.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'sync.finary.login' })).toBeEnabled()
  })

  it('names the Finary outage on a 502 instead of the generic server error', async () => {
    finaryLogin.mockImplementation((_creds, options: { onError: (error: unknown) => void }) => {
      options.onError({ response: { status: 502, data: { detail: 'Finary service is temporarily unavailable' } } })
    })
    openFinaryWizard()

    fireEvent.change(screen.getByLabelText('sync.finary.email'), { target: { value: 'j@example.com' } })
    fireEvent.change(screen.getByLabelText('sync.finary.password'), { target: { value: 'secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.finary.login' }))

    expect(await screen.findByText('sync.finary.serviceUnavailable')).toBeInTheDocument()
  })

  /** Axios' own message is boilerplate ("Request failed with status code 500"), never a banner. */
  it('translates a 500 on preview rather than echoing the axios message', async () => {
    finaryStatus.current = { connected: true }
    previewFinaryApi.mockImplementation((_totp, options: { onError: (error: unknown) => void }) => {
      const err = Object.assign(new Error('Request failed with status code 500'), { response: { status: 500, data: {} } })
      options.onError(err)
    })
    openFinaryWizard()

    fireEvent.click(screen.getByRole('button', { name: 'sync.finary.sync' }))

    expect(await screen.findByText('common.errors.serverError')).toBeInTheDocument()
    expect(screen.queryByText(/Request failed/)).not.toBeInTheDocument()
    expect(screen.queryByText('common.retry')).not.toBeInTheDocument()
  })
})

describe('AddAccountModal reopening', () => {
  /**
   * The parent opens this dialog by flipping `open`, for which Radix never calls onOpenChange,
   * so a reset-on-open never ran: Escape from a wizard reopened the dialog on that wizard.
   */
  it('returns to the source selector when the dialog is dismissed from a wizard', () => {
    const onOpenChange = vi.fn()
    render(<AddAccountModal open onOpenChange={onOpenChange} />)
    fireEvent.click(screen.getByText('sync.exchanges.title'))
    expect(screen.getByLabelText('sync.exchanges.apiKey')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Close' }))

    expect(onOpenChange).toHaveBeenCalledWith(false)
    expect(screen.queryByLabelText('sync.exchanges.apiKey')).not.toBeInTheDocument()
    expect(screen.getByText('addAccount.title')).toBeInTheDocument()
    expect(screen.getByText('sync.exchanges.title')).toBeInTheDocument()
  })
})

describe('AddAccountModal manual loan', () => {
  beforeEach(() => {
    createAccount.mockReset()
    updateDebtMetadata.mockReset()
    institutionSearch.current = { data: undefined, isError: false, isLoading: false, error: null }
  })

  function openManualForm() {
    render(<AddAccountModal open onOpenChange={vi.fn()} />)
    fireEvent.click(screen.getByText('addAccount.manual'))
    fireEvent.change(screen.getByLabelText('accounts.accountName'), { target: { value: 'Prêt immo' } })
    fireEvent.change(screen.getByLabelText('accounts.accountType'), { target: { value: 'LOAN' } })
    fireEvent.change(screen.getByLabelText('debt.borrowedAmount'), { target: { value: '100000' } })
  }

  /**
   * A loan is two requests: create the account, then save its debt metadata. When the second
   * one failed the dialog used to stay open with no message, and Save again created a twin.
   */
  it('shows the failure and reuses the created account on retry', async () => {
    createAccount.mockResolvedValue({ id: 42 })
    updateDebtMetadata
      .mockRejectedValueOnce({ response: { status: 500, data: {} } })
      .mockResolvedValueOnce({})
    openManualForm()

    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('common.errors.serverError')
    expect(screen.getByLabelText('accounts.accountName')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))

    await waitFor(() => expect(updateDebtMetadata).toHaveBeenCalledTimes(2))
    expect(createAccount).toHaveBeenCalledTimes(1)
    expect(updateDebtMetadata.mock.calls[1][0]).toMatchObject({ id: 42 })
    await waitFor(() => expect(screen.queryByLabelText('accounts.accountName')).not.toBeInTheDocument())
  })
})
