import { useState, useEffect, useMemo } from 'react'
import { useAuth } from '../context/AuthContext'
import api from '../api/axios'
import LoadingSpinner from '../components/LoadingSpinner'

const GestionUsuarios = () => {
  const { user } = useAuth()
  const [usuarios, setUsuarios] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [exito, setExito] = useState('')
  const [filtroTexto, setFiltroTexto] = useState('')
  const [filtroEstado, setFiltroEstado] = useState('todos') // 'todos' | 'activos' | 'desactivados'

  // Confirmaciones
  const [confirmRol, setConfirmRol] = useState(null)         // { usuario, nuevoRol }
  const [confirmBaja, setConfirmBaja] = useState(null)       // { usuario }
  const [confirmReactivar, setConfirmReactivar] = useState(null) // { usuario }
  const [accionLoading, setAccionLoading] = useState(false)

  useEffect(() => {
    cargarUsuarios()
  }, [])

  const cargarUsuarios = async () => {
    setLoading(true)
    try {
      const response = await api.get('/usuarios')
      setUsuarios(response.data)
      setError('')
    } catch (err) {
      setError('No se pudo cargar la lista de usuarios.')
    } finally {
      setLoading(false)
    }
  }

  const mostrarMensajeExito = (msg) => {
    setExito(msg)
    setTimeout(() => setExito(''), 4000)
  }

  // ====== Cambiar rol ======
  const solicitarCambioRol = (usuario, nuevoRol) => {
    setConfirmRol({ usuario, nuevoRol })
  }

  const confirmarCambioRol = async () => {
    if (!confirmRol) return
    setAccionLoading(true)
    try {
      await api.patch(`/usuarios/${confirmRol.usuario.id}/rol`, {
        nuevoRol: confirmRol.nuevoRol,
      })
      const accion = confirmRol.nuevoRol === 'ADMIN' ? 'promovido a Administrador' : 'degradado a Cliente'
      mostrarMensajeExito(`${confirmRol.usuario.nombre} ha sido ${accion}.`)
      setConfirmRol(null)
      await cargarUsuarios()
    } catch (err) {
      setError(err.response?.data?.message || 'No se pudo cambiar el rol.')
      setConfirmRol(null)
    } finally {
      setAccionLoading(false)
    }
  }

  // ====== Desactivar ======
  const solicitarDesactivacion = (usuario) => {
    setConfirmBaja({ usuario })
  }

  const confirmarDesactivacion = async () => {
    if (!confirmBaja) return
    setAccionLoading(true)
    try {
      await api.patch(`/usuarios/${confirmBaja.usuario.id}/desactivar`)
      mostrarMensajeExito(`${confirmBaja.usuario.nombre} ha sido desactivado.`)
      setConfirmBaja(null)
      await cargarUsuarios()
    } catch (err) {
      setError(err.response?.data?.message || 'No se pudo desactivar al usuario.')
      setConfirmBaja(null)
    } finally {
      setAccionLoading(false)
    }
  }

  // ====== Reactivar ======
  const solicitarReactivacion = (usuario) => {
    setConfirmReactivar({ usuario })
  }

  const confirmarReactivacion = async () => {
    if (!confirmReactivar) return
    setAccionLoading(true)
    try {
      await api.patch(`/usuarios/${confirmReactivar.usuario.id}/reactivar`)
      mostrarMensajeExito(`${confirmReactivar.usuario.nombre} ha sido reactivado correctamente.`)
      setConfirmReactivar(null)
      await cargarUsuarios()
    } catch (err) {
      setError(err.response?.data?.message || 'No se pudo reactivar al usuario.')
      setConfirmReactivar(null)
    } finally {
      setAccionLoading(false)
    }
  }

  // ====== Filtrado combinado (texto + estado) ======
  const usuariosFiltrados = useMemo(() => {
    return usuarios.filter(u => {
      // Filtro de estado
      if (filtroEstado === 'activos' && !u.activo) return false
      if (filtroEstado === 'desactivados' && u.activo) return false
      // Filtro de texto (nombre o email)
      if (filtroTexto) {
        const t = filtroTexto.toLowerCase()
        return u.nombre?.toLowerCase().includes(t) || u.email?.toLowerCase().includes(t)
      }
      return true
    })
  }, [usuarios, filtroTexto, filtroEstado])

  if (loading) return <LoadingSpinner message="Cargando usuarios..." />

  const totales = {
    total: usuarios.length,
    admins: usuarios.filter(u => u.role === 'ADMIN').length,
    clientes: usuarios.filter(u => u.role === 'CLIENTE').length,
    desactivados: usuarios.filter(u => !u.activo).length,
  }

  return (
    <div className="max-w-7xl mx-auto">

      {/* Cabecera */}
      <div className="mb-8">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-3xl font-bold text-gray-900 mb-2">
              👑 Gestión de Usuarios
            </h1>
            <p className="text-gray-600">
              Administra los usuarios del sistema: cambia roles, desactiva o reactiva cuentas y consulta su actividad.
            </p>
          </div>
          <div className="px-4 py-2 bg-purple-100 border-2 border-purple-600 rounded-lg">
            <span className="text-purple-800 font-bold text-sm">Solo Administradores</span>
          </div>
        </div>
      </div>

      {/* Stats rápidos */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4 mb-6">
        <div className="card text-center">
          <p className="text-sm text-gray-600">Total usuarios</p>
          <p className="text-2xl font-bold text-gray-900">{totales.total}</p>
        </div>
        <div className="card text-center">
          <p className="text-sm text-gray-600">Administradores</p>
          <p className="text-2xl font-bold text-purple-700">{totales.admins}</p>
        </div>
        <div className="card text-center">
          <p className="text-sm text-gray-600">Clientes</p>
          <p className="text-2xl font-bold text-primary-700">{totales.clientes}</p>
        </div>
        <div className="card text-center">
          <p className="text-sm text-gray-600">Desactivados</p>
          <p className="text-2xl font-bold text-red-600">{totales.desactivados}</p>
        </div>
      </div>

      {/* Filtros: estado + texto */}
      <div className="card mb-6 space-y-4">
        {/* Botones de filtro de estado */}
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium text-gray-700 mr-2">Mostrar:</span>
          <button
            onClick={() => setFiltroEstado('todos')}
            className={`px-4 py-2 rounded-xl text-sm font-medium transition-colors border
              ${filtroEstado === 'todos'
                ? 'bg-primary-600 text-white border-primary-600'
                : 'bg-white text-gray-700 border-gray-200 hover:bg-gray-50'}`}
          >
            Todos ({totales.total})
          </button>
          <button
            onClick={() => setFiltroEstado('activos')}
            className={`px-4 py-2 rounded-xl text-sm font-medium transition-colors border
              ${filtroEstado === 'activos'
                ? 'bg-green-600 text-white border-green-600'
                : 'bg-white text-gray-700 border-gray-200 hover:bg-gray-50'}`}
          >
            ✅ Solo activos ({totales.total - totales.desactivados})
          </button>
          <button
            onClick={() => setFiltroEstado('desactivados')}
            className={`px-4 py-2 rounded-xl text-sm font-medium transition-colors border
              ${filtroEstado === 'desactivados'
                ? 'bg-red-600 text-white border-red-600'
                : 'bg-white text-gray-700 border-gray-200 hover:bg-gray-50'}`}
          >
            ❌ Solo desactivados ({totales.desactivados})
          </button>
        </div>

        {/* Buscador de texto */}
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
            placeholder="Nombre o email..."
          />
          {filtroTexto && (
            <button onClick={() => setFiltroTexto('')} className="btn-secondary">
              Limpiar
            </button>
          )}
        </div>

        <p className="text-xs text-gray-500">
          Mostrando {usuariosFiltrados.length} de {totales.total} usuarios.
        </p>
      </div>

      {/* Mensajes */}
      {exito && (
        <div className="bg-green-50 border border-green-200 text-green-800 px-4 py-3 rounded-lg text-sm mb-6">
          ✅ {exito}
        </div>
      )}
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
                <th className="px-6 py-3 text-left text-xs font-semibold text-gray-700 uppercase tracking-wider">Usuario</th>
                <th className="px-6 py-3 text-left text-xs font-semibold text-gray-700 uppercase tracking-wider">Rol</th>
                <th className="px-6 py-3 text-left text-xs font-semibold text-gray-700 uppercase tracking-wider">Estado</th>
                <th className="px-6 py-3 text-center text-xs font-semibold text-gray-700 uppercase tracking-wider">Auditorías</th>
                <th className="px-6 py-3 text-left text-xs font-semibold text-gray-700 uppercase tracking-wider">Registrado</th>
                <th className="px-6 py-3 text-right text-xs font-semibold text-gray-700 uppercase tracking-wider">Acciones</th>
              </tr>
            </thead>
            <tbody className="bg-white divide-y divide-gray-200">
              {usuariosFiltrados.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-6 py-8 text-center text-gray-500">
                    No se encontraron usuarios con los filtros aplicados.
                  </td>
                </tr>
              ) : (
                usuariosFiltrados.map((u) => {
                  const esEsteUsuario = u.id === user?.userId
                  const esAdmin = u.role === 'ADMIN'
                  return (
                    <tr key={u.id} className={!u.activo ? 'bg-red-50/40' : ''}>
                      {/* Usuario */}
                      <td className="px-6 py-4 whitespace-nowrap">
                        <div className="flex items-center">
                          <div className={`w-10 h-10 rounded-full flex items-center justify-center font-bold text-white text-sm
                            ${esAdmin ? 'bg-gradient-to-br from-purple-500 to-purple-700' : 'bg-gradient-to-br from-slate-500 to-slate-700'}`}>
                            {u.nombre?.charAt(0)?.toUpperCase() || '?'}
                          </div>
                          <div className="ml-3">
                            <div className="text-sm font-semibold text-gray-900 flex items-center space-x-2">
                              <span>{u.nombre}</span>
                              {u.protegido && (
                                <span title="Usuario protegido (admin raíz)" className="inline-flex items-center px-1.5 py-0.5 text-[10px] font-bold rounded bg-yellow-100 text-yellow-800 border border-yellow-300">
                                  🛡️ PROTEGIDO
                                </span>
                              )}
                              {esEsteUsuario && (
                                <span className="inline-flex items-center px-1.5 py-0.5 text-[10px] font-medium rounded bg-blue-100 text-blue-800">
                                  TÚ
                                </span>
                              )}
                            </div>
                            <div className="text-xs text-gray-500">{u.email}</div>
                          </div>
                        </div>
                      </td>

                      {/* Rol */}
                      <td className="px-6 py-4 whitespace-nowrap">
                        {esAdmin ? (
                          <span className="px-2.5 py-1 text-xs font-semibold rounded-full bg-purple-100 text-purple-800 border border-purple-200">
                            👑 ADMIN
                          </span>
                        ) : (
                          <span className="px-2.5 py-1 text-xs font-semibold rounded-full bg-slate-100 text-slate-700 border border-slate-200">
                            CLIENTE
                          </span>
                        )}
                      </td>

                      {/* Estado */}
                      <td className="px-6 py-4 whitespace-nowrap">
                        {u.activo ? (
                          <span className="px-2.5 py-1 text-xs font-semibold rounded-full bg-green-100 text-green-800 border border-green-200">
                            ✅ Activo
                          </span>
                        ) : (
                          <span className="px-2.5 py-1 text-xs font-semibold rounded-full bg-red-100 text-red-800 border border-red-200">
                            ❌ Desactivado
                          </span>
                        )}
                      </td>

                      {/* Auditorías */}
                      <td className="px-6 py-4 whitespace-nowrap text-center">
                        <span className="text-sm font-semibold text-gray-700">{u.numeroAuditorias}</span>
                      </td>

                      {/* Fecha */}
                      <td className="px-6 py-4 whitespace-nowrap text-xs text-gray-500">
                        {u.fechaRegistro
                          ? new Date(u.fechaRegistro).toLocaleDateString('es-ES', {
                              year: 'numeric', month: 'short', day: 'numeric'
                            })
                          : '—'}
                      </td>

                      {/* Acciones */}
                      <td className="px-6 py-4 whitespace-nowrap text-right text-sm">
                        <div className="flex justify-end items-center space-x-2">

                          {/* Botón REACTIVAR — para usuarios desactivados */}
                          {!u.activo && !esEsteUsuario && (
                            <button
                              onClick={() => solicitarReactivacion(u)}
                              title="Reactivar cuenta"
                              className="px-3 py-1.5 rounded-lg text-xs font-medium text-green-700 bg-green-50 hover:bg-green-100 border border-green-200 hover:border-green-300 transition-colors"
                            >
                              ↻ Reactivar
                            </button>
                          )}

                          {/* Botones para usuarios ACTIVOS */}
                          {u.activo && !esEsteUsuario && !u.protegido && (
                            <>
                              {/* Promover */}
                              {!esAdmin && (
                                <button
                                  onClick={() => solicitarCambioRol(u, 'ADMIN')}
                                  title="Promover a Administrador"
                                  className="px-3 py-1.5 rounded-lg text-xs font-medium text-purple-700 bg-purple-50 hover:bg-purple-100 border border-purple-200 hover:border-purple-300 transition-colors"
                                >
                                  👑 Promover
                                </button>
                              )}

                              {/* Degradar */}
                              {esAdmin && (
                                <button
                                  onClick={() => solicitarCambioRol(u, 'CLIENTE')}
                                  title="Degradar a Cliente"
                                  className="px-3 py-1.5 rounded-lg text-xs font-medium text-slate-700 bg-slate-50 hover:bg-slate-100 border border-slate-200 hover:border-slate-300 transition-colors"
                                >
                                  ↓ Degradar
                                </button>
                              )}

                              {/* Desactivar */}
                              <button
                                onClick={() => solicitarDesactivacion(u)}
                                title="Desactivar usuario"
                                className="p-2 rounded-lg text-red-600 hover:text-red-700 hover:bg-red-50 border border-red-200 hover:border-red-300 transition-colors"
                              >
                                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                                    d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728L5.636 5.636" />
                                </svg>
                              </button>
                            </>
                          )}

                          {/* Sin acciones — eres tú o estás protegido y activo */}
                          {(esEsteUsuario || (u.protegido && u.activo)) && (
                            <span className="text-xs text-gray-400 italic">
                              {esEsteUsuario ? 'Eres tú' : 'Protegido'}
                            </span>
                          )}
                        </div>
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
        <p className="font-semibold mb-1">ℹ️ Reglas de seguridad:</p>
        <ul className="list-disc list-inside space-y-0.5 text-blue-800">
          <li>Los usuarios protegidos (admin raíz) no pueden ser modificados desde la interfaz.</li>
          <li>Ningún administrador puede modificar su propio rol o desactivar su propia cuenta desde aquí.</li>
          <li>El sistema nunca permite quedarse sin al menos un administrador activo.</li>
          <li>Toda reactivación queda registrada en los logs del backend para garantizar trazabilidad.</li>
        </ul>
      </div>

      {/* Modal: cambiar rol */}
      {confirmRol && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm px-4">
          <div className="bg-white rounded-2xl shadow-2xl max-w-md w-full p-6">
            <div className="flex items-center space-x-3 mb-4">
              <div className={`w-12 h-12 rounded-full flex items-center justify-center text-2xl
                ${confirmRol.nuevoRol === 'ADMIN' ? 'bg-purple-100' : 'bg-slate-100'}`}>
                {confirmRol.nuevoRol === 'ADMIN' ? '👑' : '↓'}
              </div>
              <h3 className="text-lg font-bold text-gray-900">
                {confirmRol.nuevoRol === 'ADMIN' ? 'Promover a Administrador' : 'Degradar a Cliente'}
              </h3>
            </div>

            <div className="space-y-3 text-sm text-gray-700 mb-6">
              <p>
                Vas a {confirmRol.nuevoRol === 'ADMIN' ? 'promover' : 'degradar'} a{' '}
                <strong>{confirmRol.usuario.nombre}</strong> ({confirmRol.usuario.email}).
              </p>
              {confirmRol.nuevoRol === 'ADMIN' ? (
                <p className="text-purple-800 bg-purple-50 px-3 py-2 rounded-lg">
                  Como administrador, podrá gestionar usuarios y ver todas las auditorías del sistema.
                </p>
              ) : (
                <p className="text-amber-800 bg-amber-50 px-3 py-2 rounded-lg">
                  Pasará a ser cliente y perderá el acceso al panel de administración.
                </p>
              )}
            </div>

            <div className="flex space-x-3">
              <button
                onClick={() => setConfirmRol(null)}
                disabled={accionLoading}
                className="flex-1 px-4 py-2.5 rounded-xl text-sm font-medium text-gray-700 bg-gray-100 hover:bg-gray-200 transition-colors disabled:opacity-50"
              >
                Cancelar
              </button>
              <button
                onClick={confirmarCambioRol}
                disabled={accionLoading}
                className={`flex-1 px-4 py-2.5 rounded-xl text-sm font-medium text-white transition-colors disabled:opacity-50
                  ${confirmRol.nuevoRol === 'ADMIN' ? 'bg-purple-600 hover:bg-purple-700' : 'bg-slate-600 hover:bg-slate-700'}`}
              >
                {accionLoading ? 'Procesando…' : 'Confirmar'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Modal: desactivar */}
      {confirmBaja && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm px-4">
          <div className="bg-white rounded-2xl shadow-2xl max-w-md w-full p-6">
            <div className="flex items-center space-x-3 mb-4">
              <div className="w-12 h-12 bg-red-100 rounded-full flex items-center justify-center">
                <svg className="w-6 h-6 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                    d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728L5.636 5.636" />
                </svg>
              </div>
              <h3 className="text-lg font-bold text-gray-900">Desactivar usuario</h3>
            </div>

            <div className="space-y-3 text-sm text-gray-700 mb-6">
              <p>
                Vas a desactivar a <strong>{confirmBaja.usuario.nombre}</strong>.
              </p>
              <p>
                Sus auditorías y el historial se conservarán, pero no podrá iniciar sesión hasta
                que reactives su cuenta.
              </p>
            </div>

            <div className="flex space-x-3">
              <button
                onClick={() => setConfirmBaja(null)}
                disabled={accionLoading}
                className="flex-1 px-4 py-2.5 rounded-xl text-sm font-medium text-gray-700 bg-gray-100 hover:bg-gray-200 transition-colors disabled:opacity-50"
              >
                Cancelar
              </button>
              <button
                onClick={confirmarDesactivacion}
                disabled={accionLoading}
                className="flex-1 px-4 py-2.5 rounded-xl text-sm font-medium text-white bg-red-600 hover:bg-red-700 transition-colors disabled:opacity-50"
              >
                {accionLoading ? 'Desactivando…' : 'Sí, desactivar'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Modal: reactivar — con avisos claros sobre las implicaciones */}
      {confirmReactivar && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm px-4">
          <div className="bg-white rounded-2xl shadow-2xl max-w-md w-full p-6">
            <div className="flex items-center space-x-3 mb-4">
              <div className="w-12 h-12 bg-green-100 rounded-full flex items-center justify-center">
                <svg className="w-6 h-6 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                    d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                </svg>
              </div>
              <h3 className="text-lg font-bold text-gray-900">Reactivar cuenta</h3>
            </div>

            <div className="space-y-3 text-sm text-gray-700 mb-6">
              <p>
                Vas a reactivar la cuenta de <strong>{confirmReactivar.usuario.nombre}</strong>{' '}
                <span className="text-gray-500">({confirmReactivar.usuario.email})</span>.
              </p>

              <div className="bg-amber-50 border border-amber-200 px-3 py-2 rounded-lg space-y-1.5">
                <p className="font-semibold text-amber-900 flex items-center">
                  <svg className="w-4 h-4 mr-1.5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clipRule="evenodd" />
                  </svg>
                  Implicaciones de reactivar
                </p>
                <ul className="list-disc list-inside text-amber-800 text-xs space-y-0.5">
                  <li>Podrá volver a iniciar sesión inmediatamente.</li>
                  <li>Recuperará el acceso a sus auditorías y datos.</li>
                  <li>Mantendrá su rol actual (<strong>{confirmReactivar.usuario.role}</strong>).</li>
                  <li>La acción quedará registrada en los logs del sistema.</li>
                </ul>
              </div>

              <p className="text-xs text-gray-500">
                Si la cuenta se desactivó por una solicitud de baja del propio usuario (RGPD, derecho de
                supresión), asegúrate de que la reactivación está justificada.
              </p>
            </div>

            <div className="flex space-x-3">
              <button
                onClick={() => setConfirmReactivar(null)}
                disabled={accionLoading}
                className="flex-1 px-4 py-2.5 rounded-xl text-sm font-medium text-gray-700 bg-gray-100 hover:bg-gray-200 transition-colors disabled:opacity-50"
              >
                Cancelar
              </button>
              <button
                onClick={confirmarReactivacion}
                disabled={accionLoading}
                className="flex-1 px-4 py-2.5 rounded-xl text-sm font-medium text-white bg-green-600 hover:bg-green-700 transition-colors disabled:opacity-50"
              >
                {accionLoading ? 'Reactivando…' : 'Sí, reactivar'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default GestionUsuarios
