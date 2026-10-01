import LegalLinks from './LegalLinks'
import { Outlet } from 'react-router-dom'
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
            <LegalLinks variant="private" />
          </div>
        </footer>
      </main>
    </div>
  )
}

export default Layout