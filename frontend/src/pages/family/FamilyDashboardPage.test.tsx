import '@testing-library/jest-dom'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { FamilyDashboard } from '@/features/family/api'

const { useFamilyDashboard } = vi.hoisted(() => ({ useFamilyDashboard: vi.fn() }))

vi.mock('@/features/family/hooks', () => ({ useFamilyDashboard }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'fr', resolvedLanguage: 'fr' },
  }),
}))

const { FamilyDashboardPage } = await import('./FamilyDashboardPage')

const USD_ACCOUNT = {
  id: 7,
  ownerName: 'Chloé',
  name: 'Brokerage',
  type: 'COMPTE_TITRES',
  currency: 'USD',
  balance: 1234.5,
  balanceEur: 1100,
}

function dashboard(overrides: Partial<FamilyDashboard> = {}): FamilyDashboard {
  return {
    sharedAccounts: [USD_ACCOUNT],
    sharedGoals: [],
    totalSharedNetWorth: 1100,
    ...overrides,
  }
}

beforeEach(() => {
  useFamilyDashboard.mockReset()
  document.documentElement.lang = 'fr'
})

describe('FamilyDashboardPage', () => {
  it('renders a retry surface instead of crashing when the query fails', () => {
    // `const dashboard = data!` used to throw a TypeError here (a network error has
    // no response, so the global 5xx redirect never fires) and took the whole route
    // to the ErrorBoundary.
    const refetch = vi.fn()
    useFamilyDashboard.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: { response: { status: 503 } },
      refetch,
    })

    render(<FamilyDashboardPage />)

    expect(screen.getByText('common.errors.serverError')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'common.retry' }))
    expect(refetch).toHaveBeenCalledOnce()
  })

  it('formats a foreign-currency balance in its own currency and translates the account type', () => {
    useFamilyDashboard.mockReturnValue({
      data: dashboard(),
      isLoading: false,
      isError: false,
      error: null,
      refetch: vi.fn(),
    })

    render(<FamilyDashboardPage />)

    // Used to read "1 234,50 € USD": the EUR default currency plus the code appended.
    const secondary = screen.getByText(/1\s?234,50/)
    expect(secondary.textContent).toContain('$')
    expect(secondary.textContent).not.toContain('€')
    // The raw enum used to be printed verbatim in every language.
    expect(screen.queryByText('COMPTE_TITRES')).not.toBeInTheDocument()
    expect(screen.getByText('accountTypes.compteTitres')).toBeInTheDocument()
  })
})
