import LegalLinks from './LegalLinks'
import { Outlet, Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

const PublicLayout = () => {
  const { user } = useAuth()
  const navigate = useNavigate()

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">

      {/* Header mínimo */}
      <header className="bg-white border-b border-slate-200 sticky top-0 z-10">
        <div className="max-w-5xl mx-auto px-6 py-3 flex items-center justify-between">
          <Link to={user ? '/dashboard' : '/login'} className="flex items-center space-x-2 group">
            <div className="w-8 h-8 bg-gradient-to-br from-primary-500 to-primary-700 rounded-lg flex items-center justify-center shadow-sm">
              <span className="text-white text-xs font-bold">SA</span>
            </div>
            <span className="text-sm font-bold text-slate-800 group-hover:text-primary-600 transition-colors">SmartAudits</span>
          </Link>

          <div className="flex items-center space-x-3">
            {user ? (
              <button
                onClick={() => navigate(-1)}
                className="flex items-center space-x-1.5 text-xs text-slate-500 hover:text-slate-800 transition-colors"
              >
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
                </svg>
                <span>Volver</span>
              </button>
            ) : (
              <Link to="/login" className="text-xs font-semibold text-primary-600 hover:text-primary-700 transition-colors">
                Iniciar sesión →
              </Link>
            )}
          </div>
        </div>
      </header>

      {/* Contenido */}
      <main className="flex-1 max-w-5xl mx-auto w-full px-6 py-8">
        <Outlet />
      </main>

      {/* Footer legal */}
      <footer className="border-t border-slate-200 bg-white px-6 py-5">
        <div className="max-w-5xl mx-auto flex flex-col sm:flex-row items-center justify-between gap-3">
          <p className="text-xs text-slate-400">
            © 2026 SmartAudits — Proyecto fin de ciclo DAW · ESIC University
          </p>
          <LegalLinks variant="public" />
        </div>
      </footer>

    </div>
  )
}

export default PublicLayout
