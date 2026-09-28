import { useState, useEffect, useMemo } from 'react'
import api from '../api/axios'
import LoadingSpinner from '../components/LoadingSpinner'

const HistorialAdmin = () => {
  const [acciones, setAcciones] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [filtroTexto, setFiltroTexto] = useState('')
  const [filtroTipo, setFiltroTipo] = useState('todas') // 'todas' | 'DESACTIVAR' | ...

  useEffect(() => {
    cargar()
  }, [])

  const cargar = async () => {
    setLoading(true)
    try {
      const response = await api.get('/admin/historial')
      setAcciones(response.data)
      setError('')
    } catch (err) {
      setError('No se pudo cargar el historial de acciones.')
    } finally {
      setLoading(false)
    }
  }

  // Filtrado combinado
  const filtradas = useMemo(() => {
    return acciones.filter(a => {
      if (filtroTipo !== 'todas' && a.tipoAccion !== filtroTipo) return false
      if (filtroTexto) {
        const t = filtroTexto.toLowerCase()
        return (
          a.adminNombre?.toLowerCase().includes(t) ||
          a.adminEmail?.toLowerCase().includes(t) ||
          a.objetivoNombre?.toLowerCase().includes(t) ||
          a.objetivoEmail?.toLowerCase().includes(t)
        )
      }
      return true
    })
  }, [acciones, filtroTexto, filtroTipo])

  const totales = useMemo(() => ({
    total: acciones.length,
    desactivar: acciones.filter(a => a.tipoAccion === 'DESACTIVAR').length,
    reactivar: acciones.filter(a => a.tipoAccion === 'REACTIVAR').length,
    promover: acciones.filter(a => a.tipoAccion === 'PROMOVER').length,
    degradar: acciones.filter(a => a.tipoAccion === 'DEGRADAR').length,
  }), [acciones])

  // Estilos por tipo de acción
  const getEstiloAccion = (tipo) => {
    switch (tipo) {
      case 'DESACTIVAR':
        return { bg: 'bg-red-100', text: 'text-red-800', border: 'border-red-200', icono: '❌' }
      case 'REACTIVAR':
        return { bg: 'bg-green-100', text: 'text-green-800', border: 'border-green-200', icono: '↻' }
      case 'PROMOVER':
        return { bg: 'bg-purple-100', text: 'text-purple-800', border: 'border-purple-200', icono: '👑' }
      case 'DEGRADAR':
        return { bg: 'bg-slate-100', text: 'text-slate-700', border: 'border-slate-200', icono: '↓' }
      default:
        return { bg: 'bg-gray-100', text: 'text-gray-700', border: 'border-gray-200', icono: '•' }
    }
  }

  if (loading) return <LoadingSpinner message="Cargando historial..." />

  return (
    <div className="max-w-7xl mx-auto">

      {/* Cabecera */}
      <div className="mb-8">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-3xl font-bold text-gray-900 mb-2">
              📜 Historial de Acciones Administrativas
            </h1>
            <p className="text-gray-600">
              Registro completo de todas las acciones ejecutadas por administradores sobre los usuarios del sistema.
            </p>
          </div>
          <div className="px-4 py-2 bg-purple-100 border-2 border-purple-600 rounded-lg">
            <span className="text-purple-800 font-bold text-sm">Solo Administradores</span>
          </div>
        </div>
      </div>

      {/* Stats por tipo */}
      <div className="grid grid-cols-2 md:grid-cols-5 gap-4 mb-6">
        <div className="card text-center">
          <p className="text-sm text-gray-600">Total acciones</p>
          <p className="text-2xl font-bold text-gray-900">{totales.total}</p>
        </div>
        <div className="card text-center">
          <p className="text-sm text-gray-600">❌ Desactivadas</p>
          <p className="text-2xl font-bold text-red-600">{totales.desactivar}</p>
        </div>
        <div className="card text-center">
          <p className="text-sm text-gray-600">↻ Reactivadas</p>
          <p className="text-2xl font-bold text-green-600">{totales.reactivar}</p>
        </div>
        <div className="card text-center">
          <p className="text-sm text-gray-600">👑 Promovidas</p>
          <p className="text-2xl font-bold text-purple-700">{totales.promover}</p>
        </div>
        <div className="card text-center">
          <p className="text-sm text-gray-600">↓ Degradadas</p>
          <p className="text-2xl font-bold text-slate-700">{totales.degradar}</p>
        </div>
      </div>

      {/* Filtros */}
      <div className="card mb-6 space-y-4">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium text-gray-700 mr-2">Tipo:</span>
          {[
            { id: 'todas', label: `Todas (${totales.total})`, color: 'primary' },
            { id: 'DESACTIVAR', label: `❌ Desactivar (${totales.desactivar})`, color: 'red' },
            { id: 'REACTIVAR', label: `↻ Reactivar (${totales.reactivar})`, color: 'green' },
            { id: 'PROMOVER', label: `👑 Promover (${totales.promover})`, color: 'purple' },
            { id: 'DEGRADAR', label: `↓ Degradar (${totales.degradar})`, color: 'slate' },
          ].map(opt => (
            <button
              key={opt.id}
              onClick={() => setFiltroTipo(opt.id)}
              className={`px-4 py-2 rounded-xl text-sm font-medium transition-colors border
                ${filtroTipo === opt.id
                  ? `bg-${opt.color === 'primary' ? 'primary' : opt.color}-600 text-white border-${opt.color === 'primary' ? 'primary' : opt.color}-600`
                  : 'bg-white text-gray-700 border-gray-200 hover:bg-gray-50'}`}
              style={filtroTipo === opt.id ? {
                backgroundColor: opt.color === 'red' ? '#dc2626'
                  : opt.color === 'green' ? '#16a34a'
                  : opt.color === 'purple' ? '#9333ea'
                  : opt.color === 'slate' ? '#475569'
                  : '#2563EB',
                color: 'white',
                borderColor: opt.color === 'red' ? '#dc2626'
                  : opt.color === 'green' ? '#16a34a'
                  : opt.color === 'purple' ? '#9333ea'
                  : opt.color === 'slate' ? '#475569'
                  : '#2563EB',
              } : {}}
            >
              {opt.label}
            </button>
          ))}
        </div>

        <div className="flex items-center space-x-4">
          <label htmlFor="filtroTexto" className="text-sm font-medium text-gray-700">
            🔍 Buscar:
          </label>
          <input
            id="filtroTexto"
            type="text"
            value={filtroTexto}
            onChange={(e) => setFiltroTexto(e.target.value)}
            className="input-field flex-1"
            placeholder="Buscar por admin u objetivo (nombre o email)..."
          />
          {filtroTexto && (
            <button onClick={() => setFiltroTexto('')} className="btn-secondary">
              Limpiar
            </button>
          )}
        </div>

        <p className="text-xs text-gray-500">
          Mostrando {filtradas.length} de {totales.total} acciones.
        </p>
      </div>

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg text-sm mb-6">
          {error}
        </div>
      )}

      {/* Tabla */}
      <div className="card overflow-hidden p-0">
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-6 py-3 text-left text-xs font-semibold text-gray-700 uppercase tracking-wider">Fecha</th>
                <th className="px-6 py-3 text-left text-xs font-semibold text-gray-700 uppercase tracking-wider">Acción</th>
                <th className="px-6 py-3 text-left text-xs font-semibold text-gray-700 uppercase tracking-wider">Administrador</th>
                <th className="px-6 py-3 text-left text-xs font-semibold text-gray-700 uppercase tracking-wider">Usuario afectado</th>
                <th className="px-6 py-3 text-left text-xs font-semibold text-gray-700 uppercase tracking-wider">Detalles</th>
              </tr>
            </thead>
            <tbody className="bg-white divide-y divide-gray-200">
              {filtradas.length === 0 ? (
                <tr>
                  <td colSpan={5} className="px-6 py-12 text-center text-gray-500">
                    {totales.total === 0
                      ? 'Aún no se ha registrado ninguna acción administrativa.'
                      : 'No se encontraron acciones con los filtros aplicados.'}
                  </td>
                </tr>
              ) : (
                filtradas.map((a) => {
                  const estilo = getEstiloAccion(a.tipoAccion)
                  return (
                    <tr key={a.id}>
                      <td className="px-6 py-4 whitespace-nowrap text-xs text-gray-600">
                        {new Date(a.fecha).toLocaleString('es-ES', {
                          year: 'numeric', month: 'short', day: 'numeric',
                          hour: '2-digit', minute: '2-digit', second: '2-digit'
                        })}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap">
                        <span className={`px-3 py-1 rounded-full text-xs font-semibold border ${estilo.bg} ${estilo.text} ${estilo.border}`}>
                          {estilo.icono} {a.tipoAccionEtiqueta}
                        </span>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap">
                        <div className="text-sm font-semibold text-gray-900">{a.adminNombre}</div>
                        <div className="text-xs text-gray-500">{a.adminEmail}</div>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap">
                        <div className="text-sm font-semibold text-gray-900">{a.objetivoNombre}</div>
                        <div className="text-xs text-gray-500">{a.objetivoEmail}</div>
                      </td>
                      <td className="px-6 py-4 text-xs text-gray-600">
                        {a.detalles || '—'}
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Pie informativo */}
      <div className="mt-6 p-4 bg-blue-50 border border-blue-200 rounded-lg text-sm text-blue-900">
        <p className="font-semibold mb-1">ℹ️ Sobre el historial de acciones</p>
        <ul className="list-disc list-inside space-y-0.5 text-blue-800">
          <li>Cada acción administrativa (desactivar, reactivar, promover, degradar) genera una entrada automáticamente.</li>
          <li>Los nombres y emails se guardan como snapshot en el momento de la acción.</li>
          <li>El historial es solo de consulta — no se puede modificar ni borrar desde la interfaz.</li>
          <li>Esta trazabilidad respalda los principios del RGPD sobre auditoría de tratamientos.</li>
        </ul>
      </div>
    </div>
  )
}

export default HistorialAdmin
