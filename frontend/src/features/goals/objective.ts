import type { GoalMonthEntry } from '@/types/api'

/**
 * The objective a month is measured against: the user's per-month override when one is
 * set, otherwise the auto-computed `monthlyNeeded`. An override changes the target, never
 * the amount saved (`effective`), so it is the denominator of every progress ratio here —
 * the same reading the backend's on-track badge uses (`GoalService.isOnTrackFromPastMonths`).
 */
export function monthObjective(entry: Pick<GoalMonthEntry, 'objective' | 'override'>): number {
  return entry.override ?? entry.objective
}
