import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

function memoryStorage(seed?: Record<string, string>): Storage {
  const m = new Map<string, string>(Object.entries(seed ?? {}))
  return {
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, String(v)),
    removeItem: (k) => void m.delete(k),
    clear: () => m.clear(),
    key: (i) => [...m.keys()][i] ?? null,
    get length() { return m.size },
  } as Storage
}

const valid = { username: 'alice', role: 'ADMIN' as const, memberId: 1, displayName: 'Alice' }

/**
 * The store reads sessionStorage while the module graph is evaluated — before any error
 * boundary exists — so the read must never throw, whatever a previous release left there.
 */
describe('auth-store initial state', () => {
  beforeEach(() => vi.resetModules())
  afterEach(() => vi.unstubAllGlobals())

  it('restores a valid persisted user', async () => {
    vi.stubGlobal('sessionStorage', memoryStorage({ picsou_user: JSON.stringify(valid) }))
    const { useAuthStore } = await import('./auth-store')
    expect(useAuthStore.getState().user).toEqual(valid)
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
  })

  it('treats the legacy bare-username value as signed out and clears it', async () => {
    const storage = memoryStorage({ picsou_user: 'alice' })
    vi.stubGlobal('sessionStorage', storage)
    const { useAuthStore } = await import('./auth-store')
    expect(useAuthStore.getState().user).toBeNull()
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(storage.getItem('picsou_user')).toBeNull()
  })

  it('rejects valid JSON of the wrong shape', async () => {
    vi.stubGlobal('sessionStorage', memoryStorage({ picsou_user: JSON.stringify({ username: 'alice' }) }))
    const { useAuthStore } = await import('./auth-store')
    expect(useAuthStore.getState().user).toBeNull()
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
  })

  it('survives a storage that throws on access', async () => {
    vi.stubGlobal('sessionStorage', {
      getItem: () => { throw new Error('SecurityError') },
      removeItem: () => { throw new Error('SecurityError') },
    })
    const { useAuthStore } = await import('./auth-store')
    expect(useAuthStore.getState().user).toBeNull()
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
  })

  it('round-trips login / setUsername / logout through storage', async () => {
    const storage = memoryStorage()
    vi.stubGlobal('sessionStorage', storage)
    const { useAuthStore } = await import('./auth-store')
    useAuthStore.getState().login(valid)
    useAuthStore.getState().setUsername('alice2')
    expect(JSON.parse(storage.getItem('picsou_user')!)).toMatchObject({ username: 'alice2', role: 'ADMIN' })
    useAuthStore.getState().logout()
    expect(storage.getItem('picsou_user')).toBeNull()
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
  })
})
