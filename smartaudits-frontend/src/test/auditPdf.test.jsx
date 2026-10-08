// @vitest-environment node
import { renderToBuffer } from '@react-pdf/renderer'
import { expect, it } from 'vitest'
import AuditoriaPdfDocument from '../pdf/AuditoriaPdfDocument'
import { buildAuditReport } from '../report/auditReport'
import { auditoriaCompleta, auditoriaLegado, auditoriaPuntuacionNull } from './fixtures/auditReportFixtures'

it.each([
  ['complete', auditoriaCompleta],
  ['legacy', auditoriaLegado],
  ['unavailable score', auditoriaPuntuacionNull],
])('renders the real %s audit document into PDF bytes using local built-in fonts', async (_, fixture) => {
  const buffer = await renderToBuffer(<AuditoriaPdfDocument report={buildAuditReport(fixture)} />)
  expect(buffer.subarray(0, 5).toString()).toBe('%PDF-')
  expect(buffer.toString('latin1')).toContain('%%EOF')
}, 15000)
