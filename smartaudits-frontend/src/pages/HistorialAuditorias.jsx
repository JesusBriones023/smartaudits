import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import api from '../api/axios'
import LoadingSpinner from '../components/LoadingSpinner'
import { getNivelCumplimiento } from '../utils/helpers'

const PAGE_SIZE = 10

const HistorialAuditorias = () => {
  const [auditorias, setAuditorias] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const [pagina, setPagina] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(0)

  const [filtroUsuario, setFiltroUsuario] = useState('')
  const [filtroAplicado, setFiltroAplicado] = useState('')

  const { isAdmin } = useAuth()
  const navigate = useNavigate()

  /*
   * Si cambia el rol visible de la sesión, volvemos a la primera
   * página y eliminamos cualquier filtro administrativo anterior.
   */
  useEffect(() => {
    setPagina(0)
    setFiltroUsuario('')
    setFiltroAplicado('')
  }, [isAdmin])

  /*
   * El filtro ADMIN se envía al servidor.
   *
   * Esperamos unos milisegundos mientras el usuario escribe
   * para no lanzar una petición HTTP por cada pulsación.
   */
  useEffect(() => {
    if (!isAdmin) {
      return undefined
    }

    const timeoutId = window.setTimeout(() => {
      setPagina(0)
      setFiltroAplicado(filtroUsuario.trim())
    }, 350)

    return () => {
      window.clearTimeout(timeoutId)
    }
  }, [filtroUsuario, isAdmin])

  /*
   * Carga paginada.
   *
   * El backend decide:
   * - qué auditorías puede ver el usuario;
   * - el filtro ADMIN;
   * - el orden;
   * - la paginación.
   */
  useEffect(() => {
    const controller = new AbortController()

    const cargarAuditorias = async () => {
      setLoading(true)
      setError('')

      try {
        const endpoint = isAdmin
          ? '/auditorias'
          : '/auditorias/mias'

        const params = {
          page: pagina,
          size: PAGE_SIZE
        }

        if (isAdmin && filtroAplicado) {
          params.usuario = filtroAplicado
        }

        const response = await api.get(endpoint, {
          params,
          signal: controller.signal
        })

        const data = response.data || {}

        setAuditorias(
          Array.isArray(data.content)
            ? data.content
            : []
        )

        setTotalElements(
          Number.isFinite(data.totalElements)
            ? data.totalElements
            : 0
        )

        setTotalPages(
          Number.isFinite(data.totalPages)
            ? data.totalPages
            : 0
        )
      } catch (err) {
        /*
         * Una petición abortada ocurre al cambiar rápidamente
         * de página/filtro y no debe mostrarse como error.
         */
        if (
          err.code === 'ERR_CANCELED' ||
          err.name === 'CanceledError'
        ) {
          return
        }

        setAuditorias([])
        setTotalElements(0)
        setTotalPages(0)

        setError(
          err.response?.data?.message ||
          'Error al cargar auditorías'
        )
      } finally {
        if (!controller.signal.aborted) {
          setLoading(false)
        }
      }
    }

    cargarAuditorias()

    return () => {
      controller.abort()
    }
  }, [
    isAdmin,
    pagina,
    filtroAplicado
  ])

  const cambiarPagina = (nuevaPagina) => {
    if (
      nuevaPagina < 0 ||
      nuevaPagina >= totalPages ||
      nuevaPagina === pagina
    ) {
      return
    }

    setPagina(nuevaPagina)

    window.scrollTo({
      top: 0,
      behavior: 'smooth'
    })
  }

  const limpiarFiltro = () => {
    setFiltroUsuario('')
    setFiltroAplicado('')
    setPagina(0)
  }

  const inicioMostrado =
    totalElements === 0
      ? 0
      : pagina * PAGE_SIZE + 1

  const finMostrado = Math.min(
    (pagina + 1) * PAGE_SIZE,
    totalElements
  )

  const hayFiltro =
    isAdmin && filtroAplicado.length > 0

  const hayResultados =
    auditorias.length > 0

  return (
    <div className="max-w-6xl mx-auto">

      {/* Cabecera */}
      <div className="mb-8">
        <div className="flex items-center justify-between gap-4">
          <div>
            <h1 className="text-3xl font-bold text-gray-900 mb-2">
              {isAdmin
                ? '🔐 Todas las Auditorías (Vista Administrador)'
                : 'Mis Auditorías'}
            </h1>

            <p className="text-gray-600">
              {isAdmin
                ? 'Historial completo de auditorías de todos los usuarios del sistema'
                : 'Historial completo de auditorías legales realizadas'}
            </p>
          </div>

          {isAdmin && (
            <div className="px-4 py-2 bg-purple-100 border-2 border-purple-600 rounded-lg flex-shrink-0">
              <span className="text-purple-800 font-bold text-sm">
                👑 Modo Administrador
              </span>
            </div>
          )}
        </div>
      </div>

      {/* Filtro ADMIN */}
      {isAdmin && (
        <div className="card mb-6 bg-purple-50 border-purple-200">
          <div className="flex items-center gap-4 flex-wrap">
            <label
              htmlFor="filtro"
              className="text-sm font-medium text-gray-700"
            >
              🔍 Filtrar por usuario:
            </label>

            <input
              id="filtro"
              type="text"
              value={filtroUsuario}
              onChange={(e) =>
                setFiltroUsuario(e.target.value)
              }
              className="input-field flex-1 min-w-64"
              placeholder="Escribe el nombre del usuario..."
              maxLength={100}
            />

            {filtroUsuario && (
              <button
                type="button"
                onClick={limpiarFiltro}
                className="btn-secondary"
              >
                Limpiar
              </button>
            )}
          </div>

          <div className="mt-3 flex items-center justify-between gap-3 flex-wrap">
            <p className="text-sm text-gray-600">
              {hayFiltro
                ? (
                  <>
                    Resultados para{' '}
                    <span className="font-semibold">
                      “{filtroAplicado}”
                    </span>
                    : {totalElements}
                  </>
                )
                : (
                  <>
                    Total de auditorías:{' '}
                    <span className="font-semibold">
                      {totalElements}
                    </span>
                  </>
                )}
            </p>

            {filtroUsuario !== filtroAplicado && (
              <span className="text-xs text-purple-600">
                Buscando...
              </span>
            )}
          </div>
        </div>
      )}

      {/* Error */}
      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg text-sm mb-6">
          {error}
        </div>
      )}

      {/* Loading */}
      {loading ? (
        <LoadingSpinner message="Cargando auditorías..." />
      ) : !hayResultados ? (

        hayFiltro ? (
          <div className="card text-center py-12">
            <svg
              className="w-16 h-16 text-gray-400 mx-auto mb-4"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M21 21l-4.35-4.35m1.35-5.65a7 7 0 11-14 0 7 7 0 0114 0z"
              />
            </svg>

            <h3 className="text-lg font-semibold text-gray-900 mb-2">
              No se encontraron auditorías
            </h3>

            <p className="text-gray-600 mb-6">
              No hay auditorías asociadas a un usuario cuyo nombre
              contenga “{filtroAplicado}”.
            </p>

            <button
              type="button"
              onClick={limpiarFiltro}
              className="btn-secondary"
            >
              Limpiar filtro
            </button>
          </div>
        ) : (
          <div className="card text-center py-12">
            <svg
              className="w-16 h-16 text-gray-400 mx-auto mb-4"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
              />
            </svg>

            <h3 className="text-lg font-semibold text-gray-900 mb-2">
              No hay auditorías
            </h3>

            <p className="text-gray-600 mb-6">
              {isAdmin
                ? 'Ningún usuario ha creado auditorías aún'
                : 'Aún no has creado ninguna auditoría'}
            </p>

            <button
              type="button"
              onClick={() =>
                navigate('/crear-auditoria')
              }
              className="btn-primary"
            >
              Crear Primera Auditoría
            </button>
          </div>
        )

      ) : (
        <>
          {/* Información de página */}
          <div className="flex items-center justify-between mb-4 gap-3 flex-wrap">
            <p className="text-sm text-gray-600">
              Mostrando{' '}
              <span className="font-semibold">
                {inicioMostrado}–{finMostrado}
              </span>{' '}
              de{' '}
              <span className="font-semibold">
                {totalElements}
              </span>{' '}
              auditorías
            </p>

            {totalPages > 0 && (
              <p className="text-sm text-gray-500">
                Página{' '}
                <span className="font-semibold text-gray-800">
                  {pagina + 1}
                </span>{' '}
                de{' '}
                <span className="font-semibold text-gray-800">
                  {totalPages}
                </span>
              </p>
            )}
          </div>

          {/* Auditorías */}
          <div className="space-y-4">
            {auditorias.map((auditoria) => {
              const nivel =
                getNivelCumplimiento(
                  auditoria.puntuacionRiesgo
                )

              return (
                <div
                  key={auditoria.id}
                  onClick={() =>
                    navigate(
                      `/auditoria/${auditoria.id}`
                    )
                  }
                  className="card hover:shadow-lg transition-shadow cursor-pointer"
                >
                  <div className="flex items-center justify-between gap-4">

                    <div className="flex-1 min-w-0">

                      <div className="flex items-center gap-3 mb-2 flex-wrap">
                        <h3 className="text-lg font-semibold text-gray-900 break-words">
                          {auditoria.titulo}
                        </h3>

                        <span
                          className={`px-3 py-1 rounded-full text-xs font-medium ${nivel.colorFondo}`}
                        >
                          {nivel.etiqueta}
                        </span>
                      </div>

                      <div className="flex items-center gap-4 text-sm text-gray-600 flex-wrap">

                        {isAdmin &&
                          auditoria.usuarioNombre && (
                          <span className="flex items-center font-medium text-purple-700 bg-purple-50 px-3 py-1 rounded-full">
                            <svg
                              className="w-4 h-4 mr-1"
                              fill="currentColor"
                              viewBox="0 0 20 20"
                            >
                              <path
                                fillRule="evenodd"
                                d="M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z"
                                clipRule="evenodd"
                              />
                            </svg>

                            {auditoria.usuarioNombre}
                          </span>
                        )}

                        <span className="flex items-center">
                          <svg
                            className="w-4 h-4 mr-1"
                            fill="none"
                            stroke="currentColor"
                            viewBox="0 0 24 24"
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              strokeWidth={2}
                              d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z"
                            />
                          </svg>

                          {auditoria.tipoDocumento}
                        </span>

                        <span className="flex items-center">
                          <svg
                            className="w-4 h-4 mr-1"
                            fill="none"
                            stroke="currentColor"
                            viewBox="0 0 24 24"
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              strokeWidth={2}
                              d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"
                            />
                          </svg>

                          {new Date(
                            auditoria.fechaCreacion
                          ).toLocaleDateString(
                            'es-ES',
                            {
                              year: 'numeric',
                              month: 'long',
                              day: 'numeric',
                              hour: '2-digit',
                              minute: '2-digit'
                            }
                          )}
                        </span>
                      </div>

                      {isAdmin &&
                        auditoria.usuarioEmail && (
                        <p className="text-xs text-gray-400 mt-2">
                          {auditoria.usuarioEmail}
                        </p>
                      )}
                    </div>

                    <div className="text-right ml-4 flex-shrink-0">
                      <div
                        className={`text-3xl font-bold ${nivel.colorNumero}`}
                      >
                        {auditoria.puntuacionRiesgo}
                      </div>

                      <div className="text-sm text-gray-500">
                        Cumplimiento
                      </div>
                    </div>
                  </div>
                </div>
              )
            })}
          </div>

          {/* Paginación */}
          {totalPages > 1 && (
            <div className="mt-8 flex items-center justify-between gap-4 border-t border-gray-200 pt-6">

              <button
                type="button"
                onClick={() =>
                  cambiarPagina(pagina - 1)
                }
                disabled={pagina === 0 || loading}
                className="btn-secondary disabled:opacity-40 disabled:cursor-not-allowed"
              >
                ← Anterior
              </button>

              <div className="flex items-center gap-2">
                <span className="text-sm text-gray-600">
                  Página
                </span>

                <span className="px-3 py-1.5 bg-primary-600 text-white rounded-lg text-sm font-semibold">
                  {pagina + 1}
                </span>

                <span className="text-sm text-gray-600">
                  de {totalPages}
                </span>
              </div>

              <button
                type="button"
                onClick={() =>
                  cambiarPagina(pagina + 1)
                }
                disabled={
                  pagina + 1 >= totalPages ||
                  loading
                }
                className="btn-secondary disabled:opacity-40 disabled:cursor-not-allowed"
              >
                Siguiente →
              </button>
            </div>
          )}
        </>
      )}
    </div>
  )
}

export default HistorialAuditorias