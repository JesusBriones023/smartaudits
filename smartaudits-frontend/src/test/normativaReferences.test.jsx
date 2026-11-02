import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { AuthProvider } from '../context/AuthContext'
import NormativaLey102025 from '../pages/NormativaLey102025'
import NormativaLOPDGDD from '../pages/NormativaLOPDGDD'
import NormativaLSSI from '../pages/NormativaLSSI'
import NormativaRGPD from '../pages/NormativaRGPD'

// Facts checked against the consolidated BOE texts of the RGPD, the LOPDGDD, the LSSI-CE and Ley 10/2025.
function show(Component) {
  const { container } = render(<AuthProvider>
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Component />
    </MemoryRouter>
  </AuthProvider>)
  return container
}
const openTab = label => fireEvent.click(screen.getByRole('button', { name: new RegExp(label) }))

describe('normative reference pages', () => {
  it('LSSI-CE: Art. 10 and cookie failures are not always serious, and Art. 38 is numbered as in the law', () => {
    const page = show(NormativaLSSI)
    expect(page.textContent).toContain('Leve; grave solo si falla de forma significativa la identidad o el contacto (Art. 38.3.b y 38.4.b)')
    expect(page.textContent).toContain('Leve (Art. 38.4.g): hasta 30.000€')
    expect(page.textContent).toContain('Grave si el envío es masivo, insistente o sistemático (Art. 38.3.c); en otro caso, leve')
    expect(page.textContent).toContain('El interés legítimo del RGPD no sustituye este consentimiento previo')
    expect(page.textContent).not.toMatch(/organismos sin fin de lucro|Infracción grave: hasta 150\.000€|AEPD 2025/)
    openTab('Infracciones')
    expect(page.textContent).toContain('Muy graves (Art. 38.2)')
    expect(page.textContent).toContain('Graves (Art. 38.3)')
    expect(page.textContent).toContain('Leves (Art. 38.4)')
    expect(page.textContent).toContain('6 meses')
    expect(page.textContent).not.toMatch(/Art\. 38\.1\)|5 años/)
  })

  it('LOPDGDD: the age of Art. 7 applies to consent, Art. 23 is about exclusion systems and Ley 10/2025 only changed Art. 23.1', () => {
    const page = show(NormativaLOPDGDD)
    expect(page.textContent).toContain('recoge la modificación del artículo 23.1 (sistemas de exclusión publicitaria) por la Ley 10/2025')
    expect(page.textContent).not.toContain('en materia de privacidad y telecomunicaciones')
    openTab('Artículos clave')
    expect(page.textContent).toContain('Cuando el tratamiento se basa en el consentimiento, el menor puede consentir por sí mismo si es mayor de 14 años')
    expect(page.textContent).toContain('El consentimiento para las comunicaciones comerciales electrónicas lo regula el Art. 21 LSSI-CE.')
    openTab('Infracciones')
    expect(page.textContent).toContain('Omitir el deber de informar al interesado (Arts. 13 y 14 RGPD)')
    expect(page.textContent).toContain('Informar al interesado de forma incompleta (Arts. 13 y 14 RGPD)')
    expect(page.textContent).not.toMatch(/40\.000€|No informar al interesado conforme al Art\. 13 RGPD/)
  })

  it('Ley 10/2025 is presented as a customer service law with its actual amendments', () => {
    const page = show(NormativaLey102025)
    expect(page.textContent).toContain('No es una ley general de privacidad')
    expect(page.textContent).toContain('modifica el art. 23.1 de la LOPDGDD y el art. 65.2 de la Ley General de Telecomunicaciones')
    openTab('Cambios en LOPDGDD')
    expect(page.textContent).toContain('Disposición Final 5ª')
    expect(page.textContent).not.toContain('Disposición Final 2ª')
  })

  it('RGPD fines are maximum amounts of Art. 83.4 and 83.5', () => {
    const page = show(NormativaRGPD)
    openTab('Sanciones')
    expect(page.textContent).toContain('Infracciones del Art. 83.5')
    expect(page.textContent).toContain('Hasta 20M€ o 4 % (máximo)')
    expect(page.textContent).toContain('Hasta 10M€ o 2 % (máximo)')
  })
})
