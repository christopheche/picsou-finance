import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeAll, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { AddTransactionModal } from './AddTransactionModal'

// jsdom lacks matchMedia, which DateInput probes for the touch/native date picker.
beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false, media: query, onchange: null,
      addEventListener: () => {}, removeEventListener: () => {},
      addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
    }),
  })
})

vi.mock('@tanstack/react-query', () => ({
  useQuery: () => ({ data: undefined }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: unknown) => (typeof opts === 'string' ? opts : key),
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

function fillInvestment({ qty, price, fees }: { qty: string; price: string; fees: string }) {
  fireEvent.change(screen.getByPlaceholderText('accounts.tickerOrIsinPlaceholder'), { target: { value: 'AAPL' } })
  // NumericInput renders type="text" inputMode="decimal": quantity, unit price, fees in order.
  const numeric = document.querySelectorAll('input[inputmode="decimal"]')
  fireEvent.change(numeric[0], { target: { value: qty } })
  fireEvent.change(numeric[1], { target: { value: price } })
  fireEvent.change(numeric[2], { target: { value: fees } })
}

describe('AddTransactionModal default date', () => {
  afterEach(() => { vi.useRealTimers(); vi.unstubAllEnvs() })

  it('pre-fills the local calendar day, not the UTC one', async () => {
    // 22:30Z on the 5th is 00:30 on the 6th in Paris. The default used to come from
    // toISOString(), so a deposit entered just after midnight was dated yesterday.
    vi.stubEnv('TZ', 'Europe/Paris')
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-09-05T22:30:00Z'))
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={1} accountType="CHECKING" onSubmit={onSubmit} />)

    fireEvent.change(document.querySelector('input[inputmode="decimal"]')!, { target: { value: '50' } })
    fireEvent.change(screen.getAllByRole('textbox').find(el => (el as HTMLInputElement).value === '')!, { target: { value: 'Salary' } })
    fireEvent.click(screen.getByRole('button', { name: 'common.create' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce())
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ date: '2026-09-06', description: 'Salary', amount: 50 }))
  })
})

describe('AddTransactionModal fees', () => {
  it('BUY amount includes fees: -(qty*price + fees)', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={2} accountType="PEA" onSubmit={onSubmit} />)

    fillInvestment({ qty: '10', price: '100', fees: '5' })
    fireEvent.click(screen.getByRole('button', { name: 'common.create' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce())
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      txType: 'BUY', ticker: 'AAPL', description: 'AAPL',
      quantity: 10, pricePerUnit: 100, fees: 5, amount: -1005,
    }))
  })

  it('SELL amount nets fees: +(qty*price - fees)', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={2} accountType="PEA" onSubmit={onSubmit} />)

    fireEvent.click(screen.getByRole('button', { name: 'accounts.sell' }))
    fillInvestment({ qty: '10', price: '100', fees: '5' })
    fireEvent.click(screen.getByRole('button', { name: 'common.create' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce())
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      txType: 'SELL', ticker: 'AAPL', description: 'AAPL', amount: 995, fees: 5,
    }))
  })
})
