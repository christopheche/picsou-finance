import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { apiGet, apiPost, apiPut, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
}))

vi.mock('@/lib/api-client', () => ({
  api: { get: apiGet, post: apiPost, put: apiPut, delete: apiDelete },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

const { GoalsPage } = await import('./GoalsPage')

const ACCOUNT = {
  id: 7,
  name: 'Livret A',
  type: 'SAVINGS',
  provider: null,
  currency: 'EUR',
  currentBalance: 1_000,
  currentBalanceEur: 1_000,
  lastSyncedAt: null,
  isManual: true,
  color: '#22c55e',
  ticker: null,
  logoUrl: null,
  logoKey: null,
  createdAt: '2026-01-01',
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <GoalsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('GoalsPage — create/update failures', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiPut.mockReset()
    apiDelete.mockReset()
    // DateInput probes the pointer type on mount; jsdom ships no matchMedia.
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }))
    apiGet.mockImplementation((url: string) =>
      url === '/accounts' ? Promise.resolve({ data: [ACCOUNT] }) : Promise.resolve({ data: [] }),
    )
  })

  it('shows the backend reason and keeps the dialog open when creation is rejected', async () => {
    apiPost.mockRejectedValue({
      response: { status: 422, data: { detail: 'Target amount must be positive' } },
    })
    renderPage()

    // Two "add" buttons while the list is empty (header + empty state).
    fireEvent.click((await screen.findAllByRole('button', { name: 'goals.addGoal' }))[0])
    fireEvent.click(await screen.findByRole('checkbox'))
    fireEvent.submit(screen.getByRole('button', { name: 'common.save' }).closest('form')!)

    expect(await screen.findByRole('alert')).toHaveTextContent('Target amount must be positive')
    // The dialog is still on screen: nothing was saved, so nothing may look saved.
    expect(screen.getByRole('button', { name: 'common.save' })).toBeInTheDocument()
  })

  it('closes the dialog when creation succeeds', async () => {
    apiPost.mockResolvedValue({ data: { id: 1 } })
    renderPage()

    // Two "add" buttons while the list is empty (header + empty state).
    fireEvent.click((await screen.findAllByRole('button', { name: 'goals.addGoal' }))[0])
    fireEvent.click(await screen.findByRole('checkbox'))
    fireEvent.submit(screen.getByRole('button', { name: 'common.save' }).closest('form')!)

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'common.save' })).not.toBeInTheDocument(),
    )
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
