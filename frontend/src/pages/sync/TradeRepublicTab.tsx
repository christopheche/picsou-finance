import { useState, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import {
  RefreshCw,
  Upload,
  LogOut,
  Smartphone,
  Lock,
  ShieldCheck,
  AlertTriangle,
} from 'lucide-react'
import { formatTrAuthError } from '@/lib/errors'
import { formatDateTime } from '@/lib/utils'
import { TR_VERIFICATION_CODE_LENGTH } from '@/lib/constants'
import {
  useTrSessionStatus,
  useInitiateTrAuth,
  useCompleteTrAuth,
  useSyncTradeRepublic,
  useImportTrCsv,
  useClearTrSession,
} from '@/features/sync/hooks'

type AuthState = 'IDLE' | 'AWAITING_TAN' | 'CONNECTED' | 'ERROR'

export function TradeRepublicTab() {
  const { t } = useTranslation()
  const fileInputRef = useRef<HTMLInputElement>(null)

  const [authState, setAuthState] = useState<AuthState>('IDLE')
  const [phone, setPhone] = useState('')
  const [pin, setPin] = useState('')
  const [tan, setTan] = useState('')
  const [processId, setProcessId] = useState<string | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  // Every TR call goes through the shared feature hooks so this tab and the
  // dashboard's "Sync all" modal read and invalidate the very same query key --
  // a login or logout here is visible there immediately.
  const { data: sessionStatus, isLoading: statusLoading } = useTrSessionStatus()
  const initiateMutation = useInitiateTrAuth()
  const submitTanMutation = useCompleteTrAuth()
  const syncMutation = useSyncTradeRepublic()
  const importCsvMutation = useImportTrCsv()
  const logoutMutation = useClearTrSession()

  // Derive auth state from session status
  const effectiveState = sessionStatus?.isActive ? 'CONNECTED' : authState

  function handleInitiate(e: React.FormEvent) {
    e.preventDefault()
    initiateMutation.mutate(
      { phoneNumber: phone, pin },
      {
        onSuccess: (data) => {
          setProcessId(data.processId)
          setTan('')
          setAuthState('AWAITING_TAN')
          setErrorMsg(null)
        },
        onError: (error: unknown) => {
          setErrorMsg(formatTrAuthError(error, t))
          setProcessId(null)
          setTan('')
          setAuthState('IDLE')
        },
      },
    )
  }

  function handleTan(e: React.FormEvent) {
    e.preventDefault()
    if (!processId || tan.length !== TR_VERIFICATION_CODE_LENGTH) return
    submitTanMutation.mutate(
      { processId, tan },
      {
        onSuccess: () => {
          setAuthState('IDLE')
          setTan('')
          setPhone('')
          setPin('')
          setProcessId(null)
          setErrorMsg(null)
        },
        onError: (error: unknown) => {
          setErrorMsg(formatTrAuthError(error, t))
          setTan('')
          setAuthState('AWAITING_TAN')
        },
      },
    )
  }

  function handleCsvChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    importCsvMutation.mutate(file, {
      onSuccess: () => {
        if (fileInputRef.current) fileInputRef.current.value = ''
      },
    })
  }

  function handleClearSession() {
    logoutMutation.mutate(undefined, {
      onSuccess: () => {
        setAuthState('IDLE')
        setPhone('')
        setPin('')
        setTan('')
        setProcessId(null)
      },
    })
  }

  function handleRetry() {
    setErrorMsg(null)
    if (authState === 'ERROR') {
      setAuthState('IDLE')
      setProcessId(null)
      setTan('')
    }
  }

  if (statusLoading) {
    return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  }

  return (
    <div className="space-y-6">
      {/* Session status card */}
      <Card size="sm">
        <CardContent className="py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              {effectiveState === 'CONNECTED' ? (
                <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">
                  {t('sync.tr.sessionActive')}
                </Badge>
              ) : (
                <Badge variant="outline">
                  {t('sync.tr.noSession')}
                </Badge>
              )}
              {effectiveState === 'CONNECTED' && sessionStatus?.expiresAt && (
                <span className="text-sm text-muted-foreground">
                  {t('sync.tr.expiresAt')} {formatDateTime(sessionStatus.expiresAt)}
                </span>
              )}
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Error state */}
      {(authState === 'ERROR' || errorMsg) && (
        <Card size="sm" className="border-destructive/30">
          <CardContent className="py-4">
            <div className="flex items-center gap-3">
              <AlertTriangle className="size-5 text-destructive shrink-0" />
              <p className="text-sm text-destructive flex-1">{errorMsg}</p>
              <Button size="sm" variant="outline" onClick={handleRetry}>
                {t('sync.banks.retry')}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Connected controls */}
      {effectiveState === 'CONNECTED' && (
        <div className="flex flex-wrap gap-3">
          <Button onClick={() => syncMutation.mutate()} disabled={syncMutation.isPending}>
            <RefreshCw />
            {t('sync.tr.sync')}
          </Button>

          <Button variant="outline" onClick={() => fileInputRef.current?.click()} disabled={importCsvMutation.isPending}>
            <Upload />
            {t('sync.tr.importCsv')}
          </Button>

          <Button variant="destructive" onClick={handleClearSession} disabled={logoutMutation.isPending}>
            <LogOut />
            {t('sync.tr.clearSession')}
          </Button>

          <input
            ref={fileInputRef}
            type="file"
            accept=".csv"
            className="hidden"
            onChange={handleCsvChange}
          />
        </div>
      )}

      {/* IDLE: Phone + PIN form */}
      {effectiveState === 'IDLE' && authState !== 'ERROR' && (
        <form onSubmit={handleInitiate} className="space-y-4">
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="tr-phone">
                  <Smartphone className="size-4 inline-block mr-1" />
                  {t('sync.tr.phone')}
                </Label>
                <Input
                  id="tr-phone"
                  type="tel"
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                  required
                  placeholder="+49..."
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="tr-pin">
                  <Lock className="size-4 inline-block mr-1" />
                  {t('sync.tr.pin')}
                </Label>
                <Input
                  id="tr-pin"
                  type="password"
                  value={pin}
                  onChange={(e) => setPin(e.target.value)}
                  required
                />
              </div>

              <Button type="submit" disabled={initiateMutation.isPending} className="w-full">
                {t('sync.tr.connect')}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}

      {/* AWAITING_TAN: TAN input */}
      {effectiveState === 'AWAITING_TAN' && (
        <form onSubmit={handleTan} className="space-y-4">
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="tr-tan">
                  <ShieldCheck className="size-4 inline-block mr-1" />
                  {t('sync.tr.tan')}
                </Label>
                <Input
                  id="tr-tan"
                  value={tan}
                  onChange={(e) => setTan(e.target.value.replace(/\D/g, '').slice(0, TR_VERIFICATION_CODE_LENGTH))}
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  maxLength={TR_VERIFICATION_CODE_LENGTH}
                  required
                  autoFocus
                />
              </div>

              <Button type="submit" disabled={submitTanMutation.isPending || tan.length !== TR_VERIFICATION_CODE_LENGTH} className="w-full">
                {t('sync.tr.connect')}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}
    </div>
  )
}
