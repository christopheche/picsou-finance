import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useForm, useFieldArray, Controller } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { PlusCircle, X, ShieldCheck, RotateCw } from 'lucide-react'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { formatApiError } from '@/lib/errors'
import { useUpdateSecurity, useReloadCorsFromEnv } from '@/features/admin/hooks'
import type { AdminSecuritySettings } from '@/features/admin/api'

const schema = z.object({
  allowedOrigins: z.array(z.string().min(1)).min(1),
  secureCookies: z.boolean(),
})

type FormValues = z.infer<typeof schema>

export function SecuritySection({ settings }: { settings: AdminSecuritySettings }) {
  const { t } = useTranslation()
  const update = useUpdateSecurity()
  const reloadCors = useReloadCorsFromEnv()

  const { register, handleSubmit, control, reset, formState } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: settings,
  })

  // Adopt the server's values only while the form is pristine. Every admin
  // mutation invalidates `adminKeys.settings()`, so an unconditional reset()
  // wiped origins the admin had typed here but not yet saved.
  const { isDirty } = formState
  useEffect(() => {
    if (isDirty) return
    reset(settings)
  }, [settings, reset, isDirty])

  const { fields, append, remove } = useFieldArray({ control, name: 'allowedOrigins' as never })

  const onSubmit = handleSubmit(async (values) => {
    const body = {
      allowedOrigins: values.allowedOrigins.filter((o) => o.trim().length > 0),
      secureCookies: values.secureCookies,
    }
    await update.mutateAsync(body)
    // Re-baseline on what the server accepted: the form goes pristine, the Save
    // button disables and the "Saved" note appears (and the effect above can
    // resume tracking the refetched settings).
    reset(body)
  })

  return (
    <Card className="rounded-4xl bg-card">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <ShieldCheck className="size-5 text-muted-foreground" />
          {t('admin.security.title')}
        </CardTitle>
        <CardDescription>{t('admin.security.description')}</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} className="space-y-5" noValidate>
          <div className="space-y-2">
            <Label>{t('admin.security.originsLabel')}</Label>
            <p className="text-sm text-muted-foreground">{t('admin.security.originsHint')}</p>
            <div className="space-y-2">
              {fields.map((field, idx) => (
                <div key={field.id} className="flex items-center gap-2">
                  <Input
                    placeholder="https://example.com"
                    {...register(`allowedOrigins.${idx}` as const)}
                  />
                  {fields.length > 1 && (
                    <Button type="button" variant="ghost" size="icon" onClick={() => remove(idx)}
                      aria-label={t('admin.security.originRemove')}>
                      <X className="h-4 w-4" />
                    </Button>
                  )}
                </div>
              ))}
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Button type="button" variant="outline" size="sm" onClick={() => append('')} className="mt-1">
                <PlusCircle className="mr-2 h-4 w-4" />
                {t('admin.security.originAdd')}
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="mt-1"
                onClick={() => reloadCors.mutate()}
                disabled={reloadCors.isPending}
                title={t('admin.security.reloadFromEnvHint')}
              >
                <RotateCw className="mr-2 h-4 w-4" />
                {reloadCors.isPending
                  ? t('admin.security.reloadFromEnvLoading')
                  : t('admin.security.reloadFromEnv')}
              </Button>
            </div>
            {reloadCors.error && (
              <p role="alert" className="text-sm text-destructive">
                {formatApiError(reloadCors.error, t)}
              </p>
            )}
            {reloadCors.isSuccess && (
              <p className="text-sm text-emerald-600">{t('admin.security.reloadFromEnvDone')}</p>
            )}
          </div>

          <div className="flex items-start justify-between gap-4 rounded-2xl border border-border/60 p-4">
            <div className="space-y-1">
              <Label htmlFor="admin-secure-cookies" className="text-sm font-medium">
                {t('admin.security.secureCookiesLabel')}
              </Label>
              <p className="text-sm text-muted-foreground">{t('admin.security.secureCookiesHint')}</p>
            </div>
            <Controller control={control} name="secureCookies"
              render={({ field }) => (
                <Switch id="admin-secure-cookies" checked={field.value} onCheckedChange={field.onChange} />
              )}
            />
          </div>

          {update.error && (
            <p role="alert" className="text-sm text-destructive">
              {formatApiError(update.error, t)}
            </p>
          )}

          <Button type="submit" disabled={update.isPending || !formState.isDirty} className="min-w-44">
            {update.isPending ? t('admin.security.saving') : t('admin.security.save')}
          </Button>
          {update.isSuccess && !formState.isDirty && (
            <span className="ml-3 text-sm text-emerald-600">{t('admin.security.saved')}</span>
          )}
        </form>
      </CardContent>
    </Card>
  )
}
