import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider } from './context/AuthContext'
import PrivateRoute from './components/PrivateRoute'
import Layout from './components/Layout'
import PublicLayout from './components/PublicLayout'

// Páginas de autenticación
import Login from './pages/Login'
import Register from './pages/Register'

// Páginas principales
import Dashboard from './pages/Dashboard'
import CrearAuditoria from './pages/CrearAuditoria'
import HistorialAuditorias from './pages/HistorialAuditorias'
import DetalleAuditoria from './pages/DetalleAuditoria'
import Perfil from './pages/Perfil'

// Páginas de administración
import GestionUsuarios from './pages/GestionUsuarios'
import HistorialAdmin from './pages/HistorialAdmin'

// Páginas legales de SmartAudits
import AvisoLegal from './pages/AvisoLegal'
import PoliticaPrivacidad from './pages/PoliticaPrivacidad'
import PoliticaCookies from './pages/PoliticaCookies'
import CondicionesUso from './pages/CondicionesUso'

// Páginas de normativa
import NormativaRGPD from './pages/NormativaRGPD'
import NormativaLOPDGDD from './pages/NormativaLOPDGDD'
import NormativaLSSI from './pages/NormativaLSSI'
import NormativaEPrivacy from './pages/NormativaEPrivacy'
import NormativaLey102025 from './pages/NormativaLey102025'
import NormativaAEPDCookies from './pages/NormativaAEPDCookies'

//Scroll to top on route change
import ScrollToTop from './components/ScrollToTop'

function App() {
  return (
    <AuthProvider>
      <Router>
        <ScrollToTop />
        <Routes>

          {/* Rutas de autenticación (sin layout) */}
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />

          {/* Rutas públicas — accesibles sin login, con PublicLayout */}
          <Route element={<PublicLayout />}>
            <Route path="/aviso-legal" element={<AvisoLegal />} />
            <Route path="/politica-privacidad" element={<PoliticaPrivacidad />} />
            <Route path="/politica-cookies" element={<PoliticaCookies />} />
            <Route path="/condiciones-uso" element={<CondicionesUso />} />
          </Route>

          {/* Rutas privadas — requieren login, con Layout (sidebar) */}
          <Route element={<PrivateRoute />}>
            <Route element={<Layout />}>

              {/* Principales */}
              <Route path="/dashboard" element={<Dashboard />} />
              <Route path="/crear-auditoria" element={<CrearAuditoria />} />
              <Route path="/historial" element={<HistorialAuditorias />} />
              <Route path="/auditoria/:id" element={<DetalleAuditoria />} />
              <Route path="/perfil" element={<Perfil />} />

              {/* Administración */}
              <Route path="/admin/usuarios" element={<GestionUsuarios />} />
              <Route path="/admin/historial" element={<HistorialAdmin />} />

              {/* Páginas de normativa (privadas — dentro del sidebar) */}
              <Route path="/normativa/rgpd" element={<NormativaRGPD />} />
              <Route path="/normativa/lopdgdd" element={<NormativaLOPDGDD />} />
              <Route path="/normativa/lssi-ce" element={<NormativaLSSI />} />
              <Route path="/normativa/eprivacy" element={<NormativaEPrivacy />} />
              <Route path="/normativa/ley-10-2025" element={<NormativaLey102025 />} />
              <Route path="/normativa/aepd-cookies" element={<NormativaAEPDCookies />} />

            </Route>
          </Route>

          {/* Redirecciones */}
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="*" element={<Navigate to="/dashboard" replace />} />

        </Routes>
      </Router>
    </AuthProvider>
  )
}

export default App
