import { useState, useEffect } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import api from '../api/axios'
import LoadingSpinner from '../components/LoadingSpinner'
import { getNivelCumplimiento } from '../utils/helpers'

const textoSeguro = (valor) => {
  if (valor === null || valor === undefined) return ''
  return String(valor)
}

/**
 * Solo se convierte la URL introducida por el usuario en enlace navegable
 * si utiliza HTTP o HTTPS.
 *
 * La validación definitiva del DTO se hará también en backend en la fase 0.7.
 */
const obtenerUrlReferenciaNavegable = (valor) => {
  if (!valor) return null

  try {
    const url = new URL(valor)

    if (url.protocol !== 'http:' && url.protocol !== 'https:') {
      return null
    }

    return url.href
  } catch {
    return null
  }
}

const crearTextoInforme = (auditoria) => {
  const r = auditoria.resultado || {}

  return `
AUDITORÍA LEGAL — ${textoSeguro(auditoria.titulo)}
Tipo: ${textoSeguro(auditoria.tipoDocumento) || 'No especificado'}
Fecha: ${new Date(auditoria.fechaCreacion).toLocaleDateString('es-ES')}
${auditoria.urlOpcional
    ? `URL de referencia: ${textoSeguro(auditoria.urlOpcional)}`
    : ''}
Puntuación de Cumplimiento: ${auditoria.puntuacionRiesgo ?? 0}/100

=== RESUMEN ===
${textoSeguro(r.resumen) || 'Sin resumen disponible.'}

=== INCUMPLIMIENTOS DETECTADOS ===
${r.riesgos?.map((x, i) =>
    `${i + 1}. ${textoSeguro(x)}`
  ).join('\n') || 'Ninguno'}

=== ERRORES ENCONTRADOS ===
${r.errores?.map((e, i) =>
    `${i + 1}. ${textoSeguro(e.titulo)} [${textoSeguro(e.severidad)}]
   ${textoSeguro(e.descripcion)}
   Evidencia: ${textoSeguro(e.evidencia)}
   Impacto: ${textoSeguro(e.impacto)}
   Acción: ${textoSeguro(e.accion)}`
  ).join('\n\n') || 'Ninguno'}

=== RECOMENDACIONES ===
${r.recomendaciones?.map((x, i) =>
    `${i + 1}. ${textoSeguro(x)}`
  ).join('\n') || 'Ninguna'}

=== TEXTOS SUGERIDOS ===
${r.textosSugeridos?.map((x, i) =>
    `${i + 1}. ${textoSeguro(x)}`
  ).join('\n') || 'Ninguno'}

=== ELEMENTOS FALTANTES ===
${r.faltantes?.map((x, i) =>
    `${i + 1}. ${textoSeguro(x)}`
  ).join('\n') || 'Ninguno'}

=== REFERENCIAS LEGALES ===
${r.referenciasLegales?.map((x, i) =>
    `${i + 1}. ${textoSeguro(x)}`
  ).join('\n') || 'Ninguna'}

=== TEXTO ORIGINAL AUDITADO ===
${textoSeguro(auditoria.textoOriginal)}
  `.trim()
}

const DetalleAuditoria = () => {
  const { id } = useParams()
  const [auditoria, setAuditoria] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [copiedSection, setCopiedSection] = useState('')
  const [descargando, setDescargando] = useState(false)
  const [pdfError, setPdfError] = useState('')
  const navigate = useNavigate()

  useEffect(() => {
    cargarAuditoria()
  }, [id])

  const cargarAuditoria = async () => {
    try {
      const response = await api.get(`/auditorias/${id}`)
      setAuditoria(response.data)
    } catch (err) {
      setError(
        err.response?.data?.message ||
        'Error al cargar auditoría'
      )
    } finally {
      setLoading(false)
    }
  }

  const copiarTexto = (texto, seccion) => {
    navigator.clipboard.writeText(texto)
    setCopiedSection(seccion)
    setTimeout(() => setCopiedSection(''), 2000)
  }

  const copiarResultadoCompleto = () => {
    copiarTexto(
      crearTextoInforme(auditoria),
      'completo'
    )
  }

  const descargarPDF = async () => {
  if (descargando) return

  setPdfError('')
  setDescargando(true)

  try {
    /*
     * Carga diferida:
     * el generador PDF no aumenta innecesariamente
     * el bundle inicial de SmartAudits.
     */
    const [
      { pdf },
      { default: AuditoriaPdfDocument }
    ] = await Promise.all([
      import('@react-pdf/renderer'),
      import('../pdf/AuditoriaPdfDocument')
    ])

    const blob = await pdf(
      <AuditoriaPdfDocument
        auditoria={auditoria}
      />
    ).toBlob()

    const nombreSeguro = (
      auditoria.titulo ||
      'Auditoria'
    )
      .replace(
        /[<>:"/\\|?*\u0000-\u001F]/g,
        '_'
      )
      .replace(/\s+/g, ' ')
      .trim()
      .slice(0, 120)

    const url = URL.createObjectURL(blob)

    const enlace =
      document.createElement('a')

    enlace.href = url
    enlace.download =
      `Informe - ${nombreSeguro || 'Auditoria'}.pdf`

    document.body.appendChild(enlace)
    enlace.click()
    enlace.remove()

    /*
     * Dejamos tiempo al navegador para iniciar
     * la descarga antes de liberar el Blob.
     */
    window.setTimeout(() => {
      URL.revokeObjectURL(url)
    }, 5000)

    /*
     * Registrar la descarga una vez que el PDF
     * se ha generado satisfactoriamente.
     */
    api
      .post(
        `/auditorias/${id}/descarga`
      )
      .catch(() => {
        // El registro del historial no invalida
        // un PDF ya generado legítimamente.
      })
  } catch (err) {
    console.error(
      'No se pudo generar el PDF',
      err
    )

    setPdfError(
      'No se pudo generar el PDF. Inténtalo de nuevo.'
    )
  } finally {
    setDescargando(false)
  }
}

  const getSeveridadClase = (s) => {
    if (s === 'ALTA') return 'badge-alta'
    if (s === 'MEDIA') return 'badge-media'
    return 'badge-baja'
  }

  const getRutaNormativa = (ref) => {
    const r = textoSeguro(ref).toLowerCase()

    if (
      r.includes('rgpd') ||
      r.includes('2016/679')
    ) {
      return '/normativa/rgpd'
    }

    if (
      r.includes('lopdgdd') ||
      r.includes('3/2018')
    ) {
      return '/normativa/lopdgdd'
    }

    if (
      r.includes('lssi') ||
      r.includes('34/2002')
    ) {
      return '/normativa/lssi-ce'
    }

    if (
      r.includes('eprivacy') ||
      r.includes('2002/58')
    ) {
      return '/normativa/eprivacy'
    }

    if (
      r.includes('ley 10/2025') ||
      r.includes('10/2025')
    ) {
      return '/normativa/ley-10-2025'
    }

    if (
      r.includes('aepd') ||
      r.includes('cookies')
    ) {
      return '/normativa/aepd-cookies'
    }

    return null
  }

  if (loading) {
    return (
      <LoadingSpinner message="Cargando auditoría..." />
    )
  }

  if (error || !auditoria) {
    return (
      <div className="max-w-4xl mx-auto">
        <div className="bg-red-50 border border-red-200 text-red-700 px-6 py-4 rounded-lg">
          {error || 'Auditoría no encontrada'}
        </div>

        <button
          onClick={() => navigate('/historial')}
          className="btn-secondary mt-4"
        >
          Volver al Historial
        </button>
      </div>
    )
  }

  const r = auditoria.resultado || {}
  const nivel = getNivelCumplimiento(
    auditoria.puntuacionRiesgo
  )
  const urlReferenciaNavegable =
    obtenerUrlReferenciaNavegable(
      auditoria.urlOpcional
    )

  return (
    <div className="max-w-6xl mx-auto space-y-6">

      {/* Header */}
      <div className="card bg-gradient-to-r from-primary-600 to-primary-700 text-white">
        <div className="flex items-center justify-between">
          <div className="flex-1">
            <h1 className="text-3xl font-bold mb-2">
              {auditoria.titulo}
            </h1>

            <div className="flex items-center space-x-4 text-primary-100 flex-wrap gap-y-1">
              <span>
                {auditoria.tipoDocumento}
              </span>

              <span>•</span>

              <span>
                {new Date(
                  auditoria.fechaCreacion
                ).toLocaleDateString('es-ES')}
              </span>

              {auditoria.urlOpcional && (
                <>
                  <span>•</span>

                  {urlReferenciaNavegable ? (
                    <a
                      href={urlReferenciaNavegable}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="hover:text-white underline"
                    >
                      Ver URL de referencia
                    </a>
                  ) : (
                    <span title={auditoria.urlOpcional}>
                      URL de referencia no navegable
                    </span>
                  )}
                </>
              )}
            </div>
          </div>

          <div className="text-center ml-4">
            <div className="text-5xl font-bold">
              {auditoria.puntuacionRiesgo}
            </div>

            <div className="text-primary-100 text-sm mt-1">
              Puntuación de Cumplimiento
            </div>

            <div
              className={`mt-2 px-3 py-1 rounded-full text-xs font-semibold inline-block ${nivel.colorBadge} ${nivel.colorBadgeTexto}`}
            >
              {nivel.etiqueta}
            </div>
          </div>
        </div>
      </div>

      {/* Acciones */}
      <div className="flex space-x-3 flex-wrap gap-2">
        <button
          onClick={() => navigate('/historial')}
          className="btn-secondary"
        >
          ← Volver al Historial
        </button>

        <button
          onClick={copiarResultadoCompleto}
          className="btn-primary"
        >
          {copiedSection === 'completo'
            ? '✓ Copiado'
            : '📋 Copiar Informe Completo'}
        </button>

        <button
          onClick={descargarPDF}
          disabled={descargando}
          className="px-4 py-2 bg-green-600 hover:bg-green-700 disabled:opacity-50 text-white font-medium rounded-lg transition-colors"
        >
          {descargando
            ? '⏳ Generando PDF...'
            : '📄 Descargar PDF'}
        </button>
      </div>

      {pdfError && (
        <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg text-sm">
          {pdfError}
        </div>
      )}

      {/* Resumen */}
      <div className="card">
        <h2 className="text-2xl font-bold text-gray-900 mb-4">
          📊 Resumen Ejecutivo
        </h2>

        <p className="text-gray-700 leading-relaxed">
          {r.resumen}
        </p>
      </div>

      {/* Incumplimientos */}
      {r.riesgos?.length > 0 && (
        <div className="card bg-red-50 border-red-200">
          <h2 className="text-2xl font-bold text-red-900 mb-4">
            ⚠️ Incumplimientos Detectados
          </h2>

          <ul className="space-y-2">
            {r.riesgos.map((riesgo, idx) => (
              <li
                key={idx}
                className="flex items-start text-red-800"
              >
                <svg
                  className="w-5 h-5 mr-2 mt-0.5 flex-shrink-0"
                  fill="currentColor"
                  viewBox="0 0 20 20"
                >
                  <path
                    fillRule="evenodd"
                    d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z"
                    clipRule="evenodd"
                  />
                </svg>

                <span>{riesgo}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* Errores */}
      {r.errores?.length > 0 && (
        <div className="card">
          <h2 className="text-2xl font-bold text-gray-900 mb-6">
            🔍 Errores Detectados ({r.errores.length})
          </h2>

          <div className="space-y-6">
            {r.errores.map((error, idx) => (
              <div
                key={idx}
                className="border-l-4 border-red-500 bg-red-50 p-5 rounded-r-lg"
              >
                <div className="flex items-start justify-between mb-3">
                  <h3 className="text-lg font-bold text-gray-900">
                    {error.titulo}
                  </h3>

                  <span
                    className={getSeveridadClase(
                      error.severidad
                    )}
                  >
                    {error.severidad}
                  </span>
                </div>

                <div className="space-y-3 text-sm">
                  <div>
                    <span className="font-semibold text-gray-700">
                      Descripción:
                    </span>

                    <p className="text-gray-700 mt-1">
                      {error.descripcion}
                    </p>
                  </div>

                  <div>
                    <span className="font-semibold text-gray-700">
                      Evidencia:
                    </span>

                    <p className="text-gray-600 italic mt-1 bg-white p-2 rounded border border-gray-200">
                      {error.evidencia}
                    </p>
                  </div>

                  <div>
                    <span className="font-semibold text-gray-700">
                      Impacto:
                    </span>

                    <p className="text-red-700 mt-1">
                      {error.impacto}
                    </p>
                  </div>

                  <div>
                    <span className="font-semibold text-gray-700">
                      Acción Correctiva:
                    </span>

                    <p className="text-green-700 mt-1 bg-green-50 p-2 rounded border border-green-200">
                      {error.accion}
                    </p>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Recomendaciones */}
      {r.recomendaciones?.length > 0 && (
        <div className="card bg-blue-50 border-blue-200">
          <h2 className="text-2xl font-bold text-blue-900 mb-4">
            💡 Recomendaciones
          </h2>

          <ul className="space-y-3">
            {r.recomendaciones.map((rec, idx) => (
              <li
                key={idx}
                className="flex items-start text-blue-800"
              >
                <svg
                  className="w-5 h-5 mr-2 mt-0.5 flex-shrink-0"
                  fill="currentColor"
                  viewBox="0 0 20 20"
                >
                  <path
                    fillRule="evenodd"
                    d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z"
                    clipRule="evenodd"
                  />
                </svg>

                <span>{rec}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* Textos sugeridos */}
      {r.textosSugeridos?.length > 0 && (
        <div className="card bg-green-50 border-green-200">
          <h2 className="text-2xl font-bold text-green-900 mb-4">
            📝 Textos Sugeridos
          </h2>

          <div className="space-y-4">
            {r.textosSugeridos.map((texto, idx) => (
              <div
                key={idx}
                className="bg-white p-4 rounded-lg border border-green-300"
              >
                <p className="text-gray-700 mb-2">
                  {texto}
                </p>

                <button
                  onClick={() =>
                    copiarTexto(
                      texto,
                      `texto-${idx}`
                    )
                  }
                  className="text-sm text-green-700 hover:text-green-900 font-medium"
                >
                  {copiedSection === `texto-${idx}`
                    ? '✓ Copiado'
                    : '📋 Copiar texto'}
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Faltantes */}
      {r.faltantes?.length > 0 && (
        <div className="card bg-yellow-50 border-yellow-200">
          <h2 className="text-2xl font-bold text-yellow-900 mb-4">
            ❌ Elementos No Verificables
          </h2>

          <p className="text-yellow-800 mb-4 text-sm">
            Los siguientes elementos no pudieron verificarse con
            el texto proporcionado:
          </p>

          <ul className="space-y-2">
            {r.faltantes.map((faltante, idx) => (
              <li
                key={idx}
                className="flex items-start text-yellow-800"
              >
                <svg
                  className="w-5 h-5 mr-2 mt-0.5 flex-shrink-0"
                  fill="currentColor"
                  viewBox="0 0 20 20"
                >
                  <path
                    fillRule="evenodd"
                    d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z"
                    clipRule="evenodd"
                  />
                </svg>

                <span>{faltante}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* Referencias */}
      {r.referenciasLegales?.length > 0 && (
        <div className="card">
          <h2 className="text-2xl font-bold text-gray-900 mb-4">
            📚 Referencias Legales
          </h2>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            {r.referenciasLegales.map((ref, idx) => {
              const ruta = getRutaNormativa(ref)

              return ruta ? (
                <Link
                  key={idx}
                  to={ruta}
                  className="flex items-center space-x-3 p-3 bg-slate-50 border border-slate-200 rounded-xl hover:border-primary-300 hover:bg-primary-50 transition-colors group"
                >
                  <svg
                    className="w-4 h-4 text-primary-500 flex-shrink-0"
                    fill="currentColor"
                    viewBox="0 0 20 20"
                  >
                    <path
                      fillRule="evenodd"
                      d="M12.316 3.051a1 1 0 01.633 1.265l-4 12a1 1 0 11-1.898-.632l4-12a1 1 0 011.265-.633zM5.707 6.293a1 1 0 010 1.414L3.414 10l2.293 2.293a1 1 0 11-1.414 1.414l-3-3a1 1 0 010-1.414l3-3a1 1 0 011.414 0zm8.586 0a1 1 0 011.414 0l3 3a1 1 0 010 1.414l-3 3a1 1 0 11-1.414-1.414L16.586 10l-2.293-2.293a1 1 0 010-1.414z"
                      clipRule="evenodd"
                    />
                  </svg>

                  <span className="text-sm text-slate-700 group-hover:text-primary-700 transition-colors flex-1">
                    {ref}
                  </span>

                  <svg
                    className="w-4 h-4 text-slate-400 group-hover:text-primary-500 flex-shrink-0"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M9 5l7 7-7 7"
                    />
                  </svg>
                </Link>
              ) : (
                <div
                  key={idx}
                  className="flex items-center space-x-3 p-3 bg-slate-50 border border-slate-200 rounded-xl"
                >
                  <svg
                    className="w-4 h-4 text-primary-500 flex-shrink-0"
                    fill="currentColor"
                    viewBox="0 0 20 20"
                  >
                    <path
                      fillRule="evenodd"
                      d="M12.316 3.051a1 1 0 01.633 1.265l-4 12a1 1 0 11-1.898-.632l4-12a1 1 0 011.265-.633zM5.707 6.293a1 1 0 010 1.414L3.414 10l2.293 2.293a1 1 0 11-1.414 1.414l-3-3a1 1 0 010-1.414l3-3a1 1 0 011.414 0zm8.586 0a1 1 0 011.414 0l3 3a1 1 0 010 1.414l-3 3a1 1 0 11-1.414-1.414L16.586 10l-2.293-2.293a1 1 0 010-1.414z"
                      clipRule="evenodd"
                    />
                  </svg>

                  <span className="text-sm text-slate-700">
                    {ref}
                  </span>
                </div>
              )
            })}
          </div>
        </div>
      )}

      {/* Texto original */}
      <div className="card">
        <h2 className="text-2xl font-bold text-gray-900 mb-4">
          📄 Texto Original Auditado
        </h2>

        <div className="bg-gray-50 p-4 rounded-lg border border-gray-200 max-h-96 overflow-y-auto">
          <pre className="text-sm text-gray-700 whitespace-pre-wrap font-sans">
            {auditoria.textoOriginal}
          </pre>
        </div>
      </div>
    </div>
  )
}

export default DetalleAuditoria