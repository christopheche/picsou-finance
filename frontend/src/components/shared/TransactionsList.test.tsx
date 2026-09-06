import '@testing-library/jest-dom'
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { TransactionsList, TRANSACTIONS_PAGE_SIZE } from './TransactionsList'
import type { Transaction } from '@/types/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) =>
      ({
        'accounts.buy': 'Achat',
        'accounts.sell': 'Vente',
        'accounts.dividend': 'Dividende',
        'accounts.fee': 'Frais',
      })[key] ?? key,
    i18n: { language: 'fr', resolvedLanguage: 'fr' },
  }),
}))

function transaction(overrides: Partial<Transaction>): Transaction {
  return {
    id: 1,
    date: '2026-03-04',
    description: 'Transaction',
    amount: 10,
    type: null,
    category: null,
    nativeCurrency: 'EUR',
    isManual: false,
    txType: null,
    ticker: null,
    name: null,
    quantity: null,
    pricePerUnit: null,
    fees: null,
    ...overrides,
  }
}

describe('TransactionsList', () => {
  it('keeps single-year date headings compact', () => {
    render(<TransactionsList transactions={[transaction({ description: 'Single year' })]} />)

    expect(screen.getByText('Single year')).toBeInTheDocument()
    expect(screen.queryByText(/2026/)).not.toBeInTheDocument()
  })

  it('includes the year in every heading when the list spans multiple years', () => {
    render(
      <TransactionsList
        transactions={[
          transaction({ description: 'Recent transaction' }),
          transaction({ id: 2, date: '2025-03-04', description: 'Older transaction' }),
        ]}
      />,
    )

    expect(screen.getByText(/2026/)).toBeInTheDocument()
    expect(screen.getByText(/2025/)).toBeInTheDocument()
  })

  it.each([
    ['BUY', 'Achat'],
    ['SELL', 'Vente'],
    ['DIVIDEND', 'Dividende'],
    ['FEE', 'Frais'],
  ] satisfies [NonNullable<Transaction['txType']>, string][])(
    'renders the %s fallback through frontend i18n',
    (txType, label) => {
      render(
        <TransactionsList
          transactions={[
            transaction({
              isManual: true,
              txType,
              ticker: 'AAPL',
              description: 'AAPL',
            }),
          ]}
        />,
      )

      expect(screen.getByText(`${label} AAPL`)).toBeInTheDocument()
    },
  )

  it('keeps a provider description on synced transactions', () => {
    render(
      <TransactionsList
        transactions={[
          transaction({
            txType: 'DIVIDEND',
            ticker: 'AAPL',
            description: 'Provider dividend payment',
          }),
        ]}
      />,
    )

    expect(screen.getByText('Provider dividend payment')).toBeInTheDocument()
    expect(screen.queryByText('Dividende AAPL')).not.toBeInTheDocument()
  })

  it('keeps the stored description when a manual ticker row has no txType member at all', () => {
    // The backend omits null members (`non_null`), so a null txType never arrives as `null` —
    // it is simply absent. A strict `=== null` guard never matched and the row rendered as
    // "undefined AAPL" through the label map.
    const withoutTxType = Object.fromEntries(
      Object.entries(transaction({ isManual: true, ticker: 'AAPL', description: 'Achat via API' }))
        .filter(([key]) => key !== 'txType'),
    ) as Transaction

    render(<TransactionsList transactions={[withoutTxType]} />)

    expect(screen.getByText('Achat via API')).toBeInTheDocument()
  })

  it('renders one page of rows and extends it on demand', () => {
    const rows = Array.from({ length: 1000 }, (_, i) =>
      transaction({ id: i + 1, date: `2026-01-${String((i % 28) + 1).padStart(2, '0')}`, description: `Row ${i}` }))

    render(<TransactionsList transactions={rows} />)

    expect(screen.getAllByText(/^Row \d+$/)).toHaveLength(TRANSACTIONS_PAGE_SIZE)
    fireEvent.click(screen.getByRole('button', { name: 'common.showMore' }))
    expect(screen.getAllByText(/^Row \d+$/)).toHaveLength(2 * TRANSACTIONS_PAGE_SIZE)
  })

  it('restarts from the first page when the search changes', () => {
    const rows = Array.from({ length: 500 }, (_, i) => transaction({ id: i + 1, description: `Row ${i}` }))

    render(<TransactionsList transactions={rows} />)
    fireEvent.click(screen.getByRole('button', { name: 'common.showMore' }))
    expect(screen.getAllByText(/^Row \d+$/)).toHaveLength(400)

    fireEvent.change(screen.getByPlaceholderText('common.search'), { target: { value: 'Row' } })

    expect(screen.getAllByText(/^Row \d+$/)).toHaveLength(TRANSACTIONS_PAGE_SIZE)
  })

  it('searches the localized fallback description', () => {
    render(
      <TransactionsList
        transactions={[
          transaction({
            isManual: true,
            txType: 'DIVIDEND',
            ticker: 'AAPL',
            description: 'AAPL',
          }),
        ]}
      />,
    )

    fireEvent.change(screen.getByPlaceholderText('common.search'), {
      target: { value: 'Dividende' },
    })

    expect(screen.getByText('Dividende AAPL')).toBeInTheDocument()
  })
})
