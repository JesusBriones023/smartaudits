import { describe, expect, it } from 'vitest'
import boundaries from '../../../smartaudits-backend/src/test/resources/motor/risk-boundaries.json'
import { getNivelCumplimiento } from './helpers'

// Fixed expectations include every public presentation field, including PDF colors.
const levels = {
  'BAJO RIESGO': {
    nivel: 'BAJO', etiqueta: 'Cumplimiento Alto', etiquetaRiesgo: 'BAJO RIESGO',
    colorBadge: 'bg-green-500', colorBadgeTexto: 'text-green-100',
    colorFondo: 'text-green-700 bg-green-100', colorNumero: 'text-green-600',
    colorBorder: 'border-green-200', hex: '#16a34a'
  },
  'RIESGO MODERADO': {
    nivel: 'MODERADO', etiqueta: 'Cumplimiento Medio', etiquetaRiesgo: 'RIESGO MODERADO',
    colorBadge: 'bg-yellow-500', colorBadgeTexto: 'text-yellow-100',
    colorFondo: 'text-yellow-700 bg-yellow-100', colorNumero: 'text-yellow-600',
    colorBorder: 'border-yellow-200', hex: '#ca8a04'
  },
  'RIESGO ALTO': {
    nivel: 'ALTO', etiqueta: 'Cumplimiento Bajo', etiquetaRiesgo: 'RIESGO ALTO',
    colorBadge: 'bg-orange-500', colorBadgeTexto: 'text-orange-100',
    colorFondo: 'text-orange-700 bg-orange-100', colorNumero: 'text-orange-600',
    colorBorder: 'border-orange-200', hex: '#ea580c'
  },
  'RIESGO MUY ALTO': {
    nivel: 'MUY_ALTO', etiqueta: 'Cumplimiento Crítico', etiquetaRiesgo: 'RIESGO MUY ALTO',
    colorBadge: 'bg-red-500', colorBadgeTexto: 'text-red-100',
    colorFondo: 'text-red-700 bg-red-100', colorNumero: 'text-red-600',
    colorBorder: 'border-red-200', hex: '#dc2626'
  }
}

describe('risk classification contract shared with the real backend analysis', () => {
  it('covers both sides of every boundary and both endpoints', () => {
    expect(boundaries.map(({ score }) => score)).toEqual([0, 39, 40, 64, 65, 84, 85, 100])
  })

  it.each(boundaries)('preserves all labels and colors for score $score', ({ score, riskLabel }) => {
    expect(getNivelCumplimiento(score)).toEqual(levels[riskLabel])
  })

  it.each([
    [null, 'RIESGO MUY ALTO'],
    [undefined, 'RIESGO MUY ALTO'],
    [NaN, 'RIESGO MUY ALTO'],
    ['invalid', 'RIESGO MUY ALTO'],
    ['', 'RIESGO MUY ALTO'],
    [' ', 'RIESGO MUY ALTO'],
    [-1, 'RIESGO MUY ALTO'],
    [-Infinity, 'RIESGO MUY ALTO'],
    [101, 'BAJO RIESGO'],
    [Infinity, 'BAJO RIESGO'],
    ['40', 'RIESGO ALTO'],
    ['65', 'RIESGO MODERADO'],
    ['85', 'BAJO RIESGO'],
    [39.99, 'RIESGO MUY ALTO'],
    [64.99, 'RIESGO ALTO'],
    [84.99, 'RIESGO MODERADO'],
    [false, 'RIESGO MUY ALTO'],
    [true, 'RIESGO MUY ALTO'],
    [{}, 'RIESGO MUY ALTO'],
    [[], 'RIESGO MUY ALTO']
  ])('preserves existing coercion and fallback for %s', (score, riskLabel) => {
    expect(getNivelCumplimiento(score)).toEqual(levels[riskLabel])
  })
})
