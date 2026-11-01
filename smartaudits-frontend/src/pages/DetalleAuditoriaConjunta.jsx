import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import api from '../api/axios'
import LoadingSpinner from '../components/LoadingSpinner'
import { formatDate } from '../utils/helpers'

// Informe conjunto: no hay puntuación global porque el motor solo la define por documento.
const DetalleAuditoriaConjunta = () => {
  const { id } = useParams()
  const navigate = useNavigate()
  const [informe, setInforme] = useState(null)
  const [error, setError] = useState('')

  useEffect(() => {
    let activo = true
    api.get(`/auditorias-conjuntas/${id}`)
      .then(response => { if (activo) setInforme(response.data) })
      .catch(err => { if (activo) setError(err.response?.data?.message || 'No se pudo cargar la auditoría conjunta') })
    return () => { activo = false }
  }, [id])

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

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <div className="card">
        <h1 className="text-3xl font-bold text-gray-900 mb-2">{informe.titulo}</h1>
        <p className="text-gray-600">
          {formatDate(informe.fechaCreacion)} · {informe.documentosAnalizados} documentos analizados
        </p>
        <p className="text-sm text-gray-500 mt-2">
          Cada documento tiene su propia puntuación de riesgo. No se calcula una puntuación global porque no hay
          reglas definidas para combinarlas.
        </p>
      </div>

      <section aria-labelledby="titulo-documentos" className="space-y-4">
        <h2 id="titulo-documentos" className="text-xl font-semibold text-gray-900">Documentos analizados</h2>
        {informe.documentos.map(documento => (
          <article key={documento.auditoriaId} className="card space-y-2" aria-label={documento.titulo}>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <h3 className="text-lg font-semibold text-gray-900">{documento.titulo}</h3>
                <p className="text-sm text-gray-600">{documento.tipoDocumento}</p>
              </div>
              <p className="text-sm text-gray-700">
                Puntuación de riesgo: <strong>{documento.puntuacionRiesgo ?? 'sin dato'}</strong>
              </p>
            </div>
            {documento.resumen && <p className="text-sm text-gray-700">{documento.resumen}</p>}
            <p className="text-sm font-medium text-gray-800">
              {documento.incidencias.length === 0
                ? 'Sin incidencias detectadas'
                : `${documento.incidencias.length} incidencias`}
            </p>
            {documento.incidencias.length > 0 && (
              <ul className="space-y-1">
                {documento.incidencias.map((incidencia, i) => (
                  <li key={`${incidencia.ruleId}-${i}`} className="text-sm text-gray-700">
                    <span className="font-medium">[{incidencia.severidad}]</span> {incidencia.titulo}
                  </li>
                ))}
              </ul>
            )}
            <Link to={`/auditoria/${documento.auditoriaId}`} className="text-sm text-primary-600 underline">
              Ver informe completo de «{documento.titulo}»
            </Link>
          </article>
        ))}
      </section>

      <section aria-labelledby="titulo-recomendaciones" className="card space-y-2">
        <h2 id="titulo-recomendaciones" className="text-xl font-semibold text-gray-900">Recomendaciones agrupadas</h2>
        {informe.recomendaciones.length === 0 ? (
          <p className="text-sm text-gray-600">No hay recomendaciones.</p>
        ) : (
          <ul className="space-y-2">
            {informe.recomendaciones.map(recomendacion => (
              <li key={recomendacion.texto} className="text-sm text-gray-700">
                <p>{recomendacion.texto}</p>
                <p className="text-xs text-gray-500">Aplica a: {recomendacion.documentos.join(', ')}</p>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="titulo-no-aportados" className="card space-y-2">
        <h2 id="titulo-no-aportados" className="text-xl font-semibold text-gray-900">Documentos no aportados</h2>
        {informe.categoriasNoAportadas.length === 0 ? (
          <p className="text-sm text-gray-600">Se aportaron todas las categorías legales.</p>
        ) : (
          <>
            <p className="text-sm text-gray-600">
              No se ha analizado ningún documento de estas categorías en esta auditoría:
            </p>
            <ul className="space-y-1">
              {informe.categoriasNoAportadas.map(categoria => (
                <li key={categoria} className="text-sm text-gray-700">{categoria}</li>
              ))}
            </ul>
          </>
        )}
      </section>
    </div>
  )
}

export default DetalleAuditoriaConjunta
