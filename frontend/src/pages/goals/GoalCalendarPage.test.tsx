import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GoalMonthEntry } from '@/types/api'

const { apiGet, apiPut, apiPost, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPut: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}))

vi.mock('@/lib/api-client', () => ({
  api: { get: apiGet, put: apiPut, post: apiPost, delete: apiDelete },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

const { GoalCalendarPage } = await import('./GoalCalendarPage')

/** `YYYY-MM` of the month `monthsBack` before the current one — always strictly past. */
function pastYearMonth(monthsBack: number): string {
  const date = new Date()
  date.setDate(1)
  date.setMonth(date.getMonth() - monthsBack)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

function monthLabel(yearMonth: string): string {
  const [year, month] = yearMonth.split('-').map(Number)
  return new Intl.DateTimeFormat('en-US', { month: 'short' }).format(new Date(year, month - 1, 1))
}

const EARLIER = pastYearMonth(2)
const LATER = pastYearMonth(1)

const MONTHS: GoalMonthEntry[] = [
  { yearMonth: EARLIER, objective: 300, actual: 320, manualActual: null, override: 500, effective: 320 },
  { yearMonth: LATER, objective: 300, actual: 280, manualActual: null, override: null, effective: 280 },
]

const GOAL = {
  id: 1,
  name: 'House',
  targetAmount: 10_000,
  deadline: '2030-01-01',
  currentTotal: 1_000,
  percentComplete: 10,
  monthsLeft: 12,
  monthlyNeeded: 300,
  avgMonthlyContribution: 250,
  isOnTrack: true,
  accounts: [],
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/goals/1/calendar']}>
        <Routes>
          <Route path="/goals/:id/calendar" element={<GoalCalendarPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** The objective-override field is the first numeric input of the month detail panel. */
function overrideInput(): HTMLInputElement {
  return screen.getAllByRole('textbox')[0] as HTMLInputElement
}

describe('GoalCalendarPage — month detail panel', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPut.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    // Desktop: the sticky side panel is used instead of the bottom sheet.
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: true,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }))
    apiGet.mockImplementation((url: string) =>
      url.endsWith('/months')
        ? Promise.resolve({ data: MONTHS })
        : Promise.resolve({ data: GOAL }),
    )
  })

  it('reloads the inputs from the newly selected month instead of keeping the previous one', async () => {
    renderPage()

    fireEvent.click(await screen.findByText(monthLabel(EARLIER)))
    expect(overrideInput()).toHaveValue('500')

    fireEvent.change(overrideInput(), { target: { value: '750' } })
    expect(overrideInput()).toHaveValue('750')

    fireEvent.click(screen.getByText(monthLabel(LATER)))
    // The later month carries no override: a panel that was not remounted would still
    // show "750" and save it against the wrong month.
    expect(overrideInput()).toHaveValue('')
  })

  it('counts only strictly past months in the achieved badge', async () => {
    renderPage()

    // EARLIER: 320 saved against a 500 override → missed. LATER: 280 against 300 → missed.
    // The current month is in progress and must not appear in the denominator.
    expect(await screen.findByText(`0/2 goals.achieved`)).toBeInTheDocument()
  })
})
