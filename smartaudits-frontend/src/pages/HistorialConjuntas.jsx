import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import api from '../api/axios'
import LoadingSpinner from '../components/LoadingSpinner'
import { formatDate } from '../utils/helpers'

const HistorialConjuntas = () => {
  const [pagina, setPagina] = useState(null)
  const [error, setError] = useState('')

  useEffect(() => {
    let activo = true
    api.get('/auditorias-conjuntas/mias', { params: { page: 0, size: 50 } })
      .then(response => { if (activo) setPagina(response.data) })
      .catch(err => { if (activo) setError(err.response?.data?.message || 'No se pudieron cargar las auditorías conjuntas') })
    return () => { activo = false }
  }, [])

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold text-gray-900 mb-2">Auditorías Conjuntas</h1>
          <p className="text-gray-600">Informes de varios documentos legales analizados en una única operación.</p>
        </div>
        <Link to="/crear-auditoria-conjunta" className="btn-primary">Nueva auditoría conjunta</Link>
      </div>

      {error && <p role="alert" className="text-red-700">{error}</p>}
      {!error && !pagina && <LoadingSpinner message="Cargando auditorías conjuntas..." />}
      {pagina && pagina.content.length === 0 && (
        <p className="card text-gray-600">Todavía no hay auditorías conjuntas.</p>
      )}
      {pagina && pagina.content.length > 0 && (
        <ul className="space-y-3">
          {pagina.content.map(conjunta => (
            <li key={conjunta.id} className="card">
              <Link to={`/auditoria-conjunta/${conjunta.id}`} className="text-lg font-semibold text-gray-900 underline">
                {conjunta.titulo}
              </Link>
              <p className="text-sm text-gray-600">
                {formatDate(conjunta.fechaCreacion)} · {conjunta.documentosAnalizados} documentos
              </p>
            </li>
          ))}
        </ul>
      )}
      <Link to="/historial" className="text-sm text-gray-600 underline">Volver al historial de auditorías</Link>
    </div>
  )
}

export default HistorialConjuntas
