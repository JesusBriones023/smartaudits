import { useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

const Dashboard = () => {
  const { user, isAdmin } = useAuth()
  const navigate = useNavigate()

  return (
    <div className="space-y-4">

      {/* Header de bienvenida */}
      <div className="flex items-center justify-between flex-wrap gap-3">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 tracking-tight mb-0.5">
            Hola, {user?.nombre} 👋
          </h1>
          <p className="text-slate-600 text-sm">
            Bienvenido a tu panel de auditoría legal
          </p>
        </div>
        {isAdmin && (
          <div className="px-4 py-2 bg-gradient-to-r from-accent-500 to-accent-600 text-white rounded-xl shadow-medium flex items-center space-x-2 text-sm">
            <span>👑</span>
            <span className="font-bold">Administrador</span>
          </div>
        )}
      </div>

      {/* Acciones principales */}
      <div className="grid md:grid-cols-2 gap-4">

        {/* Card: Nueva Auditoría */}
        <div
          onClick={() => navigate('/crear-auditoria')}
          className="card card-hover cursor-pointer group relative overflow-hidden !p-5"
        >
          <div className="absolute -right-6 -top-6 w-24 h-24 bg-primary-100 rounded-full opacity-50 group-hover:scale-110 transition-transform"></div>
          <div className="relative">
            <div className="w-11 h-11 bg-gradient-to-br from-primary-500 to-primary-700 rounded-xl flex items-center justify-center mb-3 shadow-glow">
              <svg className="w-5 h-5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
              </svg>
            </div>
            <h3 className="text-lg font-bold text-slate-900 mb-1">Nueva Auditoría</h3>
            <p className="text-slate-600 text-sm leading-relaxed mb-3">
              Analiza textos legales y obtén un informe de cumplimiento detallado con RGPD, LOPDGDD y LSSI-CE.
            </p>
            <span className="inline-flex items-center text-primary-700 font-semibold text-sm group-hover:translate-x-1 transition-transform">
              Empezar ahora
              <svg className="w-4 h-4 ml-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M14 5l7 7m0 0l-7 7m7-7H3" />
              </svg>
            </span>
          </div>
        </div>

        {/* Card: Historial */}
        <div
          onClick={() => navigate('/historial')}
          className="card card-hover cursor-pointer group relative overflow-hidden !p-5"
        >
          <div className="absolute -right-6 -top-6 w-24 h-24 bg-slate-200 rounded-full opacity-50 group-hover:scale-110 transition-transform"></div>
          <div className="relative">
            <div className="w-11 h-11 bg-gradient-to-br from-slate-700 to-slate-900 rounded-xl flex items-center justify-center mb-3 shadow-medium">
              <svg className="w-5 h-5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                  d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
              </svg>
            </div>
            <h3 className="text-lg font-bold text-slate-900 mb-1">
              {isAdmin ? 'Todas las Auditorías' : 'Mi Historial'}
            </h3>
            <p className="text-slate-600 text-sm leading-relaxed mb-3">
              {isAdmin
                ? 'Consulta el historial completo de auditorías de todos los usuarios.'
                : 'Consulta tus auditorías previas y revisa los resultados detallados.'}
            </p>
            <span className="inline-flex items-center text-slate-700 font-semibold text-sm group-hover:translate-x-1 transition-transform">
              Ver historial
              <svg className="w-4 h-4 ml-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M14 5l7 7m0 0l-7 7m7-7H3" />
              </svg>
            </span>
          </div>
        </div>
      </div>

      {/* Sección informativa — ¿Qué audita SmartAudits? */}
      <div className="card bg-gradient-to-br from-slate-900 via-slate-800 to-primary-900 text-white border-0 relative overflow-hidden !p-5">
        <div className="absolute right-0 top-0 w-48 h-48 bg-primary-500/10 rounded-full blur-3xl"></div>
        <div className="relative">
          <h3 className="text-lg font-bold mb-1 tracking-tight">¿Qué audita SmartAudits?</h3>
          <p className="text-slate-300 text-sm mb-4">Análisis basado en la normativa europea y española de protección de datos</p>
          <div className="grid md:grid-cols-2 gap-x-6 gap-y-2">
            {[
              { label: 'Aviso Legal y Política de Privacidad', ruta: '/normativa/lssi-ce' },
              { label: 'Política de Cookies y Consentimiento', ruta: '/normativa/aepd-cookies' },
              { label: 'Derechos ARSULIPO (RGPD)', ruta: '/normativa/rgpd' },
              { label: 'Base Legal del Tratamiento', ruta: '/normativa/rgpd' },
              { label: 'Encargados de Tratamiento', ruta: '/normativa/rgpd' },
              { label: 'Transferencias Internacionales', ruta: '/normativa/rgpd' },
              { label: 'Conservación de Datos', ruta: '/normativa/rgpd' },
              { label: 'Medidas de Seguridad', ruta: '/normativa/rgpd' },
            ].map((item, idx) => (
              <div
                key={idx}
                onClick={() => navigate(item.ruta)}
                className="flex items-center space-x-2 text-sm cursor-pointer group"
              >
                <div className="w-4 h-4 rounded-full bg-primary-500/20 flex items-center justify-center flex-shrink-0 group-hover:bg-primary-500/40 transition-colors">
                  <svg className="w-2.5 h-2.5 text-primary-400" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                  </svg>
                </div>
                <span className="text-slate-200 group-hover:text-white group-hover:underline transition-colors">{item.label}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Normativas aplicables — ahora clickables */}
      <div className="grid sm:grid-cols-3 gap-3">
        {[
          { code: 'RGPD', name: 'Reglamento UE 2016/679', color: 'from-primary-500 to-primary-700', ruta: '/normativa/rgpd' },
          { code: 'LOPDGDD', name: 'Ley Orgánica 3/2018', color: 'from-slate-700 to-slate-900', ruta: '/normativa/lopdgdd' },
          { code: 'LSSI-CE', name: 'Ley 34/2002', color: 'from-accent-500 to-accent-700', ruta: '/normativa/lssi-ce' },
        ].map((norm) => (
          <div
            key={norm.code}
            onClick={() => navigate(norm.ruta)}
            className="card text-center !p-3 cursor-pointer card-hover group"
          >
            <div className={`inline-block px-2.5 py-0.5 bg-gradient-to-r ${norm.color} text-white text-xs font-bold rounded-full mb-1 group-hover:scale-105 transition-transform`}>
              {norm.code}
            </div>
            <p className="text-xs text-slate-600">{norm.name}</p>
            <p className="text-xs text-primary-600 font-medium mt-1 opacity-0 group-hover:opacity-100 transition-opacity">Ver normativa →</p>
          </div>
        ))}
      </div>

      {/* Acceso rápido a más normativas */}
      <div className="card !p-4">
        <p className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-3">Más normativa aplicada</p>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
          {[
            { code: 'ePrivacy', name: 'Directiva 2002/58/CE', ruta: '/normativa/eprivacy', color: 'text-indigo-700 bg-indigo-50 border-indigo-100' },
            { code: 'Ley 10/2025', name: 'Privacidad y telecomunicaciones', ruta: '/normativa/ley-10-2025', color: 'text-emerald-700 bg-emerald-50 border-emerald-100' },
            { code: 'AEPD Cookies', name: 'Guía de cookies 2025', ruta: '/normativa/aepd-cookies', color: 'text-rose-700 bg-rose-50 border-rose-100' },
          ].map((item) => (
            <div
              key={item.code}
              onClick={() => navigate(item.ruta)}
              className={`flex items-center space-x-2 p-2.5 rounded-xl border cursor-pointer hover:shadow-soft transition-all ${item.color}`}
            >
              <span className="text-xs font-bold">{item.code}</span>
              <span className="text-xs">{item.name}</span>
              <svg className="w-3 h-3 ml-auto flex-shrink-0 opacity-60" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
              </svg>
            </div>
          ))}
        </div>
      </div>

    </div>
  )
}

export default Dashboard
