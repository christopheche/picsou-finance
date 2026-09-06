import { create } from 'zustand'

interface UserData {
  username: string
  role: 'ADMIN' | 'MEMBER'
  memberId: number
  displayName: string
}

interface AuthState {
  user: UserData | null
  isAuthenticated: boolean
  login: (data: UserData) => void
  logout: () => void
  setUsername: (username: string) => void
}

const STORAGE_KEY = 'picsou_user'

function isUserData(value: unknown): value is UserData {
  if (!value || typeof value !== 'object') return false
  const v = value as Record<string, unknown>
  return (
    typeof v.username === 'string' &&
    (v.role === 'ADMIN' || v.role === 'MEMBER') &&
    typeof v.memberId === 'number' &&
    typeof v.displayName === 'string'
  )
}

/**
 * Reads the persisted user without ever throwing: this runs while the module graph
 * is being evaluated (`api-client.ts` imports the store), before `ErrorBoundary`
 * mounts, so a non-JSON value — the pre-2FA format stored the bare username, a tab
 * kept open across a deploy — used to blank the whole app. Anything that is not the
 * current shape is dropped and treated as "not signed in".
 */
function readStoredUser(): UserData | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed: unknown = JSON.parse(raw)
    if (isUserData(parsed)) return parsed
  } catch {
    // fall through: unreadable storage or malformed JSON
  }
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // storage unavailable: nothing to clean up
  }
  return null
}

const storedUser = readStoredUser()

export const useAuthStore = create<AuthState>((set, get) => ({
  user: storedUser,
  isAuthenticated: storedUser !== null,
  login: (data) => {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(data))
    set({ user: data, isAuthenticated: true })
  },
  logout: () => {
    sessionStorage.removeItem(STORAGE_KEY)
    set({ user: null, isAuthenticated: false })
  },
  setUsername: (username) => {
    const current = get().user
    if (!current) return
    const updated = { ...current, username }
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(updated))
    set({ user: updated })
  },
}))
