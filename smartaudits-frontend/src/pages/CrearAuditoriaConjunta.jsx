import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import api from '../api/axios'
import LoadingSpinner from '../components/LoadingSpinner'
import { MAX_HTML_LENGTH } from './CrearAuditoria'

// Deben coincidir con AuditoriaConjunta.MAX_DOCUMENTOS y AuditoriaConjuntaService.CATEGORIAS.
export const MAX_DOCUMENTOS = 4
export const CATEGORIAS_CONJUNTAS = [
  'Política de Privacidad', 'Aviso Legal', 'Política de Cookies', 'Términos y Condiciones', 'Condiciones de Uso',
]

const documentoVacio = categoria => ({ titulo: '', tipoDocumento: categoria, html: '', urlOpcional: '' })

// Cada documento se analiza como una auditoría individual; el HTML nunca se representa
// y las URLs son solo metadatos (no se descargan).
const CrearAuditoriaConjunta = () => {
  const [titulo, setTitulo] = useState('')
  const [documentos, setDocumentos] = useState([documentoVacio(CATEGORIAS_CONJUNTAS[0])])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const navigate = useNavigate()

  const cambiar = (indice, campo, valor) =>
    setDocumentos(actuales => actuales.map((d, i) => (i === indice ? { ...d, [campo]: valor } : d)))

  const anadir = () => {
    const usadas = new Set(documentos.map(d => d.tipoDocumento))
    const libre = CATEGORIAS_CONJUNTAS.find(c => !usadas.has(c)) || CATEGORIAS_CONJUNTAS[0]
    setDocumentos(actuales => [...actuales, documentoVacio(libre)])
  }

  const quitar = indice => setDocumentos(actuales => actuales.filter((_, i) => i !== indice))

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const response = await api.post('/auditorias-conjuntas', {
        titulo,
        documentos: documentos.map(d => ({ ...d, urlOpcional: d.urlOpcional || null })),
      })
      navigate(`/auditoria-conjunta/${response.data.id}`)
    } catch (err) {
      setError(err.response?.data?.message || 'Error al crear la auditoría conjunta')
      setLoading(false)
    }
  }

  if (loading) {
    return <LoadingSpinner fullScreen message={`Analizando ${documentos.length} documentos...`} />
  }

  return (
    <div className="max-w-4xl mx-auto">
      <div className="mb-8">
        <h1 className="text-3xl font-bold text-gray-900 mb-2">Nueva Auditoría Conjunta</h1>
        <p className="text-gray-600">
          Pega el HTML de hasta {MAX_DOCUMENTOS} documentos legales de un mismo sitio. Cada uno se analiza por
          separado y obtienes un informe conjunto. No se descarga ninguna URL.
        </p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-6">
        {error && (
          <div role="alert" className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg text-sm">
            {error}
          </div>
        )}

        <div className="card">
          <label htmlFor="tituloConjunta" className="block text-sm font-medium text-gray-700 mb-2">
            Título de la Auditoría Conjunta *
          </label>
          <input
            id="tituloConjunta"
            type="text"
            value={titulo}
            onChange={(e) => setTitulo(e.target.value)}
            className="input-field"
            placeholder="ej. Documentos legales - MiEmpresa.com"
            required
          />
        </div>

        {documentos.map((documento, indice) => {
          const n = indice + 1
          return (
            <fieldset key={indice} className="card space-y-4" aria-label={`Documento ${n}`}>
              <div className="flex items-center justify-between">
                <legend className="text-lg font-semibold text-gray-900">Documento {n}</legend>
                {documentos.length > 1 && (
                  <button type="button" onClick={() => quitar(indice)} className="btn-secondary">
                    Quitar documento {n}
                  </button>
                )}
              </div>
              <div>
                <label htmlFor={`titulo-${indice}`} className="block text-sm font-medium text-gray-700 mb-2">
                  Título del documento {n} *
                </label>
                <input
                  id={`titulo-${indice}`}
                  type="text"
                  value={documento.titulo}
                  onChange={(e) => cambiar(indice, 'titulo', e.target.value)}
                  className="input-field"
                  required
                />
              </div>
              <div>
                <label htmlFor={`categoria-${indice}`} className="block text-sm font-medium text-gray-700 mb-2">
                  Categoría legal del documento {n} *
                </label>
                <select
                  id={`categoria-${indice}`}
                  value={documento.tipoDocumento}
                  onChange={(e) => cambiar(indice, 'tipoDocumento', e.target.value)}
                  className="input-field"
                >
                  {CATEGORIAS_CONJUNTAS.map(c => <option key={c} value={c}>{c}</option>)}
                </select>
              </div>
              <div>
                <label htmlFor={`url-${indice}`} className="block text-sm font-medium text-gray-700 mb-2">
                  URL del documento {n} (opcional)
                </label>
                <input
                  id={`url-${indice}`}
                  type="url"
                  value={documento.urlOpcional}
                  onChange={(e) => cambiar(indice, 'urlOpcional', e.target.value)}
                  className="input-field"
                  placeholder="https://ejemplo.com/privacidad"
                />
              </div>
              <div>
                <label htmlFor={`html-${indice}`} className="block text-sm font-medium text-gray-700 mb-2">
                  HTML del documento {n} *
                </label>
                <textarea
                  id={`html-${indice}`}
                  value={documento.html}
                  onChange={(e) => cambiar(indice, 'html', e.target.value)}
                  className="input-field font-mono text-sm"
                  rows={8}
                  maxLength={MAX_HTML_LENGTH}
                  spellCheck={false}
                  required
                />
              </div>
            </fieldset>
          )
        })}

        <p className="text-sm text-gray-500">
          Cada categoría solo puede aparecer una vez. Si un documento no es válido, no se guarda ninguno.
        </p>

        <div className="flex flex-wrap gap-4">
          {documentos.length < MAX_DOCUMENTOS && (
            <button type="button" onClick={anadir} className="btn-secondary">
              Añadir documento
            </button>
          )}
          <button type="submit" className="btn-primary">
            Analizar {documentos.length === 1 ? 'documento' : `${documentos.length} documentos`}
          </button>
          <button type="button" onClick={() => navigate('/historial-conjuntas')} className="btn-secondary">
            Cancelar
          </button>
        </div>
      </form>
    </div>
  )
}

export default CrearAuditoriaConjunta
