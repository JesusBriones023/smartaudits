import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { AuthProvider } from '../context/AuthContext'
import Login from '../pages/Login'
import Register from '../pages/Register'
import Layout from '../components/Layout'
import PublicLayout from '../components/PublicLayout'
import AvisoLegal from '../pages/AvisoLegal'
import PoliticaCookies from '../pages/PoliticaCookies'
import PoliticaPrivacidad from '../pages/PoliticaPrivacidad'

function show(Component) {
  return render(<AuthProvider>
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Component />
    </MemoryRouter>
  </AuthProvider>)
}

describe('Legal navigation in its real consumers', () => {
  it.each([
    ['Login', Login, true, 'mt-6 pt-6 border-t border-slate-100 flex flex-wrap justify-center gap-x-4 gap-y-1'],
    ['Register', Register, true, 'mt-6 pt-6 border-t border-slate-100 flex flex-wrap justify-center gap-x-4 gap-y-1'],
    ['Layout', Layout, false, 'flex items-center gap-4'],
    ['PublicLayout', PublicLayout, false, 'flex flex-wrap items-center justify-center gap-x-4 gap-y-1'],
  ])('%s preserves the four links, their order, attributes and visual variant', (_, Component, muted, wrapperClass) => {
    show(Component)
    const group = screen.getByRole('link', { name: 'Aviso legal', exact: true }).parentElement
    expect(group.tagName).toBe('DIV')
    expect(group).toHaveAttribute('class', wrapperClass)
    const links = within(group).getAllByRole('link')
    expect(links.map(link => [link.textContent.trim(), link.getAttribute('href')])).toEqual([
      ['Aviso legal', '/aviso-legal'], ['Privacidad', '/politica-privacidad'],
      ['Cookies', '/politica-cookies'], ['Condiciones de uso', '/condiciones-uso'],
    ])
    for (const link of links) {
      expect(link).not.toHaveAttribute('target')
      expect(link).not.toHaveAttribute('rel')
      expect(link).toHaveAttribute('class', muted
        ? 'text-xs text-slate-400 hover:text-slate-600 transition-colors'
        : 'text-xs text-slate-500 hover:text-primary-600 transition-colors')
    }
    expect([...group.children].map(child => child.tagName)).toEqual(['A', 'SPAN', 'A', 'SPAN', 'A', 'SPAN', 'A'])
    for (const separator of within(group).getAllByText('·')) {
      expect(separator).toHaveAttribute('class', muted ? 'text-slate-200 text-xs' : 'text-slate-300 text-xs')
    }
  })
})

const rgpd = 'https://eur-lex.europa.eu/legal-content/ES/TXT/?uri=CELEX%3A32016R0679'
const lssi = 'https://www.boe.es/buscar/act.php?id=BOE-A-2002-13758'
const lopdgdd = 'https://www.boe.es/buscar/act.php?id=BOE-A-2018-16673'
const clientela = 'https://www.boe.es/eli/es/l/2025/12/26/10'
const eprivacy = 'https://eur-lex.europa.eu/legal-content/ES/TXT/?uri=CELEX%3A32002L0058'

describe('External normative links in legal pages', () => {
  it.each([
    ['AvisoLegal', AvisoLegal, '6. Legislación aplicable y jurisdicción', [
      ['LSSI-CE', 'Ley 34/2002 — Art. 10: Información general obligatoria del prestador', lssi],
      ['RGPD', 'Reglamento (UE) 2016/679 — Protección de datos personales', rgpd],
      ['LOPDGDD', 'Ley Orgánica 3/2018 — Adaptación española del RGPD', lopdgdd],
      ['LPI', 'Real Decreto Legislativo 1/1996 — Propiedad Intelectual', 'https://www.boe.es/buscar/act.php?id=BOE-A-1996-8930'],
      ['Ley 10/2025', 'Ley de Atención a la Clientela — Modifica LOPDGDD y Telecomunicaciones', clientela],
    ]],
    ['PoliticaCookies', PoliticaCookies, 'Base legal y normativa aplicable', [
      ['LSSI-CE', 'Art. 22.2 — Base legal para cookies y almacenamiento local', lssi],
      ['ePrivacy', 'Directiva 2002/58/CE — Art. 5.3: almacenamiento en terminales', eprivacy],
      ['RGPD', 'Reglamento (UE) 2016/679 — Protección de datos en cookies', rgpd],
      ['Guía AEPD', 'Guía sobre el uso de cookies — Actualizada julio 2023', 'https://www.aepd.es/guias/guia-cookies.pdf'],
    ]],
    ['PoliticaPrivacidad', PoliticaPrivacidad, 'Normativa de referencia', [
      ['RGPD', 'Reglamento (UE) 2016/679 — Marco general de protección de datos', rgpd],
      ['LOPDGDD', 'Ley Orgánica 3/2018 — Adaptación española del RGPD', lopdgdd],
      ['LSSI-CE', 'Ley 34/2002 — Servicios de la Sociedad de la Información', lssi],
      ['Ley 10/2025', 'Ley de Atención a la Clientela — Modifica LOPDGDD', clientela],
      ['AEPD', 'Agencia Española de Protección de Datos', 'https://www.aepd.es'],
      ['ePrivacy', 'Directiva 2002/58/CE — Privacidad en comunicaciones', eprivacy],
    ]],
  ])('%s preserves its specific list, accessible names and external link attributes', (_, Component, heading, expected) => {
    show(Component)
    const section = screen.getByRole('heading', { name: heading }).parentElement
    const links = within(section).getAllByRole('link')
    expect(links).toHaveLength(expected.length)
    links.forEach((link, index) => {
      const [label, description, href] = expected[index]
      expect(link).toHaveAccessibleName(`${label} ${description}`)
      expect(link).toHaveAttribute('href', href)
      expect(link).toHaveAttribute('target', '_blank')
      expect(link).toHaveAttribute('rel', 'noopener noreferrer')
      expect(link).toHaveAttribute('class', 'flex items-center space-x-3 p-3 bg-slate-50 border border-slate-200 rounded-xl hover:border-primary-300 hover:bg-primary-50 transition-colors group')
      expect(within(link).getByText(label)).toHaveAttribute('class', 'text-xs font-bold text-primary-700 bg-primary-100 px-2 py-1 rounded-lg flex-shrink-0')
      expect(within(link).getByText(description)).toHaveAttribute('class', 'text-xs text-slate-600 group-hover:text-primary-700 transition-colors flex-1')
      expect(link.querySelector('svg')).toHaveAttribute('class', 'w-4 h-4 text-slate-400 group-hover:text-primary-500 flex-shrink-0')
    })
  })
})
