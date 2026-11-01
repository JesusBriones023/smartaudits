import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import api from '../api/axios'
import LoadingSpinner from '../components/LoadingSpinner'
import { JOINT_FALLBACKS, JOINT_LABELS, buildJointReport, jointPdfFileName } from '../report/jointReportPresentation'

// Informe conjunto. Pantalla y PDF muestran el mismo modelo (buildJointReport); no hay
// puntuación global porque el motor solo la define por documento.
const DetalleAuditoriaConjunta = () => {
  const { id } = useParams()
  const navigate = useNavigate()
  const [informe, setInforme] = useState(null)
  const [error, setError] = useState('')
  const [descargando, setDescargando] = useState(false)
  const [pdfError, setPdfError] = useState('')

  useEffect(() => {
    let activo = true
    api.get(`/auditorias-conjuntas/${id}`)
      .then(response => { if (activo) setInforme(response.data) })
      .catch(err => { if (activo) setError(err.response?.data?.message || 'No se pudo cargar la auditoría conjunta') })
    return () => { activo = false }
  }, [id])

  // El PDF se genera con los datos que devuelve el backend para esta descarga
  // (auditorías persistidas y autorizadas), no con el estado de la página.
  const descargarPdf = async () => {
    if (descargando) return
    setPdfError('')
    setDescargando(true)
    try {
      const { data } = await api.post(`/auditorias-conjuntas/${id}/descarga`)
      const [{ pdf }, { default: AuditoriaConjuntaPdfDocument }] = await Promise.all([
        import('@react-pdf/renderer'),
        import('../pdf/AuditoriaConjuntaPdfDocument'),
      ])
      const blob = await pdf(<AuditoriaConjuntaPdfDocument informe={data} />).toBlob()
      const url = URL.createObjectURL(blob)
      const enlace = document.createElement('a')
      enlace.href = url
      enlace.download = jointPdfFileName(data.titulo)
      document.body.appendChild(enlace)
      enlace.click()
      enlace.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 5000)
    } catch (err) {
      console.error('No se pudo generar el PDF de la auditoría conjunta', err)
      setPdfError(err.response?.status === 403
        ? 'No tienes permiso para descargar este informe.'
        : 'No se pudo generar el PDF. Inténtalo de nuevo.')
    } finally {
      setDescargando(false)
    }
  }

  if (error) {
    return (
      <div className="max-w-4xl mx-auto card">
        <p role="alert" className="text-red-700">{error}</p>
        <button type="button" onClick={() => navigate('/historial-conjuntas')} className="btn-secondary mt-4">
          Volver a las auditorías conjuntas
        </button>
      </div>
    )
  }
  if (!informe) {
    return <LoadingSpinner message="Cargando auditoría conjunta..." />
  }

  const report = buildJointReport(informe)

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <div className="card">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h1 className="text-3xl font-bold text-gray-900 mb-2">{report.title}</h1>
            <p className="text-gray-600">
              {report.date} · {report.documentCount} documentos analizados
            </p>
          </div>
          <button type="button" onClick={descargarPdf} disabled={descargando} className="btn-primary">
            {descargando ? 'Generando PDF...' : 'Descargar PDF'}
          </button>
        </div>
        {pdfError && <p role="alert" className="text-sm text-red-700 mt-2">{pdfError}</p>}
        <p className="text-sm text-gray-500 mt-2">{report.limitations[2]}</p>
      </div>

      <section aria-labelledby="titulo-documentos" className="space-y-4">
        <h2 id="titulo-documentos" className="text-xl font-semibold text-gray-900">Documentos analizados</h2>
        {report.documents.map(d => (
          <article key={d.id} className="card space-y-2" aria-label={d.title} data-joint-document={d.id}>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <h3 className="text-lg font-semibold text-gray-900">{d.title}</h3>
                <p className="text-sm text-gray-600" data-joint-field="category">{d.category}</p>
              </div>
              <div className="text-sm text-gray-700 text-right">
                <p>{JOINT_LABELS.score}: <strong data-joint-field="score">{d.score}</strong></p>
                {d.classification && <p data-joint-field="classification">{d.classification}</p>}
              </div>
            </div>
            {d.url && <p className="text-xs text-gray-500 break-all">{JOINT_LABELS.url}: <span data-joint-field="url">{d.url}</span></p>}
            <p className="text-xs text-gray-500">{JOINT_LABELS.engine}: <span data-joint-field="engine">{d.engine}</span></p>
            <p className="text-sm text-gray-700" data-joint-field="summary">{d.summary}</p>
            {d.risks.length > 0 && (
              <div>
                <p className="text-sm font-medium text-gray-800">{JOINT_LABELS.risks}</p>
                <ul className="space-y-1">
                  {d.risks.map((risk, i) => <li key={i} className="text-sm text-gray-700" data-joint-field="risk">{risk}</li>)}
                </ul>
              </div>
            )}
            <p className="text-sm font-medium text-gray-800">
              {d.findings.length === 0 ? JOINT_FALLBACKS.noFindings : `${d.findings.length} hallazgos`}
            </p>
            {d.findings.length > 0 && (
              <ul className="space-y-2">
                {d.findings.map((f, i) => (
                  <li key={i} className="text-sm text-gray-700" data-joint-finding={i}>
                    <p>
                      <span className="font-medium">[<span data-joint-field="severity">{f.severity}</span>]</span>{' '}
                      <span data-joint-field="findingTitle">{f.title}</span>
                    </p>
                    {f.description && <p>{JOINT_LABELS.description}: <span data-joint-field="description">{f.description}</span></p>}
                    <p className="break-all">{JOINT_LABELS.evidence}: <span data-joint-field="evidence">{f.evidence}</span></p>
                    {f.impact && <p>{JOINT_LABELS.impact}: <span data-joint-field="impact">{f.impact}</span></p>}
                    {f.action && <p>{JOINT_LABELS.action}: <span data-joint-field="action">{f.action}</span></p>}
                  </li>
                ))}
              </ul>
            )}
            <Link to={`/auditoria/${d.id}`} className="text-sm text-primary-600 underline">
              Ver informe completo de «{d.title}»
            </Link>
          </article>
        ))}
      </section>

      <section aria-labelledby="titulo-recomendaciones" className="card space-y-2">
        <h2 id="titulo-recomendaciones" className="text-xl font-semibold text-gray-900">{JOINT_LABELS.recommendations}</h2>
        {report.recommendations.length === 0 ? (
          <p className="text-sm text-gray-600">{JOINT_FALLBACKS.noRecommendations}</p>
        ) : (
          <ul className="space-y-2">
            {report.recommendations.map((r, i) => (
              <li key={i} className="text-sm text-gray-700" data-joint-recommendation={i}>
                <p data-joint-field="recommendation">{r.text}</p>
                <p className="text-xs text-gray-500">{JOINT_LABELS.appliesTo}: <span data-joint-field="appliesTo">{r.documents.join(', ')}</span></p>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="titulo-no-aportados" className="card space-y-2">
        <h2 id="titulo-no-aportados" className="text-xl font-semibold text-gray-900">{JOINT_LABELS.missing}</h2>
        {report.missing.length === 0 ? (
          <p className="text-sm text-gray-600">{JOINT_FALLBACKS.noMissing}</p>
        ) : (
          <>
            <p className="text-sm text-gray-600">No se ha analizado ningún documento de estas categorías en esta auditoría:</p>
            <ul className="space-y-1">
              {report.missing.map(c => <li key={c} className="text-sm text-gray-700" data-joint-field="missing">{c}</li>)}
            </ul>
          </>
        )}
      </section>

      <section aria-labelledby="titulo-limitaciones" className="card space-y-2">
        <h2 id="titulo-limitaciones" className="text-xl font-semibold text-gray-900">{JOINT_LABELS.limitations}</h2>
        <ul className="space-y-1">
          {report.limitations.map((l, i) => <li key={i} className="text-sm text-gray-700" data-joint-field="limitation">{l}</li>)}
        </ul>
      </section>
    </div>
  )
}

export default DetalleAuditoriaConjunta
