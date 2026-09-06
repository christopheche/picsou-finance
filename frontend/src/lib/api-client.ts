import axios from 'axios'
import { createDemoAdapter } from '@/demo'
import { useAppStore } from '@/stores/app-store'
import { useAuthStore } from '@/stores/auth-store'
import { useConnectivityStore } from '@/stores/connectivity-store'
import { useProfileStore } from '@/stores/profile-store'

declare module 'axios' {
  interface AxiosRequestConfig {
    skipGlobalErrorRedirect?: boolean
  }
}

export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
})

if (import.meta.env.VITE_DEMO_MODE === 'true') {
  api.defaults.adapter = createDemoAdapter()
}

// Add memberId to requests when an admin is viewing a managed profile.
// Only admins may impersonate (the backend ignores the override for non-admins and
// rejects it for activated members), so gating on role here keeps a stale persisted
// activeMemberId from ever affecting a regular member's requests.
api.interceptors.request.use((config) => {
  const { activeMemberId } = useProfileStore.getState()
  const isAdmin = useAuthStore.getState().user?.role === 'ADMIN'
  if (isAdmin && activeMemberId) {
    config.params = { ...config.params, memberId: activeMemberId }
  }
  return config
})

let isRefreshing = false

/**
 * Requests that 401'd while a refresh was already in flight. Each entry settles the
 * caller's promise both ways: `resolve` replays it once the refresh succeeds, `reject`
 * fails it with its own 401 when the refresh dies. A resolve-only queue used to leave
 * every queued caller pending forever on a failed refresh (TanStack queries stuck in
 * `pending`) and replayed those stale requests — old `?memberId`, old body — on the
 * next successful refresh, possibly under a different user's session.
 */
type RefreshSubscriber = { resolve: () => void; reject: () => void }
let refreshSubscribers: RefreshSubscriber[] = []

function subscribeToRefresh(subscriber: RefreshSubscriber) {
  refreshSubscribers.push(subscriber)
}

function drainRefreshSubscribers(): RefreshSubscriber[] {
  const subscribers = refreshSubscribers
  refreshSubscribers = []
  return subscribers
}

function notifyRefreshSubscribers() {
  drainRefreshSubscribers().forEach(s => s.resolve())
}

function rejectRefreshSubscribers() {
  drainRefreshSubscribers().forEach(s => s.reject())
}

export function isSetupRequiredResponse(status: number | undefined, data: unknown): boolean {
  if (status !== 503) return false
  if (typeof data === 'string') return data.includes('setup_required')
  if (data && typeof data === 'object') {
    const body = data as { code?: unknown; detail?: unknown }
    return body.code === 'setup_required' || body.detail === 'setup_required'
  }
  return false
}

api.interceptors.response.use(
  res => {
    // Mark as connected on any successful response (skip demo mode)
    if (!useAppStore.getState().demoMode) {
      useConnectivityStore.getState().setConnected(true)
    }
    return res
  },
  async error => {
    const originalRequest = error.config as typeof error.config & {
      _retry?: boolean
      _impersonationRetry?: boolean
    }

    // Network error detection (no response at all, or CORS-blocked)
    if (!error.response && !error.config?.url?.includes('/auth/')) {
      if (!useAppStore.getState().demoMode) {
        useConnectivityStore.getState().setConnected(false)
      }
    }

    // 403 on a GET that carried the admin's persisted impersonation target: the
    // backend refuses `?memberId=X` once member X has activated their own login
    // (UserContext.getMemberIdOverride). `activeMemberId` lives in localStorage and
    // is only cleared at login/logout, so without this the admin was thrown to
    // /error/403 on every page load — before the sidebar switcher (the only in-app
    // way to clear the target) could render. Drop the stale target and replay the
    // request under the admin's own scope instead. GETs only: replaying a mutation
    // under a different member would silently write to the wrong profile.
    if (
      error.response?.status === 403 &&
      error.config?.method === 'get' &&
      !originalRequest._impersonationRetry
    ) {
      const { activeMemberId } = useProfileStore.getState()
      const isAdmin = useAuthStore.getState().user?.role === 'ADMIN'
      const sentMemberId = originalRequest.params?.memberId
      if (isAdmin && activeMemberId != null && sentMemberId === activeMemberId) {
        useProfileStore.getState().reset()
        originalRequest._impersonationRetry = true
        // The request interceptor already spread `memberId` into `params`; the store
        // is null now, so it won't be re-added, but the copy on the config must go.
        const params = { ...originalRequest.params }
        delete params.memberId
        originalRequest.params = params
        return api(originalRequest)
      }
    }

    // 403: Forbidden
    if (error.response?.status === 403 && error.config?.method === 'get') {
      window.location.href = '/error/403'
      return Promise.reject(error)
    }

    // 503 setup-required: the backend's SetupFilter signals that the
    // wizard hasn't been completed yet. Bounce to /setup instead of the
    // generic 5xx error page.
    //
    // The backend sets code: "setup_required"; the detail fallback keeps
    // compatibility with older or stringified responses.
    const setupRequiredBody = isSetupRequiredResponse(
      error.response?.status,
      error.response?.data,
    )
    if (setupRequiredBody && window.location.pathname !== '/setup') {
      window.location.href = '/setup'
      return Promise.reject(error)
    }

    // 5xx: Server errors (GET only to avoid disrupting mutations)
    if (
      error.response?.status >= 500 &&
      error.response?.status < 600 &&
      error.config?.method === 'get' &&
      !error.config?.skipGlobalErrorRedirect
    ) {
      window.location.href = '/error/500?code=' + error.response.status
      return Promise.reject(error)
    }

    // 401: Unauthorized - token refresh
    if (
      error.response?.status === 401 &&
      !originalRequest._retry &&
      !originalRequest.url?.includes('/auth/')
    ) {
      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          subscribeToRefresh({
            resolve: () => {
              // A replay that 401s again must fail, not start another refresh cycle.
              originalRequest._retry = true
              resolve(api(originalRequest!))
            },
            reject: () => reject(error),
          })
        })
      }

      originalRequest._retry = true
      isRefreshing = true

      try {
        await api.post('/auth/refresh')
        notifyRefreshSubscribers()
        return api(originalRequest!)
      } catch {
        // Refresh failed: the session is dead. Clear the JS-side auth flag
        // (sessionStorage) before redirecting, otherwise PublicOnly on /login
        // sees `isAuthenticated=true` and bounces back to "/", which fires
        // /family/members → 401 → refresh → 401 → redirect → … infinite loop.
        useAuthStore.getState().logout()
        rejectRefreshSubscribers()
        if (window.location.pathname !== '/login') {
          window.location.href =
            '/login?redirect=' +
            encodeURIComponent(window.location.pathname + window.location.search)
        }
        return Promise.reject(error)
      } finally {
        isRefreshing = false
      }
    }

    return Promise.reject(error)
  }
)
