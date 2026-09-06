import '@testing-library/jest-dom'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const PEM = 'PUBLIC-KEY-PEM-CONTENT'

const generate = vi.hoisted(() => ({ isPending: false, isSuccess: false, isError: false, error: null }))

const { generateMutate, generateReset, importMutate, importReset, autoGenerate } = vi.hoisted(() => ({
  // `autoGenerate` lets a test simulate a backend that never answers, so the
  // manual escape hatch can be asserted on its own.
  autoGenerate: { enabled: true },
  generateMutate: vi.fn(),
  generateReset: vi.fn(),
  importMutate: vi.fn(),
  importReset: vi.fn(),
}))

vi.mock('@/features/setup/hooks', () => ({
  useGenerateEnableBankingKeyPair: () => ({
    mutate: generateMutate,
    reset: generateReset,
    ...generate,
  }),
  useImportEnableBankingPrivateKey: () => ({
    mutate: importMutate,
    reset: importReset,
    isPending: false,
  }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('react-router-dom', () => ({ useNavigate: () => vi.fn() }))

const { EBStep3Keypair } = await import('./EBStep3Keypair')
const { useSetupFlowStore } = await import('@/stores/setup-flow-store')

beforeEach(() => {
  useSetupFlowStore.getState().reset()
  autoGenerate.enabled = true
  generate.isPending = false
  generate.isSuccess = false
  generate.isError = false
  generateReset.mockReset().mockImplementation(() => {
    generate.isSuccess = false
  })
  generateMutate.mockReset().mockImplementation(
    (_vars: unknown, opts?: { onSuccess?: (d: { publicKeyPem: string }) => void }) => {
      if (!autoGenerate.enabled) return
      generate.isSuccess = true
      opts?.onSuccess?.({ publicKeyPem: PEM })
    },
  )
  importMutate.mockReset()
  importReset.mockReset()
})

function renderStep() {
  return render(<EBStep3Keypair onNext={vi.fn()} onBack={vi.fn()} />)
}

describe('EBStep3Keypair generate mode', () => {
  it('generates a key pair on arrival', () => {
    renderStep()

    expect(generateMutate).toHaveBeenCalledTimes(1)
    expect(screen.getByText(PEM)).toBeInTheDocument()
  })

  it('recovers when the user switches to Import and back to Generate', () => {
    // Regression: `handleSwitchMode` cleared the draft PEM but never reset the
    // mutation, so the `isSuccess` guard made the second visit to Generate a dead
    // end — no PEM, no loader, no button, and "Continue" permanently disabled.
    renderStep()
    expect(generateMutate).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByText('setup.enablebanking.keypair.modeImport'))
    expect(screen.queryByText(PEM)).not.toBeInTheDocument()

    fireEvent.click(screen.getByText('setup.enablebanking.keypair.modeGenerate'))

    expect(generateReset).toHaveBeenCalled()
    expect(generateMutate).toHaveBeenCalledTimes(2)
    expect(screen.getByText(PEM)).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'setup.enablebanking.keypair.uploadedCta' }),
    ).toBeEnabled()
  })

  it('offers an explicit Generate button when nothing has been generated yet', () => {
    autoGenerate.enabled = false
    renderStep()

    const button = screen.getByRole('button', { name: /keypair\.generate$/ })
    expect(button).toBeInTheDocument()

    autoGenerate.enabled = true
    fireEvent.click(button)
    expect(screen.getByText(PEM)).toBeInTheDocument()
  })
})
