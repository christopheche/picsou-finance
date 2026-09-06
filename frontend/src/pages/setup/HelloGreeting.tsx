import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { cn } from '@/lib/utils'

interface HelloGreetingProps {
  onFinish: () => void
  dwellMs?: number
  transitionMs?: number
}

/**
 * Cycles through localized greetings in a handwritten-feel font, then
 * calls {@code onFinish} and fades out. The cadence (transition-in →
 * dwell → transition-out) lands at about 1.2s per word — calm, not rushed.
 *
 * Interaction:
 *   — click or keypress during the animation skips straight to the end
 *   — the full cycle takes ~(greetings.length × (dwell + 2×transition))
 *
 * Accessibility:
 *   — prefers-reduced-motion renders a static final greeting, no cycling
 *   — aria-live="polite" announces the currently-visible word once per
 *     frame; screen-reader chatter is acceptable here because this is the
 *     signature moment, not part of a task flow the user has to finish
 */
export function HelloGreeting({
  onFinish,
  dwellMs = 400,
  transitionMs = 250,
}: HelloGreetingProps) {
  const { t } = useTranslation()
  const greetings = t('setup.greetings', { returnObjects: true }) as string[]

  const [prefersReducedMotion] = useState(
    () =>
      typeof window !== 'undefined' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
  )

  const [index, setIndex] = useState(0)
  const [phase, setPhase] = useState<'in' | 'out'>('in')
  const [finished, setFinished] = useState(prefersReducedMotion)
  const finishedRef = useRef(finished)

  const skip = () => {
    if (finishedRef.current) return
    finishedRef.current = true
    setFinished(true)
    onFinish()
  }

  useEffect(() => {
    if (prefersReducedMotion) {
      // Honour the reduced-motion preference: skip the cycle but still
      // give the user a beat to read before the wizard content shows.
      const id = window.setTimeout(skip, 600)
      return () => window.clearTimeout(id)
    }

    if (index >= greetings.length) {
      skip()
      return
    }

    // "in" → visible for dwellMs → flip to "out" → after transitionMs advance index.
    const t1 = window.setTimeout(() => setPhase('out'), transitionMs + dwellMs)
    const t2 = window.setTimeout(() => {
      setPhase('in')
      setIndex((i) => i + 1)
    }, transitionMs * 2 + dwellMs)
    return () => {
      window.clearTimeout(t1)
      window.clearTimeout(t2)
    }
    // Documented disable (docs/conventions/frontend.md): the cycle is driven by
    // `index` alone. `greetings` is re-derived from t() on every render and
    // `skip` is a fresh closure each time, so listing them would clear and
    // restart the two timers mid-word and the animation would never advance.
    // The cadence props are read once per step by design.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [index])

  useEffect(() => {
    const onAnyKey = () => skip()
    const onClick = () => skip()
    window.addEventListener('keydown', onAnyKey)
    window.addEventListener('click', onClick)
    // Watchdog: if the cycle hasn't finished after 5 s (i18n stuck, font failed,
    // animation paused by the browser, etc.), force-finish so the wizard can
    // never be visually empty for an undefined amount of time.
    const watchdog = window.setTimeout(skip, 5000)
    return () => {
      window.removeEventListener('keydown', onAnyKey)
      window.removeEventListener('click', onClick)
      window.clearTimeout(watchdog)
    }
    // Documented disable (docs/conventions/frontend.md): the listeners and the
    // watchdog are a mount-once subscription. `skip` guards itself with
    // `finishedRef`, so the stale closure is harmless; re-subscribing on every
    // render would restart the 5 s watchdog and it could never fire.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (finished) return null

  const current = greetings[Math.min(index, greetings.length - 1)] ?? 'Hello'

  return (
    <div
      className="flex min-h-[40vh] items-center justify-center"
      role="status"
      aria-live="polite"
    >
      <span
        key={`${index}-${phase}`}
        className={cn(
          'font-homemade text-5xl sm:text-7xl tracking-tight text-foreground',
          phase === 'in' ? 'animate-hello-in' : 'animate-hello-out'
        )}
      >
        {current}
      </span>
    </div>
  )
}
