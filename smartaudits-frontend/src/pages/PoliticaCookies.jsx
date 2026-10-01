import ExternalNormativeLink from '../components/ExternalNormativeLink'
import { useState } from 'react'

const PoliticaCookies = () => {
  const [tabActiva, setTabActiva] = useState('almacenamiento')

  const almacenamientoItems = [
    {
      nombre: 'token',
      almacen: 'localStorage',
      tipo: 'Técnica / Autenticación',
      finalidad: 'Almacena el token JWT firmado que autentica al usuario en cada petición al backend. Sin este token, el usuario no puede acceder a las funciones protegidas de la plataforma.',
      duracion: '24 horas (expiración del JWT)',
      proveedor: 'SmartAudits (propio)',
      exento: true,
      base: 'Estrictamente necesaria para la prestación del servicio (Art. 22.2 LSSI-CE)',
    },
    {
      nombre: 'user',
      almacen: 'localStorage',
      tipo: 'Técnica / Sesión',
      finalidad: 'Almacena los datos básicos del usuario autenticado (nombre, email, rol) para personalizar la interfaz y evitar peticiones innecesarias al servidor en cada carga de página.',
      duracion: 'Sesión activa (hasta cierre de sesión o expiración del token)',
      proveedor: 'SmartAudits (propio)',
      exento: true,
      base: 'Estrictamente necesaria para la prestación del servicio (Art. 22.2 LSSI-CE)',
    },
  ]

  const categoriasCookies = [
    {
      categoria: '✅ Técnicas / estrictamente necesarias',
      consentimiento: 'No requieren',
      color: 'green',
      desc: 'Imprescindibles para el funcionamiento básico del servicio. No pueden desactivarse sin que el servicio deje de funcionar. Exentas de consentimiento según la Guía AEPD y el Art. 22.2 LSSI-CE.',
      ejemplos: ['Token de sesión / autenticación JWT', 'Datos del usuario en sesión activa', 'Preferencias de idioma elegidas por el usuario', 'Carrito de la compra (en e-commerce)', 'Protección CSRF'],
      usaSmartAudits: true,
    },
    {
      categoria: '⚙️ Funcionales / preferencias',
      consentimiento: 'Requieren consentimiento',
      color: 'amber',
      desc: 'Recuerdan preferencias del usuario para mejorar su experiencia, pero no son imprescindibles. Requieren consentimiento previo salvo que sean elegidas directamente por el usuario.',
      ejemplos: ['Recordar usuario/contraseña', 'Configuración de accesibilidad', 'Vídeos embebidos de terceros (YouTube, Vimeo)', 'Personalización de la interfaz por el editor'],
      usaSmartAudits: false,
    },
    {
      categoria: '📊 Analíticas / estadísticas',
      consentimiento: 'Requieren consentimiento',
      color: 'amber',
      desc: 'Recopilan información sobre cómo los usuarios interactúan con el sitio web. Siempre requieren consentimiento previo si permiten identificar a usuarios individuales.',
      ejemplos: ['Google Analytics', 'Adobe Analytics', 'Hotjar / Microsoft Clarity', 'Matomo (sin anonimización)', 'Métricas de rendimiento con ID de usuario'],
      usaSmartAudits: false,
    },
    {
      categoria: '📢 Publicitarias / marketing',
      consentimiento: 'Requieren consentimiento',
      color: 'red',
      desc: 'Rastrean la actividad del usuario para mostrar publicidad personalizada. Son las más invasivas para la privacidad y siempre requieren consentimiento previo e inequívoco.',
      ejemplos: ['Google Ads / DoubleClick', 'Meta Pixel / Facebook Pixel', 'Remarketing y retargeting', 'Perfilado publicitario cross-site', 'Identificadores de publicidad (IDFA, GAID)'],
      usaSmartAudits: false,
    },
  ]

  const navegadores = [
    {
      nombre: 'Google Chrome',
      icono: '🌐',
      pasos: ['Abre DevTools con F12 o Ctrl+Shift+I', 'Ve a la pestaña "Application" (Aplicación)', 'En el panel izquierdo, expande "Local Storage"', 'Selecciona el origen de SmartAudits (localhost:5173)', 'Selecciona "token" y "user" y pulsa Delete', 'O haz clic derecho → "Clear" para borrar todo'],
    },
    {
      nombre: 'Mozilla Firefox',
      icono: '🦊',
      pasos: ['Abre DevTools con F12', 'Ve a la pestaña "Almacenamiento"', 'Expande "Almacenamiento local"', 'Selecciona el origen de SmartAudits', 'Haz clic derecho en las entradas y selecciona "Eliminar elemento"', 'O usa el icono de papelera para borrar todo'],
    },
    {
      nombre: 'Microsoft Edge',
      icono: '🔷',
      pasos: ['Abre DevTools con F12', 'Ve a la pestaña "Aplicación"', 'En el panel izquierdo, expande "Almacenamiento local"', 'Selecciona el origen de SmartAudits', 'Selecciona las entradas y pulsa Delete', 'O haz clic en el icono de borrar para limpiar todo'],
    },
    {
      nombre: 'Safari',
      icono: '🧭',
      pasos: ['Ve a Preferencias → Privacidad → Gestionar datos del sitio web', 'Busca "localhost" en la lista', 'Selecciona la entrada y haz clic en "Eliminar"', 'O activa el menú Desarrollador y usa la consola web', 'En consola: localStorage.clear() borra todo el localStorage'],
    },
  ]

  const tabs = [
    { id: 'almacenamiento', label: 'Qué almacenamos', icono: '💾' },
    { id: 'categorias', label: 'Tipos de cookies', icono: '🍪' },
    { id: 'gestion', label: 'Cómo gestionarlas', icono: '⚙️' },
  ]

  return (
    <div className="space-y-6 max-w-5xl">

      {/* Cabecera */}
      <div className="bg-gradient-to-br from-accent-600 via-accent-700 to-slate-900 rounded-2xl px-8 py-10 relative overflow-hidden">
        <div className="absolute right-0 top-0 w-72 h-72 bg-white/5 rounded-full blur-3xl pointer-events-none"></div>
        <div className="absolute left-0 bottom-0 w-48 h-48 bg-accent-400/10 rounded-full blur-2xl pointer-events-none"></div>
        <div className="relative">
          <div className="inline-flex items-center space-x-2 px-3 py-1.5 bg-white/15 rounded-full mb-4">
            <span className="w-2 h-2 bg-green-400 rounded-full"></span>
            <span className="text-white text-xs font-bold tracking-wider">SmartAudits · Política de Cookies · Actualizada mayo 2026</span>
          </div>
          <h1 className="text-3xl font-bold text-white mb-2 leading-tight">Política de Cookies</h1>
          <p className="text-white/70 text-sm leading-relaxed max-w-2xl">
            Información sobre el uso de tecnologías de almacenamiento local conforme al Art. 22.2 de la LSSI-CE,
            la Directiva ePrivacy 2002/58/CE y la Guía sobre el uso de las Cookies de la AEPD
            (actualizada julio 2023, exigible desde enero 2024).
          </p>
          <p className="text-white/40 text-xs mt-3">Última actualización: 20 de mayo de 2026</p>
        </div>
      </div>

      {/* Declaración principal */}
      <div className="grid sm:grid-cols-3 gap-3">
        {[
          { icono: '✅', titulo: 'Solo técnicas', desc: 'SmartAudits usa únicamente almacenamiento técnico estrictamente necesario para la autenticación.', color: 'green' },
          { icono: '🚫', titulo: 'Sin seguimiento', desc: 'No hay cookies de analítica, publicidad, marketing ni rastreo de ningún tipo.', color: 'green' },
          { icono: '🤝', titulo: 'Sin terceros', desc: 'No integramos ningún servicio externo que almacene cookies en tu dispositivo.', color: 'green' },
        ].map((item, i) => (
          <div key={i} className="p-4 bg-green-50 border border-green-200 rounded-xl">
            <span className="text-2xl mb-2 block">{item.icono}</span>
            <p className="text-sm font-bold text-green-900 mb-1">{item.titulo}</p>
            <p className="text-xs text-green-700 leading-relaxed">{item.desc}</p>
          </div>
        ))}
      </div>

      {/* Nota técnica destacada */}
      <div className="p-4 bg-primary-50 border border-primary-200 rounded-xl flex items-start space-x-3">
        <div className="w-8 h-8 bg-primary-600 rounded-lg flex items-center justify-center flex-shrink-0">
          <svg className="w-4 h-4 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
        </div>
        <div>
          <p className="text-sm font-bold text-primary-900 mb-1">Nota técnica importante</p>
          <p className="text-xs text-primary-800 leading-relaxed">
            SmartAudits utiliza <code className="bg-primary-100 px-1 py-0.5 rounded font-mono">localStorage</code> del navegador
            en lugar de cookies HTTP convencionales para la gestión de sesión. A efectos de la normativa aplicable
            (Art. 22.2 LSSI-CE y Guía AEPD), el <code className="bg-primary-100 px-1 py-0.5 rounded font-mono">localStorage</code> tiene
            la misma consideración jurídica que las cookies, ya que implica el almacenamiento de información en el
            dispositivo terminal del usuario. Por tanto, esta política le es plenamente aplicable.
          </p>
        </div>
      </div>

      {/* Tabs */}
      <div className="card !p-0 overflow-hidden">
        <div className="flex overflow-x-auto border-b border-slate-100">
          {tabs.map((tab) => (
            <button key={tab.id} onClick={() => setTabActiva(tab.id)}
              className={`flex items-center space-x-2 px-5 py-3.5 text-sm font-medium whitespace-nowrap transition-colors border-b-2 -mb-px ${tabActiva === tab.id ? 'border-accent-600 text-accent-700 bg-accent-50/50' : 'border-transparent text-slate-500 hover:text-slate-700 hover:bg-slate-50'}`}>
              <span>{tab.icono}</span><span>{tab.label}</span>
            </button>
          ))}
        </div>
        <div className="p-6">

          {tabActiva === 'almacenamiento' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">
                A continuación se detallan todas las entradas de almacenamiento local que SmartAudits
                guarda en tu dispositivo. Son las únicas que existen — no hay más.
              </p>
              {almacenamientoItems.map((item, i) => (
                <div key={i} className="rounded-xl border border-green-200 overflow-hidden bg-green-50/30">
                  <div className="bg-green-50 px-4 py-3 flex items-center justify-between gap-3">
                    <div className="flex items-center space-x-3">
                      <code className="text-sm font-bold text-slate-800 bg-white border border-slate-200 px-2 py-0.5 rounded">{item.nombre}</code>
                      <span className="text-xs text-slate-500">en <code className="bg-slate-100 px-1 rounded">{item.almacen}</code></span>
                    </div>
                    <div className="flex items-center space-x-2 flex-shrink-0">
                      <span className="text-xs font-bold text-green-700 bg-green-100 border border-green-200 px-2 py-0.5 rounded-full">{item.tipo}</span>
                      {item.exento && <span className="text-xs font-bold text-emerald-700 bg-emerald-100 px-2 py-0.5 rounded-full">Exento</span>}
                    </div>
                  </div>
                  <div className="p-4 grid sm:grid-cols-2 gap-3">
                    {[
                      { label: 'Finalidad', valor: item.finalidad, color: 'primary' },
                      { label: 'Duración', valor: item.duracion, color: 'amber' },
                      { label: 'Proveedor', valor: item.proveedor, color: 'slate' },
                      { label: 'Base legal', valor: item.base, color: 'green' },
                    ].map((campo, j) => (
                      <div key={j} className={`p-2.5 rounded-lg ${campo.color === 'primary' ? 'bg-primary-50 border border-primary-100' : campo.color === 'amber' ? 'bg-amber-50 border border-amber-100' : campo.color === 'green' ? 'bg-green-50 border border-green-100' : 'bg-slate-50 border border-slate-100'}`}>
                        <p className={`text-xs font-semibold mb-1 ${campo.color === 'primary' ? 'text-primary-700' : campo.color === 'amber' ? 'text-amber-700' : campo.color === 'green' ? 'text-green-700' : 'text-slate-600'}`}>{campo.label}</p>
                        <p className="text-xs text-slate-700 leading-relaxed">{campo.valor}</p>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
              <div className="p-4 bg-slate-50 border border-slate-200 rounded-xl">
                <p className="text-xs font-semibold text-slate-700 mb-2">¿Cómo eliminar estos datos?</p>
                <p className="text-xs text-slate-600 leading-relaxed">
                  La forma más sencilla es <strong>cerrar sesión</strong> desde el botón del menú lateral —
                  esto elimina automáticamente ambas entradas del localStorage. También puedes hacerlo
                  manualmente desde las herramientas de desarrollo de tu navegador (ver pestaña "Cómo gestionarlas").
                </p>
              </div>
            </div>
          )}

          {tabActiva === 'categorias' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">
                La Guía AEPD (julio 2023) y la normativa vigente clasifican las cookies según su finalidad.
                Esta clasificación determina si requieren consentimiento previo o están exentas.
                SmartAudits solo usa la primera categoría.
              </p>
              {categoriasCookies.map((cat, i) => (
                <div key={i} className={`rounded-xl border overflow-hidden ${cat.color === 'green' ? 'border-green-200' : cat.color === 'amber' ? 'border-amber-200' : 'border-red-200'}`}>
                  <div className={`px-4 py-3 flex items-center justify-between gap-3 ${cat.color === 'green' ? 'bg-green-50' : cat.color === 'amber' ? 'bg-amber-50' : 'bg-red-50'}`}>
                    <div>
                      <p className={`text-sm font-bold ${cat.color === 'green' ? 'text-green-900' : cat.color === 'amber' ? 'text-amber-900' : 'text-red-900'}`}>{cat.categoria}</p>
                    </div>
                    <div className="flex items-center space-x-2 flex-shrink-0">
                      <span className={`text-xs font-bold px-2 py-0.5 rounded-full ${cat.color === 'green' ? 'bg-green-200 text-green-800' : cat.color === 'amber' ? 'bg-amber-200 text-amber-800' : 'bg-red-200 text-red-800'}`}>
                        {cat.consentimiento}
                      </span>
                      {cat.usaSmartAudits
                        ? <span className="text-xs font-bold bg-primary-100 text-primary-700 px-2 py-0.5 rounded-full">SmartAudits ✓</span>
                        : <span className="text-xs font-bold bg-slate-100 text-slate-500 px-2 py-0.5 rounded-full">No usada</span>
                      }
                    </div>
                  </div>
                  <div className="p-4">
                    <p className="text-xs text-slate-600 leading-relaxed mb-3">{cat.desc}</p>
                    <p className="text-xs font-semibold text-slate-700 mb-2">Ejemplos:</p>
                    <div className="grid sm:grid-cols-2 gap-1.5">
                      {cat.ejemplos.map((e, j) => (
                        <div key={j} className="flex items-start space-x-2 text-xs p-2 bg-slate-50 rounded-lg">
                          <span className={`flex-shrink-0 mt-0.5 ${cat.color === 'green' ? 'text-green-500' : cat.color === 'amber' ? 'text-amber-500' : 'text-red-500'}`}>•</span>
                          <span className="text-slate-600">{e}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}

          {tabActiva === 'gestion' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">
                Puedes eliminar los datos de sesión de SmartAudits en cualquier momento.
                La forma más sencilla es cerrar sesión desde el menú lateral. Si prefieres hacerlo
                manualmente desde el navegador, aquí tienes las instrucciones para cada uno.
              </p>

              <div className="p-4 bg-primary-50 border border-primary-100 rounded-xl flex items-start space-x-3 mb-4">
                <span className="text-xl flex-shrink-0">💡</span>
                <div>
                  <p className="text-sm font-bold text-primary-900 mb-1">Forma recomendada: Cerrar sesión</p>
                  <p className="text-xs text-primary-800 leading-relaxed">
                    El botón <strong>"Cerrar sesión"</strong> del menú lateral elimina automáticamente el token y los datos
                    de usuario del localStorage. Es la forma más rápida y segura. También puedes usar <strong>"Darse de baja"</strong> si
                    quieres desactivar tu cuenta permanentemente.
                  </p>
                </div>
              </div>

              <div className="grid sm:grid-cols-2 gap-4">
                {navegadores.map((nav, i) => (
                  <div key={i} className="p-4 bg-slate-50 border border-slate-200 rounded-xl">
                    <div className="flex items-center space-x-2 mb-3">
                      <span className="text-xl">{nav.icono}</span>
                      <p className="text-sm font-bold text-slate-800">{nav.nombre}</p>
                    </div>
                    <ol className="space-y-1.5">
                      {nav.pasos.map((paso, j) => (
                        <li key={j} className="flex items-start space-x-2 text-xs text-slate-600">
                          <span className="flex-shrink-0 w-4 h-4 bg-slate-200 text-slate-600 rounded-full flex items-center justify-center font-bold text-xs mt-0.5">{j + 1}</span>
                          <span>{paso}</span>
                        </li>
                      ))}
                    </ol>
                  </div>
                ))}
              </div>

              <div className="p-4 bg-amber-50 border border-amber-200 rounded-xl">
                <p className="text-xs font-semibold text-amber-800 mb-1">Atención al limpiar el localStorage manualmente</p>
                <p className="text-xs text-amber-700 leading-relaxed">
                  Si limpias todo el localStorage del navegador para el origen de SmartAudits, se eliminará tanto
                  el token de sesión como los datos de usuario. La próxima vez que accedas a la plataforma
                  deberás iniciar sesión de nuevo. Esto no afecta a tus datos almacenados en el servidor
                  (auditorías, historial, perfil), que permanecen intactos.
                </p>
              </div>
            </div>
          )}

        </div>
      </div>

      {/* Base legal y normativa */}
      <div className="card">
        <h2 className="text-base font-bold text-slate-900 mb-4 pb-3 border-b border-slate-100">Base legal y normativa aplicable</h2>
        <p className="text-slate-600 text-sm leading-relaxed mb-4">
          Las tecnologías de almacenamiento utilizadas por SmartAudits están <strong>exentas de consentimiento</strong> conforme
          al Art. 22.2 LSSI-CE y la Guía de Cookies de la AEPD (julio 2023), al ser estrictamente necesarias para
          la prestación del servicio solicitado. No se instala ningún tipo de almacenamiento que requiera
          consentimiento previo.
        </p>
        <div className="grid sm:grid-cols-2 gap-2">
          {[
            { ley: 'LSSI-CE', desc: 'Art. 22.2 — Base legal para cookies y almacenamiento local', url: 'https://www.boe.es/buscar/act.php?id=BOE-A-2002-13758' },
            { ley: 'ePrivacy', desc: 'Directiva 2002/58/CE — Art. 5.3: almacenamiento en terminales', url: 'https://eur-lex.europa.eu/legal-content/ES/TXT/?uri=CELEX%3A32002L0058' },
            { ley: 'RGPD', desc: 'Reglamento (UE) 2016/679 — Protección de datos en cookies', url: 'https://eur-lex.europa.eu/legal-content/ES/TXT/?uri=CELEX%3A32016R0679' },
            { ley: 'Guía AEPD', desc: 'Guía sobre el uso de cookies — Actualizada julio 2023', url: 'https://www.aepd.es/guias/guia-cookies.pdf' },
          ].map((item) => (
            <ExternalNormativeLink key={item.ley} ley={item.ley} desc={item.desc} url={item.url} />
          ))}
        </div>
      </div>

    </div>
  )
}

export default PoliticaCookies
