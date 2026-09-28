import { createContext, useContext, useState, useEffect } from 'react'
import api from '../api/axios'

const AuthContext = createContext(null)

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const storedUser = localStorage.getItem('user')
    const token = localStorage.getItem('token')

    if (!storedUser || !token) {
      // No dejamos estados de sesión a medias.
      localStorage.removeItem('user')
      localStorage.removeItem('token')
      setUser(null)
      setLoading(false)
      return
    }

    try {
      const parsedUser = JSON.parse(storedUser)

      const validUser =
        parsedUser &&
        typeof parsedUser === 'object' &&
        Number.isInteger(parsedUser.userId) &&
        typeof parsedUser.nombre === 'string' &&
        typeof parsedUser.email === 'string' &&
        ['CLIENTE', 'ADMIN'].includes(parsedUser.role)

      if (!validUser) {
        throw new Error('Stored user is invalid')
      }

      setUser(parsedUser)
    } catch {
      // Si localStorage está corrupto, recuperamos una sesión limpia
      // en lugar de romper toda la aplicación.
      localStorage.removeItem('user')
      localStorage.removeItem('token')
      setUser(null)
    } finally {
    setLoading(false)
  }
}, [])

  const login = async (email, password) => {
    try {
      const response = await api.post('/auth/login', { email, password })
      const { token, nombre, email: userEmail, role, userId } = response.data

      const userData = { nombre, email: userEmail, role, userId }

      localStorage.setItem('token', token)
      localStorage.setItem('user', JSON.stringify(userData))
      setUser(userData)

      return { success: true }
    } catch (error) {
      return {
        success: false,
        error: error.response?.data?.message || 'Error al iniciar sesión'
      }
    }
  }

  const register = async (nombre, email, password) => {
    try {
      const response = await api.post('/auth/register', { nombre, email, password })
      const { token, nombre: userName, email: userEmail, role, userId } = response.data

      const userData = { nombre: userName, email: userEmail, role, userId }

      localStorage.setItem('token', token)
      localStorage.setItem('user', JSON.stringify(userData))
      setUser(userData)

      return { success: true }
    } catch (error) {
      return {
        success: false,
        error: error.response?.data?.message || 'Error al registrarse'
      }
    }
  }

  /**
   * Actualiza los datos del usuario en memoria + localStorage.
   * Lo usa la página de perfil tras una edición correcta.
   */
  const updateUser = (userData) => {
    localStorage.setItem('user', JSON.stringify(userData))
    setUser(userData)
  }

  const logout = () => {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    setUser(null)
  }

  const value = {
    user,
    login,
    register,
    logout,
    updateUser,
    loading,
    isAuthenticated: !!user,
    isAdmin: user?.role === 'ADMIN'
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export const useAuth = () => {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth debe usarse dentro de AuthProvider')
  }
  return context
}
