import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import NormativaNav from '../components/NormativaNav'

const NormativaLSSI = () => {
  const navigate = useNavigate()
  const [tabActiva, setTabActiva] = useState('obligaciones')

  const obligaciones = [
    {
      art: 'Art. 10', titulo: 'Información general obligatoria (Aviso Legal)',
      desc: 'Todo prestador de servicios de la sociedad de la información debe disponer de los medios que permitan a los destinatarios y a los órganos competentes acceder por medios electrónicos de forma permanente, fácil, directa y gratuita a la información del prestador.',
      campos: ['Nombre o denominación social y NIF/CIF', 'Domicilio social o dirección habitual del establecimiento', 'Datos de contacto: email, teléfono o fax', 'Datos de inscripción en el Registro Mercantil u otros registros públicos', 'En caso de actividades sujetas a autorización: datos de la autorización y organismo competente', 'En profesiones reguladas: colegio profesional, título académico y Estado de expedición', 'Número de identificación fiscal si aplica IVA', 'Información sobre precio de los servicios (impuestos, gastos de envío)', 'Códigos de conducta a los que esté adherida la empresa (si aplica)'],
      donde: 'En el Aviso Legal / Información Legal de la web, accesible desde cualquier página.',
      sancion: 'Infracción grave: hasta 150.000€',
      reglas: ['R16'],
    },
    {
      art: 'Art. 20', titulo: 'Identificación de comunicaciones comerciales',
      desc: 'Las comunicaciones comerciales realizadas por vía electrónica deben ser claramente identificables como tales desde el momento en que se reciben. Deben indicar la persona física o jurídica en cuyo nombre se realizan.',
      campos: ['La palabra "publicidad" o "publi" de forma clara al inicio', 'Identidad del anunciante', 'Las ofertas promocionales, concursos o sorteos deben identificarse como tales', 'Las condiciones de participación deben ser fácilmente accesibles y claras'],
      donde: 'En el asunto del email comercial, en el banner o en el inicio del mensaje.',
      sancion: 'Infracción leve o grave según reincidencia',
      reglas: ['R19'],
    },
    {
      art: 'Art. 21', titulo: 'Prohibición de comunicaciones comerciales no solicitadas',
      desc: 'Queda prohibido el envío de comunicaciones publicitarias o promocionales por correo electrónico u otro medio de comunicación electrónica equivalente que no hubieran sido previamente solicitadas o expresamente autorizadas por los destinatarios.',
      campos: ['Consentimiento previo y expreso del destinatario', 'Excepción: clientes existentes para productos/servicios similares a los contratados y con posibilidad de oposición fácil', 'Excepción: comunicaciones de organismos sin fin de lucro a sus miembros', 'Obligación de mantener registro de consentimientos otorgados'],
      donde: 'Aplicable a todos los canales: email, SMS, WhatsApp, push notifications.',
      sancion: 'Infracción grave: hasta 150.000€',
      reglas: ['R19'],
    },
    {
      art: 'Art. 22', titulo: 'Derechos de los destinatarios — Cookies',
      desc: 'Los prestadores de servicios podrán utilizar dispositivos de almacenamiento y recuperación de datos en equipos terminales de los destinatarios siempre que hayan obtenido su consentimiento. Se exceptúan las cookies técnicas estrictamente necesarias.',
      campos: ['Información clara sobre el uso de cookies (finalidad, duración, terceros)', 'Consentimiento previo e inequívoco para cookies no técnicas', 'Opción de rechazar tan accesible como la de aceptar (AEPD 2025)', 'Panel de configuración granular por categorías', 'Conservación del registro del consentimiento otorgado', 'Posibilidad de retirar el consentimiento en cualquier momento'],
      donde: 'Banner de cookies y política de cookies enlazada desde el banner.',
      sancion: 'Infracción grave: hasta 150.000€',
      reglas: ['R10'],
    },
    {
      art: 'Art. 27', titulo: 'Obligaciones previas a la contratación online',
      desc: 'Antes de iniciar el procedimiento de contratación, el prestador debe poner a disposición del destinatario información clara y comprensible sobre los trámites que deben seguirse para celebrar el contrato.',
      campos: ['Trámites para celebrar el contrato', 'Si el documento electrónico del contrato se archivará y si será accesible', 'Medios técnicos para identificar y corregir errores en la introducción de datos', 'Lengua o lenguas en que podrá formalizarse el contrato', 'Condiciones generales del contrato (si aplican)'],
      donde: 'En la página del carrito o proceso de checkout, antes de la confirmación.',
      sancion: 'Infracción leve o grave',
      reglas: [],
    },
  ]

  const quienObliga = [
    { tipo: '✅ Sí obliga', ejemplos: ['Tiendas online (e-commerce)', 'Plataformas SaaS y aplicaciones web', 'Blogs con publicidad o servicios de pago', 'Portales de servicios profesionales', 'Apps móviles con actividad económica', 'Marketplaces y plataformas de intermediación', 'Servicios de streaming y contenido digital', 'Webs corporativas de empresas'], color: 'green' },
    { tipo: '⚠️ Zona gris', ejemplos: ['Blogs personales sin publicidad ni ingresos', 'Webs de asociaciones sin ánimo de lucro', 'Intranets corporativas (uso interno)', 'Webs informativas sin servicios ni comercio'], color: 'amber' },
    { tipo: '❌ No obliga directamente', ejemplos: ['Correo electrónico privado', 'Mensajería privada entre particulares', 'Servicios de radiodifusión televisiva o radiofónica', 'Servicios de telecomunicaciones (tienen su propia normativa)'], color: 'red' },
  ]

  const infracciones = [
    { nivel: 'Muy graves (Art. 38.1)', plazo: '5 años', sancion: 'Hasta 600.000€', ejemplos: ['Incumplimiento de órdenes de la autoridad', 'Reincidencia en infracciones graves', 'Incumplimiento que afecte gravemente la seguridad o integridad de redes'] },
    { nivel: 'Graves (Art. 38.2)', plazo: '3 años', sancion: 'Hasta 150.000€', ejemplos: ['No disponer de Aviso Legal (Art. 10)', 'Envío de comunicaciones comerciales no solicitadas (spam)', 'Instalar cookies sin consentimiento previo (Art. 22.2)', 'No facilitar información precontractual (Art. 27)', 'Incumplir obligaciones de conservación de datos de tráfico'] },
    { nivel: 'Leves (Art. 38.3)', plazo: '1 año', sancion: 'Hasta 30.000€', ejemplos: ['Aviso Legal incompleto o con información incorrecta', 'No comunicar cambios relevantes en la política de cookies', 'Incumplimientos formales sin daño a los usuarios'] },
  ]

  const diferenciasRGPD = [
    { aspecto: 'Ámbito de aplicación', lssi: 'Servicios de la sociedad de la información — actividad económica online', rgpd: 'Cualquier tratamiento de datos personales, independientemente del canal' },
    { aspecto: 'Foco principal', lssi: 'Transparencia del prestador y comunicaciones comerciales', rgpd: 'Protección de los datos personales de los interesados' },
    { aspecto: 'Autoridad de control', lssi: 'Secretaría de Estado de Telecomunicaciones e Infraestructuras Digitales (SETSI) y CNMC', rgpd: 'AEPD (Agencia Española de Protección de Datos)' },
    { aspecto: 'Cookies', lssi: 'Base legal para instalación: Art. 22.2 LSSI-CE', rgpd: 'Protección de los datos recogidos mediante cookies' },
    { aspecto: 'Complementariedad', lssi: 'Regula el "cómo" y el "quién" del servicio online', rgpd: 'Regula el "qué datos" y "para qué" se tratan' },
  ]

  const tabs = [
    { id: 'obligaciones', label: 'Obligaciones principales', icono: '📋' },
    { id: 'quien', label: '¿A quién obliga?', icono: '🏢' },
    { id: 'infracciones', label: 'Infracciones', icono: '⚠️' },
    { id: 'diferencias', label: 'LSSI vs RGPD', icono: '⚖️' },
  ]

  return (
    <div className="space-y-6 max-w-5xl">

      {/* Cabecera */}
      <div className="bg-gradient-to-br from-accent-600 via-accent-700 to-slate-900 rounded-2xl px-8 py-10 relative overflow-hidden">
        <div className="absolute right-0 top-0 w-72 h-72 bg-white/5 rounded-full blur-3xl pointer-events-none"></div>
        <div className="absolute left-0 bottom-0 w-48 h-48 bg-accent-400/10 rounded-full blur-2xl pointer-events-none"></div>
        <div className="relative">
          <button onClick={() => navigate(-1)} className="flex items-center space-x-1 text-white/60 hover:text-white text-xs mb-5 transition-colors">
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
            <span>Volver</span>
          </button>
          <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-6">
            <div className="flex-1">
              <div className="inline-flex items-center space-x-2 px-3 py-1.5 bg-white/15 rounded-full mb-4">
                <span className="w-2 h-2 bg-orange-400 rounded-full"></span>
                <span className="text-white text-xs font-bold tracking-wider">LSSI-CE · Ley 34/2002 · Modificada varias veces hasta 2023</span>
              </div>
              <h1 className="text-3xl font-bold text-white mb-2 leading-tight">Ley de Servicios de la Sociedad<br/>de la Información y del Comercio Electrónico</h1>
              <p className="text-white/70 text-sm leading-relaxed max-w-xl">Ley 34/2002, de 11 de julio. Transpone la Directiva 2000/31/CE al ordenamiento jurídico español. Obligatoria para toda web con actividad económica en internet.</p>
            </div>
            <div className="grid grid-cols-2 sm:grid-cols-1 gap-2 sm:gap-3 sm:min-w-[130px]">
              {[{ num: '43', label: 'Artículos' }, { num: '3', label: 'Oblig. principales' }, { num: '600K€', label: 'Sanción máxima' }].map((s) => (
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
        <h2 className="text-base font-bold text-slate-900 mb-4 pb-3 border-b border-slate-100">¿Qué es la LSSI-CE y qué regula?</h2>
        <div className="grid sm:grid-cols-2 gap-4">
          <div>
            <p className="text-slate-600 text-sm leading-relaxed mb-3">La LSSI-CE regula la prestación de servicios de la sociedad de la información y el comercio electrónico en España. Transpone la Directiva europea 2000/31/CE y establece las obligaciones de <strong>transparencia e información</strong> que deben cumplir las webs y apps con actividad económica.</p>
            <p className="text-slate-600 text-sm leading-relaxed">Sus tres pilares son: la <strong>obligación de aviso legal</strong> (Art. 10), las <strong>reglas sobre comunicaciones comerciales</strong> (Arts. 19-22) y la <strong>regulación de cookies</strong> (Art. 22.2). Es complementaria al RGPD: mientras el RGPD protege los datos personales, la LSSI regula la actividad del prestador.</p>
          </div>
          <div className="space-y-2">
            <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-2">¿Qué es un "servicio de la sociedad de la información"?</p>
            <div className="p-3 bg-accent-50 border border-accent-100 rounded-xl">
              <p className="text-xs text-accent-800 leading-relaxed">Todo servicio prestado normalmente a título oneroso, a distancia, por vía electrónica y a petición individual del destinatario. <strong>Lo oneroso no significa que el usuario pague directamente</strong> — basta con que exista actividad económica (publicidad, suscripciones, comisiones, etc.).</p>
            </div>
            <div className="grid grid-cols-2 gap-2 mt-2">
              {['Tiendas online', 'Portales con publicidad', 'SaaS y apps web', 'Blogs con afiliación', 'Marketplaces', 'Servicios de streaming'].map((e, i) => (
                <div key={i} className="flex items-center space-x-2 p-2 bg-green-50 border border-green-100 rounded-lg">
                  <span className="text-green-600 text-xs font-bold">✓</span>
                  <span className="text-xs text-green-800">{e}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="card !p-0 overflow-hidden">
        <div className="flex overflow-x-auto border-b border-slate-100">
          {tabs.map((tab) => (
            <button key={tab.id} onClick={() => setTabActiva(tab.id)}
              className={`flex items-center space-x-2 px-4 py-3.5 text-sm font-medium whitespace-nowrap transition-colors border-b-2 -mb-px ${tabActiva === tab.id ? 'border-accent-600 text-accent-700 bg-accent-50/50' : 'border-transparent text-slate-500 hover:text-slate-700 hover:bg-slate-50'}`}>
              <span>{tab.icono}</span><span>{tab.label}</span>
            </button>
          ))}
        </div>
        <div className="p-6">

          {tabActiva === 'obligaciones' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">La LSSI-CE establece obligaciones concretas para los prestadores de servicios online. Estas son las más relevantes para SmartAudits y las que más infracciones generan en la práctica.</p>
              {obligaciones.map((o) => (
                <div key={o.art} className="rounded-xl border border-slate-200 overflow-hidden">
                  <div className="bg-slate-50 px-4 py-3 flex items-start justify-between gap-3">
                    <div>
                      <div className="flex items-center space-x-2 mb-0.5">
                        <span className="text-xs font-bold text-accent-700 bg-accent-100 px-2 py-0.5 rounded font-mono">{o.art}</span>
                        <p className="text-sm font-bold text-slate-900">{o.titulo}</p>
                      </div>
                      <p className="text-xs text-slate-500">{o.donde}</p>
                    </div>
                    <div className="flex items-center gap-2 flex-shrink-0">
                      {o.reglas.map((r) => <span key={r} className="text-xs bg-slate-200 text-slate-600 px-1.5 py-0.5 rounded font-mono">{r}</span>)}
                      <span className="text-xs text-red-600 font-medium bg-red-50 border border-red-100 px-2 py-0.5 rounded-full">{o.sancion}</span>
                    </div>
                  </div>
                  <div className="p-4">
                    <p className="text-xs text-slate-600 leading-relaxed mb-3">{o.desc}</p>
                    <p className="text-xs font-semibold text-slate-700 mb-2">Información/requisitos exigidos:</p>
                    <div className="grid sm:grid-cols-2 gap-1.5">
                      {o.campos.map((c, i) => (
                        <div key={i} className="flex items-start space-x-2 text-xs p-2 bg-slate-50 rounded-lg">
                          <svg className="w-3.5 h-3.5 text-accent-500 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" /></svg>
                          <span className="text-slate-600">{c}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}

          {tabActiva === 'quien' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">La LSSI-CE aplica a los "prestadores de servicios de la sociedad de la información establecidos en España". Aquí tienes una guía práctica de a quién afecta.</p>
              {quienObliga.map((q, i) => (
                <div key={i} className={`p-4 rounded-xl border ${q.color === 'green' ? 'bg-green-50 border-green-200' : q.color === 'amber' ? 'bg-amber-50 border-amber-200' : 'bg-red-50 border-red-200'}`}>
                  <p className={`text-sm font-bold mb-3 ${q.color === 'green' ? 'text-green-900' : q.color === 'amber' ? 'text-amber-900' : 'text-red-900'}`}>{q.tipo}</p>
                  <div className="grid sm:grid-cols-2 gap-1.5">
                    {q.ejemplos.map((e, j) => (
                      <div key={j} className={`flex items-start space-x-2 text-xs p-2 rounded-lg ${q.color === 'green' ? 'bg-green-100 text-green-800' : q.color === 'amber' ? 'bg-amber-100 text-amber-800' : 'bg-red-100 text-red-800'}`}>
                        <span className="flex-shrink-0 mt-0.5">•</span><span>{e}</span>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
              <div className="p-4 bg-primary-50 border border-primary-100 rounded-xl">
                <p className="text-xs font-semibold text-primary-800 mb-1">¿Y si tengo una web en España pero el servidor está fuera?</p>
                <p className="text-xs text-primary-700 leading-relaxed">La LSSI aplica según el lugar de establecimiento del prestador (donde esté la sede de dirección efectiva), no donde esté el servidor. Si tu empresa está en España, la LSSI te aplica independientemente de dónde estén los servidores.</p>
              </div>
            </div>
          )}

          {tabActiva === 'infracciones' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">La LSSI-CE establece tres niveles de infracciones con sus correspondientes sanciones. A diferencia del RGPD, las sanciones de la LSSI las impone la <strong>SETSI</strong> o la <strong>CNMC</strong>, no la AEPD.</p>
              {infracciones.map((s, i) => (
                <div key={i} className={`p-5 rounded-xl border ${i === 0 ? 'bg-red-50 border-red-200' : i === 1 ? 'bg-amber-50 border-amber-200' : 'bg-slate-50 border-slate-200'}`}>
                  <div className="flex items-start justify-between mb-3">
                    <div>
                      <p className={`text-sm font-bold ${i === 0 ? 'text-red-900' : i === 1 ? 'text-amber-900' : 'text-slate-800'}`}>{s.nivel}</p>
                      <p className={`text-xs mt-0.5 ${i === 0 ? 'text-red-600' : i === 1 ? 'text-amber-600' : 'text-slate-500'}`}>Prescripción: {s.plazo}</p>
                    </div>
                    <p className={`text-lg font-bold ${i === 0 ? 'text-red-700' : i === 1 ? 'text-amber-700' : 'text-slate-600'}`}>{s.sancion}</p>
                  </div>
                  <div className="grid sm:grid-cols-2 gap-1.5">
                    {s.ejemplos.map((e, j) => (
                      <div key={j} className={`flex items-start space-x-2 text-xs p-2 rounded-lg ${i === 0 ? 'bg-red-100 text-red-800' : i === 1 ? 'bg-amber-100 text-amber-800' : 'bg-slate-100 text-slate-700'}`}>
                        <span className="flex-shrink-0 mt-0.5">•</span><span>{e}</span>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}

          {tabActiva === 'diferencias' && (
            <div className="space-y-3">
              <p className="text-sm text-slate-500 mb-4">La LSSI-CE y el RGPD son normas complementarias pero con ámbitos y enfoques diferentes. Entender sus diferencias es clave para saber qué norma aplica en cada situación.</p>
              {diferenciasRGPD.map((d, i) => (
                <div key={i} className="rounded-xl border border-slate-100 overflow-hidden">
                  <div className="bg-slate-50 px-4 py-2.5">
                    <p className="text-xs font-bold text-slate-800">{d.aspecto}</p>
                  </div>
                  <div className="grid sm:grid-cols-2 divide-y sm:divide-y-0 sm:divide-x divide-slate-100">
                    <div className="p-3">
                      <p className="text-xs font-semibold text-accent-600 mb-1">LSSI-CE</p>
                      <p className="text-xs text-slate-600 leading-relaxed">{d.lssi}</p>
                    </div>
                    <div className="p-3 bg-slate-50/50">
                      <p className="text-xs font-semibold text-primary-600 mb-1">RGPD</p>
                      <p className="text-xs text-slate-600 leading-relaxed">{d.rgpd}</p>
                    </div>
                  </div>
                </div>
              ))}
              <div className="p-4 bg-primary-50 border border-primary-100 rounded-xl mt-4">
                <p className="text-xs font-semibold text-primary-800 mb-1">Resumen: ¿cuándo aplica cada norma en las cookies?</p>
                <p className="text-xs text-primary-700 leading-relaxed">La <strong>LSSI (Art. 22.2)</strong> establece la base legal para instalar cookies: necesitas información y consentimiento. El <strong>RGPD</strong> aplica cuando esas cookies recogen datos personales: establece qué tipo de consentimiento se necesita (libre, específico, informado e inequívoco) y los derechos del usuario sobre esos datos. <strong>Ambas aplican simultáneamente</strong> en la gran mayoría de casos.</p>
              </div>
            </div>
          )}

        </div>
      </div>

      {/* Botones */}
      <div className="flex flex-col sm:flex-row items-center justify-center gap-3">
        <a href="https://www.boe.es/buscar/act.php?id=BOE-A-2002-13758" target="_blank" rel="noopener noreferrer"
          className="inline-flex items-center space-x-2 px-5 py-3 bg-accent-600 hover:bg-accent-700 text-white rounded-xl font-semibold text-sm transition-colors shadow-soft hover:shadow-medium">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" /></svg>
          <span>Texto oficial en el BOE</span>
        </a>
        <a href="https://www.aepd.es/guias/guia-cookies.pdf" target="_blank" rel="noopener noreferrer"
          className="inline-flex items-center space-x-2 px-5 py-3 bg-white border border-slate-200 hover:border-slate-300 text-slate-700 rounded-xl font-semibold text-sm transition-colors shadow-soft">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" /></svg>
          <span>Guía Cookies AEPD</span>
        </a>
      </div>
      <NormativaNav />
    </div>
  )
}

export default NormativaLSSI
