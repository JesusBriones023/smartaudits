import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { pdf } from '@react-pdf/renderer'
import api from '../api/axios'
import DetalleAuditoria from '../pages/DetalleAuditoria'
import AuditoriaPdfDocument from '../pdf/AuditoriaPdfDocument'

vi.mock('../api/axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
// Keep the real PDF document; replace only the binary rendering boundary here.
// auditPdf.test.jsx additionally exercises the actual renderer without a browser.
vi.mock('@react-pdf/renderer', async importOriginal => ({
  ...await importOriginal(), pdf: vi.fn(),
}))

const audit = {
  id: 42, titulo: 'Informe / privado', tipoDocumento: 'Aviso Legal',
  fechaCreacion: '2026-01-02T12:00:00', puntuacionRiesgo: 62,
  urlOpcional: 'https://example.invalid/legal', textoOriginal: '<script>alert("texto")</script>',
  resultado: {
    resumen: 'Resumen sintético', riesgos: ['Plazo ausente'],
    errores: [{ titulo: 'Conservación', severidad: 'MEDIA', descripcion: 'Falta plazo',
      evidencia: 'Sin duración', impacto: 'Conservación excesiva', accion: 'Indicar plazo' }],
    recomendaciones: ['Definir duración'], textosSugeridos: ['Conservamos durante un año.'],
    faltantes: ['Duración'], referenciasLegales: ['RGPD'],
  },
}

beforeEach(() => {
  api.get.mockResolvedValue({ data: audit })
  api.post.mockResolvedValue({})
})

async function showDetail() {
  const view = render(<MemoryRouter initialEntries={['/auditoria/42']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
    <Routes><Route path="/auditoria/:id" element={<DetalleAuditoria />} /></Routes>
  </MemoryRouter>)
  await screen.findByRole('heading', { name: audit.titulo })
  return view
}

describe('Active audit exports', () => {
  it('copies the complete report as plain text, including every analysis section and the original input', async () => {
    const user = userEvent.setup()
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    const { container } = await showDetail()
    await user.click(screen.getByRole('button', { name: /Copiar Informe Completo/ }))
    expect(writeText).toHaveBeenCalledTimes(1)
    const copied = writeText.mock.calls[0][0]
    expect(copied).toContain(`AUDITORÍA LEGAL — ${audit.titulo}`)
    expect(copied).toContain('Puntuación de Cumplimiento: 62/100')
    expect(copied).toContain(`URL de referencia: ${audit.urlOpcional}`)
    for (const text of ['Resumen sintético', 'Plazo ausente', 'Conservación [MEDIA]', 'Falta plazo',
      'Evidencia: Sin duración', 'Impacto: Conservación excesiva', 'Acción: Indicar plazo',
      'Definir duración', 'Conservamos durante un año.', 'Duración', 'RGPD', audit.textoOriginal]) {
      expect(copied).toContain(text)
    }
    expect(screen.getByRole('button', { name: /Copiado/ })).toBeVisible()
    expect(container.querySelector('script')).toBeNull()
  })

  it('generates a PDF through the active document, downloads it and records the successful download', async () => {
    let finishPdf
    const pending = new Promise(resolve => { finishPdf = resolve })
    const toBlob = vi.fn().mockReturnValue(pending)
    pdf.mockReturnValue({ toBlob })
    const createObjectURL = vi.fn().mockReturnValue('blob:synthetic-pdf')
    vi.stubGlobal('URL', class extends URL {
      static createObjectURL = createObjectURL
      static revokeObjectURL = vi.fn()
    })
    const downloads = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () {
      downloads.push({ href: this.href, filename: this.download })
    })
    await showDetail()
    fireEvent.click(screen.getByRole('button', { name: /Descargar PDF/ }))
    await waitFor(() => expect(toBlob).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('button', { name: /Generando PDF/ })).toBeDisabled()
    expect(api.post).not.toHaveBeenCalled()
    const document = pdf.mock.calls[0][0]
    expect(document.type).toBe(AuditoriaPdfDocument)
    expect(document.props.auditoria).toEqual(audit)
    const blob = new Blob(['synthetic pdf'], { type: 'application/pdf' })
    await act(async () => finishPdf(blob))
    expect(createObjectURL).toHaveBeenCalledWith(blob)
    expect(downloads).toEqual([{ href: 'blob:synthetic-pdf', filename: 'Informe - Informe _ privado.pdf' }])
    expect(api.post).toHaveBeenCalledExactlyOnceWith('/auditorias/42/descarga')
    expect(screen.getByRole('button', { name: /Descargar PDF/ })).toBeEnabled()
  })

  it('shows PDF generation errors without recording a download and leaves the report usable', async () => {
    pdf.mockReturnValue({ toBlob: vi.fn().mockRejectedValue(new Error('Synthetic rendering failure')) })
    vi.spyOn(console, 'error').mockImplementation(() => {})
    await showDetail()
    fireEvent.click(screen.getByRole('button', { name: /Descargar PDF/ }))
    expect(await screen.findByText('No se pudo generar el PDF. Inténtalo de nuevo.')).toBeVisible()
    expect(api.post).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: /Descargar PDF/ })).toBeEnabled()
    expect(screen.getByRole('button', { name: /Copiar Informe Completo/ })).toBeEnabled()
  })

  it.each(['http://example.invalid/legal', 'https://example.invalid/legal'])('keeps safe reference URL %s navigable with its existing protections', async url => {
    api.get.mockResolvedValue({ data: { ...audit, urlOpcional: url } })
    await showDetail()
    const link = screen.getByRole('link', { name: 'Ver URL de referencia' })
    expect(link).toHaveAttribute('href', url)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.getByRole('link', { name: /RGPD/ })).toHaveAttribute('href', '/normativa/rgpd')
  })
})
