import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import api from '../api/axios'
import LoadingSpinner from '../components/LoadingSpinner'

const CrearAuditoria = () => {
  const [titulo, setTitulo] = useState('')
  const [tipoDocumento, setTipoDocumento] = useState('Política de Privacidad')
  const [textoOriginal, setTextoOriginal] = useState('')
  const [urlOpcional, setUrlOpcional] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const navigate = useNavigate()

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)

    try {
      const response = await api.post('/auditorias', {
        titulo,
        tipoDocumento,
        textoOriginal,
        urlOpcional: urlOpcional || null
      })

      navigate(`/auditoria/${response.data.id}`)
    } catch (err) {
      setError(err.response?.data?.message || 'Error al crear la auditoría')
      setLoading(false)
    }
  }

  if (loading) {
    return <LoadingSpinner fullScreen message="Analizando texto legal..." />
  }

  return (
    <div className="max-w-4xl mx-auto">
      <div className="mb-8">
        <h1 className="text-3xl font-bold text-gray-900 mb-2">Nueva Auditoría Legal</h1>
        <p className="text-gray-600">
          Pega el texto legal que deseas auditar y el sistema analizará su cumplimiento con RGPD, LOPDGDD y LSSI-CE
        </p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-6">
        {error && (
          <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg text-sm">
            {error}
          </div>
        )}

        <div className="card">
          <div className="space-y-5">
            <div>
              <label htmlFor="titulo" className="block text-sm font-medium text-gray-700 mb-2">
                Título de la Auditoría *
              </label>
              <input
                id="titulo"
                type="text"
                value={titulo}
                onChange={(e) => setTitulo(e.target.value)}
                className="input-field"
                placeholder="ej. Auditoría Política de Privacidad - MiEmpresa.com"
                required
              />
            </div>

            <div>
              <label htmlFor="tipoDocumento" className="block text-sm font-medium text-gray-700 mb-2">
                Tipo de Documento *
              </label>
              <select
                id="tipoDocumento"
                value={tipoDocumento}
                onChange={(e) => setTipoDocumento(e.target.value)}
                className="input-field"
                required
              >
                <option value="Política de Privacidad">Política de Privacidad</option>
                <option value="Aviso Legal">Aviso Legal</option>
                <option value="Política de Cookies">Política de Cookies</option>
                <option value="Términos y Condiciones">Términos y Condiciones</option>
                <option value="Condiciones de Uso">Condiciones de Uso</option>
                <option value="Documento Completo">Documento Completo (todos los anteriores)</option>
              </select>
            </div>

            <div>
              <label htmlFor="urlOpcional" className="block text-sm font-medium text-gray-700 mb-2">
                URL del Sitio Web (opcional)
              </label>
              <input
                id="urlOpcional"
                type="url"
                value={urlOpcional}
                onChange={(e) => setUrlOpcional(e.target.value)}
                className="input-field"
                placeholder="https://ejemplo.com/privacidad"
              />
            </div>

            <div>
              <label htmlFor="textoOriginal" className="block text-sm font-medium text-gray-700 mb-2">
                Texto Legal a Auditar *
              </label>
              <textarea
                id="textoOriginal"
                value={textoOriginal}
                onChange={(e) => setTextoOriginal(e.target.value)}
                className="input-field"
                rows={15}
                placeholder="Pega aquí el texto completo de tu política de privacidad, aviso legal, política de cookies o cualquier otro documento legal que desees auditar..."
                required
              />
              <p className="text-sm text-gray-500 mt-2">
                Cuanto más completo sea el texto, más preciso será el análisis
              </p>
            </div>
          </div>
        </div>

        <div className="flex space-x-4">
          <button
            type="submit"
            disabled={loading}
            className="btn-primary"
          >
            Analizar documento
          </button>
          <button
            type="button"
            onClick={() => navigate('/dashboard')}
            className="btn-secondary"
          >
            Cancelar
          </button>
        </div>
      </form>
    </div>
  )
}

export default CrearAuditoria