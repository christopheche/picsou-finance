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

/**
 * And the third direction: a key every locale defines but no code ever asks for.
 * Parity keeps the four files in step, so a dead string is dead four times over and
 * translators keep maintaining it; ~49 of them had piled up (the whole `realEstate.*`
 * namespace, superseded by `property.*`, among them) before this check existed.
 *
 * The scan is deliberately generous — it counts a key as used on any of:
 *   - the full dotted path appearing as a string literal anywhere in `src/`
 *     (not only inside `t()`: `labelKey:` maps and `i18nKey` props count too);
 *   - an *ancestor* path of two or more segments appearing as a literal, which covers
 *     namespace access — `labelNs="holdings.insight.countryNames"` and
 *     `t('setup.greetings', { returnObjects: true })`;
 *   - a template literal in the source matching the key once every `${…}` is treated
 *     as one path segment: `` t(`property.kind.${kind}`) `` and the mid-segment
 *     `` t(`property.add.step${step}Hint`) `` both resolve here.
 * A single-segment ancestor (`'goals'`, `'settings'`) is *not* enough: those words
 * appear as plain strings all over the app and would whitewash their whole namespace.
 * A key no rule can reach belongs in ALLOWED_DYNAMIC below, with a reason.
 */

// Any dotted path in quotes of any kind.
const DOTTED_LITERAL = /['"`]([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*)['"`]/g
// A template literal made only of key characters and `${…}` holes, e.g. `sync.finary.step${s}`.
// One starting with a hole (`` `${labelNs}.${raw}` ``) names no namespace, so it is skipped —
// the literal-ancestor rule is what covers those call sites.
const KEY_TEMPLATE = /`([A-Za-z0-9_.]+(?:\$\{[^`{}]*\}[A-Za-z0-9_.]*)+)`/g

// Keys reached in a way the scan cannot see. Empty on purpose: keep it that way.
const ALLOWED_DYNAMIC: string[] = []

function templateToPattern(body: string): RegExp {
  const segments = body.split(/\$\{[^{}]*\}/).map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  return new RegExp(`^${segments.join('[A-Za-z0-9_]+')}$`)
}

describe('translation keys defined in the bundles', () => {
  const literals = new Set<string>()
  const patterns: RegExp[] = []
  for (const [path, content] of Object.entries(sources)) {
    if (/\.(test|spec)\.tsx?$/.test(path)) continue
    for (const match of content.matchAll(DOTTED_LITERAL)) literals.add(match[1])
    for (const match of content.matchAll(KEY_TEMPLATE)) patterns.push(templateToPattern(match[1]))
  }

  const isUsed = (key: string) => {
    if (literals.has(key)) return true
    const segments = key.split('.')
    for (let cut = 2; cut < segments.length; cut++) {
      if (literals.has(segments.slice(0, cut).join('.'))) return true
    }
    return patterns.some((pattern) => pattern.test(key))
  }

  it('are all referenced from the source', () => {
    const allowed = new Set(ALLOWED_DYNAMIC)
    const unused = [...collectKeys(en as TranslationNode)]
      .filter((key) => !allowed.has(key) && !isUsed(key))
      .sort()
    expect(unused).toEqual([])
    // The rules are only as good as the scan: a glob or a regex that stops matching
    // would mark everything unused, or nothing.
    expect(patterns.length).toBeGreaterThan(20)
    expect(literals.size).toBeGreaterThan(500)
  })
})
