import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useParams, useNavigate } from 'react-router-dom'
import { useActivateAccount } from '@/features/auth/hooks'
import { formatApiError } from '@/lib/errors'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'

/** How long the success card stays up before the user lands on /login. */
const REDIRECT_DELAY_MS = 2000

export function ActivationPage() {
  const { t } = useTranslation()
  const { token } = useParams<{ token: string }>()
  const navigate = useNavigate()
  const activate = useActivateAccount()
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [acknowledged, setAcknowledged] = useState(false)
  const [validationError, setValidationError] = useState<string | null>(null)

  // Redirect from an effect (not from the submit handler) so the timer is cleared
  // if the user navigates away first -- a navigate() on an unmounted page would
  // otherwise yank them back to /login from wherever they went.
  useEffect(() => {
    if (!activate.isSuccess) return
    const id = window.setTimeout(() => navigate('/login'), REDIRECT_DELAY_MS)
    return () => window.clearTimeout(id)
  }, [activate.isSuccess, navigate])

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setValidationError(null)
    activate.reset()

    if (!acknowledged) {
      setValidationError(t('auth.activation.acknowledgeRequired'))
      return
    }
    if (password.length < 8) {
      setValidationError(t('auth.activation.passwordTooShort'))
      return
    }
    if (password !== confirmPassword) {
      setValidationError(t('auth.activation.passwordMismatch'))
      return
    }
    if (!token) {
      setValidationError(t('auth.activation.invalidLink'))
      return
    }

    activate.mutate({ token, password })
  }

  if (activate.isSuccess) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background p-4">
        <Card className="w-full max-w-md">
          <CardHeader>
            <CardTitle>{t('auth.activation.successTitle')}</CardTitle>
            <CardDescription>{t('auth.activation.successDescription')}</CardDescription>
          </CardHeader>
        </Card>
      </div>
    )
  }

  const error =
    validationError ??
    (activate.isError ? formatApiError(activate.error, t, 'auth.activation.failed') : null)

  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-4">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>{t('auth.activation.title')}</CardTitle>
          <CardDescription>{t('auth.activation.description')}</CardDescription>
        </CardHeader>
        <CardContent>
          {/* Warning */}
          <div className="mb-6 rounded-lg border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-amber-700 dark:text-amber-400">
            <p className="font-semibold mb-1">{t('auth.activation.warningTitle')}</p>
            <p>{t('auth.activation.warningBody')}</p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="flex items-start gap-2">
              <input
                type="checkbox"
                id="acknowledge"
                checked={acknowledged}
                onChange={(e) => setAcknowledged(e.target.checked)}
                className="mt-1"
              />
              <Label htmlFor="acknowledge" className="text-sm">
                {t('auth.activation.acknowledgeLabel')}
              </Label>
            </div>

            <div className="space-y-2">
              <Label htmlFor="password">{t('auth.activation.passwordLabel')}</Label>
              <Input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={t('auth.activation.passwordPlaceholder')}
                minLength={8}
                required
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="confirmPassword">{t('auth.activation.confirmPasswordLabel')}</Label>
              <Input
                id="confirmPassword"
                type="password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                placeholder={t('auth.activation.confirmPasswordPlaceholder')}
                required
              />
            </div>

            {error && (
              <p role="alert" className="text-sm text-destructive">{error}</p>
            )}

            <Button type="submit" className="w-full" disabled={!acknowledged || activate.isPending}>
              {activate.isPending
                ? t('auth.activation.submitting')
                : t('auth.activation.submit')}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
