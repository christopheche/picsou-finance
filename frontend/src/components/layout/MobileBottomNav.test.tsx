import '@testing-library/jest-dom'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { MobileBottomNav } from './MobileBottomNav'
import { NAV_ITEMS, CLASSIC_SETTINGS_NAV_ITEM } from './sidebar-nav-items'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'fr', resolvedLanguage: 'fr' },
  }),
}))

describe('MobileBottomNav', () => {
  it('renders every sidebar route plus Settings, in sidebar order', () => {
    // The bar used to keep its own copy of the items; a route added to the sidebar never
    // reached the phone. It now derives from the same registry.
    render(<MemoryRouter initialEntries={['/goals']}><MobileBottomNav /></MemoryRouter>)

    const links = screen.getAllByRole('link')
    const expected = [...NAV_ITEMS, CLASSIC_SETTINGS_NAV_ITEM]
    expect(links.map(l => l.getAttribute('href'))).toEqual(expected.map(i => i.path))
    expect(links.map(l => l.getAttribute('title'))).toEqual(expected.map(i => i.labelKey))
  })

  it('only marks the dashboard active on the exact root path', () => {
    render(<MemoryRouter initialEntries={['/accounts/3']}><MobileBottomNav /></MemoryRouter>)

    const active = screen.getAllByRole('link').filter(l => l.className.includes('ring-1'))
    expect(active.map(l => l.getAttribute('href'))).toEqual(['/accounts'])
  })
})
