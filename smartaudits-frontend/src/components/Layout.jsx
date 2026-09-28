import { Outlet } from 'react-router-dom'
import { Link } from 'react-router-dom'
import Sidebar from './Sidebar'

const Layout = () => {
  return (
    <div className="min-h-screen bg-slate-50">
      <Sidebar />
      <main className="ml-56 min-h-screen flex flex-col">
        <div className="flex-1 px-6 py-5 max-w-7xl mx-auto w-full animate-fade-in">
          <Outlet />
        </div>

        {/* Footer legal */}
        <footer className="ml-0 border-t border-slate-200 bg-white px-6 py-4">
          <div className="max-w-7xl mx-auto flex flex-col sm:flex-row items-center justify-between gap-2">
            <p className="text-xs text-slate-400">
              © 2025 SmartAudits — Plataforma de auditoría legal y cumplimiento normativo. Todos los derechos reservados.
            </p>
            <div className="flex items-center gap-4">
              <Link to="/aviso-legal" className="text-xs text-slate-500 hover:text-primary-600 transition-colors">
                Aviso legal
              </Link>
              <span className="text-slate-300 text-xs">·</span>
              <Link to="/politica-privacidad" className="text-xs text-slate-500 hover:text-primary-600 transition-colors">
                Privacidad
              </Link>
              <span className="text-slate-300 text-xs">·</span>
              <Link to="/politica-cookies" className="text-xs text-slate-500 hover:text-primary-600 transition-colors">
                Cookies
              </Link>
              <span className="text-slate-300 text-xs">·</span>
              <Link to="/condiciones-uso" className="text-xs text-slate-500 hover:text-primary-600 transition-colors">
                Condiciones de uso
              </Link>
            </div>
          </div>
        </footer>
      </main>
    </div>
  )
}

export default Layout