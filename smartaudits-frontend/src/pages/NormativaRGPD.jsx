import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import NormativaNav from '../components/NormativaNav'

const NormativaRGPD = () => {
  const navigate = useNavigate()
  const [tabActiva, setTabActiva] = useState('principios')

  const principios = [
    { num: '1', nombre: 'Licitud, lealtad y transparencia', articulo: 'Art. 5.1.a', desc: 'Los datos deben tratarse de forma lícita, leal y transparente en relación con el interesado. El responsable debe poder demostrar que el tratamiento tiene una base legal válida y que el interesado conoce cómo se tratan sus datos.', ejemplo: 'Una web que usa los emails de sus clientes para enviarles publicidad sin haberlo informado previamente infringe este principio, aunque tenga una base legal para el tratamiento principal.', reglas: ['R04', 'R05', 'R12'] },
    { num: '2', nombre: 'Limitación de la finalidad', articulo: 'Art. 5.1.b', desc: 'Los datos deben recogerse con fines determinados, explícitos y legítimos, y no ser tratados posteriormente de forma incompatible con dichos fines.', ejemplo: 'Una empresa que recoge el email para gestionar una compra no puede usarlo luego para perfilado publicitario sin informar de ello y obtener una base legal independiente.', reglas: ['R04'] },
    { num: '3', nombre: 'Minimización de datos', articulo: 'Art. 5.1.c', desc: 'Los datos deben ser adecuados, pertinentes y limitados a lo necesario en relación con los fines para los que son tratados. Solo se deben recoger los datos estrictamente necesarios para cada finalidad.', ejemplo: 'Un formulario de contacto que pide fecha de nacimiento, NIF y teléfono cuando solo necesita nombre y email para responder una consulta infringe este principio.', reglas: ['R04'] },
    { num: '4', nombre: 'Exactitud', articulo: 'Art. 5.1.d', desc: 'Los datos deben ser exactos y, si fuera necesario, actualizados. Deben adoptarse medidas razonables para suprimir o rectificar sin dilación los datos inexactos.', ejemplo: 'Un fichero de clientes con direcciones postales obsoletas que se sigue usando para envíos físicos sin mecanismo de actualización infringe este principio.', reglas: ['R06'] },
    { num: '5', nombre: 'Limitación del plazo de conservación', articulo: 'Art. 5.1.e', desc: 'Los datos deben mantenerse de forma que permita identificar a los interesados durante no más tiempo del necesario para los fines del tratamiento.', ejemplo: 'Conservar datos de candidatos rechazados en un proceso de selección de forma indefinida sin base legal infringe este principio. El plazo recomendado por la AEPD es de 1 año.', reglas: ['R07'] },
    { num: '6', nombre: 'Integridad y confidencialidad', articulo: 'Art. 5.1.f', desc: 'Los datos deben tratarse de tal manera que se garantice una seguridad adecuada, incluida la protección contra el tratamiento no autorizado o ilícito y contra su pérdida, destrucción o daño accidental.', ejemplo: 'Almacenar contraseñas en texto plano, no usar HTTPS o no controlar los accesos al sistema de datos son infracciones directas de este principio.', reglas: ['R15', 'R18'] },
    { num: '7', nombre: 'Responsabilidad proactiva (Accountability)', articulo: 'Art. 5.2', desc: 'El responsable del tratamiento debe ser capaz de demostrar el cumplimiento de todos los principios anteriores. No basta con cumplir — hay que poder demostrarlo con registros, evaluaciones de impacto y contratos con encargados.', ejemplo: 'Una empresa que afirma cumplir el RGPD pero no tiene registro de actividades de tratamiento ni documenta sus bases legales infringe este principio.', reglas: ['R01', 'R03'] },
  ]

  const derechos = [
    { letra: 'A', nombre: 'Acceso', articulo: 'Art. 15', color: 'bg-blue-500', desc: 'Derecho a obtener confirmación de si se están tratando datos que te conciernen y, en tal caso, acceder a dichos datos e información sobre fines, categorías, destinatarios, plazos y origen.', plazo: '1 mes (prorrogable 2 meses en casos complejos)', como: 'Solicitud por escrito al responsable. El acceso es gratuito salvo solicitudes manifiestamente infundadas o excesivas.' },
    { letra: 'R', nombre: 'Rectificación', articulo: 'Art. 16', color: 'bg-green-500', desc: 'Derecho a obtener sin dilación indebida la rectificación de los datos inexactos que te conciernan, así como la completación de datos incompletos.', plazo: '1 mes', como: 'Indica qué datos son incorrectos y cuál es la información correcta. El responsable debe comunicar la rectificación a cada destinatario.' },
    { letra: 'S', nombre: 'Supresión (Derecho al olvido)', articulo: 'Art. 17', color: 'bg-red-500', desc: 'Derecho a obtener la supresión de datos cuando ya no sean necesarios, retires el consentimiento, se hayan tratado ilícitamente, o deba cumplirse una obligación legal.', plazo: '1 mes', como: 'No aplica cuando el tratamiento es necesario para ejercicio de libertad de expresión, cumplimiento de obligaciones legales o defensa de reclamaciones.' },
    { letra: 'U', nombre: 'Limitación del tratamiento', articulo: 'Art. 18', color: 'bg-yellow-500', desc: 'Derecho a obtener la limitación del tratamiento cuando impugnes la exactitud de los datos, el tratamiento sea ilícito, o hayas ejercido el derecho de oposición.', plazo: '1 mes', como: 'Durante la limitación, los datos solo pueden conservarse. El tratamiento adicional requiere tu consentimiento o es para reclamaciones.' },
    { letra: 'L', nombre: 'Portabilidad', articulo: 'Art. 20', color: 'bg-purple-500', desc: 'Derecho a recibir tus datos en un formato estructurado, de uso común y lectura mecánica (JSON, CSV…) y transmitirlos a otro responsable sin que el primero lo impida.', plazo: '1 mes', como: 'Solo aplica cuando el tratamiento se basa en consentimiento o contrato y se efectúa por medios automatizados.' },
    { letra: 'I', nombre: 'Oposición', articulo: 'Art. 21', color: 'bg-orange-500', desc: 'Derecho a oponerte en cualquier momento al tratamiento basado en interés legítimo o interés público, incluido el perfilado. Oposición absoluta al tratamiento con fines de marketing directo.', plazo: 'Inmediato para marketing directo. Para otros fines el responsable puede alegar motivos legítimos imperiosos.', como: 'Para marketing directo: oposición incondicional. Para otros tratamientos: el responsable puede continuar si demuestra motivos legítimos prevalentes.' },
    { letra: 'P', nombre: 'No decisiones automatizadas', articulo: 'Art. 22', color: 'bg-slate-600', desc: 'Derecho a no ser objeto de una decisión basada únicamente en tratamiento automatizado, incluido el perfilado, que produzca efectos jurídicos significativos sobre ti.', plazo: '1 mes', como: 'Excepciones: contrato, ley o consentimiento explícito. En esos casos tienes derecho a intervención humana, expresar tu punto de vista e impugnar la decisión.' },
  ]

  const basesLegales = [
    { art: '6.1.a', nombre: 'Consentimiento', desc: 'El interesado ha dado su consentimiento para el tratamiento de sus datos para uno o varios fines específicos.', req: 'Debe ser libre, específico, informado, inequívoco y demostrable. No vale el silencio, casillas premarcadas ni consentimiento genérico.', ejemplos: ['Suscripción a newsletter', 'Cookies analíticas', 'Comunicaciones comerciales'], revocable: true },
    { art: '6.1.b', nombre: 'Ejecución de contrato', desc: 'El tratamiento es necesario para la ejecución de un contrato en el que el interesado es parte, o para la aplicación de medidas precontractuales a petición del interesado.', req: 'El tratamiento debe ser estrictamente necesario para la ejecución del contrato, no meramente útil o conveniente.', ejemplos: ['Datos de envío para una compra', 'Datos bancarios para procesar pago', 'Email para confirmar pedido'], revocable: false },
    { art: '6.1.c', nombre: 'Obligación legal', desc: 'El tratamiento es necesario para el cumplimiento de una obligación legal aplicable al responsable del tratamiento.', req: 'La obligación legal debe estar establecida por el Derecho de la UE o de los Estados miembros.', ejemplos: ['Datos fiscales para Hacienda', 'Registro empleados para SS', 'Conservación de facturas 4 años'], revocable: false },
    { art: '6.1.d', nombre: 'Intereses vitales', desc: 'El tratamiento es necesario para proteger intereses vitales del interesado o de otra persona física. Solo aplicable en situaciones de emergencia.', req: 'Solo cuando el interesado no puede dar su consentimiento y ninguna otra base legal es aplicable. Uso muy restringido.', ejemplos: ['Emergencias médicas', 'Catástrofes naturales', 'Riesgo vital'], revocable: false },
    { art: '6.1.e', nombre: 'Interés público', desc: 'El tratamiento es necesario para el cumplimiento de una misión realizada en interés público o en el ejercicio de poderes públicos.', req: 'Principalmente aplicable a organismos públicos. Las empresas privadas rara vez pueden invocarla.', ejemplos: ['Administraciones públicas', 'Registros oficiales', 'Estadísticas oficiales'], revocable: false },
    { art: '6.1.f', nombre: 'Interés legítimo', desc: 'El tratamiento es necesario para la satisfacción de intereses legítimos perseguidos por el responsable, siempre que no prevalezcan los derechos del interesado.', req: 'Requiere un test de ponderación documentado. No aplicable a organismos públicos en el ejercicio de sus funciones.', ejemplos: ['Seguridad de redes', 'Prevención del fraude', 'Marketing a clientes existentes'], revocable: true },
  ]

  const sanciones = [
    { nivel: 'Muy graves', importe: '20M€ o 4%', color: 'red', infracciones: ['Tratar datos sin base legal (Art. 6)', 'Vulnerar los principios del Art. 5', 'Infringir los derechos ARSULIPO (Arts. 15-22)', 'Transferencias internacionales sin garantías', 'Condiciones de consentimiento inválidas', 'Tratar categorías especiales sin excepción válida'] },
    { nivel: 'Graves', importe: '10M€ o 2%', color: 'amber', infracciones: ['No mantener registro de actividades (Art. 30)', 'No cooperar con la autoridad de control', 'No notificar brechas en plazo (Arts. 33-34)', 'No designar DPO cuando es obligatorio', 'No realizar evaluación de impacto cuando proceda', 'No aplicar medidas de seguridad adecuadas'] },
  ]

  const casosReales = [
    { empresa: 'Meta (Instagram)', pais: 'Irlanda / UE', ano: '2023', importe: '1.200M€', motivo: 'Transferencia de datos de usuarios europeos a EE.UU. sin garantías adecuadas tras la invalidación del Privacy Shield.', leccion: 'Las transferencias internacionales requieren mecanismos sólidos. Las Cláusulas Contractuales Tipo deben implementarse correctamente.' },
    { empresa: 'Amazon Europe', pais: 'Luxemburgo', ano: '2021', importe: '746M€', motivo: 'Sistema de publicidad comportamental sin consentimiento válido. Las cookies publicitarias se instalaban sin base legal adecuada.', leccion: 'El consentimiento para publicidad debe ser libre, específico e inequívoco. El modelo "seguir navegando = aceptar" no es válido.' },
    { empresa: 'Google LLC', pais: 'Francia (CNIL)', ano: '2022', importe: '150M€', motivo: 'El botón para rechazar cookies era más difícil de encontrar y usar que el de aceptar (dark pattern).', leccion: 'Aceptar y rechazar cookies deben ser igualmente accesibles. Los dark patterns son ilegales.' },
    { empresa: 'Vodafone España', pais: 'España (AEPD)', ano: '2022', importe: '8,15M€', motivo: 'Comunicaciones comerciales sin consentimiento, cesión de datos sin base legal y obstaculización del ejercicio de derechos.', leccion: 'Las comunicaciones comerciales requieren consentimiento previo. Obstaculizar el ejercicio de derechos es sancionable por sí solo.' },
  ]

  const tabs = [
    { id: 'principios', label: '7 Principios', icono: '⚖️' },
    { id: 'derechos', label: 'Derechos ARSULIPO', icono: '🛡️' },
    { id: 'bases', label: 'Bases Legales', icono: '📋' },
    { id: 'sanciones', label: 'Sanciones', icono: '⚠️' },
    { id: 'casos', label: 'Casos Reales', icono: '🔍' },
  ]

  return (
    <div className="space-y-6 max-w-5xl">

      {/* Cabecera */}
      <div className="bg-gradient-to-br from-primary-600 via-primary-800 to-slate-900 rounded-2xl px-8 py-10 relative overflow-hidden">
        <div className="absolute right-0 top-0 w-72 h-72 bg-white/5 rounded-full blur-3xl pointer-events-none"></div>
        <div className="absolute left-0 bottom-0 w-48 h-48 bg-primary-400/10 rounded-full blur-2xl pointer-events-none"></div>
        <div className="relative">
          <button onClick={() => navigate(-1)} className="flex items-center space-x-1 text-white/60 hover:text-white text-xs mb-5 transition-colors">
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
            <span>Volver</span>
          </button>
          <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-6">
            <div className="flex-1">
              <div className="inline-flex items-center space-x-2 px-3 py-1.5 bg-white/15 rounded-full mb-4">
                <span className="w-2 h-2 bg-green-400 rounded-full"></span>
                <span className="text-white text-xs font-bold tracking-wider">RGPD · En vigor desde 25 mayo 2018</span>
              </div>
              <h1 className="text-3xl font-bold text-white mb-2 leading-tight">Reglamento General de<br/>Protección de Datos</h1>
              <p className="text-white/70 text-sm leading-relaxed max-w-xl">Reglamento (UE) 2016/679 del Parlamento Europeo y del Consejo, de 27 de abril de 2016. Norma de referencia en materia de protección de datos en la Unión Europea.</p>
            </div>
            <div className="grid grid-cols-3 sm:grid-cols-1 gap-2 sm:gap-3 sm:min-w-[130px]">
              {[{ num: '99', label: 'Artículos' }, { num: '7', label: 'Principios' }, { num: '8', label: 'Derechos' }].map((s) => (
                <div key={s.label} className="text-center px-3 py-2 bg-white/10 rounded-xl">
                  <p className="text-2xl font-bold text-white">{s.num}</p>
                  <p className="text-white/60 text-xs">{s.label}</p>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* Qué es y a quién afecta */}
      <div className="card">
        <h2 className="text-base font-bold text-slate-900 mb-4 pb-3 border-b border-slate-100">¿Qué es el RGPD y a quién afecta?</h2>
        <div className="grid sm:grid-cols-2 gap-4">
          <div>
            <p className="text-slate-600 text-sm leading-relaxed mb-3">El RGPD es la norma europea de referencia en materia de protección de datos personales. Tiene <strong>aplicación directa</strong> en todos los países de la UE sin necesidad de transposición nacional, lo que lo convierte en el reglamento de privacidad más influyente del mundo.</p>
            <p className="text-slate-600 text-sm leading-relaxed">En España se complementa con la <strong>LOPDGDD (Ley Orgánica 3/2018)</strong>, que adapta ciertos aspectos al ordenamiento jurídico español: el umbral de edad para el consentimiento de menores (14 años), el régimen del DPO o el tratamiento de datos en el ámbito laboral.</p>
          </div>
          <div className="space-y-2">
            <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-2">¿A quién afecta?</p>
            {[
              { icono: '🏢', texto: 'Cualquier empresa u organización que trate datos de personas físicas en la UE' },
              { icono: '🌍', texto: 'Empresas fuera de la UE que ofrezcan bienes/servicios a personas en la UE o monitoricen su comportamiento' },
              { icono: '👤', texto: 'Tanto si actúas como responsable del tratamiento como si eres encargado' },
              { icono: '📱', texto: 'Apps, webs, tiendas físicas, empleadores — cualquier sector y tamaño' },
            ].map((item, i) => (
              <div key={i} className="flex items-start space-x-2 p-2.5 bg-slate-50 rounded-xl">
                <span className="text-base flex-shrink-0">{item.icono}</span>
                <p className="text-xs text-slate-600 leading-relaxed">{item.texto}</p>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Responsable vs Encargado */}
      <div className="card">
        <h2 className="text-base font-bold text-slate-900 mb-4 pb-3 border-b border-slate-100">Figuras clave: Responsable vs Encargado del tratamiento</h2>
        <div className="grid sm:grid-cols-2 gap-4">
          <div className="p-4 bg-primary-50 border border-primary-100 rounded-xl">
            <p className="text-sm font-bold text-primary-900 mb-2">Responsable del tratamiento</p>
            <p className="text-xs text-primary-800 leading-relaxed mb-2">Persona física o jurídica que <strong>determina los fines y los medios</strong> del tratamiento. Decide para qué se usan los datos y cómo.</p>
            <ul className="space-y-1">
              {['Informar al interesado', 'Establecer bases legales', 'Gestionar el ejercicio de derechos', 'Notificar brechas a la AEPD', 'Firmar contratos con encargados'].map((o, i) => (
                <li key={i} className="text-xs text-primary-700 flex items-start space-x-1.5"><span className="flex-shrink-0">•</span><span>{o}</span></li>
              ))}
            </ul>
            <p className="text-xs text-primary-600 mt-2 italic">Ej: La tienda online que vende productos y recoge datos de sus clientes.</p>
          </div>
          <div className="p-4 bg-slate-50 border border-slate-200 rounded-xl">
            <p className="text-sm font-bold text-slate-800 mb-2">Encargado del tratamiento</p>
            <p className="text-xs text-slate-600 leading-relaxed mb-2">Persona física o jurídica que <strong>trata datos por cuenta del responsable</strong>. Ejecuta el tratamiento según instrucciones del responsable, sin decidir los fines.</p>
            <ul className="space-y-1">
              {['Tratar datos solo según instrucciones', 'Firmar contrato de encargo (Art. 28)', 'Garantizar confidencialidad del personal', 'Notificar brechas al responsable', 'Colaborar en el ejercicio de derechos'].map((o, i) => (
                <li key={i} className="text-xs text-slate-600 flex items-start space-x-1.5"><span className="flex-shrink-0">•</span><span>{o}</span></li>
              ))}
            </ul>
            <p className="text-xs text-slate-500 mt-2 italic">Ej: La empresa de hosting que almacena los datos de la tienda online.</p>
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="card !p-0 overflow-hidden">
        <div className="flex overflow-x-auto border-b border-slate-100">
          {tabs.map((tab) => (
            <button key={tab.id} onClick={() => setTabActiva(tab.id)}
              className={`flex items-center space-x-2 px-4 py-3.5 text-sm font-medium whitespace-nowrap transition-colors border-b-2 -mb-px ${tabActiva === tab.id ? 'border-primary-600 text-primary-700 bg-primary-50/50' : 'border-transparent text-slate-500 hover:text-slate-700 hover:bg-slate-50'}`}>
              <span>{tab.icono}</span><span>{tab.label}</span>
            </button>
          ))}
        </div>
        <div className="p-6">

          {tabActiva === 'principios' && (
            <div className="space-y-3">
              <p className="text-sm text-slate-500 mb-4">El Art. 5 RGPD establece 7 principios que rigen <strong>todo</strong> tratamiento de datos personales. Su incumplimiento puede acarrear sanciones del nivel más alto (hasta 20M€ o 4%).</p>
              {principios.map((p) => (
                <div key={p.num} className="p-4 bg-slate-50 rounded-xl border border-slate-100 hover:border-primary-200 hover:bg-primary-50/20 transition-colors">
                  <div className="flex items-start justify-between gap-3 mb-2">
                    <div className="flex items-center space-x-3">
                      <div className="w-7 h-7 bg-primary-600 text-white rounded-lg flex items-center justify-center font-bold text-xs flex-shrink-0">{p.num}</div>
                      <div>
                        <p className="text-sm font-bold text-slate-900">{p.nombre}</p>
                        <span className="text-xs text-primary-600 font-medium">{p.articulo}</span>
                      </div>
                    </div>
                    <div className="flex gap-1 flex-shrink-0">{p.reglas.map((r) => <span key={r} className="text-xs bg-slate-200 text-slate-600 px-1.5 py-0.5 rounded font-mono">{r}</span>)}</div>
                  </div>
                  <p className="text-xs text-slate-600 leading-relaxed mb-2">{p.desc}</p>
                  <div className="bg-amber-50 border border-amber-100 rounded-lg p-2.5">
                    <p className="text-xs text-amber-800"><span className="font-semibold">Ejemplo práctico:</span> {p.ejemplo}</p>
                  </div>
                </div>
              ))}
            </div>
          )}

          {tabActiva === 'derechos' && (
            <div className="space-y-3">
              <p className="text-sm text-slate-500 mb-4">Los Arts. 15-22 RGPD reconocen 7 derechos fundamentales. El responsable debe atender solicitudes en el plazo máximo de <strong>1 mes</strong>. La respuesta es gratuita.</p>
              {derechos.map((d) => (
                <div key={d.letra} className="p-4 bg-slate-50 rounded-xl border border-slate-100 hover:border-slate-300 transition-colors">
                  <div className="flex items-start space-x-3 mb-2">
                    <div className={`w-9 h-9 ${d.color} text-white rounded-xl flex items-center justify-center font-bold text-sm flex-shrink-0`}>{d.letra}</div>
                    <div className="flex-1">
                      <div className="flex items-center space-x-2 mb-1">
                        <p className="text-sm font-bold text-slate-900">{d.nombre}</p>
                        <span className="text-xs text-primary-600 font-medium bg-primary-50 px-2 py-0.5 rounded-full">{d.articulo}</span>
                      </div>
                      <p className="text-xs text-slate-600 leading-relaxed">{d.desc}</p>
                    </div>
                  </div>
                  <div className="ml-12 grid sm:grid-cols-2 gap-2 mt-2">
                    <div className="bg-blue-50 border border-blue-100 rounded-lg p-2"><p className="text-xs text-blue-800"><span className="font-semibold">Plazo:</span> {d.plazo}</p></div>
                    <div className="bg-green-50 border border-green-100 rounded-lg p-2"><p className="text-xs text-green-800"><span className="font-semibold">Cómo ejercerlo:</span> {d.como}</p></div>
                  </div>
                </div>
              ))}
            </div>
          )}

          {tabActiva === 'bases' && (
            <div className="space-y-3">
              <p className="text-sm text-slate-500 mb-4">El Art. 6 RGPD establece 6 bases legales que legitiman el tratamiento. <strong>Cada tratamiento debe tener una base legal identificada y documentada antes de iniciarse.</strong></p>
              {basesLegales.map((b) => (
                <div key={b.art} className="p-4 bg-slate-50 rounded-xl border border-slate-100 hover:border-primary-200 transition-colors">
                  <div className="flex items-start justify-between gap-3 mb-2">
                    <div className="flex items-center space-x-3">
                      <span className="text-xs font-bold text-primary-700 bg-primary-100 px-2 py-1 rounded-lg font-mono">{b.art}</span>
                      <p className="text-sm font-bold text-slate-900">{b.nombre}</p>
                    </div>
                    {b.revocable && <span className="text-xs bg-amber-100 text-amber-700 px-2 py-0.5 rounded-full font-medium flex-shrink-0">Revocable</span>}
                  </div>
                  <p className="text-xs text-slate-600 leading-relaxed mb-2">{b.desc}</p>
                  <div className="bg-slate-100 rounded-lg p-2.5 mb-2"><p className="text-xs text-slate-700"><span className="font-semibold">Requisitos:</span> {b.req}</p></div>
                  <div className="flex flex-wrap gap-1.5">
                    <span className="text-xs text-slate-500 font-medium">Ejemplos:</span>
                    {b.ejemplos.map((e, i) => <span key={i} className="text-xs bg-white border border-slate-200 text-slate-600 px-2 py-0.5 rounded-full">{e}</span>)}
                  </div>
                </div>
              ))}
            </div>
          )}

          {tabActiva === 'sanciones' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">El Art. 83 RGPD establece un sistema sancionador de dos niveles. Las multas se calculan sobre el volumen de negocio <strong>global</strong> anual, aplicándose el importe mayor entre la cuantía fija y el porcentaje.</p>
              {sanciones.map((s) => (
                <div key={s.nivel} className={`p-5 rounded-xl border ${s.color === 'red' ? 'bg-red-50 border-red-200' : 'bg-amber-50 border-amber-200'}`}>
                  <div className="flex items-center justify-between mb-4">
                    <p className={`text-sm font-bold ${s.color === 'red' ? 'text-red-900' : 'text-amber-900'}`}>Infracciones {s.nivel}</p>
                    <div className="text-right">
                      <p className={`text-2xl font-bold ${s.color === 'red' ? 'text-red-700' : 'text-amber-700'}`}>{s.importe}</p>
                      <p className={`text-xs ${s.color === 'red' ? 'text-red-600' : 'text-amber-600'}`}>del volumen de negocio anual global</p>
                    </div>
                  </div>
                  <div className="grid sm:grid-cols-2 gap-1.5">
                    {s.infracciones.map((inf, i) => (
                      <div key={i} className={`flex items-start space-x-2 text-xs p-2 rounded-lg ${s.color === 'red' ? 'bg-red-100 text-red-800' : 'bg-amber-100 text-amber-800'}`}>
                        <span className="flex-shrink-0 mt-0.5">⚡</span><span>{inf}</span>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
              <div className="p-4 bg-slate-50 border border-slate-200 rounded-xl">
                <p className="text-xs font-semibold text-slate-700 mb-2">Criterios de graduación (Art. 83.2 RGPD)</p>
                <div className="grid sm:grid-cols-2 gap-1.5">
                  {['Naturaleza, gravedad y duración de la infracción', 'Intencionalidad o negligencia', 'Medidas adoptadas para reducir daños', 'Grado de responsabilidad del responsable', 'Infracciones anteriores', 'Grado de cooperación con la autoridad', 'Categorías de datos afectados', 'Número de interesados afectados', 'Beneficios obtenidos de la infracción', 'Comunicación de la infracción a los interesados'].map((c, i) => (
                    <p key={i} className="text-xs text-slate-600 flex items-start space-x-1.5"><span className="text-slate-400 flex-shrink-0">•</span><span>{c}</span></p>
                  ))}
                </div>
              </div>
            </div>
          )}

          {tabActiva === 'casos' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">Casos reales de sanciones por incumplimiento del RGPD. Demuestran que la norma se aplica con rigor, incluso a las mayores empresas del mundo.</p>
              {casosReales.map((c, i) => (
                <div key={i} className="p-4 bg-slate-50 rounded-xl border border-slate-100 hover:border-red-200 transition-colors">
                  <div className="flex items-start justify-between gap-3 mb-3">
                    <div>
                      <p className="text-sm font-bold text-slate-900">{c.empresa}</p>
                      <p className="text-xs text-slate-500">{c.pais} · {c.ano}</p>
                    </div>
                    <span className="text-lg font-bold text-red-600 flex-shrink-0">{c.importe}</span>
                  </div>
                  <p className="text-xs text-slate-600 leading-relaxed mb-2"><span className="font-semibold text-slate-700">Motivo:</span> {c.motivo}</p>
                  <div className="bg-primary-50 border border-primary-100 rounded-lg p-2.5">
                    <p className="text-xs text-primary-800"><span className="font-semibold">Lección:</span> {c.leccion}</p>
                  </div>
                </div>
              ))}
            </div>
          )}

        </div>
      </div>

      {/* DPO */}
      <div className="card">
        <h2 className="text-base font-bold text-slate-900 mb-4 pb-3 border-b border-slate-100">El Delegado de Protección de Datos (DPO) — Art. 37</h2>
        <div className="grid sm:grid-cols-3 gap-4">
          <div className="p-3 bg-red-50 border border-red-100 rounded-xl">
            <p className="text-xs font-bold text-red-800 mb-2">Designación obligatoria</p>
            <ul className="space-y-1">
              {['Organismos y autoridades públicas', 'Tratamientos a gran escala de categorías especiales (salud, religión, orientación sexual…)', 'Observación sistemática a gran escala de personas (videovigilancia masiva)'].map((i, idx) => (
                <li key={idx} className="text-xs text-red-700 flex items-start space-x-1.5"><span className="flex-shrink-0">•</span><span>{i}</span></li>
              ))}
            </ul>
          </div>
          <div className="p-3 bg-green-50 border border-green-100 rounded-xl">
            <p className="text-xs font-bold text-green-800 mb-2">Funciones principales</p>
            <ul className="space-y-1">
              {['Informar y asesorar al responsable', 'Supervisar el cumplimiento del RGPD', 'Asesorar en evaluaciones de impacto (EIPD)', 'Cooperar con la AEPD', 'Punto de contacto con los interesados'].map((i, idx) => (
                <li key={idx} className="text-xs text-green-700 flex items-start space-x-1.5"><span className="flex-shrink-0">•</span><span>{i}</span></li>
              ))}
            </ul>
          </div>
          <div className="p-3 bg-primary-50 border border-primary-100 rounded-xl">
            <p className="text-xs font-bold text-primary-800 mb-2">Garantías del DPO</p>
            <ul className="space-y-1">
              {['Independencia funcional total', 'No puede ser destituido por ejercer sus funciones', 'Conocimientos especializados requeridos', 'Puede ser interno o externo a la organización', 'Sus datos de contacto deben publicarse y comunicarse a la AEPD'].map((i, idx) => (
                <li key={idx} className="text-xs text-primary-700 flex items-start space-x-1.5"><span className="flex-shrink-0">•</span><span>{i}</span></li>
              ))}
            </ul>
          </div>
        </div>
      </div>

      {/* Botones */}
      <div className="flex flex-col sm:flex-row items-center justify-center gap-3">
        <a href="https://eur-lex.europa.eu/legal-content/ES/TXT/?uri=CELEX%3A32016R0679" target="_blank" rel="noopener noreferrer"
          className="inline-flex items-center space-x-2 px-5 py-3 bg-primary-600 hover:bg-primary-700 text-white rounded-xl font-semibold text-sm transition-colors shadow-soft hover:shadow-medium">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" /></svg>
          <span>Texto oficial en EUR-Lex</span>
        </a>
        <a href="https://www.aepd.es/reglamentos/rgpd" target="_blank" rel="noopener noreferrer"
          className="inline-flex items-center space-x-2 px-5 py-3 bg-white border border-slate-200 hover:border-slate-300 text-slate-700 rounded-xl font-semibold text-sm transition-colors shadow-soft">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" /></svg>
          <span>Guía RGPD en AEPD</span>
        </a>
      </div>
      <NormativaNav />
    </div>
  )
}

export default NormativaRGPD
