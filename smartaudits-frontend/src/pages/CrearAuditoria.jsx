import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import api from '../api/axios'
import LoadingSpinner from '../components/LoadingSpinner'
import DeteccionEnlacesLegales from '../components/DeteccionEnlacesLegales'

// Debe coincidir con ExtractorTextoHtml.MAX_HTML_LENGTH del backend.
export const MAX_HTML_LENGTH = 500000

const CrearAuditoria = () => {
  const [formato, setFormato] = useState('texto')
  const [contenidoHtml, setContenidoHtml] = useState('')
  const [deteccion, setDeteccion] = useState(null)
  const [detectando, setDetectando] = useState(false)
  const [errorDeteccion, setErrorDeteccion] = useState('')
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
      // En modo HTML el backend extrae el texto legible; el HTML nunca se representa
      // aquí y la URL opcional es solo un metadato (no se descarga).
      const response = formato === 'html'
        ? await api.post('/auditorias/html', {
          titulo,
          tipoDocumento,
          html: contenidoHtml,
          urlOpcional: urlOpcional || null
        })
        : await api.post('/auditorias', {
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

  // Análisis estático en el backend: no descarga ni sigue ninguna URL y no crea auditoría.
  const detectarEnlaces = async () => {
    setErrorDeteccion('')
    setDeteccion(null)
    setDetectando(true)
    try {
      const response = await api.post('/auditorias/html/enlaces-legales', {
        html: contenidoHtml,
        urlOpcional: urlOpcional || null
      })
      setDeteccion(response.data)
    } catch (err) {
      setErrorDeteccion(err.response?.data?.message || 'No se pudieron detectar los documentos legales')
    } finally {
      setDetectando(false)
    }
  }

  // Un resultado solo vale para el HTML y la URL con los que se obtuvo.
  const cambiarHtml = (valor) => {
    setContenidoHtml(valor)
    setDeteccion(null)
  }

  const cambiarUrl = (valor) => {
    setUrlOpcional(valor)
    setDeteccion(null)
  }

  if (loading) {
    return (
      <LoadingSpinner
        fullScreen
        message={formato === 'html' ? 'Extrayendo y analizando el texto del HTML...' : 'Analizando texto legal...'}
      />
    )
  }

  return (
    <div className="max-w-4xl mx-auto">
      <div className="mb-8">
        <h1 className="text-3xl font-bold text-gray-900 mb-2">Nueva Auditoría Legal</h1>
        <p className="text-gray-600">
          Pega el texto legal o el HTML de la página que deseas auditar y el sistema analizará su cumplimiento con RGPD, LOPDGDD y LSSI-CE
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
                onChange={(e) => cambiarUrl(e.target.value)}
                className="input-field"
                placeholder="https://ejemplo.com/privacidad"
              />
            </div>

            <fieldset>
              <legend className="block text-sm font-medium text-gray-700 mb-2">Formato del contenido *</legend>
              <div className="flex flex-wrap gap-4">
                <label className="flex items-center gap-2 text-sm text-gray-700">
                  <input
                    type="radio"
                    name="formato"
                    value="texto"
                    checked={formato === 'texto'}
                    onChange={() => setFormato('texto')}
                  />
                  Texto
                </label>
                <label className="flex items-center gap-2 text-sm text-gray-700">
                  <input
                    type="radio"
                    name="formato"
                    value="html"
                    checked={formato === 'html'}
                    onChange={() => setFormato('html')}
                  />
                  HTML
                </label>
              </div>
            </fieldset>

            {formato === 'html' ? (
            <div>
              <label htmlFor="contenidoHtml" className="block text-sm font-medium text-gray-700 mb-2">
                HTML de la Página *
              </label>
              <textarea
                id="contenidoHtml"
                value={contenidoHtml}
                onChange={(e) => cambiarHtml(e.target.value)}
                className="input-field font-mono text-sm"
                rows={15}
                maxLength={MAX_HTML_LENGTH}
                spellCheck={false}
                placeholder="Pega aquí el código HTML de tu política de privacidad, aviso legal o política de cookies..."
                required
              />
              <p className="text-sm text-gray-500 mt-2">
                Solo se analiza el texto visible: se ignoran scripts, estilos y elementos ocultos.
                No se descarga ninguna URL. Máximo {MAX_HTML_LENGTH.toLocaleString('es-ES')} caracteres
                ({contenidoHtml.length.toLocaleString('es-ES')} usados).
              </p>
              <div className="mt-3 space-y-3">
                <button
                  type="button"
                  onClick={detectarEnlaces}
                  disabled={detectando || !contenidoHtml.trim()}
                  className="btn-secondary"
                >
                  {detectando ? 'Detectando...' : 'Detectar documentos legales'}
                </button>
                {errorDeteccion && (
                  <p role="alert" className="text-sm text-red-700">{errorDeteccion}</p>
                )}
                {deteccion && <DeteccionEnlacesLegales resultado={deteccion} />}
              </div>
            </div>
            ) : (
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
            )}
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