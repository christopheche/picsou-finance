import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createElement, type ReactNode } from 'react'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

// zustand's profile-store uses `persist`, which reads localStorage at module
// eval; jsdom doesn't provide one. Install in-memory shims BEFORE the stores are
// (dynamically) imported below — same constraint as api-client.test.ts.
function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    getItem: k => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, String(v)),
    removeItem: k => void m.delete(k),
    clear: () => m.clear(),
    key: i => [...m.keys()][i] ?? null,
    get length() { return m.size },
  } as Storage
}
vi.stubGlobal('localStorage', memoryStorage())
vi.stubGlobal('sessionStorage', memoryStorage())

const { logout, updateUsername } = vi.hoisted(() => ({ logout: vi.fn(), updateUsername: vi.fn() }))
vi.mock('./api', () => ({
  authApi: { logout, updateUsername },
}))

const { useLogout, useUpdateUsername } = await import('./hooks')
const { useAuthStore } = await import('@/stores/auth-store')

function makeWrapper(queryClient: QueryClient) {
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
}

beforeEach(() => {
  useAuthStore.getState().login({ username: 'chloe', role: 'ADMIN', memberId: 1, displayName: 'Chloé' })
  logout.mockReset()
  updateUsername.mockReset()
})

describe('useLogout (server-confirmed logout only)', () => {
  it('clears local auth state and the query cache when the server call succeeds', async () => {
    logout.mockResolvedValue(undefined)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    queryClient.setQueryData(['session-probe'], { username: 'chloe' })

    const { result } = renderHook(() => useLogout(), { wrapper: makeWrapper(queryClient) })
    await act(async () => {
      await result.current.mutateAsync()
    })

    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(useAuthStore.getState().user).toBeNull()
    // resetClientState() -> queryClient.clear() also drops the cached session-probe
    // result, so a future RequireAuth mount can't replay a stale "authenticated" probe.
    expect(queryClient.getQueryData(['session-probe'])).toBeUndefined()
  })

  it('does NOT clear local auth state when the server call fails', async () => {
    // The session cookies are still valid server-side if /auth/logout failed --
    // presenting a "logged out" UI here would let RequireAuth's session-probe
    // silently re-authenticate the user right back in on the next mount.
    logout.mockRejectedValue(new Error('network error'))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    const { result } = renderHook(() => useLogout(), { wrapper: makeWrapper(queryClient) })
    act(() => {
      result.current.mutate()
    })
    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(useAuthStore.getState().isAuthenticated).toBe(true)
    expect(useAuthStore.getState().user).toMatchObject({ username: 'chloe' })
  })
})

describe('useUpdateUsername (rename owned by the feature layer)', () => {
  it('stores the new username once the server accepted it', async () => {
    updateUsername.mockResolvedValue({ data: {} })
    const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })

    const { result } = renderHook(() => useUpdateUsername(), { wrapper: makeWrapper(queryClient) })
    await act(async () => {
      await result.current.mutateAsync('chloe.c')
    })

    expect(updateUsername).toHaveBeenCalledWith('chloe.c')
    expect(useAuthStore.getState().user).toMatchObject({ username: 'chloe.c' })
  })

  it('leaves the displayed username alone when the call fails', async () => {
    // The cookie still carries the old identity: showing the new name would make
    // the UI disagree with the session until the next refresh.
    updateUsername.mockRejectedValue({ response: { status: 409 } })
    const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })

    const { result } = renderHook(() => useUpdateUsername(), { wrapper: makeWrapper(queryClient) })
    act(() => {
      result.current.mutate('taken')
    })
    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(useAuthStore.getState().user).toMatchObject({ username: 'chloe' })
  })
})
