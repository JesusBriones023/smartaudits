import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import api from '../api/axios'

const Perfil = () => {
  const { user, updateUser } = useAuth()
  const navigate = useNavigate()

  const [nombre, setNombre] = useState('')
  const [email, setEmail] = useState('')
  const [passwordActual, setPasswordActual] = useState('')
  const [passwordNueva, setPasswordNueva] = useState('')
  const [confirmarPassword, setConfirmarPassword] = useState('')

  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [exito, setExito] = useState('')

  useEffect(() => {
    if (user) {
      setNombre(user.nombre || '')
      setEmail(user.email || '')
    }
  }, [user])

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setExito('')

    // Validación local de contraseñas si el usuario quiere cambiarla
    const quiereCambiarPassword = passwordNueva.length > 0 || passwordActual.length > 0

    if (quiereCambiarPassword) {
      if (!passwordActual) {
        setError('Indica tu contraseña actual para poder cambiarla')
        return
      }
      if (passwordNueva.length < 6) {
        setError('La nueva contraseña debe tener al menos 6 caracteres')
        return
      }
      if (passwordNueva !== confirmarPassword) {
        setError('La nueva contraseña y la confirmación no coinciden')
        return
      }
    }

    setLoading(true)
    try {
      const payload = {
        nombre: nombre.trim(),
        email: email.trim(),
        passwordActual: quiereCambiarPassword ? passwordActual : null,
        passwordNueva: quiereCambiarPassword ? passwordNueva : null,
      }

      const response = await api.put('/usuarios/perfil', payload)
      const { token, nombre: nombreActual, email: emailActual, role, userId } = response.data

      // Actualizamos token y user en localStorage + contexto
      localStorage.setItem('token', token)
      const userActualizado = { nombre: nombreActual, email: emailActual, role, userId }
      localStorage.setItem('user', JSON.stringify(userActualizado))
      if (typeof updateUser === 'function') updateUser(userActualizado)

      // Limpiar campos de contraseña
      setPasswordActual('')
      setPasswordNueva('')
      setConfirmarPassword('')

      setExito('Perfil actualizado correctamente.')
      // El mensaje desaparece a los 4s
      setTimeout(() => setExito(''), 4000)
    } catch (err) {
      setError(
        err.response?.data?.message ||
        err.response?.data?.error ||
        'No se pudo actualizar el perfil. Inténtalo de nuevo.'
      )
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="max-w-2xl mx-auto">
      <div className="mb-8">
        <h1 className="text-3xl font-bold text-gray-900 mb-2">Editar perfil</h1>
        <p className="text-gray-600">
          Modifica tus datos personales y, si lo deseas, cambia tu contraseña.
        </p>
      </div>

      {exito && (
        <div className="bg-green-50 border border-green-200 text-green-800 px-4 py-3 rounded-lg text-sm mb-6 flex items-start space-x-2">
          <svg className="w-5 h-5 flex-shrink-0 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
            <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
          </svg>
          <span>{exito}</span>
        </div>
      )}

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg text-sm mb-6 flex items-start space-x-2">
          <svg className="w-5 h-5 flex-shrink-0 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
            <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clipRule="evenodd" />
          </svg>
          <span>{error}</span>
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-6">

        {/* Datos personales */}
        <div className="card">
          <h2 className="text-lg font-semibold text-gray-900 mb-4 pb-3 border-b border-gray-200">
            Datos personales
          </h2>

          <div className="space-y-4">
            <div>
              <label htmlFor="nombre" className="block text-sm font-medium text-gray-700 mb-2">
                Nombre completo *
              </label>
              <input
                id="nombre"
                type="text"
                value={nombre}
                onChange={(e) => setNombre(e.target.value)}
                className="input-field"
                placeholder="Tu nombre"
                required
                minLength={2}
                maxLength={100}
              />
            </div>

            <div>
              <label htmlFor="email" className="block text-sm font-medium text-gray-700 mb-2">
                Email *
              </label>
              <input
                id="email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="input-field"
                placeholder="tu@email.com"
                required
              />
              <p className="text-xs text-gray-500 mt-1">
                Si cambias el email, tu sesión se renovará automáticamente.
              </p>
            </div>
          </div>
        </div>

        {/* Cambio de contraseña — opcional */}
        <div className="card">
          <h2 className="text-lg font-semibold text-gray-900 mb-1 pb-3 border-b border-gray-200">
            Cambiar contraseña
          </h2>
          <p className="text-sm text-gray-500 mb-4 mt-3">
            Déjalo en blanco si no quieres modificar la contraseña.
          </p>

          <div className="space-y-4">
            <div>
              <label htmlFor="passwordActual" className="block text-sm font-medium text-gray-700 mb-2">
                Contraseña actual
              </label>
              <input
                id="passwordActual"
                type="password"
                value={passwordActual}
                onChange={(e) => setPasswordActual(e.target.value)}
                className="input-field"
                placeholder="••••••••"
                autoComplete="current-password"
              />
            </div>

            <div>
              <label htmlFor="passwordNueva" className="block text-sm font-medium text-gray-700 mb-2">
                Nueva contraseña
              </label>
              <input
                id="passwordNueva"
                type="password"
                value={passwordNueva}
                onChange={(e) => setPasswordNueva(e.target.value)}
                className="input-field"
                placeholder="Mínimo 6 caracteres"
                autoComplete="new-password"
              />
            </div>

            <div>
              <label htmlFor="confirmarPassword" className="block text-sm font-medium text-gray-700 mb-2">
                Confirmar nueva contraseña
              </label>
              <input
                id="confirmarPassword"
                type="password"
                value={confirmarPassword}
                onChange={(e) => setConfirmarPassword(e.target.value)}
                className="input-field"
                placeholder="Repite la nueva contraseña"
                autoComplete="new-password"
              />
            </div>
          </div>
        </div>

        {/* Botones */}
        <div className="flex space-x-4">
          <button
            type="submit"
            disabled={loading}
            className="btn-primary"
          >
            {loading ? 'Guardando…' : 'Guardar cambios'}
          </button>
          <button
            type="button"
            onClick={() => navigate('/dashboard')}
            disabled={loading}
            className="btn-secondary"
          >
            Cancelar
          </button>
        </div>
      </form>
    </div>
  )
}

export default Perfil
