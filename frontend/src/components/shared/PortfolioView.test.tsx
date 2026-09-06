import '@testing-library/jest-dom'
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import type { PortfolioLine } from '@/features/accounts/hooks'
import { formatCurrency } from '@/lib/utils'

const LINES: PortfolioLine[] = [
  {
    id: '2-AAPL', name: 'Apple', isCash: false, ticker: 'AAPL', quantity: 8, accountName: 'PEA', accountType: 'PEA',
    accountColor: '#000', valueEur: 3200, costBasisEur: 1000, averageBuyIn: 125, quoteCurrency: 'EUR',
    pnlEur: 2200, pnlPercent: 220, priceUpdatedAt: null,
  },
  {
    id: '2-MSFT', name: 'Microsoft', isCash: false, ticker: 'MSFT', quantity: 10, accountName: 'PEA', accountType: 'PEA',
    accountColor: '#000', valueEur: 46800, costBasisEur: 40000, averageBuyIn: 4000, quoteCurrency: 'EUR',
    pnlEur: 6800, pnlPercent: 17, priceUpdatedAt: null,
  },
]

vi.mock('@/features/accounts/hooks', () => ({
  usePortfolio: () => ({ data: LINES, isLoading: false, isError: false, error: null, refetch: vi.fn() }),
  portfolioLineLabel: (line: PortfolioLine) => line.name,
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

const { PortfolioView } = await import('./PortfolioView')

function headerTotal(): string {
  return screen.getByText('portfolio.totalValue').nextElementSibling?.textContent ?? ''
}

describe('PortfolioView', () => {
  it('keeps the total value over the whole portfolio while a search narrows the rows', () => {
    // The figure is labelled "total value"; summing the search-filtered rows silently turned it
    // into a subtotal of whatever matched, under the same label.
    render(<PortfolioView />)
    const total = formatCurrency(50000, 'EUR', 'en-US')
    expect(headerTotal()).toBe(total)

    fireEvent.change(screen.getByPlaceholderText('portfolio.searchPlaceholder'), { target: { value: 'AAPL' } })

    expect(screen.getByText('Apple')).toBeInTheDocument()
    expect(screen.queryByText('Microsoft')).not.toBeInTheDocument()
    expect(headerTotal()).toBe(total)
  })

  it('marks the active sort button as pressed', () => {
    render(<PortfolioView />)

    expect(screen.getByRole('button', { name: 'portfolio.sortByValue' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'portfolio.sortByPnl' }))
    expect(screen.getByRole('button', { name: 'portfolio.sortByPnl' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'portfolio.sortByValue' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('formats the gain percentage in the app locale', () => {
    render(<PortfolioView />)

    expect(screen.getByText('+220.0%')).toBeInTheDocument()
  })
})
