import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { filterByRange } from './chart-range'

const point = (date: string) => ({ date, total: 1 })

describe('filterByRange', () => {
  // The bug only shows behind UTC: a `LocalDate` parsed as an instant is UTC midnight, which is
  // the previous evening in New York, so the point dated exactly on the range start was dropped.
  beforeEach(() => { vi.stubEnv('TZ', 'America/New_York') })
  afterEach(() => { vi.unstubAllEnvs() })

  const now = new Date(2026, 8, 6, 15, 0) // 2026-09-06 15:00 local

  it('keeps the point dated exactly on the range start, west of UTC', () => {
    const data = [point('2026-08-29'), point('2026-08-30'), point('2026-09-05')]

    expect(filterByRange(data, '7D', now).map(p => p.date)).toEqual(['2026-08-30', '2026-09-05'])
  })

  it('starts the year-to-date range on 1 January', () => {
    const data = [point('2025-12-31'), point('2026-01-01'), point('2026-06-01')]

    expect(filterByRange(data, 'YTD', now).map(p => p.date)).toEqual(['2026-01-01', '2026-06-01'])
  })

  it('counts months back in the local calendar', () => {
    const data = [point('2026-06-05'), point('2026-06-06'), point('2026-08-06')]

    expect(filterByRange(data, '3M', now).map(p => p.date)).toEqual(['2026-06-06', '2026-08-06'])
    expect(filterByRange(data, '1M', now).map(p => p.date)).toEqual(['2026-08-06'])
  })

  it('returns everything for ALL', () => {
    const data = [point('2019-01-01'), point('2026-09-05')]

    expect(filterByRange(data, 'ALL', now)).toBe(data)
  })

  it('still honours the offset of a point that carries a time', () => {
    // 2026-08-30T03:00Z is 23:00 on the 29th in New York — before a 7D window opening on the 30th.
    const data = [point('2026-08-30T03:00:00Z'), point('2026-08-30T12:00:00Z')]

    expect(filterByRange(data, '7D', now).map(p => p.date)).toEqual(['2026-08-30T12:00:00Z'])
  })
})
