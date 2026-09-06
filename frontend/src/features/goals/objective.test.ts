import { describe, expect, it } from 'vitest'
import { monthObjective } from './objective'

describe('monthObjective', () => {
  it('falls back to the computed objective when no override is set', () => {
    expect(monthObjective({ objective: 500, override: null })).toBe(500)
  })

  it('uses the override as the target when one is set', () => {
    // Planning to save 1000 with 200 actually saved must read as 20%, not 200%.
    expect(monthObjective({ objective: 500, override: 1000 })).toBe(1000)
  })

  it('honours an override of 0 (a month deliberately skipped)', () => {
    expect(monthObjective({ objective: 500, override: 0 })).toBe(0)
  })
})
