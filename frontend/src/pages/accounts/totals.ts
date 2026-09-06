import type { Account } from '@/types/api'

/**
 * Net EUR total of a list of accounts, from the *viewer's* point of view.
 *
 * `currentBalanceEur` is the account's **full** value, never the viewer's share: the backend
 * deliberately returns the whole figure with `sharePercent` alongside it and weights only its
 * own aggregates (dashboard, history, real-estate summary) — see
 * [`account-ownership-shares.md`](../../../../docs/features/account-ownership-shares.md).
 * Anything summing balances on the client must therefore apply the share itself, or a
 * 50 %-owned house counts for 100 % here while the dashboard hero counts half of it.
 *
 * A missing or null `sharePercent` means sole ownership (100 %). Loans are liabilities and
 * subtract from the total.
 */
export function sumWeightedBalances(accounts: Account[]): number {
  return accounts.reduce((sum, account) => {
    const value = account.currentBalanceEur * ((account.sharePercent ?? 100) / 100)
    return account.type === 'LOAN' ? sum - value : sum + value
  }, 0)
}
