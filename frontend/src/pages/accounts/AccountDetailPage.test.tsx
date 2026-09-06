import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
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

const { AccountDetailPage } = await import('./AccountDetailPage')

function renderPage(id: string) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/accounts/${id}`]}>
        <Routes>
          <Route path="/accounts/:id" element={<AccountDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AccountDetailPage — unreachable account', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiPut.mockReset()
    apiDelete.mockReset()
  })

  it('reports a deleted account instead of rendering an empty page', async () => {
    apiGet.mockRejectedValue({ response: { status: 404, data: { detail: 'Account not found' } } })
    renderPage('123')

    expect(await screen.findByText('Account not found')).toBeInTheDocument()
    // The way back to the list survives the failure.
    expect(screen.getByRole('button', { name: 'common.back' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'common.retry' })).toBeInTheDocument()
  })

  it('reports a non-numeric id, whose query never runs at all', async () => {
    renderPage('not-a-number')

    expect(await screen.findByText('error.notFound')).toBeInTheDocument()
    // No `/accounts/NaN/...` request goes out for an unusable id.
    for (const [url] of apiGet.mock.calls) expect(url).not.toContain('NaN')
    // Nothing to retry: the query is disabled, so only the back button is offered.
    expect(screen.queryByRole('button', { name: 'common.retry' })).not.toBeInTheDocument()
  })
})
