import '@testing-library/jest-dom'
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { ColorPicker } from './ColorPicker'
import { ACCOUNT_COLORS } from '@/lib/constants'

describe('ColorPicker', () => {
  it('names each swatch and marks the selected one as pressed', () => {
    // The swatches carry no text: without a name a screen reader announced eight bare
    // "button"s, and nothing said which colour was the current one.
    const selected = ACCOUNT_COLORS[1]
    render(<ColorPicker value={selected} onChange={vi.fn()} />)

    expect(screen.getAllByRole('button')).toHaveLength(ACCOUNT_COLORS.length)
    for (const color of ACCOUNT_COLORS) {
      expect(screen.getByRole('button', { name: color })).toHaveAttribute(
        'aria-pressed',
        String(color === selected),
      )
    }
  })

  it('reports the clicked colour', () => {
    const onChange = vi.fn()
    render(<ColorPicker value={ACCOUNT_COLORS[0]} onChange={onChange} />)

    fireEvent.click(screen.getByRole('button', { name: ACCOUNT_COLORS[2] }))

    expect(onChange).toHaveBeenCalledWith(ACCOUNT_COLORS[2])
  })
})
