import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { apiPost, navigate } = vi.hoisted(() => ({ apiPost: vi.fn(), navigate: vi.fn() }))

vi.mock('@/lib/api-client', () => ({ api: { post: apiPost } }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('react-router-dom', () => ({
  useNavigate: () => navigate,
  useParams: () => ({ token: 'activation-token' }),
}))

const { ActivationPage } = await import('./ActivationPage')

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return render(<ActivationPage />, { wrapper })
}

/** Fills the form so `submit` reaches the API call. */
function fillValidForm(password = 'correct horse') {
  fireEvent.click(screen.getByLabelText('auth.activation.acknowledgeLabel'))
  fireEvent.change(screen.getByLabelText('auth.activation.passwordLabel'), {
    target: { value: password },
  })
  fireEvent.change(screen.getByLabelText('auth.activation.confirmPasswordLabel'), {
    target: { value: password },
  })
}

beforeEach(() => {
  apiPost.mockReset()
  navigate.mockReset()
})

describe('ActivationPage', () => {
  it('renders every string through useTranslation', () => {
    // First-contact screen for an invited family member: it used to be hardcoded
    // English, so a FR/DE/ES member got an untranslated page.
    renderPage()

    expect(screen.getByText('auth.activation.title')).toBeInTheDocument()
    expect(screen.getByText('auth.activation.warningTitle')).toBeInTheDocument()
    expect(screen.getByText('auth.activation.warningBody')).toBeInTheDocument()
    expect(screen.getByRole('button')).toHaveTextContent('auth.activation.submit')
  })

  it('translates client-side validation instead of hardcoding English', () => {
    renderPage()
    fireEvent.click(screen.getByLabelText('auth.activation.acknowledgeLabel'))
    fireEvent.change(screen.getByLabelText('auth.activation.passwordLabel'), {
      target: { value: 'correct horse' },
    })
    fireEvent.change(screen.getByLabelText('auth.activation.confirmPasswordLabel'), {
      target: { value: 'correct hoarse' },
    })
    fireEvent.click(screen.getByRole('button'))

    expect(screen.getByRole('alert')).toHaveTextContent('auth.activation.passwordMismatch')
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('shows the backend reason, never the axios boilerplate, when activation fails', async () => {
    // `err.message` here is "Request failed with status code 410" — the exact string
    // docs/conventions/error-handling.md forbids showing.
    apiPost.mockRejectedValue({
      response: { status: 410, data: { detail: 'This activation link has expired.' } },
      message: 'Request failed with status code 410',
    })
    renderPage()
    fillValidForm()
    fireEvent.click(screen.getByRole('button'))

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('This activation link has expired.'),
    )
    expect(screen.queryByText(/Request failed with status code/)).not.toBeInTheDocument()
  })

  it('falls back to a translated message when the server sends nothing safe', async () => {
    apiPost.mockRejectedValue({
      response: { status: 500, data: { detail: 'java.lang.IllegalStateException: boom' } },
      message: 'Request failed with status code 500',
    })
    renderPage()
    fillValidForm()
    fireEvent.click(screen.getByRole('button'))

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('common.errors.serverError'),
    )
  })

  it('confirms success and redirects to the login page', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    apiPost.mockResolvedValue({ data: {} })
    renderPage()
    fillValidForm()
    fireEvent.click(screen.getByRole('button'))

    await waitFor(() =>
      expect(screen.getByText('auth.activation.successTitle')).toBeInTheDocument(),
    )
    await vi.advanceTimersByTimeAsync(2000)
    expect(navigate).toHaveBeenCalledWith('/login')
    vi.useRealTimers()
  })
})
