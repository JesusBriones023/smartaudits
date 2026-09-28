import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import NormativaNav from '../components/NormativaNav'

const NormativaAEPDCookies = () => {
  const navigate = useNavigate()
  const [tabActiva, setTabActiva] = useState('guia')

  const requisitosGuia = [
    {
      titulo: 'Botones de aceptar y rechazar al mismo nivel',
      novedad: true,
      critico: true,
      desc: 'Las opciones de aceptar y rechazar cookies deben ser igualmente prominentes, accesibles y visibles. El botón de rechazo no puede ser más pequeño, estar en color apagado, estar escondido en un submenú ni requerir más clics que el de aceptación.',
      ejemplos_ok: ['Dos botones del mismo tamaño y color prominente en la primera capa', '"Aceptar" y "Rechazar" en la misma fila con el mismo contraste', 'Panel de configuración accesible directamente desde el banner'],
      ejemplos_mal: ['Botón "Aceptar" en verde llamativo y "Rechazar" en gris claro', '"Rechazar" oculto dentro de "Más opciones" o "Configurar"', '"Aceptar todo" en la primera capa y "Rechazar" solo accesible tras varios clics'],
      reglas: ['R10'],
    },
    {
      titulo: 'Prohibición de dark patterns (patrones engañosos)',
      novedad: true,
      critico: true,
      desc: 'Está prohibido usar técnicas de diseño que manipulen al usuario para aceptar cookies. La AEPD se basa en las Directrices 03/2022 del CEPD sobre patrones engañosos, que identifica varias categorías de dark patterns ilegales.',
      ejemplos_ok: ['Lenguaje claro y neutro en las opciones', 'Diseño equilibrado que no favorezca ninguna opción', 'Información completa y comprensible en la primera capa'],
      ejemplos_mal: ['Continuar navegando implica aceptar cookies', 'Casillas premarcadas en "Aceptar todas"', 'Botón de rechazo con texto confuso como "No quiero mejorar mi experiencia"', 'Interfaz de configuración compleja que desanima al rechazo'],
      reglas: ['R10', 'R12'],
    },
    {
      titulo: 'Panel de preferencias granular',
      novedad: false,
      critico: true,
      desc: 'Debe existir un panel de configuración donde el usuario pueda aceptar o rechazar cada categoría de cookies por separado: técnicas (no modificables), analíticas, publicitarias, funcionales, etc. No se puede ofrecer solo "Aceptar todo" o "Rechazar todo" sin opción intermedia.',
      ejemplos_ok: ['Panel con toggles por categoría (analíticas ON/OFF, publicitarias ON/OFF)', 'Lista de todas las cookies con finalidad, duración y proveedor', 'Posibilidad de guardar preferencias personalizadas'],
      ejemplos_mal: ['Solo dos opciones: "Aceptar todo" o "Rechazar todo" sin configuración', 'Categorías agrupadas de forma confusa para dificultar el rechazo selectivo'],
      reglas: ['R10'],
    },
    {
      titulo: 'Cookies de personalización — clasificación actualizada',
      novedad: true,
      critico: false,
      desc: 'La guía actualizada distingue entre cookies de personalización técnicas y no técnicas. Si el propio usuario decide sus preferencias (idioma, moneda), son técnicas y no requieren consentimiento. Si es el editor quien personaliza basándose en datos del usuario, requieren consentimiento.',
      ejemplos_ok: ['Idioma elegido por el usuario = cookie técnica exenta', 'Región/moneda elegida por el usuario = cookie técnica exenta'],
      ejemplos_mal: ['Editor decide el idioma según historial de navegación = requiere consentimiento', 'Recomendaciones personalizadas basadas en comportamiento = requiere consentimiento'],
      reglas: ['R10'],
    },
    {
      titulo: 'Muro de cookies (cookie wall) — reglas actualizadas',
      novedad: true,
      critico: false,
      desc: 'La guía AEPD clarifica las condiciones en que puede haber un "muro de cookies". Se prohíbe condicionar el acceso al servicio únicamente a la aceptación de cookies, SALVO que exista una alternativa real y gratuita sin cookies, o una alternativa de pago razonable (modelo "pagar o consentir").',
      ejemplos_ok: ['Muro que ofrece: "Acepta cookies" O "Accede gratis sin publicidad comportamental" O "Suscríbete por X€/mes"', 'El acceso gratuito alternativo no puede ser significativamente peor que la versión con cookies'],
      ejemplos_mal: ['Muro que solo ofrece "Acepta cookies" para acceder sin alternativa', 'Precio de suscripción desproporcionado que en la práctica obliga a aceptar cookies'],
      reglas: ['R10', 'R12'],
    },
    {
      titulo: 'Renovación periódica del consentimiento',
      novedad: false,
      critico: false,
      desc: 'El consentimiento para cookies no es indefinido. La AEPD recomienda renovarlo cada 12-24 meses como máximo, mostrando de nuevo el banner de cookies a usuarios que ya lo aceptaron.',
      ejemplos_ok: ['Mostrar banner de nuevo a los 12-24 meses', 'Permitir al usuario retirar el consentimiento en cualquier momento desde el panel de configuración', 'Mantener un registro de cuándo y cómo se prestó el consentimiento'],
      ejemplos_mal: ['Consentimiento prestado una vez válido para siempre', 'No ofrecer mecanismo para retirar el consentimiento tras haberlo prestado'],
      reglas: ['R10', 'R12'],
    },
    {
      titulo: 'Información completa y accesible',
      novedad: false,
      critico: true,
      desc: 'El banner de primera capa debe contener información básica clara: qué tipos de cookies se usan, para qué y quién las instala (si hay terceros). La política de cookies completa debe ser accesible con un solo clic desde el banner.',
      ejemplos_ok: ['Primera capa: tipos de cookies, finalidad, opción de configurar', 'Política completa: listado de todas las cookies con nombre, proveedor, finalidad y duración', 'Información actualizada cuando cambian las cookies utilizadas'],
      ejemplos_mal: ['Banner genérico "Usamos cookies para mejorar tu experiencia" sin más detalle', 'Política de cookies que no lista las cookies específicas utilizadas', 'Información desactualizada que no refleja las cookies realmente instaladas'],
      reglas: ['R10'],
    },
  ]

  const cmpRequisitos = [
    { requisito: 'Registrar el consentimiento (qué, cuándo, cómo, para qué)', critico: true },
    { requisito: 'No instalar cookies antes de que el usuario tome una decisión', critico: true },
    { requisito: 'Bloquear scripts de terceros hasta que se otorgue el consentimiento correspondiente', critico: true },
    { requisito: 'Permitir retirar el consentimiento con la misma facilidad que se prestó', critico: true },
    { requisito: 'Ser compatible con Google Consent Mode v2 si se usa Google Analytics o Ads', critico: false },
    { requisito: 'Detectar automáticamente las cookies instaladas y actualizarlas', critico: false },
    { requisito: 'Ofrecer versión accesible del banner (contraste, tamaño de letra, compatibilidad con lectores de pantalla)', critico: false },
    { requisito: 'Funcionar correctamente en móvil (el banner no puede bloquear la navegación)', critico: false },
  ]

  const cronologia = [
    { fecha: 'Noviembre 2019', evento: 'Primera Guía AEPD de Cookies publicada', tipo: 'guia' },
    { fecha: 'Julio 2023', evento: 'Guía actualizada — incorpora directrices del CEPD sobre dark patterns (03/2022). Botones aceptar/rechazar al mismo nivel, clasificación actualizada de cookies de personalización y muros de cookies.', tipo: 'actualizacion' },
    { fecha: '11 enero 2024', evento: 'Plazo máximo para implementar los cambios de la Guía 2023. Desde esta fecha es exigible el cumplimiento de los nuevos requisitos.', tipo: 'plazo' },
    { fecha: '2024-2026', evento: 'La AEPD intensifica las inspecciones sectoriales sobre el uso de cookies. Varias empresas españolas reciben requerimientos por incumplimientos en banners de cookies.', tipo: 'aplicacion' },
    { fecha: 'Mayo 2026', evento: 'Estado actual: Guía de julio 2023 plenamente exigible. Inspecciones activas. El nuevo Reglamento ePrivacy, que podría cambiar parte del marco, aún no ha sido aprobado.', tipo: 'actual' },
  ]

  const tabs = [
    { id: 'guia', label: 'Requisitos de la Guía', icono: '📋' },
    { id: 'cmp', label: 'Gestores de consentimiento', icono: '⚙️' },
    { id: 'cronologia', label: 'Cronología', icono: '📅' },
  ]

  return (
    <div className="space-y-6 max-w-5xl">

      {/* Cabecera */}
      <div className="bg-gradient-to-br from-rose-600 via-rose-700 to-slate-900 rounded-2xl px-8 py-10 relative overflow-hidden">
        <div className="absolute right-0 top-0 w-72 h-72 bg-white/5 rounded-full blur-3xl pointer-events-none"></div>
        <div className="absolute left-0 bottom-0 w-48 h-48 bg-rose-400/10 rounded-full blur-2xl pointer-events-none"></div>
        <div className="relative">
          <button onClick={() => navigate(-1)} className="flex items-center space-x-1 text-white/60 hover:text-white text-xs mb-5 transition-colors">
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
            <span>Volver</span>
          </button>
          <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-6">
            <div className="flex-1">
              <div className="inline-flex items-center space-x-2 px-3 py-1.5 bg-white/15 rounded-full mb-4">
                <span className="w-2 h-2 bg-rose-300 rounded-full"></span>
                <span className="text-white text-xs font-bold tracking-wider">AEPD · Guía de Cookies · Actualizada julio 2023 · Exigible desde enero 2024</span>
              </div>
              <h1 className="text-3xl font-bold text-white mb-2 leading-tight">Guía sobre el uso<br/>de las Cookies — AEPD</h1>
              <p className="text-white/70 text-sm leading-relaxed max-w-xl">
                Documento interpretativo de la Agencia Española de Protección de Datos (AEPD).
                Referencia principal para el cumplimiento del Art. 22.2 LSSI-CE en España.
                Última actualización: julio 2023. Plenamente exigible desde el 11 de enero de 2024.
              </p>
            </div>
            <div className="grid grid-cols-2 sm:grid-cols-1 gap-2 sm:gap-3 sm:min-w-[140px]">
              {[
                { num: '7', label: 'Requisitos clave' },
                { num: 'Ene. 2024', label: 'Exigible desde' },
                { num: 'R10', label: 'Regla SmartAudits' },
              ].map((s) => (
                <div key={s.label} className="text-center px-3 py-2 bg-white/10 rounded-xl">
                  <p className="text-base font-bold text-white">{s.num}</p>
                  <p className="text-white/60 text-xs">{s.label}</p>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* Qué es */}
      <div className="card">
        <h2 className="text-base font-bold text-slate-900 mb-4 pb-3 border-b border-slate-100">¿Qué es la Guía de Cookies de la AEPD?</h2>
        <div className="grid sm:grid-cols-2 gap-4">
          <div>
            <p className="text-slate-600 text-sm leading-relaxed mb-3">
              La Guía de Cookies de la AEPD es el documento interpretativo de referencia para implementar
              correctamente el Art. 22.2 de la LSSI-CE en España. No es una ley en sí misma, sino una
              guía de cumplimiento que la AEPD usa como referencia en sus investigaciones y sanciones.
            </p>
            <p className="text-slate-600 text-sm leading-relaxed">
              Fue actualizada en <strong>julio de 2023</strong> para incorporar las Directrices 03/2022 del
              Comité Europeo de Protección de Datos (CEPD) sobre patrones engañosos en redes sociales.
              Desde el <strong>11 de enero de 2024</strong> sus requisitos son plenamente exigibles a todas
              las webs españolas que utilicen cookies.
            </p>
          </div>
          <div className="space-y-2">
            <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-2">Base legal de la Guía</p>
            {[
              { norm: 'Art. 22.2 LSSI-CE', desc: 'Base legal española para la instalación de cookies' },
              { norm: 'Art. 4.11 RGPD', desc: 'Definición de consentimiento válido' },
              { norm: 'Art. 7 RGPD', desc: 'Condiciones para el consentimiento' },
              { norm: 'Directrices CEPD 03/2022', desc: 'Dark patterns en plataformas de redes sociales' },
              { norm: 'Art. 5.3 Directiva ePrivacy', desc: 'Obligación de consentimiento para cookies' },
            ].map((item, i) => (
              <div key={i} className="flex items-start space-x-3 p-2.5 bg-slate-50 rounded-xl">
                <span className="text-xs font-bold text-rose-700 bg-rose-100 px-2 py-0.5 rounded font-mono flex-shrink-0 whitespace-nowrap">{item.norm}</span>
                <p className="text-xs text-slate-600">{item.desc}</p>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="card !p-0 overflow-hidden">
        <div className="flex overflow-x-auto border-b border-slate-100">
          {tabs.map((tab) => (
            <button key={tab.id} onClick={() => setTabActiva(tab.id)}
              className={`flex items-center space-x-2 px-4 py-3.5 text-sm font-medium whitespace-nowrap transition-colors border-b-2 -mb-px ${tabActiva === tab.id ? 'border-rose-600 text-rose-700 bg-rose-50/50' : 'border-transparent text-slate-500 hover:text-slate-700 hover:bg-slate-50'}`}>
              <span>{tab.icono}</span><span>{tab.label}</span>
            </button>
          ))}
        </div>
        <div className="p-6">

          {tabActiva === 'guia' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">
                Estos son los 7 requisitos principales de la Guía AEPD, todos plenamente exigibles desde el 11 de enero de 2024.
                Las etiquetas <span className="bg-rose-100 text-rose-700 px-1.5 py-0.5 rounded text-xs font-bold">Novedad 2023</span> indican
                los cambios introducidos en la última actualización.
              </p>
              {requisitosGuia.map((req, i) => (
                <div key={i} className={`rounded-xl border overflow-hidden ${req.critico ? 'border-slate-200' : 'border-slate-100'}`}>
                  <div className={`px-4 py-3 flex items-start justify-between gap-3 ${req.critico ? 'bg-slate-50' : 'bg-slate-50/50'}`}>
                    <div className="flex items-start space-x-2 flex-1">
                      {req.critico && <span className="text-xs font-bold text-red-600 bg-red-50 border border-red-100 px-2 py-0.5 rounded-full flex-shrink-0 mt-0.5">Crítico</span>}
                      {req.novedad && <span className="text-xs font-bold text-rose-700 bg-rose-100 px-2 py-0.5 rounded-full flex-shrink-0 mt-0.5">Novedad 2023</span>}
                      <p className="text-sm font-bold text-slate-900">{req.titulo}</p>
                    </div>
                    <div className="flex gap-1 flex-shrink-0">
                      {req.reglas.map((r) => <span key={r} className="text-xs bg-slate-200 text-slate-600 px-1.5 py-0.5 rounded font-mono">{r}</span>)}
                    </div>
                  </div>
                  <div className="p-4 space-y-3">
                    <p className="text-xs text-slate-600 leading-relaxed">{req.desc}</p>
                    <div className="grid sm:grid-cols-2 gap-3">
                      <div>
                        <p className="text-xs font-semibold text-green-700 mb-1.5">✅ Ejemplos correctos</p>
                        <div className="space-y-1">
                          {req.ejemplos_ok.map((e, j) => (
                            <div key={j} className="flex items-start space-x-2 p-2 bg-green-50 rounded-lg">
                              <span className="text-green-500 flex-shrink-0 text-xs mt-0.5">•</span>
                              <span className="text-xs text-green-800">{e}</span>
                            </div>
                          ))}
                        </div>
                      </div>
                      <div>
                        <p className="text-xs font-semibold text-red-700 mb-1.5">❌ Ejemplos incorrectos</p>
                        <div className="space-y-1">
                          {req.ejemplos_mal.map((e, j) => (
                            <div key={j} className="flex items-start space-x-2 p-2 bg-red-50 rounded-lg">
                              <span className="text-red-500 flex-shrink-0 text-xs mt-0.5">•</span>
                              <span className="text-xs text-red-800">{e}</span>
                            </div>
                          ))}
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}

          {tabActiva === 'cmp' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">
                Un gestor de consentimiento (CMP — Consent Management Platform) es la herramienta técnica
                que implementa el banner de cookies y registra las preferencias del usuario.
                Estos son los requisitos que debe cumplir cualquier CMP para ser válido en España.
              </p>
              <div className="space-y-2">
                {cmpRequisitos.map((req, i) => (
                  <div key={i} className={`flex items-start space-x-3 p-3 rounded-xl border ${req.critico ? 'bg-red-50 border-red-100' : 'bg-slate-50 border-slate-100'}`}>
                    <div className={`w-5 h-5 rounded-full flex items-center justify-center flex-shrink-0 mt-0.5 ${req.critico ? 'bg-red-500' : 'bg-slate-400'}`}>
                      <svg className="w-3 h-3 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                      </svg>
                    </div>
                    <div className="flex-1">
                      <p className={`text-xs ${req.critico ? 'text-red-800 font-medium' : 'text-slate-600'}`}>{req.requisito}</p>
                      {req.critico && <span className="text-xs text-red-600 font-bold">Obligatorio</span>}
                    </div>
                  </div>
                ))}
              </div>
              <div className="p-4 bg-primary-50 border border-primary-100 rounded-xl mt-4">
                <p className="text-xs font-semibold text-primary-800 mb-2">CMPs populares en España (mayo 2026)</p>
                <p className="text-xs text-primary-700 leading-relaxed mb-2">
                  Los gestores de consentimiento más usados en el mercado español son: Cookiebot, OneTrust, Didomi,
                  Axeptio, Borlabs Cookie y TrustArc. Todos deben cumplir los requisitos de la Guía AEPD y,
                  si se usa Google Analytics o Google Ads, ser compatibles con <strong>Google Consent Mode v2</strong>
                  (obligatorio desde marzo 2024 para las herramientas de Google).
                </p>
                <p className="text-xs text-primary-600 italic">
                  SmartAudits usa localStorage para la sesión, que está exento de consentimiento al ser estrictamente necesario.
                </p>
              </div>
            </div>
          )}

          {tabActiva === 'cronologia' && (
            <div className="space-y-3">
              <p className="text-sm text-slate-500 mb-4">
                Evolución de la regulación de cookies en España desde la primera Guía AEPD hasta mayo de 2026.
              </p>
              <div className="relative">
                <div className="absolute left-4 top-0 bottom-0 w-0.5 bg-slate-200"></div>
                <div className="space-y-4">
                  {cronologia.map((item, i) => (
                    <div key={i} className="relative pl-10">
                      <div className={`absolute left-3 top-1.5 w-3 h-3 rounded-full border-2 border-white ${
                        item.tipo === 'actual' ? 'bg-rose-500' :
                        item.tipo === 'plazo' ? 'bg-red-500' :
                        item.tipo === 'actualizacion' ? 'bg-amber-500' :
                        item.tipo === 'aplicacion' ? 'bg-orange-500' :
                        'bg-slate-400'
                      }`}></div>
                      <div className={`p-3 rounded-xl border ${
                        item.tipo === 'actual' ? 'bg-rose-50 border-rose-200' :
                        item.tipo === 'plazo' ? 'bg-red-50 border-red-200' :
                        item.tipo === 'actualizacion' ? 'bg-amber-50 border-amber-200' :
                        'bg-slate-50 border-slate-200'
                      }`}>
                        <p className={`text-xs font-bold mb-1 ${
                          item.tipo === 'actual' ? 'text-rose-700' :
                          item.tipo === 'plazo' ? 'text-red-700' :
                          item.tipo === 'actualizacion' ? 'text-amber-700' :
                          'text-slate-600'
                        }`}>{item.fecha}</p>
                        <p className="text-xs text-slate-700 leading-relaxed">{item.evento}</p>
                        {item.tipo === 'actual' && (
                          <span className="inline-block mt-1.5 text-xs font-bold text-rose-700 bg-rose-100 px-2 py-0.5 rounded-full">Estado actual — mayo 2026</span>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}

        </div>
      </div>

      {/* Botones */}
      <div className="flex flex-col sm:flex-row items-center justify-center gap-3">
        <a href="https://www.aepd.es/guias/guia-cookies.pdf" target="_blank" rel="noopener noreferrer"
          className="inline-flex items-center space-x-2 px-5 py-3 bg-rose-600 hover:bg-rose-700 text-white rounded-xl font-semibold text-sm transition-colors shadow-soft hover:shadow-medium">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" /></svg>
          <span>Guía oficial en AEPD (PDF)</span>
        </a>
        <a href="https://www.aepd.es/guias" target="_blank" rel="noopener noreferrer"
          className="inline-flex items-center space-x-2 px-5 py-3 bg-white border border-slate-200 hover:border-slate-300 text-slate-700 rounded-xl font-semibold text-sm transition-colors shadow-soft">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" /></svg>
          <span>Todas las guías AEPD</span>
        </a>
      </div>
      <NormativaNav />
    </div>
  )
}

export default NormativaAEPDCookies
