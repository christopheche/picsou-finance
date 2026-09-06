import { create } from 'zustand'
import { persist } from 'zustand/middleware'

/**
 * The admin's impersonation target (`?memberId=` on every request, see
 * `lib/api-client.ts`). `null` means "my own profile". Persisted so a reload keeps
 * the selected profile; cleared at every auth boundary by `resetClientState`, and
 * by the api-client when the backend refuses a stale target with a 403.
 *
 * A `viewMode`/`setViewMode` pair used to live here; nothing read it and the setter
 * wrote `activeMemberId: undefined`, wiping the target on the 'managed' mode whose
 * whole point is an active member. The mode is derivable: `activeMemberId != null`.
 */
interface ProfileState {
  activeMemberId: number | null
  setActiveMember: (memberId: number | null) => void
  reset: () => void
}

export const useProfileStore = create<ProfileState>()(
  persist(
    (set) => ({
      activeMemberId: null,
      setActiveMember: (memberId) => set({ activeMemberId: memberId }),
      reset: () => set({ activeMemberId: null }),
    }),
    { name: 'picsou-profile', partialize: (state) => ({ activeMemberId: state.activeMemberId }) }
  )
)
