import { describe, expect, it } from 'vitest'
import { sumWeightedBalances } from './totals'
import type { Account, AccountType } from '@/types/api'

function account(overrides: Partial<Account> & { currentBalanceEur: number }): Account {
  return {
    id: 1,
    name: 'Account',
    type: 'CHECKING' as AccountType,
    provider: null,
    currency: 'EUR',
    currentBalance: overrides.currentBalanceEur,
    lastSyncedAt: null,
    isManual: true,
    color: '#6366f1',
    ticker: null,
    logoUrl: null,
    logoKey: null,
    createdAt: '2026-01-01',
    ...overrides,
  }
}

describe('sumWeightedBalances', () => {
  it('counts an account without a share at its full value', () => {
    expect(sumWeightedBalances([account({ currentBalanceEur: 1000 })])).toBe(1000)
  })

  it('treats a null sharePercent as sole ownership', () => {
    expect(sumWeightedBalances([account({ currentBalanceEur: 1000, sharePercent: null })])).toBe(1000)
  })

  it('applies the viewer share to a co-owned account', () => {
    const accounts = [
      account({ id: 1, type: 'REAL_ESTATE', currentBalanceEur: 400_000, sharePercent: 50 }),
      account({ id: 2, currentBalanceEur: 2_000 }),
    ]
    expect(sumWeightedBalances(accounts)).toBe(202_000)
  })

  it('subtracts a loan, share-weighted like any other account', () => {
    const accounts = [
      account({ id: 1, type: 'REAL_ESTATE', currentBalanceEur: 400_000, sharePercent: 50 }),
      account({ id: 2, type: 'LOAN', currentBalanceEur: 200_000, sharePercent: 50 }),
    ]
    expect(sumWeightedBalances(accounts)).toBe(100_000)
  })

  it('is zero for an empty list', () => {
    expect(sumWeightedBalances([])).toBe(0)
  })
})
