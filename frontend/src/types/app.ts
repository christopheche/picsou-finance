// Frontend-only types. `Theme` lives in `lib/theme.ts` next to the code that uses it;
// the app's language codes come from `SUPPORTED_LOCALES` in `i18n/locales.ts` — never
// redeclare a locale union here, it drifts from the registry (an `'fr' | 'en'` copy used
// to sit here while the app shipped four languages).
export type { Theme } from '@/lib/theme'
