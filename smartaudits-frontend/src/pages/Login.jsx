import { useState, useEffect } from 'react'
import { Link, useNavigate, useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

const Login = () => {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [mensajeInfo, setMensajeInfo] = useState('')
  const [loading, setLoading] = useState(false)
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  // Mensaje informativo si venimos de hacer una baja u otra acción
  useEffect(() => {
    if (location.state?.mensaje) {
      setMensajeInfo(location.state.mensaje)
      // Limpiamos el state del history para que no reaparezca al recargar
      window.history.replaceState({}, document.title)
    }
  }, [location.state])

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setMensajeInfo('')
    setLoading(true)

    const result = await login(email, password)

    if (result.success) {
      navigate('/dashboard')
    } else {
      setError(result.error)
    }

    setLoading(false)
  }

  return (
    <div className="min-h-screen flex">

      {/* IZQUIERDA — Panel decorativo */}
      <div className="hidden lg:flex lg:w-1/2 bg-gradient-to-br from-slate-900 via-slate-800 to-primary-900 relative overflow-hidden">
        {/* Patrón decorativo */}
        <div className="absolute inset-0 opacity-10"
             style={{
               backgroundImage: 'radial-gradient(circle at 20% 50%, white 1px, transparent 1px), radial-gradient(circle at 80% 80%, white 1px, transparent 1px)',
               backgroundSize: '40px 40px'
             }}>
        </div>

        {/* Contenido */}
        <div className="relative z-10 flex flex-col justify-between p-12 text-white w-full">
          <div className="flex items-center space-x-3">
            <div className="w-12 h-12 bg-gradient-to-br from-primary-400 to-primary-600 rounded-2xl flex items-center justify-center shadow-glow">
              <span className="text-white font-bold text-xl">SA</span>
            </div>
            <div>
              <p className="text-2xl font-bold tracking-tight">SmartAudits</p>
              <p className="text-primary-300 text-sm">Auditoría Legal Automatizada</p>
            </div>
          </div>

          <div className="space-y-6 max-w-md">
            <h1 className="text-5xl font-bold leading-tight tracking-tight">
              Cumplimiento legal,<br />
              <span className="text-primary-400">simplificado.</span>
            </h1>
            <p className="text-slate-300 text-lg leading-relaxed">
              Audita tus textos legales y detecta incumplimientos del RGPD, LOPDGDD y LSSI-CE en segundos.
            </p>

            <div className="space-y-3 pt-4">
              <div className="flex items-center space-x-3">
                <div className="w-6 h-6 rounded-full bg-primary-500/20 flex items-center justify-center">
                  <svg className="w-4 h-4 text-primary-400" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                  </svg>
                </div>
                <span className="text-slate-200">Análisis basado en normativa europea</span>
              </div>
              <div className="flex items-center space-x-3">
                <div className="w-6 h-6 rounded-full bg-primary-500/20 flex items-center justify-center">
                  <svg className="w-4 h-4 text-primary-400" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                  </svg>
                </div>
                <span className="text-slate-200">Informes detallados con recomendaciones</span>
              </div>
              <div className="flex items-center space-x-3">
                <div className="w-6 h-6 rounded-full bg-primary-500/20 flex items-center justify-center">
                  <svg className="w-4 h-4 text-primary-400" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                  </svg>
                </div>
                <span className="text-slate-200">Historial completo de tus auditorías</span>
              </div>
            </div>
          </div>

          <p className="text-slate-500 text-sm">
            © {new Date().getFullYear()} SmartAudits — Proyecto fin de ciclo DAW
          </p>
        </div>
      </div>

      {/* DERECHA — Formulario */}
      <div className="flex-1 flex items-center justify-center p-8 bg-slate-50">
        <div className="w-full max-w-md animate-slide-up">

          {/* Logo móvil */}
          <div className="lg:hidden flex items-center space-x-3 mb-10">
            <div className="w-12 h-12 bg-gradient-to-br from-primary-500 to-primary-700 rounded-2xl flex items-center justify-center">
              <span className="text-white font-bold text-xl">SA</span>
            </div>
            <div>
              <p className="text-2xl font-bold text-slate-900 tracking-tight">SmartAudits</p>
              <p className="text-slate-500 text-sm">Auditoría Legal</p>
            </div>
          </div>

          <div className="mb-8">
            <h2 className="text-3xl font-bold text-slate-900 mb-2 tracking-tight">
              Bienvenido de vuelta
            </h2>
            <p className="text-slate-600">
              Inicia sesión para acceder a tu panel
            </p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-5" autoComplete="on">
            {mensajeInfo && (
              <div className="bg-blue-50 border border-blue-200 text-blue-800 px-4 py-3 rounded-xl text-sm flex items-start space-x-2">
                <svg className="w-5 h-5 flex-shrink-0 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clipRule="evenodd" />
                </svg>
                <span>{mensajeInfo}</span>
              </div>
            )}

            {error && (
              <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-xl text-sm flex items-start space-x-2">
                <svg className="w-5 h-5 flex-shrink-0 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clipRule="evenodd" />
                </svg>
                <span>{error}</span>
              </div>
            )}

            <div>
              <label htmlFor="email" className="block text-sm font-semibold text-slate-700 mb-2">
                Email
              </label>
              <input
                id="email"
                name="email"
                type="email"
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="input-field"
                placeholder="tu@email.com"
                required
              />
            </div>

            <div>
              <label htmlFor="password" className="block text-sm font-semibold text-slate-700 mb-2">
                Contraseña
              </label>
              <input
                id="password"
                name="password"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="input-field"
                placeholder="••••••••"
                required
              />
            </div>

            <button
              type="submit"
              disabled={loading}
              className="w-full btn-primary py-3 text-base"
            >
              {loading ? 'Iniciando sesión...' : 'Iniciar sesión →'}
            </button>
          </form>

          <div className="mt-8 text-center">
            <p className="text-sm text-slate-600">
              ¿No tienes cuenta?{' '}
              <Link to="/register" className="text-primary-600 hover:text-primary-700 font-semibold">
                Regístrate gratis
              </Link>
            </p>
          </div>

          <div className="mt-6 pt-6 border-t border-slate-100 flex flex-wrap justify-center gap-x-4 gap-y-1">
            <Link to="/aviso-legal" className="text-xs text-slate-400 hover:text-slate-600 transition-colors">Aviso legal</Link>
            <span className="text-slate-200 text-xs">·</span>
            <Link to="/politica-privacidad" className="text-xs text-slate-400 hover:text-slate-600 transition-colors">Privacidad</Link>
            <span className="text-slate-200 text-xs">·</span>
            <Link to="/politica-cookies" className="text-xs text-slate-400 hover:text-slate-600 transition-colors">Cookies</Link>
            <span className="text-slate-200 text-xs">·</span>
            <Link to="/condiciones-uso" className="text-xs text-slate-400 hover:text-slate-600 transition-colors">Condiciones de uso</Link>
          </div>
        </div>
      </div>
    </div>
  )
}

export default Login
