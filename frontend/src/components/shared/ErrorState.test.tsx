import '@testing-library/jest-dom'
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { ErrorState } from './ErrorState'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'fr', resolvedLanguage: 'fr' },
  }),
}))

describe('ErrorState', () => {
  it('translates the default title and the retry button', () => {
    // The shared error surface used to hardcode "Error" and "Retry": callers could translate
    // the title but never the button, so a French user got a translated card with an English control.
    const onRetry = vi.fn()
    render(<ErrorState onRetry={onRetry} />)

    expect(screen.getByRole('heading')).toHaveTextContent('common.errorTitle')
    fireEvent.click(screen.getByRole('button', { name: 'common.retry' }))
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('keeps a caller-provided title and hides the button without a handler', () => {
    render(<ErrorState title="Chargement impossible" message="détail" />)

    expect(screen.getByRole('heading')).toHaveTextContent('Chargement impossible')
    expect(screen.getByText('détail')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
