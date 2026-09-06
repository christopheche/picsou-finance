import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { AxiosAdapter } from 'axios'

// zustand's `persist` (profile-store) needs a working localStorage; jsdom here
// doesn't provide one, so install a tiny in-memory shim before importing stores.
function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, String(v)),
    removeItem: (k) => void m.delete(k),
    clear: () => m.clear(),
    key: (i) => [...m.keys()][i] ?? null,
    get length() { return m.size },
  } as Storage
}
vi.stubGlobal('localStorage', memoryStorage())
vi.stubGlobal('sessionStorage', memoryStorage())

const { api, isSetupRequiredResponse } = await import('./api-client')
const { useAuthStore } = await import('@/stores/auth-store')
const { useProfileStore } = await import('@/stores/profile-store')

describe('isSetupRequiredResponse', () => {
  it('detects setup-required ProblemDetail code responses', () => {
    expect(isSetupRequiredResponse(503, {
      code: 'setup_required',
      detail: 'Picsou is not configured yet.',
    })).toBe(true)
  })

  it('keeps compatibility with setup-required detail responses', () => {
    expect(isSetupRequiredResponse(503, { detail: 'setup_required' })).toBe(true)
  })

  it('ignores generic 503 responses', () => {
    expect(isSetupRequiredResponse(503, { detail: 'Connector unavailable' })).toBe(false)
  })

  it('requires a 503 status', () => {
    expect(isSetupRequiredResponse(502, { code: 'setup_required' })).toBe(false)
  })
})

/**
 * The request interceptor must only attach `?memberId=` for admins (the backend
 * ignores the override for non-admins and rejects it for activated members). This
 * stops a stale persisted `activeMemberId` on a shared browser from ever scoping a
 * regular member's requests to someone else's data.
 */
describe('api-client memberId interceptor', () => {
  let captured: Record<string, unknown> | undefined

  beforeEach(() => {
    captured = undefined
    // Capture the outgoing params instead of hitting the network.
    const echoAdapter: AxiosAdapter = async (config) => {
      captured = config.params
      return {
        data: null,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    api.defaults.adapter = echoAdapter
  })

  afterEach(() => {
    useAuthStore.getState().logout()
    useProfileStore.getState().reset()
  })

  function asUser(role: 'ADMIN' | 'MEMBER') {
    useAuthStore.getState().login({ username: 'u', role, memberId: 1, displayName: 'U' })
  }

  it('attaches memberId when an admin is impersonating a managed profile', async () => {
    asUser('ADMIN')
    useProfileStore.getState().setActiveMember(5)
    await api.get('/dashboard')
    expect(captured?.memberId).toBe(5)
  })

  it('does NOT attach memberId for a non-admin, even with a stale activeMemberId', async () => {
    asUser('MEMBER')
    useProfileStore.getState().setActiveMember(5)
    await api.get('/dashboard')
    expect(captured?.memberId).toBeUndefined()
  })

  it('does NOT attach memberId when no profile is active', async () => {
    asUser('ADMIN')
    useProfileStore.getState().reset()
    await api.get('/dashboard')
    expect(captured?.memberId).toBeUndefined()
  })

  it('does not redirect global 5xx errors when a GET opts out', async () => {
    const href = window.location.href
    api.defaults.adapter = async (config) => Promise.reject({
      config,
      response: { status: 502, data: { detail: 'Connector unavailable' } },
    })

    await expect(
      api.get('/sync/institutions', { params: { query: 'boursobank' }, skipGlobalErrorRedirect: true }),
    ).rejects.toMatchObject({ response: { status: 502 } })

    expect(window.location.href).toBe(href)
  })
})

type ScriptedResponse = { status: number; data?: unknown; delayMs?: number }

/**
 * Adapter driven by a per-route script: `script('GET /dashboard')` decides the
 * status of the next call to that route (a FIFO per key, last entry repeating),
 * and every dispatched call is recorded as `METHOD /url` with its params.
 */
function scriptedAdapter(script: Record<string, ScriptedResponse[]>) {
  const calls: Array<{ key: string; params?: Record<string, unknown>; retry?: boolean }> = []
  const adapter: AxiosAdapter = async (config) => {
    const key = `${config.method?.toUpperCase()} ${config.url}`
    calls.push({ key, params: config.params, retry: (config as { _retry?: boolean })._retry })
    const queue = script[key] ?? [{ status: 200 }]
    const next = queue.length > 1 ? queue.shift()! : queue[0]
    if (next.delayMs) await new Promise(r => setTimeout(r, next.delayMs))
    const response = { data: next.data ?? null, status: next.status, statusText: '', headers: {}, config }
    if (next.status >= 400) return Promise.reject({ config, response })
    return response
  }
  return { adapter, calls }
}

function stubLocation(pathname: string) {
  vi.stubGlobal('location', { href: `http://localhost${pathname}`, pathname, search: '' })
}

/**
 * A persisted admin impersonation target that the backend now refuses (the managed
 * member activated their own login → 403 on every `?memberId=X` GET) must not lock
 * the admin out on `/error/403`: the stale target is dropped and the GET replayed
 * under the admin's own scope.
 */
describe('api-client 403 impersonation self-heal', () => {
  beforeEach(() => stubLocation('/'))
  afterEach(() => {
    vi.unstubAllGlobals()
    useAuthStore.getState().logout()
    useProfileStore.getState().reset()
  })

  function asUser(role: 'ADMIN' | 'MEMBER') {
    useAuthStore.getState().login({ username: 'u', role, memberId: 1, displayName: 'U' })
  }

  it('drops the stale activeMemberId and replays the GET without memberId', async () => {
    asUser('ADMIN')
    useProfileStore.getState().setActiveMember(5)
    const calls: Array<Record<string, unknown> | undefined> = []
    api.defaults.adapter = async (config) => {
      calls.push(config.params)
      if (config.params?.memberId === 5) {
        return Promise.reject({ config, response: { status: 403, data: { detail: "Cannot access an independent member's data" } } })
      }
      return { data: { total: 1 }, status: 200, statusText: 'OK', headers: {}, config }
    }

    const res = await api.get('/dashboard')

    expect(res.data).toEqual({ total: 1 })
    expect(calls).toHaveLength(2)
    expect(calls[0]?.memberId).toBe(5)
    expect(calls[1]?.memberId).toBeUndefined()
    expect(useProfileStore.getState().activeMemberId).toBeNull()
    expect(window.location.href).toBe('http://localhost/')
  })

  it('still redirects a genuine GET 403 that carried no impersonation target', async () => {
    asUser('MEMBER')
    api.defaults.adapter = async (config) => Promise.reject({ config, response: { status: 403 } })

    await expect(api.get('/family/members')).rejects.toMatchObject({ response: { status: 403 } })

    expect(window.location.href).toBe('/error/403')
  })

  it('redirects when the replay without memberId is itself forbidden (no retry loop)', async () => {
    asUser('ADMIN')
    useProfileStore.getState().setActiveMember(5)
    let attempts = 0
    api.defaults.adapter = async (config) => {
      attempts++
      return Promise.reject({ config, response: { status: 403 } })
    }

    await expect(api.get('/admin/settings')).rejects.toMatchObject({ response: { status: 403 } })

    expect(attempts).toBe(2)
    expect(useProfileStore.getState().activeMemberId).toBeNull()
    expect(window.location.href).toBe('/error/403')
  })

  it('does not reset the target on a mutation 403 (never replays a write under another member)', async () => {
    asUser('ADMIN')
    useProfileStore.getState().setActiveMember(5)
    let attempts = 0
    api.defaults.adapter = async (config) => {
      attempts++
      return Promise.reject({ config, response: { status: 403 } })
    }

    await expect(api.post('/accounts', { name: 'x' })).rejects.toMatchObject({ response: { status: 403 } })

    expect(attempts).toBe(1)
    expect(useProfileStore.getState().activeMemberId).toBe(5)
    expect(window.location.href).toBe('http://localhost/')
  })
})

describe('api-client 5xx opt-out for GET-shaped mutations', () => {
  beforeEach(() => stubLocation('/sync/callback'))
  afterEach(() => vi.unstubAllGlobals())

  it('lets bankSyncApi.complete surface a 500 to its onError instead of redirecting', async () => {
    const { bankSyncApi } = await import('@/features/sync/api')
    api.defaults.adapter = async (config) => Promise.reject({
      config,
      response: { status: 500, data: { detail: 'boom' } },
    })

    await expect(bankSyncApi.complete('code-1', 'state-1')).rejects.toMatchObject({ response: { status: 500 } })

    expect(window.location.href).toBe('http://localhost/sync/callback')
  })
})

describe('api-client 401 refresh interceptor', () => {
  beforeEach(() => {
    stubLocation('/accounts')
    useAuthStore.getState().login({ username: 'u', role: 'MEMBER', memberId: 1, displayName: 'U' })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    useAuthStore.getState().logout()
  })

  it('refreshes once on a 401 and replays the original request', async () => {
    const { adapter, calls } = scriptedAdapter({
      'GET /dashboard': [{ status: 401 }, { status: 200, data: { ok: true } }],
      'POST /auth/refresh': [{ status: 200 }],
    })
    api.defaults.adapter = adapter

    const res = await api.get('/dashboard')

    expect(res.data).toEqual({ ok: true })
    expect(calls.map(c => c.key)).toEqual(['GET /dashboard', 'POST /auth/refresh', 'GET /dashboard'])
    expect(calls[2].retry).toBe(true)
  })

  it('queues concurrent 401s behind a single refresh and replays them all', async () => {
    const { adapter, calls } = scriptedAdapter({
      'GET /a': [{ status: 401 }, { status: 200, data: 'a' }],
      'GET /b': [{ status: 401 }, { status: 200, data: 'b' }],
      'POST /auth/refresh': [{ status: 200, delayMs: 20 }],
    })
    api.defaults.adapter = adapter

    const [a, b] = await Promise.all([api.get('/a'), api.get('/b')])

    expect([a.data, b.data]).toEqual(['a', 'b'])
    expect(calls.filter(c => c.key === 'POST /auth/refresh')).toHaveLength(1)
    expect(calls.filter(c => c.key === 'GET /a')).toHaveLength(2)
    expect(calls.filter(c => c.key === 'GET /b')).toHaveLength(2)
    // The queued replay is marked so a second 401 fails instead of looping.
    expect(calls.filter(c => c.key === 'GET /b')[1].retry).toBe(true)
  })

  it('rejects every queued request when the refresh fails, logs out and redirects to /login', async () => {
    const { adapter, calls } = scriptedAdapter({
      'GET /a': [{ status: 401 }],
      'GET /b': [{ status: 401 }],
      'POST /auth/refresh': [{ status: 401, delayMs: 20 }],
    })
    api.defaults.adapter = adapter

    const results = await Promise.allSettled([api.get('/a'), api.get('/b')])

    expect(results.map(r => r.status)).toEqual(['rejected', 'rejected'])
    expect(calls.filter(c => c.key === 'POST /auth/refresh')).toHaveLength(1)
    expect(calls.filter(c => c.key === 'GET /b')).toHaveLength(1)
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(window.location.href).toBe('/login?redirect=%2Faccounts')
  })

  it('does not replay requests queued behind a failed refresh on the next successful one', async () => {
    stubLocation('/login')
    const { adapter, calls } = scriptedAdapter({
      'GET /a': [{ status: 401 }],
      'GET /b': [{ status: 401 }],
      'GET /c': [{ status: 401 }, { status: 200, data: 'c' }],
      'POST /auth/refresh': [{ status: 401, delayMs: 20 }, { status: 200 }],
    })
    api.defaults.adapter = adapter

    const first = await Promise.allSettled([api.get('/a'), api.get('/b')])
    expect(first.map(r => r.status)).toEqual(['rejected', 'rejected'])

    const c = await api.get('/c')

    expect(c.data).toBe('c')
    expect(calls.filter(c => c.key === 'GET /a')).toHaveLength(1)
    expect(calls.filter(c => c.key === 'GET /b')).toHaveLength(1)
    expect(calls.filter(c => c.key === 'POST /auth/refresh')).toHaveLength(2)
  })

  it('never refreshes for a 401 on an /auth/ route', async () => {
    const { adapter, calls } = scriptedAdapter({
      'POST /auth/login': [{ status: 401 }],
    })
    api.defaults.adapter = adapter

    await expect(api.post('/auth/login', {})).rejects.toMatchObject({ response: { status: 401 } })

    expect(calls.map(c => c.key)).toEqual(['POST /auth/login'])
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
  })
})
