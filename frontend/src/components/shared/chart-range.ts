import { parseApiDate } from '@/lib/utils'
import type { TimeRange } from '@/components/shared/TimeRangeSelector'

/**
 * Keeps the points dated on or after the start of `range`, counted back from `now` in the
 * browser's local calendar.
 *
 * Shared by the history charts so they agree on where a range starts. Points are backend
 * `LocalDate`s and go through `parseApiDate`: parsed as an instant they land on UTC midnight,
 * so west of UTC a point dated exactly on the range start fell before a `from` built from
 * local midnight and was dropped. `ALL` (and an unknown range) returns the data untouched.
 */
export function filterByRange<T extends { date: string }>(data: T[], range: TimeRange, now = new Date()): T[] {
  if (range === 'ALL') return data
  let from: Date
  switch (range) {
    case '24H': from = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1); break
    case '7D': from = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 7); break
    case '1M': from = new Date(now.getFullYear(), now.getMonth() - 1, now.getDate()); break
    case '3M': from = new Date(now.getFullYear(), now.getMonth() - 3, now.getDate()); break
    case 'YTD': from = new Date(now.getFullYear(), 0, 1); break
    case '1Y': from = new Date(now.getFullYear() - 1, now.getMonth(), now.getDate()); break
    default: return data
  }
  return data.filter(p => parseApiDate(p.date) >= from)
}
