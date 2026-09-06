import '@testing-library/jest-dom'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const hooks = vi.hoisted(() => ({
  useSessions: vi.fn(),
  useRevokeSession: vi.fn(),
  useRevokeAllSessionsExceptCurrent: vi.fn(),
}))

vi.mock('@/features/mfa/hooks', () => ({
  useSessions: hooks.useSessions,
  useRevokeSession: hooks.useRevokeSession,
  useRevokeAllSessionsExceptCurrent: hooks.useRevokeAllSessionsExceptCurrent,
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'fr', resolvedLanguage: 'fr' },
  }),
}))

const { SessionsList } = await import('./SessionsList')
const { useAppStore } = await import('@/stores/app-store')

const SESSION = {
  id: 'sess-1',
  userAgent: null,
  ipPrefix: '192.168.1',
  lastUsedAt: '2026-03-05T12:00:00Z',
  current: false,
  trustedFor2fa: false,
}

beforeEach(() => {
  hooks.useSessions.mockReturnValue({ data: [SESSION], isLoading: false })
  hooks.useRevokeSession.mockReturnValue({ mutate: vi.fn(), isPending: false, variables: undefined })
  hooks.useRevokeAllSessionsExceptCurrent.mockReturnValue({ mutate: vi.fn(), isPending: false })
  useAppStore.setState({ dateFormat: 'locale' })
})

describe('SessionsList', () => {
  it('translates the fallback device label', () => {
    render(<SessionsList />)

    expect(screen.getByText('settings.sessionsUnknownDevice')).toBeInTheDocument()
    expect(screen.queryByText('Unknown device')).not.toBeInTheDocument()
  })

  it("honours the user's ISO date-format preference like every other date", () => {
    // The row used its own toLocaleString() helper, so sessions were the one place
    // in Settings that ignored the dateFormat setting.
    useAppStore.setState({ dateFormat: 'iso' })

    render(<SessionsList />)

    expect(screen.getByText(/05-03-2026/)).toBeInTheDocument()
  })
})
