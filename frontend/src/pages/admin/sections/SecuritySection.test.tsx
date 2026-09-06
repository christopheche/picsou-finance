import '@testing-library/jest-dom'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminSecuritySettings } from '@/features/admin/api'

const hooks = vi.hoisted(() => ({
  useUpdateSecurity: vi.fn(),
  useReloadCorsFromEnv: vi.fn(),
}))

vi.mock('@/features/admin/hooks', () => ({
  useUpdateSecurity: hooks.useUpdateSecurity,
  useReloadCorsFromEnv: hooks.useReloadCorsFromEnv,
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

// The secure-cookie Switch is a Radix primitive, which measures its thumb.
vi.stubGlobal('ResizeObserver', class {
  observe() {}
  unobserve() {}
  disconnect() {}
})

const { SecuritySection } = await import('./SecuritySection')

function idleMutation() {
  return {
    mutate: vi.fn(),
    mutateAsync: vi.fn().mockResolvedValue(undefined),
    isPending: false,
    isError: false,
    isSuccess: false,
    error: null,
  }
}

function settings(origins: string[]): AdminSecuritySettings {
  return { allowedOrigins: origins, secureCookies: true }
}

beforeEach(() => {
  hooks.useUpdateSecurity.mockReturnValue(idleMutation())
  hooks.useReloadCorsFromEnv.mockReturnValue(idleMutation())
})

describe('SecuritySection form seeding', () => {
  it('keeps in-progress edits when the settings query refetches', () => {
    // Any admin mutation (an integration toggle, a key-pair generation) invalidates
    // adminKeys.settings(); the unconditional reset(settings) that followed wiped
    // origins the admin had typed but not yet saved.
    const { rerender } = render(<SecuritySection settings={settings(['https://a.example'])} />)

    const input = screen.getByDisplayValue('https://a.example')
    fireEvent.change(input, { target: { value: 'https://typed-but-unsaved.example' } })

    rerender(<SecuritySection settings={settings(['https://a.example'])} />)

    expect(screen.getByDisplayValue('https://typed-but-unsaved.example')).toBeInTheDocument()
  })

  it('still adopts server values while the form is pristine', () => {
    // "Reload from .env" rewrites the origins server-side; a pristine form must
    // show what came back.
    const { rerender } = render(<SecuritySection settings={settings(['https://a.example'])} />)

    rerender(<SecuritySection settings={settings(['https://reloaded.example'])} />)

    expect(screen.getByDisplayValue('https://reloaded.example')).toBeInTheDocument()
  })
})
