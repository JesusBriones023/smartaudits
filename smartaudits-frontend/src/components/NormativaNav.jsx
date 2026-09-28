import { Link, useLocation } from 'react-router-dom'

const normativas = [
  { ruta: '/normativa/rgpd', codigo: 'RGPD', nombre: 'Reglamento General de Protección de Datos', color: 'bg-primary-600' },
  { ruta: '/normativa/lopdgdd', codigo: 'LOPDGDD', nombre: 'Ley Orgánica de Protección de Datos y Derechos Digitales', color: 'bg-slate-700' },
  { ruta: '/normativa/lssi-ce', codigo: 'LSSI-CE', nombre: 'Ley de Servicios de la Sociedad de la Información', color: 'bg-accent-600' },
  { ruta: '/normativa/eprivacy', codigo: 'ePrivacy', nombre: 'Directiva ePrivacy 2002/58/CE', color: 'bg-indigo-600' },
  { ruta: '/normativa/ley-10-2025', codigo: 'Ley 10/2025', nombre: 'Ley de Atención a la Clientela', color: 'bg-emerald-600' },
  { ruta: '/normativa/aepd-cookies', codigo: 'Guía AEPD', nombre: 'Guía sobre el uso de las Cookies', color: 'bg-rose-600' },
]

const NormativaNav = () => {
  const { pathname } = useLocation()
  const indiceActual = normativas.findIndex(n => n.ruta === pathname)
  const anterior = indiceActual > 0 ? normativas[indiceActual - 1] : null
  const siguiente = indiceActual < normativas.length - 1 ? normativas[indiceActual + 1] : null

  return (
    <div className="space-y-3 mt-2">

      {/* Navegación anterior / siguiente */}
      <div className="grid grid-cols-2 gap-3">
        {anterior ? (
          <Link to={anterior.ruta}
            className="flex items-center space-x-3 p-3 bg-white border border-slate-200 rounded-xl hover:border-primary-300 hover:bg-primary-50 transition-colors group">
            <svg className="w-4 h-4 text-slate-400 group-hover:text-primary-500 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
            <div className="min-w-0">
              <p className="text-xs text-slate-400 group-hover:text-primary-500">Anterior</p>
              <p className="text-sm font-semibold text-slate-700 group-hover:text-primary-700 truncate">{anterior.codigo}</p>
            </div>
          </Link>
        ) : <div />}

        {siguiente ? (
          <Link to={siguiente.ruta}
            className="flex items-center justify-end space-x-3 p-3 bg-white border border-slate-200 rounded-xl hover:border-primary-300 hover:bg-primary-50 transition-colors group text-right">
            <div className="min-w-0">
              <p className="text-xs text-slate-400 group-hover:text-primary-500">Siguiente</p>
              <p className="text-sm font-semibold text-slate-700 group-hover:text-primary-700 truncate">{siguiente.codigo}</p>
            </div>
            <svg className="w-4 h-4 text-slate-400 group-hover:text-primary-500 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
            </svg>
          </Link>
        ) : <div />}
      </div>

      {/* Todas las normativas */}
      <div className="p-4 bg-white border border-slate-200 rounded-xl">
        <p className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-3">Toda la normativa</p>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
          {normativas.map((n) => {
            const esActual = n.ruta === pathname
            return (
              <Link key={n.ruta} to={n.ruta}
                className={`flex items-center space-x-2 p-2.5 rounded-xl border transition-colors ${
                  esActual
                    ? 'border-primary-200 bg-primary-50 cursor-default'
                    : 'border-slate-100 bg-slate-50 hover:border-primary-200 hover:bg-primary-50'
                }`}>
                <div className={`w-2 h-2 rounded-full flex-shrink-0 ${esActual ? 'bg-primary-500' : 'bg-slate-300'}`}></div>
                <div className="min-w-0">
                  <p className={`text-xs font-bold truncate ${esActual ? 'text-primary-700' : 'text-slate-700'}`}>{n.codigo}</p>
                </div>
              </Link>
            )
          })}
        </div>
      </div>

    </div>
  )
}

export default NormativaNav
