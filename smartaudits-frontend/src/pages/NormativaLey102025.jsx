import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import NormativaNav from '../components/NormativaNav'

const NormativaLey102025 = () => {
  const navigate = useNavigate()
  const [tabActiva, setTabActiva] = useState('que-es')

  const modificacionesLOPDGDD = [
    {
      art: 'Art. 23.1 LOPDGDD',
      titulo: 'Comunicaciones comerciales y exclusión publicitaria',
      antes: 'El tratamiento de datos para evitar envíos comerciales a quienes se opusieron era una práctica aceptada pero sin respaldo legal explícito en la LOPDGDD.',
      despues: 'Se reconoce expresamente como lícito el tratamiento de datos personales cuando el objetivo sea evitar el envío de comunicaciones comerciales a quienes hayan manifestado su negativa u oposición a recibirlas.',
      impacto: 'Las empresas pueden y deben tratar datos de personas que han ejercido el derecho de oposición al marketing, precisamente para cumplir con esa oposición. También se contempla la creación de sistemas de información sectoriales para gestionar preferencias de comunicaciones.',
      reglas: ['R19'],
    },
    {
      art: 'Disposición Final 2ª',
      titulo: 'Modificación de la Ley General de Telecomunicaciones (Ley 11/2022)',
      antes: 'Los operadores de telecomunicaciones tenían obligaciones de atención al cliente dispersas en normativa sectorial.',
      despues: 'Se refuerza la obligación de que los operadores dispongan de un servicio de atención a la clientela gratuito. Si el canal de reclamaciones es telefónico, deben informar del derecho del consumidor a solicitar un justificante de la reclamación en soporte duradero.',
      impacto: 'Los operadores de telecomunicaciones tienen nuevas obligaciones de trazabilidad de reclamaciones y grabación de llamadas según desarrollo reglamentario.',
      reglas: [],
    },
  ]

  const obligacionesAtencion = [
    {
      colectivo: 'Empresas de servicios básicos de interés general',
      ejemplos: 'Agua, energía, transporte, correos, telecomunicaciones, servicios financieros',
      obligaciones: ['Tiempo máximo de espera en atención telefónica: 3 minutos', 'Resolución de incidencias en el menor tiempo posible', 'Acceso a atención personalizada (no solo bots o sistemas automatizados)', 'Canal de atención 24/7 para incidencias del servicio contratado', 'Clave identificativa para cada reclamación que requiera seguimiento', 'Justificante de reclamación a petición del usuario'],
    },
    {
      colectivo: 'Grandes empresas',
      ejemplos: 'Más de 250 trabajadores O más de 50M€ facturación O más de 43M€ de balance anual',
      obligaciones: ['Servicio de atención al cliente diferenciado del comercial', 'Tiempos de respuesta máximos definidos', 'Evaluación anual de calidad del servicio', 'Accesibilidad para personas con discapacidad', 'No derivar reclamaciones contractuales únicamente a bots', 'Registro de reclamaciones y resoluciones'],
    },
  ]

  const relacionPrivacidad = [
    {
      aspecto: 'Datos tratados en atención al cliente',
      desc: 'La Ley 10/2025 obliga a recoger y conservar datos de las reclamaciones (clave identificativa, fecha, hora, contenido). Esto genera nuevas actividades de tratamiento que deben incluirse en el Registro de Actividades de Tratamiento y tener base legal (Art. 6.1.b o 6.1.c RGPD).',
      accion: 'Actualizar el RAT con las nuevas actividades de tratamiento derivadas de la gestión de reclamaciones.',
    },
    {
      aspecto: 'Grabación de llamadas',
      desc: 'La Ley prevé la grabación de un número significativo de llamadas de atención al cliente. Esto requiere informar al interesado antes de la grabación, indicar la finalidad (control de calidad o cumplimiento legal), el plazo de conservación y sus derechos.',
      accion: 'Implementar información de privacidad en los sistemas de grabación (locutor de aviso) y definir plazos de conservación.',
    },
    {
      aspecto: 'Listas de exclusión publicitaria',
      desc: 'La modificación del Art. 23.1 LOPDGDD refuerza la Lista Robinson y los sistemas de exclusión sectoriales. Las empresas deben consultar estas listas antes de realizar comunicaciones comerciales y tratar esos datos para cumplir las oposiciones recibidas.',
      accion: 'Revisar el proceso de envío de comunicaciones comerciales e integrar la consulta a listas de exclusión.',
    },
    {
      aspecto: 'Contratos por teléfono nulos sin consentimiento',
      desc: 'La Ley declara nulos los contratos cerrados en llamadas telefónicas no consentidas. Los operadores de telecomunicaciones deben bloquear llamadas de empresas que no usen los códigos identificativos previstos.',
      accion: 'Asegurarse de que todos los procesos de contratación telefónica cuentan con consentimiento documentado y verificable.',
    },
  ]

  const tabs = [
    { id: 'que-es', label: '¿Qué es?', icono: '📋' },
    { id: 'lopdgdd', label: 'Cambios en LOPDGDD', icono: '🔒' },
    { id: 'atencion', label: 'Atención al cliente', icono: '📞' },
    { id: 'privacidad', label: 'Impacto en privacidad', icono: '🛡️' },
  ]

  return (
    <div className="space-y-6 max-w-5xl">

      {/* Cabecera */}
      <div className="bg-gradient-to-br from-emerald-600 via-emerald-700 to-slate-900 rounded-2xl px-8 py-10 relative overflow-hidden">
        <div className="absolute right-0 top-0 w-72 h-72 bg-white/5 rounded-full blur-3xl pointer-events-none"></div>
        <div className="absolute left-0 bottom-0 w-48 h-48 bg-emerald-400/10 rounded-full blur-2xl pointer-events-none"></div>
        <div className="relative">
          <button onClick={() => navigate(-1)} className="flex items-center space-x-1 text-white/60 hover:text-white text-xs mb-5 transition-colors">
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
            <span>Volver</span>
          </button>
          <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-6">
            <div className="flex-1">
              <div className="inline-flex items-center space-x-2 px-3 py-1.5 bg-white/15 rounded-full mb-4">
                <span className="w-2 h-2 bg-emerald-300 rounded-full"></span>
                <span className="text-white text-xs font-bold tracking-wider">Ley 10/2025 · BOE 27/12/2025 · En vigor desde 28/12/2025</span>
              </div>
              <h1 className="text-3xl font-bold text-white mb-2 leading-tight">Ley 10/2025 de Servicios<br/>de Atención a la Clientela</h1>
              <p className="text-white/70 text-sm leading-relaxed max-w-xl">
                Ley 10/2025, de 26 de diciembre, por la que se regulan los servicios de atención a la clientela.
                Publicada en el BOE el 27 de diciembre de 2025. Modifica la LOPDGDD y la Ley General de Telecomunicaciones.
              </p>
              <p className="text-white/50 text-xs mt-2">Período de adaptación: 12 meses desde el 28/12/2025 · Plenamente exigible desde diciembre 2026</p>
            </div>
            <div className="grid grid-cols-2 sm:grid-cols-1 gap-2 sm:gap-3 sm:min-w-[140px]">
              {[
                { num: 'Dic. 2025', label: 'Aprobada' },
                { num: '12 meses', label: 'Adaptación' },
                { num: 'LOPDGDD', label: 'Modifica' },
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

      {/* Aviso importante */}
      <div className="p-4 bg-amber-50 border border-amber-200 rounded-xl flex items-start space-x-3">
        <span className="text-xl flex-shrink-0">⚠️</span>
        <div>
          <p className="text-sm font-bold text-amber-900 mb-1">Período de adaptación en curso — mayo 2026</p>
          <p className="text-xs text-amber-800 leading-relaxed">
            La Ley 10/2025 entró en vigor el 28 de diciembre de 2025 pero establece un período de adaptación de 12 meses.
            Las empresas tienen hasta <strong>diciembre de 2026</strong> para cumplir con todas sus obligaciones.
            A mayo de 2026, las empresas afectadas deben estar en proceso de adaptación.
          </p>
        </div>
      </div>

      {/* Tabs */}
      <div className="card !p-0 overflow-hidden">
        <div className="flex overflow-x-auto border-b border-slate-100">
          {tabs.map((tab) => (
            <button key={tab.id} onClick={() => setTabActiva(tab.id)}
              className={`flex items-center space-x-2 px-4 py-3.5 text-sm font-medium whitespace-nowrap transition-colors border-b-2 -mb-px ${tabActiva === tab.id ? 'border-emerald-600 text-emerald-700 bg-emerald-50/50' : 'border-transparent text-slate-500 hover:text-slate-700 hover:bg-slate-50'}`}>
              <span>{tab.icono}</span><span>{tab.label}</span>
            </button>
          ))}
        </div>
        <div className="p-6">

          {tabActiva === 'que-es' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-600 leading-relaxed">
                La Ley 10/2025 es la <strong>primera ley en España que regula de forma específica los servicios de atención a la clientela</strong>.
                Su objetivo principal es establecer estándares mínimos de calidad en la atención al cliente,
                reducir tiempos de espera, garantizar la accesibilidad y proteger a los consumidores frente a abusos.
              </p>
              <p className="text-sm text-slate-600 leading-relaxed">
                Aunque su eje es la atención al cliente, incluye <strong>modificaciones directas a la LOPDGDD</strong> (Art. 23.1)
                y a la <strong>Ley General de Telecomunicaciones</strong> (Art. 65.2 Ley 11/2022) que son relevantes
                para la protección de datos y las comunicaciones comerciales.
              </p>

              <div className="grid sm:grid-cols-3 gap-3 mt-4">
                {[
                  { icono: '📞', titulo: 'Atención al cliente', desc: 'Estándares mínimos de calidad, tiempos de respuesta y accesibilidad para empresas de servicios básicos y grandes empresas.' },
                  { icono: '🔒', titulo: 'Protección de datos', desc: 'Modifica el Art. 23.1 LOPDGDD para clarificar el tratamiento de datos en exclusión publicitaria y listas Robinson.' },
                  { icono: '📡', titulo: 'Telecomunicaciones', desc: 'Refuerza las obligaciones de atención al cliente de los operadores y declara nulos contratos por llamadas no consentidas.' },
                ].map((item, i) => (
                  <div key={i} className="p-4 bg-emerald-50 border border-emerald-100 rounded-xl">
                    <span className="text-2xl mb-2 block">{item.icono}</span>
                    <p className="text-sm font-bold text-emerald-900 mb-1">{item.titulo}</p>
                    <p className="text-xs text-emerald-700 leading-relaxed">{item.desc}</p>
                  </div>
                ))}
              </div>

              <div className="mt-4 p-4 bg-slate-50 border border-slate-200 rounded-xl">
                <p className="text-xs font-semibold text-slate-700 mb-2">¿A quién aplica la Ley 10/2025?</p>
                <div className="grid sm:grid-cols-2 gap-2">
                  {[
                    { tipo: 'Servicios básicos de interés general', ejemplos: 'Agua, energía, transporte, telecomunicaciones, financiero, correos' },
                    { tipo: 'Grandes empresas', ejemplos: '+250 trabajadores O +50M€ facturación O +43M€ balance' },
                  ].map((item, i) => (
                    <div key={i} className="p-3 bg-white border border-slate-100 rounded-xl">
                      <p className="text-xs font-semibold text-slate-800 mb-1">{item.tipo}</p>
                      <p className="text-xs text-slate-500">{item.ejemplos}</p>
                    </div>
                  ))}
                </div>
                <p className="text-xs text-slate-500 mt-2 italic">Las pymes que no presten servicios básicos y no superen los umbrales no están directamente obligadas, aunque sí afectadas por las modificaciones de la LOPDGDD.</p>
              </div>
            </div>
          )}

          {tabActiva === 'lopdgdd' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">
                La Ley 10/2025 introduce modificaciones en la LOPDGDD y en la Ley General de Telecomunicaciones
                a través de sus disposiciones finales. Estas son las más relevantes para SmartAudits.
              </p>
              {modificacionesLOPDGDD.map((m, i) => (
                <div key={i} className="rounded-xl border border-slate-200 overflow-hidden">
                  <div className="bg-slate-50 px-4 py-3 flex items-center justify-between gap-3">
                    <div>
                      <div className="flex items-center space-x-2 mb-0.5">
                        <span className="text-xs font-bold text-emerald-700 bg-emerald-100 px-2 py-0.5 rounded font-mono">{m.art}</span>
                        <p className="text-sm font-bold text-slate-900">{m.titulo}</p>
                      </div>
                    </div>
                    {m.reglas.length > 0 && (
                      <div className="flex gap-1 flex-shrink-0">
                        {m.reglas.map((r) => <span key={r} className="text-xs bg-slate-200 text-slate-600 px-1.5 py-0.5 rounded font-mono">{r}</span>)}
                      </div>
                    )}
                  </div>
                  <div className="p-4 space-y-3">
                    <div className="grid sm:grid-cols-2 gap-3">
                      <div className="p-3 bg-red-50 border border-red-100 rounded-xl">
                        <p className="text-xs font-semibold text-red-700 mb-1">Antes de la Ley 10/2025</p>
                        <p className="text-xs text-red-800 leading-relaxed">{m.antes}</p>
                      </div>
                      <div className="p-3 bg-green-50 border border-green-100 rounded-xl">
                        <p className="text-xs font-semibold text-green-700 mb-1">Después de la Ley 10/2025</p>
                        <p className="text-xs text-green-800 leading-relaxed">{m.despues}</p>
                      </div>
                    </div>
                    <div className="p-3 bg-primary-50 border border-primary-100 rounded-xl">
                      <p className="text-xs font-semibold text-primary-800 mb-1">Impacto práctico</p>
                      <p className="text-xs text-primary-700 leading-relaxed">{m.impacto}</p>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}

          {tabActiva === 'atencion' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">
                La Ley establece obligaciones concretas según el tipo de empresa. Estas son las principales.
              </p>
              {obligacionesAtencion.map((o, i) => (
                <div key={i} className="rounded-xl border border-slate-200 overflow-hidden">
                  <div className="bg-slate-50 px-4 py-3">
                    <p className="text-sm font-bold text-slate-900">{o.colectivo}</p>
                    <p className="text-xs text-slate-500 mt-0.5">{o.ejemplos}</p>
                  </div>
                  <div className="p-4">
                    <div className="grid sm:grid-cols-2 gap-1.5">
                      {o.obligaciones.map((ob, j) => (
                        <div key={j} className="flex items-start space-x-2 text-xs p-2 bg-slate-50 rounded-lg">
                          <svg className="w-3.5 h-3.5 text-emerald-500 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                          </svg>
                          <span className="text-slate-600">{ob}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
              ))}
              <div className="p-4 bg-amber-50 border border-amber-200 rounded-xl">
                <p className="text-xs font-semibold text-amber-800 mb-1">Contratos nulos por llamadas no consentidas</p>
                <p className="text-xs text-amber-700 leading-relaxed">
                  La Ley 10/2025 declara nulos los contratos cerrados en llamadas telefónicas que el consumidor no haya solicitado o autorizado.
                  Los operadores de telecomunicaciones deberán bloquear las llamadas de empresas que no utilicen los códigos identificativos
                  previstos en el desarrollo reglamentario.
                </p>
              </div>
            </div>
          )}

          {tabActiva === 'privacidad' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">
                La Ley 10/2025 genera nuevas obligaciones de privacidad para las empresas afectadas.
                Estas son las principales implicaciones prácticas para el cumplimiento del RGPD.
              </p>
              {relacionPrivacidad.map((item, i) => (
                <div key={i} className="p-4 bg-slate-50 rounded-xl border border-slate-100 hover:border-slate-300 transition-colors">
                  <p className="text-sm font-bold text-slate-900 mb-2">{item.aspecto}</p>
                  <p className="text-xs text-slate-600 leading-relaxed mb-3">{item.desc}</p>
                  <div className="p-2.5 bg-emerald-50 border border-emerald-100 rounded-lg">
                    <p className="text-xs text-emerald-800"><span className="font-semibold">Acción recomendada:</span> {item.accion}</p>
                  </div>
                </div>
              ))}
            </div>
          )}

        </div>
      </div>

      {/* Botones */}
      <div className="flex flex-col sm:flex-row items-center justify-center gap-3">
        <a href="https://www.boe.es/eli/es/l/2025/12/26/10" target="_blank" rel="noopener noreferrer"
          className="inline-flex items-center space-x-2 px-5 py-3 bg-emerald-600 hover:bg-emerald-700 text-white rounded-xl font-semibold text-sm transition-colors shadow-soft hover:shadow-medium">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" /></svg>
          <span>Texto oficial en el BOE</span>
        </a>
        <a href="https://www.aepd.es" target="_blank" rel="noopener noreferrer"
          className="inline-flex items-center space-x-2 px-5 py-3 bg-white border border-slate-200 hover:border-slate-300 text-slate-700 rounded-xl font-semibold text-sm transition-colors shadow-soft">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" /></svg>
          <span>AEPD — Recursos de cumplimiento</span>
        </a>
      </div>
      <NormativaNav />
    </div>
  )
}

export default NormativaLey102025
