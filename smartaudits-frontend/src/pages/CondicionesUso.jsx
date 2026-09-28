import { useState } from 'react'

const CondicionesUso = () => {
  const [tabActiva, setTabActiva] = useState('servicio')

  const funcionesPlataforma = [
    { icono: '📋', titulo: 'Análisis de textos legales', desc: 'Analiza políticas de privacidad, avisos legales, condiciones de uso y políticas de cookies frente a RGPD, LOPDGDD, LSSI-CE y normativa vigente a mayo de 2026.' },
    { icono: '⚖️', titulo: 'Informes de cumplimiento', desc: 'Genera informes detallados con puntuación de riesgo (0-100), incidencias clasificadas por severidad (ALTA/MEDIA/BAJA), recomendaciones y textos sugeridos.' },
    { icono: '📊', titulo: 'Historial de auditorías', desc: 'Acceso al historial completo de auditorías realizadas, con posibilidad de consultar el detalle de cualquier informe anterior.' },
    { icono: '🔍', titulo: 'Base de conocimiento normativo', desc: 'Acceso a páginas informativas sobre cada normativa auditada: RGPD, LOPDGDD, LSSI-CE, Directiva ePrivacy, Ley 10/2025 y Guía AEPD de Cookies.' },
    { icono: '👤', titulo: 'Gestión de cuenta', desc: 'Registro, autenticación, edición del perfil y posibilidad de baja voluntaria (desactivación lógica de la cuenta).' },
    { icono: '👑', titulo: 'Panel de administración', desc: 'Para usuarios con rol ADMIN: gestión de usuarios, visualización de todas las auditorías y acceso al historial de acciones administrativas.' },
  ]

  const obligacionesUsuario = [
    { tipo: 'registro', titulo: 'En el registro', items: ['Proporcionar datos verídicos: nombre real y correo electrónico válido', 'No crear cuentas con identidades falsas o de terceros sin su consentimiento', 'Mantener actualizados los datos de contacto'] },
    { tipo: 'uso', titulo: 'Durante el uso', items: ['Mantener la confidencialidad de las credenciales de acceso (email y contraseña)', 'No compartir la cuenta con terceros', 'Notificar cualquier uso no autorizado de la cuenta a contacto@smartaudits.local', 'No intentar vulnerar la seguridad, integridad o disponibilidad de la plataforma', 'No introducir contenidos ilícitos, ofensivos, falsos o que vulneren derechos de terceros', 'No usar la plataforma para actividades comerciales sin autorización expresa del autor'] },
    { tipo: 'analisis', titulo: 'Al realizar auditorías', items: ['Asegurarse de tener autorización para analizar los textos que se introduzcan', 'No introducir datos personales innecesarios en los textos a analizar', 'Usar los informes generados como referencia orientativa, nunca como dictamen jurídico', 'No atribuir a los informes un valor legal que no tienen'] },
  ]

  const usosPermitidos = [
    { permitido: true, texto: 'Analizar textos legales propios (política de privacidad de tu empresa, aviso legal, etc.)' },
    { permitido: true, texto: 'Analizar textos legales de terceros con fines educativos, de investigación o de cumplimiento' },
    { permitido: true, texto: 'Usar los informes como referencia orientativa para mejorar documentos legales' },
    { permitido: true, texto: 'Uso académico y formativo en el contexto de estudios de derecho, informática o compliance' },
    { permitido: true, texto: 'Consultar la base de conocimiento normativo para formación e información' },
    { permitido: false, texto: 'Usar los informes como sustituto de asesoramiento jurídico profesional' },
    { permitido: false, texto: 'Intentar acceder a datos de otros usuarios o vulnerar la seguridad del sistema' },
    { permitido: false, texto: 'Reproducir o distribuir el código fuente o diseño de SmartAudits sin autorización' },
    { permitido: false, texto: 'Usar la plataforma para actividades ilegales o que vulneren derechos de terceros' },
    { permitido: false, texto: 'Realizar scraping automatizado o uso masivo de la plataforma sin autorización' },
  ]

  const procesosBaja = [
    { paso: '1', titulo: 'Baja voluntaria desde la plataforma', desc: 'El usuario puede desactivar su cuenta en cualquier momento desde la opción "Darse de baja" del menú lateral izquierdo. La baja es instantánea.', tipo: 'usuario', detalle: 'La cuenta queda desactivada (baja lógica). No podrás iniciar sesión pero tus datos e historial quedan conservados en el sistema por razones de trazabilidad.' },
    { paso: '2', titulo: 'Supresión completa de datos', desc: 'Si deseas la eliminación total de todos tus datos personales (supresión conforme al Art. 17 RGPD), debes solicitarlo por email.', tipo: 'usuario', detalle: 'Contacta a contacto@smartaudits.local indicando "Solicitud de supresión de datos". Responderemos en el plazo máximo de 1 mes conforme al Art. 17 RGPD.' },
    { paso: '3', titulo: 'Desactivación por administrador', desc: 'Los administradores de la plataforma pueden desactivar cuentas de usuarios en caso de incumplimiento de estas condiciones.', tipo: 'admin', detalle: 'La desactivación por parte de un administrador implica el mismo estado que la baja voluntaria. Se notificará al usuario si es posible.' },
    { paso: '4', titulo: 'Reactivación', desc: 'Las cuentas desactivadas solo pueden reactivarse por un administrador del sistema desde el panel de gestión de usuarios.', tipo: 'admin', detalle: 'Contacta a contacto@smartaudits.local para solicitar la reactivación si consideras que la desactivación fue indebida.' },
  ]

  const tabs = [
    { id: 'servicio', label: 'El servicio', icono: '🖥️' },
    { id: 'obligaciones', label: 'Obligaciones del usuario', icono: '📋' },
    { id: 'usos', label: 'Usos permitidos', icono: '✅' },
    { id: 'baja', label: 'Baja y cancelación', icono: '🚪' },
  ]

  return (
    <div className="space-y-6 max-w-5xl">

      {/* Cabecera */}
      <div className="bg-gradient-to-br from-slate-700 via-slate-800 to-slate-900 rounded-2xl px-8 py-10 relative overflow-hidden">
        <div className="absolute right-0 top-0 w-72 h-72 bg-white/5 rounded-full blur-3xl pointer-events-none"></div>
        <div className="absolute left-0 bottom-0 w-48 h-48 bg-slate-400/10 rounded-full blur-2xl pointer-events-none"></div>
        <div className="relative">
          <div className="inline-flex items-center space-x-2 px-3 py-1.5 bg-white/15 rounded-full mb-4">
            <span className="w-2 h-2 bg-green-400 rounded-full"></span>
            <span className="text-white text-xs font-bold tracking-wider">SmartAudits · Condiciones de Uso · Actualizadas mayo 2026</span>
          </div>
          <h1 className="text-3xl font-bold text-white mb-2 leading-tight">Condiciones de Uso</h1>
          <p className="text-white/70 text-sm leading-relaxed max-w-2xl">
            Términos y condiciones que regulan el acceso, registro y uso de la plataforma SmartAudits.
            El acceso a la plataforma implica la aceptación plena de estas condiciones.
            Si no estás de acuerdo con alguna de ellas, debes abstenerte de utilizar SmartAudits.
          </p>
          <p className="text-white/40 text-xs mt-3">Última actualización: 20 de mayo de 2026</p>
        </div>
      </div>

      {/* Aceptación */}
      <div className="p-4 bg-amber-50 border border-amber-200 rounded-xl flex items-start space-x-3">
        <span className="text-xl flex-shrink-0">⚠️</span>
        <div>
          <p className="text-sm font-bold text-amber-900 mb-1">Aceptación de las condiciones</p>
          <p className="text-xs text-amber-800 leading-relaxed">
            El acceso y uso de SmartAudits implica la <strong>aceptación plena y sin reservas</strong> de las presentes
            Condiciones de Uso. SmartAudits se reserva el derecho a modificarlas en cualquier momento.
            Los cambios serán publicados en esta página y entrarán en vigor desde su publicación.
            El uso continuado de la plataforma tras los cambios implica la aceptación de los nuevos términos.
          </p>
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

          {tabActiva === 'servicio' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">
                SmartAudits es una <strong>plataforma educativa de auditoría legal automatizada</strong> desarrollada
                como Trabajo de Fin de Ciclo del Grado Superior en DAW en ESIC University (2025-2026).
                A continuación se describen todas sus funciones.
              </p>
              <div className="grid sm:grid-cols-2 gap-3">
                {funcionesPlataforma.map((f, i) => (
                  <div key={i} className="p-4 bg-slate-50 border border-slate-100 rounded-xl hover:border-slate-300 transition-colors">
                    <div className="flex items-start space-x-3">
                      <span className="text-xl flex-shrink-0">{f.icono}</span>
                      <div>
                        <p className="text-sm font-bold text-slate-900 mb-1">{f.titulo}</p>
                        <p className="text-xs text-slate-600 leading-relaxed">{f.desc}</p>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
              <div className="p-4 bg-amber-50 border border-amber-200 rounded-xl mt-2">
                <p className="text-xs font-bold text-amber-900 mb-1">⚠️ Carácter orientativo de los análisis</p>
                <p className="text-xs text-amber-800 leading-relaxed">
                  Los informes generados por SmartAudits son el resultado de un motor de análisis automatizado
                  basado en reglas normativas. Tienen carácter <strong>meramente orientativo y educativo</strong>.
                  No constituyen asesoramiento jurídico profesional y no sustituyen la consulta con un abogado
                  especializado en protección de datos. Para cualquier decisión legal, consulte con un profesional.
                </p>
              </div>
              <div className="grid sm:grid-cols-3 gap-3 mt-2">
                {[
                  { label: 'Disponibilidad', valor: 'Sin garantía de SLA — proyecto académico', icon: '⏰' },
                  { label: 'Coste', valor: 'Gratuito — sin actividad comercial', icon: '💰' },
                  { label: 'Soporte', valor: 'contacto@smartaudits.local', icon: '📧' },
                ].map((item, i) => (
                  <div key={i} className="p-3 bg-slate-50 border border-slate-200 rounded-xl text-center">
                    <span className="text-xl block mb-1">{item.icon}</span>
                    <p className="text-xs font-semibold text-slate-700 mb-0.5">{item.label}</p>
                    <p className="text-xs text-slate-500">{item.valor}</p>
                  </div>
                ))}
              </div>
            </div>
          )}

          {tabActiva === 'obligaciones' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">
                El uso de SmartAudits implica las siguientes obligaciones para el usuario, organizadas
                por fase de uso de la plataforma.
              </p>
              {obligacionesUsuario.map((seccion, i) => (
                <div key={i} className="rounded-xl border border-slate-200 overflow-hidden">
                  <div className="bg-slate-50 px-4 py-3">
                    <p className="text-sm font-bold text-slate-900">{seccion.titulo}</p>
                  </div>
                  <div className="p-4">
                    <div className="grid sm:grid-cols-2 gap-1.5">
                      {seccion.items.map((item, j) => (
                        <div key={j} className="flex items-start space-x-2 p-2.5 bg-slate-50 rounded-lg">
                          <svg className="w-3.5 h-3.5 text-primary-500 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                          </svg>
                          <span className="text-xs text-slate-600 leading-relaxed">{item}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
              ))}
              <div className="p-4 bg-slate-50 border border-slate-200 rounded-xl">
                <p className="text-xs font-semibold text-slate-700 mb-2">Registro de usuarios menores de edad</p>
                <p className="text-xs text-slate-600 leading-relaxed">
                  Conforme al Art. 7 de la LOPDGDD (Ley Orgánica 3/2018), SmartAudits está dirigido a mayores
                  de <strong>14 años</strong>. Los menores de 14 años necesitan el consentimiento de sus padres
                  o tutores legales para registrarse. Al registrarte, declaras tener al menos 14 años o contar
                  con el consentimiento parental requerido.
                </p>
              </div>
            </div>
          )}

          {tabActiva === 'usos' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">
                A continuación se detalla qué usos están permitidos y cuáles están prohibidos en SmartAudits.
              </p>
              <div className="grid sm:grid-cols-2 gap-3">
                <div>
                  <p className="text-sm font-bold text-green-800 mb-3 flex items-center space-x-2">
                    <span className="w-5 h-5 bg-green-500 rounded-full flex items-center justify-center">
                      <svg className="w-3 h-3 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" /></svg>
                    </span>
                    <span>Usos permitidos</span>
                  </p>
                  <div className="space-y-1.5">
                    {usosPermitidos.filter(u => u.permitido).map((item, i) => (
                      <div key={i} className="flex items-start space-x-2 p-2.5 bg-green-50 border border-green-100 rounded-lg">
                        <span className="text-green-500 flex-shrink-0 text-xs mt-0.5 font-bold">✓</span>
                        <span className="text-xs text-green-800 leading-relaxed">{item.texto}</span>
                      </div>
                    ))}
                  </div>
                </div>
                <div>
                  <p className="text-sm font-bold text-red-800 mb-3 flex items-center space-x-2">
                    <span className="w-5 h-5 bg-red-500 rounded-full flex items-center justify-center">
                      <svg className="w-3 h-3 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M6 18L18 6M6 6l12 12" /></svg>
                    </span>
                    <span>Usos prohibidos</span>
                  </p>
                  <div className="space-y-1.5">
                    {usosPermitidos.filter(u => !u.permitido).map((item, i) => (
                      <div key={i} className="flex items-start space-x-2 p-2.5 bg-red-50 border border-red-100 rounded-lg">
                        <span className="text-red-500 flex-shrink-0 text-xs mt-0.5 font-bold">✗</span>
                        <span className="text-xs text-red-800 leading-relaxed">{item.texto}</span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
              <div className="p-4 bg-slate-50 border border-slate-200 rounded-xl">
                <p className="text-xs font-semibold text-slate-700 mb-1">Consecuencias del incumplimiento</p>
                <p className="text-xs text-slate-600 leading-relaxed">
                  El incumplimiento de estas condiciones puede dar lugar a la desactivación inmediata de la cuenta
                  por parte de un administrador, sin previo aviso en casos graves. El autor se reserva el derecho
                  a ejercer las acciones legales que correspondan ante usos ilícitos o que causen daños a terceros.
                </p>
              </div>
            </div>
          )}

          {tabActiva === 'baja' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">
                SmartAudits implementa un sistema de <strong>baja lógica</strong> (soft delete): las cuentas
                desactivadas quedan inaccesibles pero los datos se conservan por razones de trazabilidad del sistema.
                Para la supresión completa de datos, debes solicitarlo expresamente.
              </p>
              <div className="space-y-3">
                {procesosBaja.map((proceso, i) => (
                  <div key={i} className={`rounded-xl border overflow-hidden ${proceso.tipo === 'usuario' ? 'border-primary-200' : 'border-slate-200'}`}>
                    <div className={`px-4 py-3 flex items-center space-x-3 ${proceso.tipo === 'usuario' ? 'bg-primary-50' : 'bg-slate-50'}`}>
                      <div className={`w-7 h-7 rounded-full flex items-center justify-center font-bold text-sm flex-shrink-0 ${proceso.tipo === 'usuario' ? 'bg-primary-600 text-white' : 'bg-slate-400 text-white'}`}>
                        {proceso.paso}
                      </div>
                      <div>
                        <p className="text-sm font-bold text-slate-900">{proceso.titulo}</p>
                        <span className={`text-xs font-medium ${proceso.tipo === 'usuario' ? 'text-primary-600' : 'text-slate-500'}`}>
                          {proceso.tipo === 'usuario' ? 'Acción del usuario' : 'Acción del administrador'}
                        </span>
                      </div>
                    </div>
                    <div className="p-4 space-y-2">
                      <p className="text-xs text-slate-600 leading-relaxed">{proceso.desc}</p>
                      <div className={`p-2.5 rounded-lg ${proceso.tipo === 'usuario' ? 'bg-primary-50 border border-primary-100' : 'bg-slate-50 border border-slate-100'}`}>
                        <p className={`text-xs leading-relaxed ${proceso.tipo === 'usuario' ? 'text-primary-800' : 'text-slate-700'}`}>
                          <span className="font-semibold">Detalle:</span> {proceso.detalle}
                        </p>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
              <div className="p-4 bg-green-50 border border-green-200 rounded-xl">
                <p className="text-xs font-semibold text-green-800 mb-1">Tus derechos en la baja</p>
                <p className="text-xs text-green-700 leading-relaxed">
                  La baja no elimina automáticamente tus datos personales. Si deseas ejercer tu <strong>derecho de supresión
                  (Art. 17 RGPD)</strong>, debes solicitarlo expresamente. Responderemos en el plazo máximo de 1 mes.
                  Consulta la{' '}
                  <span className="underline font-medium">Política de Privacidad</span>{' '}
                  para más información sobre cómo ejercer tus derechos.
                </p>
              </div>
            </div>
          )}

        </div>
      </div>

      {/* Propiedad intelectual y legislación */}
      <div className="grid sm:grid-cols-2 gap-4">
        <div className="card">
          <h2 className="text-base font-bold text-slate-900 mb-3 pb-3 border-b border-slate-100">Propiedad intelectual</h2>
          <p className="text-slate-600 text-sm leading-relaxed mb-3">
            El código fuente, diseño, lógica de análisis y contenidos de SmartAudits son propiedad de su autor
            y están protegidos por la normativa de propiedad intelectual vigente (RDL 1/1996).
          </p>
          <p className="text-slate-600 text-sm leading-relaxed">
            El usuario conserva todos los derechos sobre los textos que introduce para ser analizados.
            SmartAudits <strong>no cede, vende ni comparte</strong> dichos textos con ningún tercero.
            El análisis se realiza exclusivamente en el servidor propio.
          </p>
        </div>
        <div className="card">
          <h2 className="text-base font-bold text-slate-900 mb-3 pb-3 border-b border-slate-100">Legislación aplicable</h2>
          <p className="text-slate-600 text-sm leading-relaxed mb-3">
            Estas condiciones se rigen por la legislación española y europea. Para cualquier controversia,
            las partes se someten a los juzgados y tribunales de <strong>Madrid</strong>.
          </p>
          <div className="space-y-1.5">
            {[
              { ley: 'LSSI-CE', url: 'https://www.boe.es/buscar/act.php?id=BOE-A-2002-13758' },
              { ley: 'RGPD (UE) 2016/679', url: 'https://eur-lex.europa.eu/legal-content/ES/TXT/?uri=CELEX%3A32016R0679' },
              { ley: 'LOPDGDD 3/2018', url: 'https://www.boe.es/buscar/act.php?id=BOE-A-2018-16673' },
              { ley: 'Ley 10/2025', url: 'https://www.boe.es/eli/es/l/2025/12/26/10' },
            ].map((item) => (
              <a key={item.ley} href={item.url} target="_blank" rel="noopener noreferrer"
                className="flex items-center justify-between p-2 bg-slate-50 border border-slate-200 rounded-lg hover:border-primary-300 hover:bg-primary-50 transition-colors group">
                <span className="text-xs font-medium text-slate-700 group-hover:text-primary-700">{item.ley}</span>
                <svg className="w-3.5 h-3.5 text-slate-400 group-hover:text-primary-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
                </svg>
              </a>
            ))}
          </div>
        </div>
      </div>

    </div>
  )
}

export default CondicionesUso
