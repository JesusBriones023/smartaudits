import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import NormativaNav from '../components/NormativaNav'

const NormativaEPrivacy = () => {
  const navigate = useNavigate()
  const [tabActiva, setTabActiva] = useState('que-regula')

  const ambitosRegulados = [
    {
      titulo: 'Cookies y tecnologías de seguimiento',
      icono: '🍪',
      art: 'Art. 5.3',
      desc: 'Cualquier almacenamiento de información o acceso a información ya almacenada en el terminal del usuario requiere consentimiento previo e información clara, salvo que sea estrictamente necesario para prestar el servicio solicitado.',
      incluye: ['Cookies HTTP convencionales', 'localStorage y sessionStorage', 'IndexedDB', 'Web beacons y píxeles de seguimiento', 'Fingerprinting del dispositivo', 'Supercookies y ETags', 'Flash cookies (Local Shared Objects)', 'Identificadores de publicidad (IDFA, GAID)'],
      exento: ['Cookies de sesión para transmisión de comunicación', 'Cookies técnicas estrictamente necesarias para prestar el servicio (carrito de la compra, autenticación, preferencias de idioma)', 'Cookies de seguridad'],
      reglas: ['R10'],
    },
    {
      titulo: 'Confidencialidad de las comunicaciones',
      icono: '🔐',
      art: 'Arts. 5-6',
      desc: 'Los Estados miembros deben garantizar la confidencialidad de las comunicaciones realizadas a través de redes de comunicaciones públicas, incluidos los datos de tráfico. Se prohíbe escuchar, grabar, almacenar u otro tipo de interceptación o vigilancia de las comunicaciones sin consentimiento.',
      incluye: ['Mensajes de correo electrónico', 'Mensajería instantánea', 'Llamadas de voz sobre IP (VoIP)', 'SMS y MMS', 'Datos de tráfico de las comunicaciones'],
      exento: ['Investigaciones judiciales autorizadas', 'Seguridad nacional (con garantías proporcionales)', 'Protección técnica de la red'],
      reglas: [],
    },
    {
      titulo: 'Datos de tráfico y localización',
      icono: '📍',
      art: 'Arts. 6-9',
      desc: 'Los datos de tráfico relativos a los abonados y usuarios deben suprimirse o hacerse anónimos cuando ya no sean necesarios para la transmisión de la comunicación. Los datos de localización distintos de los de tráfico solo pueden tratarse con consentimiento.',
      incluye: ['Dirección IP en logs de acceso', 'Datos de localización GPS', 'Metadatos de llamadas y mensajes', 'Historial de navegación del operador'],
      exento: ['Facturación (durante el período legal)', 'Detección de fraudes', 'Interoperabilidad de servicios (anonimizados)'],
      reglas: [],
    },
    {
      titulo: 'Comunicaciones comerciales no solicitadas (spam)',
      icono: '📧',
      art: 'Art. 13',
      desc: 'Las llamadas automáticas sin intervención humana y el correo electrónico con fines de venta directa se permiten únicamente respecto de abonados que hayan dado su consentimiento previo. Los SMS, fax y otros canales equivalentes siguen la misma regla.',
      incluye: ['Email marketing sin consentimiento (spam)', 'SMS publicitarios sin opt-in', 'Llamadas automáticas de marketing', 'Fax publicitario no solicitado', 'Push notifications sin permiso'],
      exento: ['Clientes existentes para productos/servicios similares (con opt-out)', 'Comunicaciones de organismos sin ánimo de lucro a sus miembros'],
      reglas: ['R19'],
    },
    {
      titulo: 'Directorios de abonados',
      icono: '📒',
      art: 'Art. 12',
      desc: 'Los abonados deben ser informados sobre la finalidad de los directorios públicos en los que se incluirán sus datos y sobre cualquier posibilidad de búsqueda inversa. Deben poder solicitar su exclusión gratuitamente.',
      incluye: ['Directorios telefónicos públicos', 'Listines en papel o electrónicos', 'Búsqueda inversa por número de teléfono'],
      exento: [],
      reglas: [],
    },
  ]

  const cookiesTipos = [
    { categoria: 'Técnicas / estrictamente necesarias', consentimiento: '❌ No requieren', ejemplos: ['Sesión autenticada (token de login)', 'Carrito de la compra', 'Preferencias de idioma o región', 'Equilibrio de carga del servidor', 'Protección CSRF', 'Cookies de seguridad'], color: 'green' },
    { categoria: 'Funcionales o de preferencias', consentimiento: '✅ Requieren', ejemplos: ['Recordar usuario/contraseña', 'Personalización de la interfaz', 'Configuración de accesibilidad', 'Vídeos embebidos de terceros (YouTube, Vimeo)'], color: 'amber' },
    { categoria: 'Analíticas / estadísticas', consentimiento: '✅ Requieren', ejemplos: ['Google Analytics', 'Adobe Analytics', 'Matomo/Piwik', 'Hotjar', 'Microsoft Clarity', 'Métricas de rendimiento con identificación de usuario'], color: 'amber' },
    { categoria: 'Publicitarias / marketing', consentimiento: '✅ Requieren', ejemplos: ['Google Ads / DoubleClick', 'Facebook Pixel / Meta Pixel', 'Remarketing y retargeting', 'Perfilado publicitario', 'Publicidad comportamental', 'Seguimiento cross-site'], color: 'red' },
  ]

  const nuevoReglamento = [
    { titulo: '¿Por qué se necesita un nuevo Reglamento?', desc: 'La Directiva ePrivacy de 2002 fue creada antes del auge de las apps, las redes sociales y los servicios OTT (Over-The-Top) como WhatsApp o Gmail. Estos servicios no estaban sujetos a las mismas obligaciones que los operadores de telecomunicaciones tradicionales, creando un vacío regulatorio.' },
    { titulo: '¿Qué cambiaría con el nuevo Reglamento ePrivacy?', desc: 'Ampliaría el ámbito a todos los servicios de comunicaciones electrónicas (incluidos OTT). Establecería reglas más claras sobre cookies. Armonizaría las sanciones con el RGPD (hasta 20M€ o 4%). Introduciría el concepto de "consentimiento técnico" gestionado desde el navegador.' },
    { titulo: '¿En qué punto está la negociación a mayo de 2026?', desc: 'El Consejo de la UE acordó su posición negociadora en febrero de 2021 y desde entonces mantiene negociaciones con el Parlamento Europeo. A mayo de 2026 el texto definitivo aún no ha sido aprobado — es uno de los procesos legislativos europeos más prolongados de la historia reciente, con más de 9 años desde la propuesta inicial de 2017.' },
    { titulo: '¿Cuándo entrará en vigor?', desc: 'Una vez aprobado, el Reglamento entrará en vigor 20 días después de su publicación en el Diario Oficial de la UE y será aplicable 2 años después. Según las estimaciones más recientes, la aplicación efectiva podría situarse entre 2026 y 2028, dependiendo de cuándo se cierre el acuerdo final entre el Parlamento y el Consejo.' },
  ]
  const tabs = [
    { id: 'que-regula', label: 'Qué regula', icono: '📋' },
    { id: 'cookies', label: 'Tipos de cookies', icono: '🍪' },
    { id: 'nuevo', label: 'Nuevo Reglamento', icono: '🔄' },
  ]

  return (
    <div className="space-y-6 max-w-5xl">

      {/* Cabecera */}
      <div className="bg-gradient-to-br from-indigo-600 via-indigo-700 to-slate-900 rounded-2xl px-8 py-10 relative overflow-hidden">
        <div className="absolute right-0 top-0 w-72 h-72 bg-white/5 rounded-full blur-3xl pointer-events-none"></div>
        <div className="absolute left-0 bottom-0 w-48 h-48 bg-indigo-400/10 rounded-full blur-2xl pointer-events-none"></div>
        <div className="relative">
          <button onClick={() => navigate(-1)} className="flex items-center space-x-1 text-white/60 hover:text-white text-xs mb-5 transition-colors">
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
            <span>Volver</span>
          </button>
          <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-6">
            <div className="flex-1">
              <div className="inline-flex items-center space-x-2 px-3 py-1.5 bg-white/15 rounded-full mb-4">
                <span className="w-2 h-2 bg-indigo-300 rounded-full animate-pulse"></span>
                <span className="text-white text-xs font-bold tracking-wider">ePrivacy · Directiva 2002/58/CE · Nuevo Reglamento en tramitación</span>
              </div>
              <h1 className="text-3xl font-bold text-white mb-2 leading-tight">Directiva ePrivacy</h1>
              <p className="text-white/70 text-sm leading-relaxed max-w-xl">Directiva 2002/58/CE del Parlamento Europeo y del Consejo, de 12 de julio de 2002, relativa al tratamiento de los datos personales y a la protección de la intimidad en el sector de las comunicaciones electrónicas.</p>
              <p className="text-white/50 text-xs mt-2">Modificada por la Directiva 2009/136/CE · Transpuesta en España mediante LSSI-CE Art. 22</p>
            </div>
            <div className="grid grid-cols-2 sm:grid-cols-1 gap-2 sm:gap-3 sm:min-w-[140px]">
              {[{ num: '2002', label: 'Año de origen' }, { num: '5', label: 'Ámbitos regulados' }, { num: '2026+', label: 'Nuevo Regl. pendiente' }].map((s) => (
                <div key={s.label} className="text-center px-3 py-2 bg-white/10 rounded-xl">
                  <p className="text-xl font-bold text-white">{s.num}</p>
                  <p className="text-white/60 text-xs">{s.label}</p>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* Qué es */}
      <div className="card">
        <h2 className="text-base font-bold text-slate-900 mb-4 pb-3 border-b border-slate-100">¿Qué es la Directiva ePrivacy y cuál es su relación con el RGPD?</h2>
        <div className="grid sm:grid-cols-2 gap-4">
          <div>
            <p className="text-slate-600 text-sm leading-relaxed mb-3">La Directiva ePrivacy (también conocida como "Directiva sobre la privacidad y las comunicaciones electrónicas") regula la privacidad en el ámbito específico de las comunicaciones electrónicas. Es la norma de referencia para todo lo relacionado con <strong>cookies, spam y confidencialidad de las comunicaciones</strong>.</p>
            <p className="text-slate-600 text-sm leading-relaxed">Se aplica de forma complementaria al RGPD: el RGPD establece el marco general de protección de datos, mientras que la ePrivacy establece reglas específicas para las comunicaciones electrónicas. Cuando ambas son aplicables, la ePrivacy actúa como <strong>lex specialis</strong> (norma especial que prevalece sobre la general).</p>
          </div>
          <div className="space-y-2">
            <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-2">ePrivacy vs RGPD — diferencias clave</p>
            {[
              { aspecto: 'Ámbito', eprivacy: 'Comunicaciones electrónicas y cookies', rgpd: 'Cualquier dato personal' },
              { aspecto: 'Tipo de norma', eprivacy: 'Directiva (requiere transposición nacional)', rgpd: 'Reglamento (aplicación directa en toda la UE)' },
              { aspecto: 'Relación', eprivacy: 'Norma especial (lex specialis)', rgpd: 'Norma general' },
              { aspecto: 'Consentimiento cookies', eprivacy: 'Art. 5.3: base legal para instalación', rgpd: 'Tipo y calidad del consentimiento' },
            ].map((item, i) => (
              <div key={i} className="rounded-xl border border-slate-100 overflow-hidden">
                <div className="bg-slate-50 px-3 py-1.5">
                  <p className="text-xs font-semibold text-slate-700">{item.aspecto}</p>
                </div>
                <div className="grid grid-cols-2 divide-x divide-slate-100">
                  <div className="px-3 py-2">
                    <p className="text-xs text-indigo-600 font-medium mb-0.5">ePrivacy</p>
                    <p className="text-xs text-slate-600">{item.eprivacy}</p>
                  </div>
                  <div className="px-3 py-2">
                    <p className="text-xs text-primary-600 font-medium mb-0.5">RGPD</p>
                    <p className="text-xs text-slate-600">{item.rgpd}</p>
                  </div>
                </div>
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
              className={`flex items-center space-x-2 px-4 py-3.5 text-sm font-medium whitespace-nowrap transition-colors border-b-2 -mb-px ${tabActiva === tab.id ? 'border-indigo-600 text-indigo-700 bg-indigo-50/50' : 'border-transparent text-slate-500 hover:text-slate-700 hover:bg-slate-50'}`}>
              <span>{tab.icono}</span><span>{tab.label}</span>
            </button>
          ))}
        </div>
        <div className="p-6">

          {tabActiva === 'que-regula' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">La Directiva ePrivacy regula 5 ámbitos principales en el sector de las comunicaciones electrónicas. Todos son relevantes para la auditoría de textos legales.</p>
              {ambitosRegulados.map((a, i) => (
                <div key={i} className="rounded-xl border border-slate-200 overflow-hidden">
                  <div className="bg-slate-50 px-4 py-3 flex items-center justify-between gap-3">
                    <div className="flex items-center space-x-3">
                      <span className="text-xl">{a.icono}</span>
                      <div>
                        <div className="flex items-center space-x-2">
                          <p className="text-sm font-bold text-slate-900">{a.titulo}</p>
                          <span className="text-xs text-indigo-600 font-mono bg-indigo-50 px-2 py-0.5 rounded">{a.art}</span>
                        </div>
                      </div>
                    </div>
                    {a.reglas.length > 0 && (
                      <div className="flex gap-1">{a.reglas.map((r) => <span key={r} className="text-xs bg-slate-200 text-slate-600 px-1.5 py-0.5 rounded font-mono">{r}</span>)}</div>
                    )}
                  </div>
                  <div className="p-4 space-y-3">
                    <p className="text-xs text-slate-600 leading-relaxed">{a.desc}</p>
                    <div className="grid sm:grid-cols-2 gap-3">
                      <div>
                        <p className="text-xs font-semibold text-slate-700 mb-1.5">Incluye:</p>
                        <div className="space-y-1">
                          {a.incluye.map((item, j) => (
                            <div key={j} className="flex items-start space-x-2 text-xs">
                              <span className="text-red-400 flex-shrink-0 mt-0.5">•</span>
                              <span className="text-slate-600">{item}</span>
                            </div>
                          ))}
                        </div>
                      </div>
                      {a.exento.length > 0 && (
                        <div>
                          <p className="text-xs font-semibold text-slate-700 mb-1.5">Exento / excepciones:</p>
                          <div className="space-y-1">
                            {a.exento.map((item, j) => (
                              <div key={j} className="flex items-start space-x-2 text-xs">
                                <span className="text-green-500 flex-shrink-0 mt-0.5">✓</span>
                                <span className="text-slate-600">{item}</span>
                              </div>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}

          {tabActiva === 'cookies' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">La ePrivacy (Art. 5.3) y la LSSI-CE (Art. 22.2) clasifican las cookies en función de si requieren consentimiento previo. Esta es la clasificación estándar reconocida por la AEPD.</p>
              {cookiesTipos.map((c, i) => (
                <div key={i} className={`p-4 rounded-xl border ${c.color === 'green' ? 'bg-green-50 border-green-200' : c.color === 'amber' ? 'bg-amber-50 border-amber-200' : 'bg-red-50 border-red-200'}`}>
                  <div className="flex items-center justify-between mb-3">
                    <p className={`text-sm font-bold ${c.color === 'green' ? 'text-green-900' : c.color === 'amber' ? 'text-amber-900' : 'text-red-900'}`}>{c.categoria}</p>
                    <span className={`text-xs font-bold px-3 py-1 rounded-full ${c.color === 'green' ? 'bg-green-200 text-green-800' : c.color === 'amber' ? 'bg-amber-200 text-amber-800' : 'bg-red-200 text-red-800'}`}>{c.consentimiento}</span>
                  </div>
                  <div className="grid sm:grid-cols-2 gap-1.5">
                    {c.ejemplos.map((e, j) => (
                      <div key={j} className={`flex items-start space-x-2 text-xs p-2 rounded-lg ${c.color === 'green' ? 'bg-green-100 text-green-800' : c.color === 'amber' ? 'bg-amber-100 text-amber-800' : 'bg-red-100 text-red-800'}`}>
                        <span className="flex-shrink-0 mt-0.5">•</span><span>{e}</span>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
              <div className="p-4 bg-indigo-50 border border-indigo-100 rounded-xl">
                <p className="text-xs font-semibold text-indigo-800 mb-1">¿Qué pasa con Google Analytics?</p>
                <p className="text-xs text-indigo-700 leading-relaxed">Google Analytics es una cookie analítica que <strong>requiere consentimiento previo</strong> ya que identifica o puede identificar a usuarios individuales mediante el ID de cliente de GA y transmite datos a servidores de Google (EE.UU.). La AEPD y otras autoridades europeas han declarado que su uso sin consentimiento y sin configuración adecuada puede infringir el RGPD y la ePrivacy. La solución: pedir consentimiento, anonimizar la IP y usar el modo de consentimiento de Google (Consent Mode v2).</p>
              </div>
            </div>
          )}

          {tabActiva === 'nuevo' && (
            <div className="space-y-4">
              <div className="flex items-center space-x-3 p-3 bg-amber-50 border border-amber-200 rounded-xl mb-4">
                <span className="text-xl">⏳</span>
                <p className="text-xs text-amber-800 font-medium">El nuevo Reglamento ePrivacy lleva en negociación desde 2017 y a mayo de 2026 aún no ha sido aprobado. Sigue vigente la Directiva de 2002. Se estima que la entrada en vigor efectiva sería entre 2026 y 2028 una vez aprobado.</p>
              </div>
              {nuevoReglamento.map((item, i) => (
                <div key={i} className="p-4 bg-slate-50 rounded-xl border border-slate-100">
                  <p className="text-sm font-bold text-slate-900 mb-2">{item.titulo}</p>
                  <p className="text-xs text-slate-600 leading-relaxed">{item.desc}</p>
                </div>
              ))}
              <div className="p-4 bg-primary-50 border border-primary-100 rounded-xl">
                <p className="text-xs font-semibold text-primary-800 mb-1">Impacto en SmartAudits</p>
                <p className="text-xs text-primary-700 leading-relaxed">Cuando el Reglamento ePrivacy sea aprobado, afectará directamente a las reglas R10 y R19 del motor de SmartAudits. Se actualizarán las reglas para reflejar los nuevos requisitos, especialmente en lo referente al consentimiento técnico gestionado desde el navegador y los nuevos umbrales de cookies exentas.</p>
              </div>
            </div>
          )}

        </div>
      </div>

      {/* Botones */}
      <div className="flex flex-col sm:flex-row items-center justify-center gap-3">
        <a href="https://eur-lex.europa.eu/legal-content/ES/TXT/?uri=CELEX%3A32002L0058" target="_blank" rel="noopener noreferrer"
          className="inline-flex items-center space-x-2 px-5 py-3 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl font-semibold text-sm transition-colors shadow-soft hover:shadow-medium">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" /></svg>
          <span>Texto oficial en EUR-Lex</span>
        </a>
        <a href="https://eur-lex.europa.eu/legal-content/ES/TXT/?uri=CELEX%3A52017PC0010" target="_blank" rel="noopener noreferrer"
          className="inline-flex items-center space-x-2 px-5 py-3 bg-white border border-slate-200 hover:border-slate-300 text-slate-700 rounded-xl font-semibold text-sm transition-colors shadow-soft">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" /></svg>
          <span>Propuesta nuevo Reglamento</span>
        </a>
      </div>
      <NormativaNav />
    </div>
  )
}

export default NormativaEPrivacy
