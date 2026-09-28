import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import NormativaNav from '../components/NormativaNav'

const NormativaLOPDGDD = () => {
  const navigate = useNavigate()
  const [tabActiva, setTabActiva] = useState('diferencias')

  const diferenciasRGPD = [
    { aspecto: 'Edad de consentimiento de menores', rgpd: 'Permite a los Estados miembros fijar entre 13 y 16 años', lopdgdd: '14 años en España (Art. 7). Entre 14-18 pueden consentir solos. Menores de 14 necesitan consentimiento parental.', articulo: 'Art. 7 LOPDGDD' },
    { aspecto: 'Tratamiento de datos en el ámbito laboral', rgpd: 'No regula en detalle. Deja margen a los Estados.', lopdgdd: 'Regula expresamente la videovigilancia laboral, el control del uso de dispositivos digitales, la geolocalización y el derecho a la desconexión digital (Arts. 87-92).', articulo: 'Arts. 87-92 LOPDGDD' },
    { aspecto: 'Delegado de Protección de Datos (DPO)', rgpd: 'Define los supuestos mínimos obligatorios (Art. 37)', lopdgdd: 'Amplía los supuestos de designación obligatoria en España: colegios profesionales, centros sanitarios, entidades financieras, empresas de seguros, empresas de publicidad, partidos políticos, operadores de telecomunicaciones y otros (Art. 34).', articulo: 'Art. 34 LOPDGDD' },
    { aspecto: 'Sistemas de información crediticia (listas de morosos)', rgpd: 'No regula específicamente', lopdgdd: 'Regula las condiciones para incluir datos en sistemas de información crediticia: deuda cierta, vencida, exigible y no impugnada. Información previa al deudor. Plazo máximo de 5 años (Art. 20).', articulo: 'Art. 20 LOPDGDD' },
    { aspecto: 'Tratamiento de datos de fallecidos', rgpd: 'No aplica (solo personas vivas)', lopdgdd: 'Regula el acceso de herederos y personas vinculadas a datos de fallecidos, incluyendo entornos digitales (Arts. 2-3).', articulo: 'Arts. 2-3 LOPDGDD' },
    { aspecto: 'Derechos digitales', rgpd: 'No contempla específicamente', lopdgdd: 'Título X: catálogo completo de derechos digitales nuevos (Arts. 79-97): neutralidad, acceso universal, seguridad, educación digital, desconexión, olvido, testamento digital, etc.', articulo: 'Título X LOPDGDD' },
  ]

  const articulosClave = [
    { num: 'Art. 6', titulo: 'Tratamiento basado en consentimiento', desc: 'Concreta los requisitos del consentimiento en España. El responsable debe poder demostrar que el consentimiento fue válidamente prestado. No se considera consentimiento válido la mera inactividad del interesado.', reglas: ['R05', 'R12'] },
    { num: 'Art. 7', titulo: 'Consentimiento de menores de edad', desc: 'En España el umbral es de 14 años. Los menores de 14 requieren el consentimiento de los titulares de la patria potestad o tutela. El responsable debe verificar razonablemente la edad y la autenticidad del consentimiento parental.', reglas: ['R13'] },
    { num: 'Art. 11', titulo: 'Transparencia e información al interesado', desc: 'Regula el sistema de información en dos capas: una capa básica (identidad del responsable, finalidades, derechos y DPO) y una capa adicional con toda la información del Art. 13 RGPD. La capa básica puede ir en el propio formulario de recogida.', reglas: ['R01', 'R04'] },
    { num: 'Art. 20', titulo: 'Sistemas de información crediticia', desc: 'Condiciones para el tratamiento de datos en listas de morosos: deuda cierta, vencida y exigible; no impugnada o con fallo judicial; información previa al deudor; plazo máximo 5 años; datos exactos y actualizados.', reglas: [] },
    { num: 'Art. 23', titulo: 'Sistemas de exclusión publicitaria', desc: 'Las comunicaciones comerciales requieren consentimiento previo. El destinatario puede oponerse en cualquier momento de forma gratuita y sencilla. Se reconoce la Lista de Exclusión Publicitaria (Lista Robinson) y otros sistemas de exclusión.', reglas: ['R19'] },
    { num: 'Art. 24', titulo: 'Sistemas de información de denuncias internas', desc: 'Regula los canales de denuncia internos (whistleblowing). Los datos tratados tienen protecciones específicas: confidencialidad del denunciante, prohibición de represalias, conservación máxima 3 meses si no hay investigación.', reglas: [] },
    { num: 'Art. 34', titulo: 'Supuestos de designación obligatoria de DPO', desc: 'Amplía los supuestos del Art. 37 RGPD en España. Obligatorio para: colegios profesionales, centros docentes, entidades que traten datos de afiliados, centros sanitarios, entidades financieras y de seguros, empresas de publicidad, operadores de telecomunicaciones y otros.', reglas: ['R03'] },
    { num: 'Art. 37', titulo: 'Registro de actividades de tratamiento', desc: 'El responsable debe mantener un registro actualizado de todas las actividades de tratamiento bajo su responsabilidad. Debe incluir: nombre del responsable, finalidades, descripción de categorías de interesados y datos, destinatarios, transferencias y medidas de seguridad.', reglas: [] },
  ]

  const derechosDigitales = [
    { art: 'Art. 79', titulo: 'Neutralidad de internet', icono: '🌐', desc: 'Derecho de acceso a internet de forma neutral, sin discriminación por origen, destino, protocolo o aplicación.' },
    { art: 'Art. 80', titulo: 'Acceso universal', icono: '♿', desc: 'Derecho de acceso universal a internet para garantizar la participación en la vida social, económica y cultural.' },
    { art: 'Art. 81', titulo: 'Seguridad digital', icono: '🔐', desc: 'Derecho a la seguridad de las comunicaciones que se transmitan por internet y a la integridad de los dispositivos.' },
    { art: 'Art. 82', titulo: 'Educación digital', icono: '📚', desc: 'Derecho a recibir las instrucciones necesarias para hacer un uso seguro y responsable de internet y de las redes sociales.' },
    { art: 'Art. 84', titulo: 'Derecho al olvido en buscadores', icono: '🔍', desc: 'Derecho a solicitar a los motores de búsqueda la retirada de enlaces a información desactualizada, inexacta o ya no relevante.' },
    { art: 'Art. 85', titulo: 'Derecho al olvido en redes sociales', icono: '📱', desc: 'Derecho a solicitar la supresión de datos personales publicados siendo menor de edad o en redes sociales que el usuario ya no utiliza.' },
    { art: 'Art. 86', titulo: 'Derecho de portabilidad en redes sociales', icono: '📦', desc: 'Derecho a recibir los contenidos propios publicados en redes sociales en un formato estructurado, de uso común y lectura mecánica.' },
    { art: 'Art. 87', titulo: 'Derecho a la intimidad en el trabajo', icono: '💼', desc: 'Los empleadores pueden controlar el uso de dispositivos digitales siempre que respeten ciertos límites e informen previamente a los trabajadores.' },
    { art: 'Art. 88', titulo: 'Derecho a la desconexión digital', icono: '🔕', desc: 'Los trabajadores tienen derecho a no atender dispositivos digitales fuera del horario laboral para garantizar el descanso y la conciliación.' },
    { art: 'Art. 90', titulo: 'Derecho ante la videovigilancia laboral', icono: '📷', desc: 'El empleador puede instalar sistemas de videovigilancia o grabación de sonidos, pero debe informar a los trabajadores y cumplir proporcionalidad.' },
    { art: 'Art. 96', titulo: 'Testamento digital', icono: '📝', desc: 'Derecho a establecer instrucciones sobre el destino de los contenidos digitales propios y el acceso a los mismos tras el fallecimiento.' },
    { art: 'Art. 97', titulo: 'Políticas de impulso del derecho digital', icono: '🏛️', desc: 'Las Administraciones Públicas deben promover el pleno ejercicio de los derechos digitales.' },
  ]

  const infracciones = [
    { nivel: 'Muy graves (Art. 72)', plazo: '3 años', ejemplos: ['Uso fraudulento o desleal de datos personales', 'Vulnerar el deber de confidencialidad', 'Transferencias internacionales sin garantías', 'Incumplimiento del deber de bloqueo durante el período de conservación', 'No atender reiteradamente el ejercicio de derechos'], importe: '20M€ o 4%' },
    { nivel: 'Graves (Art. 73)', plazo: '2 años', ejemplos: ['No registrar las actividades de tratamiento', 'No informar al interesado conforme al Art. 13 RGPD', 'No designar representante cuando sea obligatorio', 'Incumplir medidas de seguridad (Art. 32 RGPD)', 'No comunicar brechas de seguridad a los interesados'], importe: '10M€ o 2%' },
    { nivel: 'Leves (Art. 74)', plazo: '1 año', ejemplos: ['No publicar los datos de contacto del DPO', 'No atender en plazo una solicitud de ejercicio de derechos', 'No facilitar el acceso del interesado a sus datos', 'Incumplimientos formales en el registro de actividades'], importe: 'Apercibimiento o hasta 40.000€' },
  ]

  const tabs = [
    { id: 'diferencias', label: 'Diferencias con el RGPD', icono: '⚖️' },
    { id: 'articulos', label: 'Artículos clave', icono: '📋' },
    { id: 'digitales', label: 'Derechos digitales', icono: '💻' },
    { id: 'infracciones', label: 'Infracciones', icono: '⚠️' },
  ]

  return (
    <div className="space-y-6 max-w-5xl">

      {/* Cabecera */}
      <div className="bg-gradient-to-br from-slate-700 via-slate-800 to-slate-900 rounded-2xl px-8 py-10 relative overflow-hidden">
        <div className="absolute right-0 top-0 w-72 h-72 bg-white/5 rounded-full blur-3xl pointer-events-none"></div>
        <div className="absolute left-0 bottom-0 w-48 h-48 bg-slate-400/10 rounded-full blur-2xl pointer-events-none"></div>
        <div className="relative">
          <button onClick={() => navigate(-1)} className="flex items-center space-x-1 text-white/60 hover:text-white text-xs mb-5 transition-colors">
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
            <span>Volver</span>
          </button>
          <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-6">
            <div className="flex-1">
              <div className="inline-flex items-center space-x-2 px-3 py-1.5 bg-white/15 rounded-full mb-4">
                <span className="w-2 h-2 bg-blue-400 rounded-full"></span>
                <span className="text-white text-xs font-bold tracking-wider">LOPDGDD · En vigor desde 7 diciembre 2018 · Consolidada dic. 2025</span>
              </div>
              <h1 className="text-3xl font-bold text-white mb-2 leading-tight">Ley Orgánica de Protección de<br/>Datos y Garantía de Derechos Digitales</h1>
              <p className="text-white/70 text-sm leading-relaxed max-w-xl">Ley Orgánica 3/2018, de 5 de diciembre. Adapta el RGPD al ordenamiento jurídico español e incorpora un catálogo de derechos digitales pionero en Europa.</p>
            </div>
            <div className="grid grid-cols-2 sm:grid-cols-1 gap-2 sm:gap-3 sm:min-w-[140px]">
              {[{ num: '97', label: 'Artículos' }, { num: '19', label: 'Derechos digitales' }, { num: 'Dic. 2025', label: 'Última actualización' }].map((s) => (
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
        <h2 className="text-base font-bold text-slate-900 mb-4 pb-3 border-b border-slate-100">¿Qué es la LOPDGDD y cuál es su relación con el RGPD?</h2>
        <div className="grid sm:grid-cols-2 gap-4">
          <div>
            <p className="text-slate-600 text-sm leading-relaxed mb-3">La LOPDGDD es la ley española que <strong>adapta y complementa el RGPD</strong> en el ordenamiento jurídico nacional. El RGPD deja ciertos aspectos a la discreción de los Estados miembros — la LOPDGDD los concreta para España.</p>
            <p className="text-slate-600 text-sm leading-relaxed mb-3">Su principal novedad fue la incorporación del <strong>Título X</strong>, que recoge un catálogo de 19 derechos digitales sin precedente en Europa: desde el derecho a la desconexión digital hasta el testamento digital, pasando por la protección frente a la videovigilancia laboral.</p>
            <p className="text-slate-600 text-sm leading-relaxed">La versión <strong>consolidada de diciembre 2025</strong> incorpora las modificaciones introducidas por la Ley 10/2025 en materia de privacidad y telecomunicaciones.</p>
          </div>
          <div className="space-y-2">
            <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-2">Estructura de la ley</p>
            {[
              { titulo: 'Títulos I-III', desc: 'Disposiciones generales, principios y bases legales adaptados al RGPD' },
              { titulo: 'Títulos IV-VI', desc: 'Derechos de los interesados, encargados del tratamiento y DPO' },
              { titulo: 'Títulos VII-VIII', desc: 'Transferencias internacionales y autoridades de control (AEPD y autonómicas)' },
              { titulo: 'Título IX', desc: 'Régimen sancionador: infracciones muy graves, graves y leves' },
              { titulo: 'Título X', desc: 'Derechos digitales — 19 derechos pioneros en Europa (Arts. 79-97)' },
            ].map((item, i) => (
              <div key={i} className="flex items-start space-x-3 p-2.5 bg-slate-50 rounded-xl">
                <span className="text-xs font-bold text-slate-700 bg-slate-200 px-2 py-0.5 rounded font-mono flex-shrink-0">{item.titulo}</span>
                <p className="text-xs text-slate-600">{item.desc}</p>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* La AEPD */}
      <div className="card">
        <h2 className="text-base font-bold text-slate-900 mb-4 pb-3 border-b border-slate-100">La Agencia Española de Protección de Datos (AEPD)</h2>
        <div className="grid sm:grid-cols-3 gap-4">
          <div className="p-3 bg-primary-50 border border-primary-100 rounded-xl">
            <p className="text-xs font-bold text-primary-800 mb-2">¿Qué es?</p>
            <p className="text-xs text-primary-700 leading-relaxed">La AEPD es la autoridad de control independiente en España. Supervisa el cumplimiento del RGPD y la LOPDGDD, investiga reclamaciones y sanciona incumplimientos. Fue la primera autoridad de protección de datos del mundo (fundada en 1993).</p>
          </div>
          <div className="p-3 bg-slate-50 border border-slate-200 rounded-xl">
            <p className="text-xs font-bold text-slate-800 mb-2">Funciones principales</p>
            <ul className="space-y-1">
              {['Resolver reclamaciones de los ciudadanos', 'Iniciar investigaciones de oficio', 'Imponer multas y sanciones', 'Publicar guías y recomendaciones', 'Informar y asesorar a organizaciones', 'Cooperar con otras autoridades europeas (CEPD)'].map((f, i) => (
                <li key={i} className="text-xs text-slate-600 flex items-start space-x-1.5"><span className="flex-shrink-0 text-slate-400">•</span><span>{f}</span></li>
              ))}
            </ul>
          </div>
          <div className="p-3 bg-green-50 border border-green-100 rounded-xl">
            <p className="text-xs font-bold text-green-800 mb-2">Cómo presentar una reclamación</p>
            <ul className="space-y-1">
              {['Accede a www.aepd.es', 'Sección "Sede electrónica"', 'Formulario de reclamación online', 'Requiere certificado digital o Cl@ve', 'Plazo de resolución: 9 meses', 'Es gratuito para el ciudadano'].map((f, i) => (
                <li key={i} className="text-xs text-green-700 flex items-start space-x-1.5"><span className="flex-shrink-0">•</span><span>{f}</span></li>
              ))}
            </ul>
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="card !p-0 overflow-hidden">
        <div className="flex overflow-x-auto border-b border-slate-100">
          {tabs.map((tab) => (
            <button key={tab.id} onClick={() => setTabActiva(tab.id)}
              className={`flex items-center space-x-2 px-4 py-3.5 text-sm font-medium whitespace-nowrap transition-colors border-b-2 -mb-px ${tabActiva === tab.id ? 'border-slate-700 text-slate-900 bg-slate-50/50' : 'border-transparent text-slate-500 hover:text-slate-700 hover:bg-slate-50'}`}>
              <span>{tab.icono}</span><span>{tab.label}</span>
            </button>
          ))}
        </div>
        <div className="p-6">

          {tabActiva === 'diferencias' && (
            <div className="space-y-3">
              <p className="text-sm text-slate-500 mb-4">El RGPD establece un marco mínimo común para toda la UE. La LOPDGDD lo concreta y en algunos casos lo amplía para España. Estas son las principales diferencias relevantes para SmartAudits.</p>
              {diferenciasRGPD.map((d, i) => (
                <div key={i} className="rounded-xl border border-slate-100 overflow-hidden">
                  <div className="bg-slate-50 px-4 py-2.5 flex items-center justify-between">
                    <p className="text-xs font-bold text-slate-800">{d.aspecto}</p>
                    <span className="text-xs font-mono text-slate-500 bg-slate-200 px-2 py-0.5 rounded">{d.articulo}</span>
                  </div>
                  <div className="grid sm:grid-cols-2 divide-y sm:divide-y-0 sm:divide-x divide-slate-100">
                    <div className="p-3">
                      <p className="text-xs font-semibold text-primary-600 mb-1">RGPD (UE)</p>
                      <p className="text-xs text-slate-600 leading-relaxed">{d.rgpd}</p>
                    </div>
                    <div className="p-3 bg-slate-50/50">
                      <p className="text-xs font-semibold text-slate-700 mb-1">LOPDGDD (España)</p>
                      <p className="text-xs text-slate-600 leading-relaxed">{d.lopdgdd}</p>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}

          {tabActiva === 'articulos' && (
            <div className="space-y-3">
              <p className="text-sm text-slate-500 mb-4">Artículos de la LOPDGDD directamente relevantes para las auditorías legales realizadas por SmartAudits.</p>
              {articulosClave.map((art) => (
                <div key={art.num} className="p-4 bg-slate-50 rounded-xl border border-slate-100 hover:border-slate-300 transition-colors">
                  <div className="flex items-start justify-between gap-3 mb-2">
                    <div className="flex items-center space-x-3">
                      <span className="text-xs font-bold text-slate-700 bg-slate-200 px-2 py-0.5 rounded-lg">{art.num}</span>
                      <p className="text-sm font-semibold text-slate-800">{art.titulo}</p>
                    </div>
                    {art.reglas.length > 0 && (
                      <div className="flex gap-1 flex-shrink-0">{art.reglas.map((r) => <span key={r} className="text-xs bg-slate-200 text-slate-600 px-1.5 py-0.5 rounded font-mono">{r}</span>)}</div>
                    )}
                  </div>
                  <p className="text-xs text-slate-600 leading-relaxed">{art.desc}</p>
                </div>
              ))}
            </div>
          )}

          {tabActiva === 'digitales' && (
            <div>
              <p className="text-sm text-slate-500 mb-4">El Título X de la LOPDGDD (Arts. 79-97) incorpora un catálogo de derechos digitales sin precedente en Europa. Son derechos fundamentales adaptados al entorno digital.</p>
              <div className="grid sm:grid-cols-2 gap-3">
                {derechosDigitales.map((d) => (
                  <div key={d.art} className="p-3 bg-slate-50 rounded-xl border border-slate-100 hover:border-slate-300 transition-colors">
                    <div className="flex items-start space-x-3">
                      <span className="text-xl flex-shrink-0">{d.icono}</span>
                      <div>
                        <div className="flex items-center space-x-2 mb-1">
                          <p className="text-xs font-bold text-slate-800">{d.titulo}</p>
                          <span className="text-xs text-slate-400 font-mono">{d.art}</span>
                        </div>
                        <p className="text-xs text-slate-600 leading-relaxed">{d.desc}</p>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {tabActiva === 'infracciones' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">La LOPDGDD clasifica las infracciones en tres niveles con diferentes plazos de prescripción. Las cuantías máximas son las del RGPD, pero la AEPD puede imponer apercibimientos en lugar de multas cuando sea proporcionado.</p>
              {infracciones.map((s, i) => (
                <div key={i} className={`p-5 rounded-xl border ${i === 0 ? 'bg-red-50 border-red-200' : i === 1 ? 'bg-amber-50 border-amber-200' : 'bg-slate-50 border-slate-200'}`}>
                  <div className="flex items-start justify-between mb-3">
                    <div>
                      <p className={`text-sm font-bold ${i === 0 ? 'text-red-900' : i === 1 ? 'text-amber-900' : 'text-slate-800'}`}>{s.nivel}</p>
                      <p className={`text-xs mt-0.5 ${i === 0 ? 'text-red-600' : i === 1 ? 'text-amber-600' : 'text-slate-500'}`}>Prescripción: {s.plazo}</p>
                    </div>
                    <div className="text-right">
                      <p className={`text-lg font-bold ${i === 0 ? 'text-red-700' : i === 1 ? 'text-amber-700' : 'text-slate-600'}`}>{s.importe}</p>
                    </div>
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
              <div className="p-4 bg-primary-50 border border-primary-100 rounded-xl">
                <p className="text-xs font-semibold text-primary-800 mb-1">Apercibimiento como alternativa a la multa</p>
                <p className="text-xs text-primary-700 leading-relaxed">La LOPDGDD permite a la AEPD sustituir la multa por un apercibimiento cuando el infractor sea una Administración Pública, cuando la infracción sea leve, o cuando sea la primera infracción de una pyme sin daños a interesados. Esta es una diferencia importante respecto al régimen del RGPD.</p>
              </div>
            </div>
          )}

        </div>
      </div>

      {/* Botones */}
      <div className="flex flex-col sm:flex-row items-center justify-center gap-3">
        <a href="https://www.boe.es/buscar/act.php?id=BOE-A-2018-16673" target="_blank" rel="noopener noreferrer"
          className="inline-flex items-center space-x-2 px-5 py-3 bg-slate-800 hover:bg-slate-900 text-white rounded-xl font-semibold text-sm transition-colors shadow-soft hover:shadow-medium">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" /></svg>
          <span>Texto oficial en el BOE</span>
        </a>
        <a href="https://www.aepd.es/derechos-y-deberes/conoce-tus-derechos" target="_blank" rel="noopener noreferrer"
          className="inline-flex items-center space-x-2 px-5 py-3 bg-white border border-slate-200 hover:border-slate-300 text-slate-700 rounded-xl font-semibold text-sm transition-colors shadow-soft">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" /></svg>
          <span>Derechos en AEPD</span>
        </a>
      </div>
      <NormativaNav />
    </div>
  )
}

export default NormativaLOPDGDD
