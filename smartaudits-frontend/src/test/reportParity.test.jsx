import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import api from '../api/axios'
import DetalleAuditoria from '../pages/DetalleAuditoria'
import AuditoriaPdfDocument from '../pdf/AuditoriaPdfDocument'
import { buildAuditReport } from '../report/auditReport'
import { REPORT_LABELS, REPORT_POLICIES, REPORT_SECTIONS, reportHeader, renderReportText, seccionesInforme } from '../report/reportPresentation'
import { auditoriaCompleta, auditoriaLegado, auditoriaPuntuacionNull } from './fixtures/auditReportFixtures'

vi.mock('../api/axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }))

// Resolve the actual document and its local components, keeping primitive PDF
// nodes. No renderer or report content is substituted by the test.
function pdfTree(element) {
  if (element == null || typeof element === 'boolean') return []
  if (Array.isArray(element)) return element.flatMap(pdfTree)
  if (typeof element !== 'object') return [String(element)]
  if (typeof element.type === 'function') return pdfTree(element.type(element.props))
  return [{ props: element.props, children: pdfTree(element.props.children) }]
}

function pdfNodes(tree, attribute, value) {
  return tree.flatMap(node => typeof node === 'string' ? [] : [
    ...((value === undefined ? node.props[attribute] !== undefined : node.props[attribute] === value) ? [node] : []),
    ...pdfNodes(node.children, attribute, value),
  ])
}

const pdfText = tree => tree.map(node => typeof node === 'string' ? node : pdfText(node.children)).join('')
const findingFields = ['title', 'severity', 'description', 'evidence', 'impact', 'action']

function expectedSections(report, policy) {
  return seccionesInforme(report, policy).map(section => ({
    id: section.id, title: section.title,
    content: section.kind === 'text' ? section.text
      : section.kind === 'findings' ? section.items.map(item => Object.fromEntries(findingFields.map(key => [key, item[key]])))
        : section.items,
  }))
}

const heading = text => text.replace(/^[^\p{L}]+/u, '').replace(/ \(\d+\)$/, '').trim()

function screenSections(container) {
  return [...container.querySelectorAll('[data-report-section]')].map(section => {
    const id = section.dataset.reportSection
    const field = name => section.querySelector(`[data-report-field="${name}"]`)
    return {
      id, title: heading(field('heading').textContent),
      content: id === 'findings'
        ? [...section.querySelectorAll('[data-report-field="finding"]')].map(item => Object.fromEntries(
          findingFields.map(key => [key, item.querySelector(`[data-report-field="${key}"]`).textContent])))
        : field('text') ? field('text').textContent
          : [...section.querySelectorAll('[data-report-field="item"]')].map(item => item.textContent),
    }
  })
}

function pdfSections(tree) {
  return pdfNodes(tree, 'data-report-section').map(section => {
    const id = section.props['data-report-section']
    const fields = key => pdfNodes(section.children, 'data-report-field', key)
    return {
      id, title: heading(pdfText(fields('heading'))),
      content: id === 'findings'
        ? fields('finding').map(item => Object.fromEntries(findingFields.map(key =>
          [key, pdfText(pdfNodes(item.children, 'data-report-field', key))])))
        : fields('text').length ? pdfText(fields('text')) : fields('item').map(item => pdfText([item])),
    }
  })
}

function copySections(text) {
  const headings = [...text.matchAll(/^=== (.+) ===\n/gm)]
  return headings.map((match, index) => {
    const section = REPORT_SECTIONS.find(item => item.title.toUpperCase() === match[1])
    expect(section, `Unknown copy heading: ${match[1]}`).toBeDefined()
    const start = match.index + match[0].length
    const end = index + 1 < headings.length ? headings[index + 1].index - 2 : text.length
    const body = text.slice(start, end)
    let content
    if (section.kind === 'text') content = body
    else if (body === section.emptyText) content = []
    else if (section.kind === 'findings') {
      content = body.split('\n\n').map(item => {
        const lines = item.split('\n')
        const first = lines.shift().match(/^\d+\. (.*) \[(.*)\]$/)
        expect(first).not.toBeNull()
        const entry = { title: first[1], severity: first[2] }
        for (const key of findingFields.slice(2)) {
          const prefix = `   ${REPORT_LABELS[key]}: `
          const line = lines.shift()
          expect(line.startsWith(prefix)).toBe(true)
          entry[key] = line.slice(prefix.length)
        }
        expect(lines).toEqual([])
        return entry
      })
    } else {
      content = body.split(/^\d+\. /m).slice(1)
        .map((item, i, all) => i + 1 < all.length ? item.slice(0, -1) : item)
    }
    return { id: section.id, title: section.title, content }
  })
}

describe('semantic parity of the active report outputs', () => {
  it.each([null, 'DESCONOCIDA'])('keeps the legacy visual fallback for severity %j without classifying it as BAJA', async severity => {
    const audit = { ...auditoriaLegado, resultado: {
      ...auditoriaLegado.resultado,
      errores: [{ titulo: 'Hallazgo sin nivel', severidad: severity }],
    } }
    const report = buildAuditReport(audit)
    expect(report.findings[0].severityLevel).toBeNull()
    api.get.mockResolvedValue({ data: audit })
    const { container } = render(<MemoryRouter initialEntries={[`/auditoria/${audit.id}`]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes><Route path="/auditoria/:id" element={<DetalleAuditoria />} /></Routes>
    </MemoryRouter>)
    await screen.findByRole('heading', { name: audit.titulo })
    const badge = container.querySelector('[data-report-field="severity"]')
    expect(badge).toHaveClass('badge-baja')
    expect(badge.textContent).toBe(severity ?? '')
    const tree = pdfTree(<AuditoriaPdfDocument report={report} />)
    const [pdfSeverity] = pdfNodes(tree, 'data-report-field', 'severity')
    expect(pdfSeverity.props.style).toEqual(expect.arrayContaining([
      expect.objectContaining({ backgroundColor: '#DCFCE7', color: '#15803D' }),
    ]))
    expect(pdfText([pdfSeverity])).toBe(severity ?? '')
    expect(report.findings[0].severityLevel).toBeNull()
  })

  it.each([
    ['complete', auditoriaCompleta], ['historical', auditoriaLegado], ['unavailable score', auditoriaPuntuacionNull],
  ])('preserves the header, section order and every visible field: %s', async (_, audit) => {
    const before = structuredClone(audit)
    const report = buildAuditReport(audit)
    api.get.mockResolvedValue({ data: audit })
    const { container } = render(<MemoryRouter initialEntries={[`/auditoria/${audit.id}`]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes><Route path="/auditoria/:id" element={<DetalleAuditoria />} /></Routes>
    </MemoryRouter>)
    await screen.findByRole('heading', { name: audit.titulo })
    const tree = pdfTree(<AuditoriaPdfDocument report={report} />)
    const copied = renderReportText(report)

    expect(screenSections(container)).toEqual(expectedSections(report, REPORT_POLICIES.screen))
    expect(pdfSections(tree)).toEqual(expectedSections(report, REPORT_POLICIES.pdf))
    expect(copySections(copied)).toEqual(expectedSections(report, REPORT_POLICIES.copy))

    const header = reportHeader(report)
    for (const key of ['title', 'documentType', 'date', 'score', 'level']) {
      expect(container.querySelector(`[data-report-field="${key}"]`)?.textContent ?? null).toBe(header[key])
      const nodes = pdfNodes(tree, 'data-report-field', key)
      expect(nodes.length ? pdfText([nodes[0]]) : null).toBe(header[key])
    }
    const lines = copied.slice(0, copied.indexOf('\n\n')).split('\n')
    expect(lines[0]).toBe(`AUDITORÍA LEGAL — ${header.title}`)
    expect(lines[1]).toBe(`Tipo: ${header.documentType}`)
    expect(lines[2]).toBe(`Fecha: ${header.date}`)
    const score = lines.find(line => line.startsWith('Puntuación de Cumplimiento: '))
    expect(score?.slice('Puntuación de Cumplimiento: '.length).replace(/\/100$/, '')).toBe(header.score)
    const level = lines.find(line => line.startsWith('Clasificación: '))
    expect(level?.slice('Clasificación: '.length) ?? null).toBe(header.level)
    expect(lines).toContain(`URL de referencia: ${header.referenceUrl}`)
    expect(pdfText(pdfNodes(tree, 'data-report-field', 'referenceUrl'))).toBe(header.referenceUrl)

    const reference = container.querySelector('[data-report-field="referenceUrl"]')
    if (report.metadata.referenceUrl.href) expect(reference).toHaveAttribute('href', report.metadata.referenceUrl.href)
    else {
      expect(reference).not.toHaveAttribute('href')
      expect(reference).toHaveAttribute('title', report.metadata.referenceUrl.text)
    }
    expect(container.querySelector('script, img, [onerror], [onclick], a[href^="javascript:"]')).toBeNull()
    const outputs = [container.textContent, copied, pdfText(tree)]
    for (const output of outputs) {
      for (const hiddenValue of ['Propietario reservado', 'reservado@example.invalid', 'motor-superior-2',
        'reglas-superiores-1', '2026-01-01T11:00:00', 'MANUAL', 'R05', 'LEGAL_TEXT', 'regla-1', 'regla-2']) {
        expect(output).not.toContain(hiddenValue)
      }
      expect(output).not.toMatch(/\b(?:undefined|null)\b/)
    }
    expect(audit).toEqual(before)
    expect(buildAuditReport(audit)).toEqual(report)
  })
})
