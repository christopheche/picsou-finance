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

describe('profile-store', () => {
  beforeEach(() => vi.resetModules())
  afterEach(() => vi.unstubAllGlobals())

  it('persists the impersonation target and clears it on reset', async () => {
    const storage = memoryStorage()
    vi.stubGlobal('localStorage', storage)
    const { useProfileStore } = await import('./profile-store')

    useProfileStore.getState().setActiveMember(5)
    expect(useProfileStore.getState().activeMemberId).toBe(5)
    expect(JSON.parse(storage.getItem('picsou-profile')!).state).toEqual({ activeMemberId: 5 })

    useProfileStore.getState().reset()
    expect(useProfileStore.getState().activeMemberId).toBeNull()
  })

  it('rehydrates a target persisted by a previous session (the stale-target case)', async () => {
    vi.stubGlobal('localStorage', memoryStorage({
      'picsou-profile': JSON.stringify({ state: { activeMemberId: 7, viewMode: 'managed' }, version: 0 }),
    }))
    const { useProfileStore } = await import('./profile-store')
    expect(useProfileStore.getState().activeMemberId).toBe(7)
    // The retired `viewMode` key is not carried forward on the next write.
    useProfileStore.getState().setActiveMember(null)
    expect(JSON.parse(localStorage.getItem('picsou-profile')!).state).toEqual({ activeMemberId: null })
  })
})
