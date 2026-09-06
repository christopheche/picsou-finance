import '@testing-library/jest-dom'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const SECRET = 'psk_super_secret_value'

const hooks = vi.hoisted(() => ({
  useAccessKeys: vi.fn(),
  useCreateAccessKey: vi.fn(),
  useRevokeAccessKey: vi.fn(),
}))

vi.mock('@/features/accessKeys/hooks', () => hooks)

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'fr', resolvedLanguage: 'fr' },
  }),
}))

const { AccessKeysSection } = await import('./AccessKeysSection')

const createKey = {
  mutate: vi.fn(
    (
      _input: unknown,
      opts?: { onSuccess?: (r: { id: number; name: string; secret: string }) => void },
    ) => opts?.onSuccess?.({ id: 1, name: 'MCP', secret: SECRET }),
  ),
  reset: vi.fn(),
  isPending: false,
  isError: false,
  error: null,
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  createKey.mutate.mockClear()
  createKey.reset.mockClear()
  hooks.useAccessKeys.mockReturnValue({ data: [], isLoading: false })
  hooks.useCreateAccessKey.mockReturnValue(createKey)
  hooks.useRevokeAccessKey.mockReturnValue({
    mutate: vi.fn(),
    reset: vi.fn(),
    isPending: false,
    isError: false,
    error: null,
  })
})

afterEach(() => {
  vi.useRealTimers()
})

/** Opens the dialog, fills the form and creates a key so the secret is displayed. */
function openDialog() {
  fireEvent.click(screen.getByRole('button', { name: 'accessKeys.newKey' }))
}

function createAKey() {
  openDialog()
  fireEvent.change(screen.getByLabelText('accessKeys.nameLabel'), { target: { value: 'MCP' } })
  fireEvent.click(screen.getAllByRole('checkbox')[0])
  fireEvent.click(screen.getByRole('button', { name: 'accessKeys.create' }))
}

describe('AccessKeysSection one-time secret', () => {
  it('drops the secret from state when the dialog is closed', () => {
    // "Done" only closed the dialog: the plaintext secret stayed in component
    // state (and in React DevTools) for as long as Settings stayed mounted.
    render(<AccessKeysSection />)
    createAKey()

    expect(screen.getByText(SECRET)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'accessKeys.done' }))
    act(() => {
      vi.advanceTimersByTime(300)
    })

    expect(screen.queryByText(SECRET)).not.toBeInTheDocument()
    expect(createKey.reset).toHaveBeenCalled()
  })

  it('does not resurrect the previous secret when the dialog is reopened', () => {
    render(<AccessKeysSection />)
    createAKey()
    fireEvent.click(screen.getByRole('button', { name: 'accessKeys.done' }))
    act(() => {
      vi.advanceTimersByTime(300)
    })

    openDialog()

    expect(screen.queryByText(SECRET)).not.toBeInTheDocument()
    expect(screen.getByText('accessKeys.createTitle')).toBeInTheDocument()
  })
})
