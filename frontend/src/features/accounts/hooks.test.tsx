import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const { holdings, prices, list, addSnapshot, deleteAccount } = vi.hoisted(() => ({
  holdings: vi.fn(),
  prices: vi.fn(),
  list: vi.fn(),
  addSnapshot: vi.fn(),
  deleteAccount: vi.fn(),
}))

vi.mock('./api', () => ({
  accountsApi: { holdings, prices, list, addSnapshot, delete: deleteAccount },
}))

const {
  WEALTH_QUERY_KEYS,
  portfolioLineLabel,
  useAddSnapshot,
  useDeleteAccount,
  useHoldingsWithLivePrices,
  usePortfolio,
} = await import('./hooks')
const { syncKeys } = await import('@/features/sync/hooks')

function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

describe('useHoldingsWithLivePrices', () => {
  beforeEach(() => {
    holdings.mockReset()
    prices.mockReset()
  })

  it('uses the backend EUR cost basis when enriching a foreign quote', async () => {
    holdings.mockResolvedValue([
      {
        ticker: 'US',
        name: 'US share',
        quantity: 2,
        averageBuyIn: 80,
        currentPrice: 100,
        quoteCurrency: 'USD',
        currentValueEur: 180,
        costBasisEur: 140,
        pnlEur: 40,
        pnlPercent: 28.57,
        priceUpdatedAt: '2026-07-20T08:00:00Z',
      },
    ])
    prices.mockResolvedValue({ US: 95 })

    const { result } = renderHook(() => useHoldingsWithLivePrices(42), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(1)
    expect(result.current.data?.[0]).toMatchObject({
      currentPrice: 95,
      quoteCurrency: 'EUR',
      currentValueEur: 190,
      costBasisEur: 140,
      pnlEur: 50,
    })
    expect(result.current.data?.[0].pnlPercent).toBeCloseTo(35.714, 3)
  })
})

describe('usePortfolio', () => {
  beforeEach(() => {
    holdings.mockReset()
    prices.mockReset()
    list.mockReset()
  })

  const brokerage = {
    id: 1,
    name: 'PEA',
    type: 'PEA',
    color: '#000',
    currentBalanceEur: 1000,
  }

  it('fails the query when an account\'s holdings cannot be loaded', async () => {
    list.mockResolvedValue([brokerage])
    holdings.mockRejectedValue(new Error('boom'))
    prices.mockResolvedValue({})

    const { result } = renderHook(() => usePortfolio(), { wrapper: makeWrapper() })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.data).toBeUndefined()
  })

  it('keeps backend values when only the live-price call fails', async () => {
    list.mockResolvedValue([brokerage])
    holdings.mockResolvedValue([
      {
        ticker: 'AAA',
        name: 'Fund',
        quantity: 1,
        averageBuyIn: 10,
        currentPrice: 12,
        quoteCurrency: 'EUR',
        currentValueEur: 12,
        costBasisEur: 10,
        pnlEur: 2,
        pnlPercent: 20,
        priceUpdatedAt: '2026-07-20T08:00:00Z',
      },
    ])
    prices.mockRejectedValue(new Error('price provider down'))

    const { result } = renderHook(() => usePortfolio(), { wrapper: makeWrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.[0]).toMatchObject({ valueEur: 12, pnlEur: 2 })
  })
})

describe('usePortfolio cash line', () => {
  beforeEach(() => {
    holdings.mockReset()
    prices.mockReset()
    list.mockReset()
  })

  it('marks the aggregated cash line so the UI labels it through i18n', async () => {
    list.mockResolvedValue([
      { id: 4, name: 'BNP', type: 'CHECKING', color: '#000', currentBalanceEur: 100 },
      { id: 5, name: 'Bourso', type: 'SAVINGS', color: '#000', currentBalanceEur: 50 },
    ])

    const { result } = renderHook(() => usePortfolio(), { wrapper: makeWrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    const cash = result.current.data?.find(l => l.id === 'cash-aggregated')
    expect(cash).toMatchObject({ isCash: true, ticker: 'EUR', valueEur: 150 })
    // No hardcoded French label reaches the other locales: the name is the bare currency code.
    expect(cash?.name).toBe('EUR')
    const t = ((key: string) => `<${key}>`) as unknown as Parameters<typeof portfolioLineLabel>[1]
    expect(portfolioLineLabel(cash!, t)).toBe('<portfolio.cash>')
  })

  it('leaves instrument lines labelled by their backend name', () => {
    const t = ((key: string) => `<${key}>`) as unknown as Parameters<typeof portfolioLineLabel>[1]
    const line = { name: 'MSCI World', isCash: false } as Parameters<typeof portfolioLineLabel>[0]
    expect(portfolioLineLabel(line, t)).toBe('MSCI World')
  })
})

function makeClientHarness() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return { client, wrapper }
}

describe('wealth invalidations', () => {
  beforeEach(() => {
    addSnapshot.mockReset()
    deleteAccount.mockReset()
  })

  it('covers every key the dashboard renders net worth from', () => {
    expect(WEALTH_QUERY_KEYS.map(k => k[0])).toEqual(
      expect.arrayContaining(['accounts', 'dashboard', 'history', 'pnl', 'net-worth-intraday', 'real-estate']),
    )
  })

  it('useAddSnapshot refetches the list, dashboard and history — not only the detail page', async () => {
    addSnapshot.mockResolvedValue(undefined)
    const { client, wrapper } = makeClientHarness()
    const invalidations = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useAddSnapshot(), { wrapper })

    await act(() => result.current.mutateAsync({ id: 4, balance: 3100, date: '2026-09-01' }))

    expect(addSnapshot).toHaveBeenCalledWith(4, 3100, '2026-09-01')
    expect(invalidations).toHaveBeenCalledWith({ queryKey: ['accounts', 4, 'history'] })
    for (const queryKey of WEALTH_QUERY_KEYS) {
      expect(invalidations).toHaveBeenCalledWith({ queryKey: [...queryKey] })
    }
  })

  it('useDeleteAccount also drops the sync-connection lists and the loan summary', async () => {
    deleteAccount.mockResolvedValue(undefined)
    const { client, wrapper } = makeClientHarness()
    const invalidations = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useDeleteAccount(), { wrapper })

    await act(() => result.current.mutateAsync(7))

    // The literal in the hook must stay equal to the sync feature's key root.
    expect(invalidations).toHaveBeenCalledWith({ queryKey: [...syncKeys.all] })
    expect(invalidations).toHaveBeenCalledWith({ queryKey: ['goals'] })
    expect(invalidations).toHaveBeenCalledWith({ queryKey: ['loan-summary', 7] })
    for (const queryKey of WEALTH_QUERY_KEYS) {
      expect(invalidations).toHaveBeenCalledWith({ queryKey: [...queryKey] })
    }
  })
})
