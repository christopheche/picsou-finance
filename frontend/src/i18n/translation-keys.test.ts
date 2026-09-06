import { describe, expect, it } from 'vitest'
import en from './locales/en.json'

/**
 * locales-parity.test.ts compares the four JSON files with each other, so it can
 * never see a key the *code* asks for but no locale defines: `t('settings.usernameTooShort')`
 * rendered the raw key string in the username form for as long as it existed.
 * This test closes the other direction — every literal `t('…')` key in the source
 * must resolve in the English reference bundle.
 */

type TranslationNode = string | TranslationNode[] | { [key: string]: TranslationNode }

// Vite's glob import, not node:fs — the app tsconfig has no node types.
const sources = import.meta.glob('/src/**/*.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

function collectKeys(tree: TranslationNode, prefix = '', into = new Set<string>()): Set<string> {
  if (typeof tree === 'string') {
    into.add(prefix)
    return into
  }
  if (Array.isArray(tree)) {
    tree.forEach((value, index) => collectKeys(value, `${prefix}.${index}`, into))
    return into
  }
  for (const [key, value] of Object.entries(tree)) {
    collectKeys(value, prefix ? `${prefix}.${key}` : key, into)
  }
  return into
}

// Only single-quoted string literals: a template literal or a variable is a
// dynamic key (e.g. `admin.members.${status}`) that this check cannot resolve.
const LITERAL_T_CALL = /\bt\(\s*'([A-Za-z0-9_.]+)'/g

describe('translation keys used in code', () => {
  const known = collectKeys(en as TranslationNode)

  // A namespace object is a legitimate target too: `t('setup.greetings', { returnObjects: true })`
  // resolves to an array whose leaves are flattened as `setup.greetings.0`, `.1`, …
  const isKnown = (key: string) =>
    known.has(key) || [...known].some((candidate) => candidate.startsWith(`${key}.`))

  it('all resolve in the English bundle', () => {
    const missing: string[] = []
    for (const [path, content] of Object.entries(sources)) {
      if (/\.(test|spec)\.tsx?$/.test(path)) continue
      for (const match of content.matchAll(LITERAL_T_CALL)) {
        if (!isKnown(match[1])) missing.push(`${match[1]} (${path})`)
      }
    }
    expect(missing).toEqual([])
    // Guard against the glob silently matching nothing and the test passing vacuously.
    expect(Object.keys(sources).length).toBeGreaterThan(100)
  })
})
