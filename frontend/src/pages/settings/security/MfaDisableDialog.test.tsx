import '@testing-library/jest-dom'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { mutateAsync, reset } = vi.hoisted(() => ({ mutateAsync: vi.fn(), reset: vi.fn() }))

vi.mock('@/features/mfa/hooks', () => ({
  useMfaDisable: () => ({ mutateAsync, reset, isPending: false }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

const { MfaDisableDialog } = await import('./MfaDisableDialog')

function submitDisableForm() {
  fireEvent.change(screen.getByLabelText('settings.currentPassword'), {
    target: { value: 'hunter2hunter2' },
  })
  fireEvent.change(screen.getByLabelText('auth.mfaCodeLabel'), { target: { value: '123456' } })
  fireEvent.click(screen.getByRole('button', { name: 'settings.mfaDisable' }))
}

beforeEach(() => {
  mutateAsync.mockReset()
  reset.mockReset()
})

describe('MfaDisableDialog error display', () => {
  it('translates a server error instead of rendering `${status} — err.message`', async () => {
    // The dialog used to print "500 — Request failed with status code 500".
    mutateAsync.mockRejectedValue({
      response: { status: 500, data: { detail: 'An unexpected error occurred' } },
      message: 'Request failed with status code 500',
    })
    render(<MfaDisableDialog open onOpenChange={vi.fn()} />)
    submitDisableForm()

    await waitFor(() =>
      expect(screen.getByText('common.errors.serverError')).toBeInTheDocument(),
    )
    expect(screen.queryByText(/Request failed with status code/)).not.toBeInTheDocument()
    expect(screen.queryByText(/^500 —/)).not.toBeInTheDocument()
  })

  it('translates a 401 (wrong password) rather than echoing the status', async () => {
    mutateAsync.mockRejectedValue({
      response: { status: 401 },
      message: 'Request failed with status code 401',
    })
    render(<MfaDisableDialog open onOpenChange={vi.fn()} />)
    submitDisableForm()

    await waitFor(() =>
      expect(screen.getByText('common.errors.unauthorized')).toBeInTheDocument(),
    )
  })

  it('keeps the backend reason on a 400 but never a leaky detail', async () => {
    mutateAsync.mockRejectedValue({
      response: { status: 400, data: { detail: 'com.picsou.exception.MfaException: bad code' } },
      message: 'Request failed with status code 400',
    })
    render(<MfaDisableDialog open onOpenChange={vi.fn()} />)
    submitDisableForm()

    await waitFor(() => expect(screen.getByText('auth.mfaInvalidCode')).toBeInTheDocument())
    expect(screen.queryByText(/com\.picsou/)).not.toBeInTheDocument()
  })
})
