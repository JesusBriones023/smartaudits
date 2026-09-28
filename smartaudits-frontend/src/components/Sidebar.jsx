import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import api from '../api/axios'

const Sidebar = () => {
  const { user, logout, isAdmin } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [showBajaModal, setShowBajaModal] = useState(false)
  const [bajaLoading, setBajaLoading] = useState(false)
  const [bajaError, setBajaError] = useState('')

  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  const handleConfirmarBaja = async () => {
    setBajaLoading(true)
    setBajaError('')
    try {
      await api.delete('/auth/baja')
      logout()
      navigate('/login', {
        state: { mensaje: 'Tu cuenta ha sido desactivada correctamente.' }
      })
    } catch (err) {
      setBajaError(
        err.response?.data?.message ||
        'No se pudo procesar la baja. Inténtalo de nuevo.'
      )
      setBajaLoading(false)
    }
  }

  const isActive = (path) => location.pathname === path || location.pathname.startsWith(path + '/')

  // Items comunes a todos los usuarios
  const navItems = [
    {
      path: '/dashboard',
      label: 'Dashboard',
      icon: (
        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6" />
        </svg>
      )
    },
    {
      path: '/crear-auditoria',
      label: 'Nueva Auditoría',
      icon: (
        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M12 4v16m8-8H4" />
        </svg>
      )
    },
    {
      path: '/historial',
      label: 'Historial',
      icon: (
        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" />
        </svg>
      )
    },
    {
      path: '/perfil',
      label: 'Editar perfil',
      icon: (
        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
        </svg>
      )
    },
  ]

  // Items solo para admin
  const adminItems = [
    {
      path: '/admin/usuarios',
      label: 'Gestión Usuarios',
      icon: (
        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" />
        </svg>
      )
    },
    {
      path: '/admin/historial',
      label: 'Historial Acciones',
      icon: (
        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9h2m-2 4h6m-6-8h6m-6 0V9a2 2 0 012-2h2a2 2 0 012 2v0" />
        </svg>
      )
    },
  ]

  return (
    <>
      <aside className="fixed left-0 top-0 h-screen w-56 bg-slate-900 border-r border-slate-800 flex flex-col z-40">

        {/* Logo */}
        <div className="px-6 py-6 border-b border-slate-800">
          <Link to="/dashboard" className="flex items-center space-x-3 group">
            <div className="w-10 h-10 bg-gradient-to-br from-primary-500 to-primary-700 rounded-xl flex items-center justify-center shadow-glow group-hover:shadow-strong transition-shadow">
              <span className="text-white font-bold text-lg">SA</span>
            </div>
            <div>
              <p className="text-white font-bold text-lg leading-none tracking-tight">SmartAudits</p>
              <p className="text-slate-500 text-xs mt-1">Auditoría Legal</p>
            </div>
          </Link>
        </div>

        {/* Navegación */}
        <nav className="flex-1 px-4 py-6 space-y-1 overflow-y-auto">
          <p className="px-3 text-xs font-semibold text-slate-500 uppercase tracking-wider mb-3">
            Menú principal
          </p>
          {navItems.map((item) => (
            <Link
              key={item.path}
              to={item.path}
              className={`flex items-center space-x-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-all
                ${isActive(item.path)
                  ? 'bg-primary-600 text-white shadow-glow'
                  : 'text-slate-300 hover:text-white hover:bg-slate-800'
                }`}
            >
              {item.icon}
              <span>{item.label}</span>
            </Link>
          ))}

          {/* Sección admin — solo visible para admins */}
          {isAdmin && (
            <>
              <p className="px-3 text-xs font-semibold text-accent-500 uppercase tracking-wider mt-6 mb-3">
                Administración
              </p>
              {adminItems.map((item) => (
                <Link
                  key={item.path}
                  to={item.path}
                  className={`flex items-center space-x-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-all
                    ${isActive(item.path)
                      ? 'bg-accent-600 text-white shadow-glow'
                      : 'text-slate-300 hover:text-white hover:bg-slate-800'
                    }`}
                >
                  {item.icon}
                  <span>{item.label}</span>
                </Link>
              ))}
            </>
          )}
        </nav>

        {/* Footer del sidebar — usuario */}
        <div className="px-4 py-4 border-t border-slate-800 space-y-3">
          <Link to="/perfil" className="block px-3 py-3 bg-slate-800/50 rounded-xl hover:bg-slate-700/60 transition-colors group">
            <div className="flex items-center space-x-3">
              <div className={`w-10 h-10 rounded-full flex items-center justify-center font-bold text-white
                ${isAdmin ? 'bg-gradient-to-br from-accent-500 to-accent-700' : 'bg-gradient-to-br from-slate-600 to-slate-700'}`}>
                {user?.nombre?.charAt(0)?.toUpperCase() || '?'}
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-white text-sm font-semibold truncate group-hover:text-primary-300 transition-colors">{user?.nombre}</p>
                <p className="text-xs">
                  {isAdmin ? (
                    <span className="text-accent-400 font-semibold">👑 Administrador</span>
                  ) : (
                    <span className="text-slate-400">Cliente</span>
                  )}
                </p>
              </div>
              <svg className="w-4 h-4 text-slate-500 group-hover:text-primary-400 transition-colors flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
              </svg>
            </div>
          </Link>

          <button
            onClick={handleLogout}
            className="w-full flex items-center justify-center space-x-2 px-3 py-2.5 rounded-xl text-sm font-medium text-slate-300 hover:text-white hover:bg-slate-800 border border-slate-700 hover:border-slate-600 transition-all"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
            </svg>
            <span>Cerrar sesión</span>
          </button>

          <button
            onClick={() => setShowBajaModal(true)}
            className="w-full flex items-center justify-center space-x-2 px-3 py-2 rounded-xl text-xs font-medium text-red-400 hover:text-red-300 hover:bg-red-950/30 border border-red-900/40 hover:border-red-800/60 transition-all"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6M1 7h22M9 7V4a1 1 0 011-1h4a1 1 0 011 1v3" />
            </svg>
            <span>Darse de baja</span>
          </button>
        </div>
      </aside>

      {/* Modal de confirmación de baja */}
      {showBajaModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm px-4">
          <div className="bg-white rounded-2xl shadow-2xl max-w-md w-full p-6">
            <div className="flex items-center space-x-3 mb-4">
              <div className="w-12 h-12 bg-red-100 rounded-full flex items-center justify-center">
                <svg className="w-6 h-6 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                    d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                </svg>
              </div>
              <h3 className="text-lg font-bold text-gray-900">Confirmar baja de cuenta</h3>
            </div>

            <div className="space-y-3 text-sm text-gray-700 mb-6">
              <p>
                Tu cuenta se <strong>desactivará</strong> y no podrás volver a iniciar sesión.
              </p>
              <p>
                Tus auditorías y el historial <strong>se conservarán</strong> en el sistema —
                no se borra nada.
              </p>
              <p className="text-gray-500 text-xs">
                Si más adelante quieres recuperar el acceso, ponte en contacto con un administrador.
              </p>
            </div>

            {bajaError && (
              <div className="bg-red-50 border border-red-200 text-red-700 px-3 py-2 rounded-lg text-sm mb-4">
                {bajaError}
              </div>
            )}

            <div className="flex space-x-3">
              <button
                onClick={() => setShowBajaModal(false)}
                disabled={bajaLoading}
                className="flex-1 px-4 py-2.5 rounded-xl text-sm font-medium text-gray-700 bg-gray-100 hover:bg-gray-200 transition-colors disabled:opacity-50"
              >
                Cancelar
              </button>
              <button
                onClick={handleConfirmarBaja}
                disabled={bajaLoading}
                className="flex-1 px-4 py-2.5 rounded-xl text-sm font-medium text-white bg-red-600 hover:bg-red-700 transition-colors disabled:opacity-50"
              >
                {bajaLoading ? 'Procesando…' : 'Sí, darme de baja'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}

export default Sidebar
