import '@testing-library/jest-dom'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { PortfolioLine } from '@/features/accounts/hooks'

const usePriceHistory = vi.fn(() => ({ data: [], isLoading: false }))

vi.mock('@/features/accounts/hooks', () => ({
  usePriceHistory: (...args: unknown[]) => usePriceHistory(...(args as [])),
  portfolioLineLabel: (line: PortfolioLine, t: (key: string) => string) => (line.isCash ? t('portfolio.cash') : line.name),
}))

vi.mock('@/components/shared/HoldingInsightSection', () => ({
  HoldingInsightSection: ({ ticker }: { ticker: string | null }) => <div data-testid="insight">{ticker ?? 'none'}</div>,
}))

vi.mock('@/components/shared/NetWorthChart', () => ({
  NetWorthChart: () => <div data-testid="chart" />,
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

const { HoldingDetailModal } = await import('./HoldingDetailModal')

const BASE: PortfolioLine = {
  id: '2-AAPL',
  name: 'Apple',
  isCash: false,
  ticker: 'AAPL',
  quantity: 8,
  accountName: 'PEA',
  accountType: 'PEA',
  accountColor: '#000',
  valueEur: 1600,
  costBasisEur: 1000,
  averageBuyIn: 125,
  quoteCurrency: 'EUR',
  pnlEur: 600,
  pnlPercent: 60,
  priceUpdatedAt: '2026-09-05',
}

describe('HoldingDetailModal', () => {
  beforeEach(() => { usePriceHistory.mockClear() })

  it('shows the aggregated cash line as a plain total, without a unit price or a price lookup', () => {
    // usePortfolio appends a synthetic cash line with quantity 0 and the pseudo-ticker 'EUR'.
    // The modal used to open it in price mode: value / 0 rendered as "∞ €" and the history
    // query went off to fetch prices for "EUR".
    render(
      <HoldingDetailModal
        line={{ ...BASE, id: 'cash-aggregated', name: 'EUR', isCash: true, ticker: 'EUR', quantity: 0, valueEur: 1234, costBasisEur: null, averageBuyIn: null, pnlEur: null, pnlPercent: null }}
        onClose={vi.fn()}
      />,
    )

    expect(document.body.textContent).not.toContain('∞')
    expect(screen.getByText('holdings.totalValue')).toBeInTheDocument()
    expect(screen.queryByText('holdings.unitPrice')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'holdings.assetPrice' })).not.toBeInTheDocument()
    expect(usePriceHistory).toHaveBeenCalledWith(null, expect.anything(), expect.anything())
    expect(screen.getByTestId('insight')).toHaveTextContent('none')
  })

  it('opens a priced holding on its unit price with the mode toggle', () => {
    render(<HoldingDetailModal line={BASE} onClose={vi.fn()} />)

    expect(screen.getByText('holdings.unitPrice')).toBeInTheDocument()
    expect(screen.getByText('€200.00')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'holdings.assetPrice' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'holdings.myPosition' })).toHaveAttribute('aria-pressed', 'false')
    expect(usePriceHistory).toHaveBeenCalledWith('AAPL', expect.anything(), expect.anything())
    expect(screen.getByTestId('insight')).toHaveTextContent('AAPL')
  })

  it('formats the quantity, buy-in and gain in the app locale', () => {
    render(<HoldingDetailModal line={{ ...BASE, quantity: 1234.5, averageBuyIn: 125.5 }} onClose={vi.fn()} />)

    expect(screen.getByText('1,234.5')).toBeInTheDocument()
    expect(screen.getByText(/125\.50 holdings\.perShare/)).toBeInTheDocument()
    expect(screen.getByText('+60.0%')).toBeInTheDocument()
  })
})
