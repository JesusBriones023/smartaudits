// @vitest-environment node
import { renderToBuffer } from '@react-pdf/renderer'
import { expect, it } from 'vitest'
import AuditoriaPdfDocument from '../pdf/AuditoriaPdfDocument'

it('renders the real audit document into PDF bytes using local built-in fonts', async () => {
  const buffer = await renderToBuffer(<AuditoriaPdfDocument auditoria={{
    id: 42, titulo: 'Informe sintético', tipoDocumento: 'Aviso Legal',
    fechaCreacion: '2026-01-02T12:00:00', puntuacionRiesgo: 62,
    textoOriginal: '<script>texto literal</script>',
    resultado: {
      resumen: 'Resumen de prueba', riesgos: ['Plazo ausente'],
      errores: [{ titulo: 'Conservación', severidad: 'MEDIA', descripcion: 'Falta plazo',
        evidencia: 'Sin duración', impacto: 'Conservación excesiva', accion: 'Indicar plazo' }],
      recomendaciones: ['Definir duración'], textosSugeridos: ['Durante un año.'],
      faltantes: ['Duración'], referenciasLegales: ['RGPD'],
    },
  }} />)
  expect(buffer.subarray(0, 5).toString()).toBe('%PDF-')
  expect(buffer.toString('latin1')).toContain('%%EOF')
}, 15000)
