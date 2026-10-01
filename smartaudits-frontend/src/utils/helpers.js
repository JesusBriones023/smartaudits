export const formatDate = (dateString) => {
  const date = new Date(dateString)
  return date.toLocaleDateString('es-ES', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

export const truncateText = (text, maxLength = 100) => {
  if (text.length <= maxLength) return text
  return text.substring(0, maxLength) + '...'
}

/**
 * Devuelve el nivel de cumplimiento a partir de la puntuación (0-100).
 *
 * Los umbrales coinciden EXACTAMENTE con los del MotorAnalisisLegal.java.
 * El contrato entre capas se verifica con motor/risk-boundaries.json en tests.
 *
 * Niveles:
 *   85-100 → Cumplimiento Alto      (verde)   — BAJO RIESGO
 *   65-84  → Cumplimiento Medio     (amarillo) — RIESGO MODERADO
 *   40-64  → Cumplimiento Bajo      (naranja) — RIESGO ALTO
 *   0-39   → Cumplimiento Crítico   (rojo)    — RIESGO MUY ALTO
 */
export const getNivelCumplimiento = (puntuacion) => {
  const p = puntuacion ?? 0

  if (p >= 85) {
    return {
      nivel: 'BAJO',
      etiqueta: 'Cumplimiento Alto',
      etiquetaRiesgo: 'BAJO RIESGO',
      // Tailwind classes
      colorBadge: 'bg-green-500',
      colorBadgeTexto: 'text-green-100',
      colorFondo: 'text-green-700 bg-green-100',
      colorNumero: 'text-green-600',
      colorBorder: 'border-green-200',
      // Hex (para el PDF)
      hex: '#16a34a'
    }
  }

  if (p >= 65) {
    return {
      nivel: 'MODERADO',
      etiqueta: 'Cumplimiento Medio',
      etiquetaRiesgo: 'RIESGO MODERADO',
      colorBadge: 'bg-yellow-500',
      colorBadgeTexto: 'text-yellow-100',
      colorFondo: 'text-yellow-700 bg-yellow-100',
      colorNumero: 'text-yellow-600',
      colorBorder: 'border-yellow-200',
      hex: '#ca8a04'
    }
  }

  if (p >= 40) {
    return {
      nivel: 'ALTO',
      etiqueta: 'Cumplimiento Bajo',
      etiquetaRiesgo: 'RIESGO ALTO',
      colorBadge: 'bg-orange-500',
      colorBadgeTexto: 'text-orange-100',
      colorFondo: 'text-orange-700 bg-orange-100',
      colorNumero: 'text-orange-600',
      colorBorder: 'border-orange-200',
      hex: '#ea580c'
    }
  }

  return {
    nivel: 'MUY_ALTO',
    etiqueta: 'Cumplimiento Crítico',
    etiquetaRiesgo: 'RIESGO MUY ALTO',
    colorBadge: 'bg-red-500',
    colorBadgeTexto: 'text-red-100',
    colorFondo: 'text-red-700 bg-red-100',
    colorNumero: 'text-red-600',
    colorBorder: 'border-red-200',
    hex: '#dc2626'
  }
}
