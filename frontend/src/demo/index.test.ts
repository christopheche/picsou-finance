import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'
import { createDemoAdapter } from './index'
import { mockAccounts } from './data/accounts'

/**
 * The adapter answers `{}` for any route without a handler (see docs/features/demo-mode.md),
 * so a frontend/backend endpoint drift only shows up as a console warning and a page reading
 * fields off an empty object. These cases pin the routes that drifted once already.
 */
describe('demo adapter handler table', () => {
  const adapter = createDemoAdapter()
  let warn: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    vi.useFakeTimers()
    warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
  })
  afterEach(() => {
    vi.useRealTimers()
    warn.mockRestore()
  })

  async function call(method: string, url: string, data?: unknown) {
    const config = { method, url, data: data === undefined ? undefined : JSON.stringify(data), headers: {} } as InternalAxiosRequestConfig
    const pending = adapter(config)
    await vi.advanceTimersByTimeAsync(1_000)
    return (await pending).data as Record<string, unknown>
  }

  it.each([
    ['/finary/status', ['connected', 'sessionId', 'status', 'lastSyncedAt', 'maskedEmail']],
    ['/degiro/status', ['isActive', 'status', 'lastSyncedAt']],
    ['/bourse-direct/status', ['isActive', 'syncStatus', 'lastSyncError']],
    ['/amundi/status', ['isActive', 'syncStatus']],
    ['/bourso/status', ['isActive', 'syncStatus']],
    ['/tr/status', ['isActive', 'expiresAt']],
    ['/ibkr/status', ['connected', 'status']],
  ])('serves the status route %s that features/sync/api.ts reads', async (url, keys) => {
    const data = await call('get', url)
    expect(warn).not.toHaveBeenCalled()
    for (const key of keys) expect(data).toHaveProperty(key)
  })

  it('serves an empty holdings array for every mock account, not only the investment ones', async () => {
    for (const account of mockAccounts) {
      const data = await call('get', `/accounts/${account.id}/holdings`)
      expect(Array.isArray(data), `GET /accounts/${account.id}/holdings`).toBe(true)
    }
    expect(warn).not.toHaveBeenCalled()
  })

  it('returns the API-sync preview token under the backend field name', async () => {
    const data = await call('post', '/finary/api-sync/preview')
    expect(typeof data.fileToken).toBe('string')
    expect(data).not.toHaveProperty('syncToken')
  })

  it('returns accounts carrying the logo members of the real DTO', async () => {
    const created = await call('post', '/accounts', { name: 'X', type: 'CHECKING' })
    expect(created).toMatchObject({ logoUrl: null, logoKey: null })
    const linked = (await call('get', '/sync/complete')) as unknown as Array<Record<string, unknown>>
    expect(linked[0]).toMatchObject({ logoUrl: null, logoKey: null })
  })

  it('still warns (and resolves {}) for an unregistered route', async () => {
    const data = await call('get', '/finary/configured')
    expect(data).toEqual({})
    expect(warn).toHaveBeenCalledOnce()
  })
})
