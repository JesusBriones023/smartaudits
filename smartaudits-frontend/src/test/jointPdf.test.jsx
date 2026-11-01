// @vitest-environment node
import { inflateSync } from 'node:zlib'
import { renderToBuffer } from '@react-pdf/renderer'
import { describe, expect, it } from 'vitest'
import AuditoriaConjuntaPdfDocument, { textoPdf } from '../pdf/AuditoriaConjuntaPdfDocument'
import { JOINT_LIMITATIONS, buildJointReport } from '../report/jointReportPresentation'
import { informePocosHallazgos, informeVariosDocumentos, payloadHostil } from './fixtures/jointReportFixtures'

// Test-only reader of the text drawn by react-pdf with the built-in fonts: inflates the
// content streams and decodes the hex strings of each TJ/Tj (WinAnsi = windows-1252).
function pdfLines(buffer) {
  const raw = buffer.toString('latin1')
  const decoder = new TextDecoder('windows-1252')
  const lines = []
  for (const match of raw.matchAll(/stream\r?\n([\s\S]*?)\r?\nendstream/g)) {
    let data
    try { data = inflateSync(Buffer.from(match[1], 'latin1')).toString('latin1') } catch { continue }
    for (const op of data.matchAll(/\[([^\]]*)\]\s*TJ|<([0-9a-fA-F]*)>\s*Tj/g)) {
      const parts = op[1] !== undefined ? [...op[1].matchAll(/<([0-9a-fA-F]*)>/g)].map(x => x[1]) : [op[2]]
      lines.push(parts.map(hex => decoder.decode(Buffer.from(hex, 'hex'))).join(''))
    }
  }
  return lines
}

async function render(informe) {
  const buffer = await renderToBuffer(<AuditoriaConjuntaPdfDocument informe={informe} />)
  return { buffer, text: pdfLines(buffer).join('\n') }
}

const pageCount = buffer => (buffer.toString('latin1').match(/\/Type \/Page\b/g) || []).length

describe('joint audit PDF', () => {
  it('produces a valid multi-page A4 PDF with every section of several documents', async () => {
    const { buffer, text } = await render(informeVariosDocumentos)
    expect(buffer.subarray(0, 5).toString()).toBe('%PDF-')
    expect(buffer.toString('latin1').trimEnd().endsWith('%%EOF')).toBe(true)
    expect(buffer.toString('latin1')).toMatch(/\/MediaBox \[0 0 595\.2\d* 841\.8\d*\]/)
    // Cover + one page per document (long texts overflow onto more pages) + final sections.
    expect(pageCount(buffer)).toBeGreaterThanOrEqual(5)
    for (const fragment of [
      'SmartAudits', 'Resumen de documentos', 'Documento 1: Política de Privacidad', 'Documento 3: Términos y Condiciones',
      'Puntuación de Cumplimiento', '62/100', '90/100', 'No disponible', 'Cumplimiento Bajo', 'Cumplimiento Alto',
      'Conservación excesiva de datos', 'Plazo de conservación ausente', 'ALTA', 'MEDIA', 'BAJA',
      'Recomendaciones agrupadas', 'Indicar plazos de conservación concretos', 'Documentos no aportados',
      'Política de Cookies', 'Condiciones de Uso', 'Limitaciones del análisis', 'Página 1 de',
    ]) {
      expect(text, fragment).toContain(fragment)
    }
    expect(text).toContain('No acredita por sí solo el cumplimiento normativo')
  }, 30000)

  it('keeps accents and special characters of user content', async () => {
    const { text } = await render(informeVariosDocumentos)
    for (const fragment of ['Ñandú', '«Tienda', '¿cumple?', '¡Revísalo!', 'Política & Términos', 'Cláusula']) {
      expect(text, fragment).toContain(fragment)
    }
    // The em dash is outside Latin-1 (react-pdf would drop it): drawn as an ASCII dash.
    expect(text).toContain('Ñandú» - Política')
  }, 30000)

  it('transliterates characters the built-in fonts cannot draw, keeping Latin-1 intact', () => {
    const C = n => String.fromCodePoint(n)
    expect(textoPdf('áéíóú ÁÉÍÓÚ ñÑ üÜ ¿¡ «» ç · ª º')).toBe('áéíóú ÁÉÍÓÚ ñÑ üÜ ¿¡ «» ç · ª º')
    expect(textoPdf('a' + C(0x2014) + 'b ' + C(0x2013) + ' c' + C(0x2026))).toBe('a-b - c...')
    expect(textoPdf(C(0x201C) + 'cita' + C(0x201D) + ' ' + C(0x2018) + 'x' + C(0x2019))).toBe(`"cita" 'x'`)
    expect(textoPdf('10 ' + C(0x20AC) + ' ' + C(0x2022) + ' Ce' + C(0x0161) + 'ko ' + C(0x0151))).toBe('10 EUR - Cesko o')
    expect(textoPdf('ok ' + C(0x1F600) + ' ' + C(0x6F22))).toBe('ok ? ?')
    expect(textoPdf('a' + C(0x200B) + 'b')).toBe('ab')
  })

  it('wraps long unbroken URLs and evidence instead of losing them', async () => {
    const { text } = await render(informeVariosDocumentos)
    const lines = text.split('\n')
    expect(lines.some(l => l.includes('https://www.ejemplo-de-'))).toBe(true)
    expect(lines.every(l => l.length < 200)).toBe(true)
    const flat = text.replace(/-?\n/g, '')
    expect(flat).toContain('x'.repeat(100))
    expect(flat).toContain('-sin-espacios-sin-espacios')
  }, 30000)

  it('lays out long reports with many findings on many pages (regression: react-pdf layout overflow)', async () => {
    const finding = i => ({ ruleId: 'R' + i, titulo: 'Hallazgo ' + i + ' sobre información obligatoria', severidad: ['ALTA', 'MEDIA', 'BAJA'][i % 3],
      descripcion: 'El documento no incluye la información exigida sobre el tratamiento de los datos. '.repeat(3),
      evidencia: i % 2 ? '' : 'Texto analizado sin base jurídica', impacto: 'Posible sanción. '.repeat(4), accion: 'Incluir la información. '.repeat(3) })
    const doc = id => ({ auditoriaId: id, titulo: 'Documento ' + id, tipoDocumento: 'Aviso Legal', urlOpcional: null, puntuacionRiesgo: 20 + id,
      resumen: 'Resumen del análisis. '.repeat(5), riesgos: Array.from({ length: 40 }, (_, i) => 'Riesgo ' + i),
      versionMotor: '2', versionReglas: '1', incidencias: Array.from({ length: 40 }, (_, i) => finding(i)) })
    const largo = { ...informePocosHallazgos, documentosAnalizados: 3, documentos: [doc(1), doc(2), doc(3)],
      recomendaciones: Array.from({ length: 40 }, (_, i) => ({ texto: 'Recomendación ' + i, auditoriaIds: [1, 2], documentos: ['Documento 1', 'Documento 2'] })) }
    const { buffer, text } = await render(largo)
    expect(pageCount(buffer)).toBeGreaterThan(10)
    expect(text).toContain('Hallazgo 39 sobre información obligatoria')
    expect(text).toContain('Recomendación 39')
    expect(text).toMatch(/Página \d+ de \d+/)
  }, 60000)

  it('renders a report with few findings and no recommendations', async () => {
    const { buffer, text } = await render(informePocosHallazgos)
    expect(buffer.subarray(0, 5).toString()).toBe('%PDF-')
    expect(text).toContain('Sin hallazgos detectados')
    expect(text).toContain('No hay recomendaciones.')
    expect(text).toContain('95/100')
    expect(text).not.toMatch(/global[^\n]*\d+\/100/i)
  }, 30000)

  it('draws hostile user content as plain text', async () => {
    const hostile = { ...informePocosHallazgos, titulo: payloadHostil,
      documentos: [{ ...informePocosHallazgos.documentos[0], resumen: payloadHostil }] }
    const { buffer, text } = await render(hostile)
    expect(text).toContain('<script>window.testXss()</script>')
    // No actions, scripts or annotations are created from user text.
    expect(buffer.toString('latin1')).not.toMatch(/\/(JavaScript|JS|OpenAction|Launch|URI)\b/)
  }, 30000)

  it('states the limitations without inventing a global score or legal references', async () => {
    const report = buildJointReport(informeVariosDocumentos)
    expect(report).not.toHaveProperty('globalScore')
    expect(report.limitations).toEqual(JOINT_LIMITATIONS)
    const { text } = await render(informeVariosDocumentos)
    expect(text.replace(/\n/g, ' ')).toContain('se basa únicamente en el HTML aportado por el usuario')
    expect(text).not.toMatch(/art(í|i)culo\s+\d+|RGPD art/i)
  }, 30000)
})
