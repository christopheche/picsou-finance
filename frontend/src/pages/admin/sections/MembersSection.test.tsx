import '@testing-library/jest-dom'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const hooks = vi.hoisted(() => ({
  useFamilyMembers: vi.fn(),
  useCreateUserWithLogin: vi.fn(),
  useDeleteMember: vi.fn(),
  useGenerateActivationLink: vi.fn(),
  useResetMemberPassword: vi.fn(),
  useAdminForceDisableMfa: vi.fn(),
}))

vi.mock('@/features/family/hooks', () => ({
  useFamilyMembers: hooks.useFamilyMembers,
  useCreateUserWithLogin: hooks.useCreateUserWithLogin,
  useDeleteMember: hooks.useDeleteMember,
  useGenerateActivationLink: hooks.useGenerateActivationLink,
  useResetMemberPassword: hooks.useResetMemberPassword,
}))

vi.mock('@/features/mfa/hooks', () => ({
  useAdminForceDisableMfa: hooks.useAdminForceDisableMfa,
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

const { MembersSection } = await import('./MembersSection')

/** A mutation stub: idle unless an `error` is supplied. */
function mutation(error?: unknown) {
  return {
    mutate: vi.fn(),
    reset: vi.fn(),
    isPending: false,
    isError: error !== undefined,
    error: error ?? null,
  }
}

beforeEach(() => {
  hooks.useFamilyMembers.mockReturnValue({ data: [], isLoading: false })
  hooks.useCreateUserWithLogin.mockReturnValue(mutation())
  hooks.useDeleteMember.mockReturnValue(mutation())
  hooks.useGenerateActivationLink.mockReturnValue(mutation())
  hooks.useResetMemberPassword.mockReturnValue(mutation())
  hooks.useAdminForceDisableMfa.mockReturnValue(mutation())
})

describe('MembersSection error surfacing', () => {
  it('shows why creating a user failed', () => {
    // The two-step create (profile, then activation link) used to fail silently:
    // no link, no message — so the admin re-submitted and got a duplicate profile.
    hooks.useCreateUserWithLogin.mockReturnValue(
      mutation({ response: { status: 409, data: { detail: 'A member with that name exists' } } }),
    )

    render(<MembersSection />)

    expect(screen.getByRole('alert')).toHaveTextContent('A member with that name exists')
  })

  it('shows why generating an activation link failed', () => {
    hooks.useGenerateActivationLink.mockReturnValue(mutation({ response: { status: 500 } }))

    render(<MembersSection />)

    expect(screen.getByRole('alert')).toHaveTextContent('common.errors.serverError')
  })

  it('shows why a password reset failed', () => {
    hooks.useResetMemberPassword.mockReturnValue(mutation({ response: { status: 403 } }))

    render(<MembersSection />)

    expect(screen.getByRole('alert')).toHaveTextContent('common.errors.forbidden')
  })

  it('stays quiet while every mutation is idle', () => {
    render(<MembersSection />)

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
