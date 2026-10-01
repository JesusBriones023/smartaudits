import ExternalNormativeLink from '../components/ExternalNormativeLink'
import { useNavigate } from 'react-router-dom'

const AvisoLegal = () => {
  const navigate = useNavigate()

  const stackTecnologico = [
    { capa: 'Frontend', tecnologias: ['React 18', 'Vite', 'Tailwind CSS', 'React Router v6', 'Axios'] },
    { capa: 'Backend', tecnologias: ['Java 17', 'Spring Boot 3', 'Spring Security', 'JWT', 'Spring Data JPA'] },
    { capa: 'Base de datos', tecnologias: ['MariaDB', 'Docker', 'Adminer'] },
    { capa: 'Análisis legal', tecnologias: ['Motor propio en Java', 'Sin APIs externas de IA', '19 reglas normativas'] },
  ]

  const normativaAplicable = [
    { ley: 'LSSI-CE', desc: 'Ley 34/2002 — Art. 10: Información general obligatoria del prestador', url: 'https://www.boe.es/buscar/act.php?id=BOE-A-2002-13758', color: 'accent' },
    { ley: 'RGPD', desc: 'Reglamento (UE) 2016/679 — Protección de datos personales', url: 'https://eur-lex.europa.eu/legal-content/ES/TXT/?uri=CELEX%3A32016R0679', color: 'primary' },
    { ley: 'LOPDGDD', desc: 'Ley Orgánica 3/2018 — Adaptación española del RGPD', url: 'https://www.boe.es/buscar/act.php?id=BOE-A-2018-16673', color: 'slate' },
    { ley: 'LPI', desc: 'Real Decreto Legislativo 1/1996 — Propiedad Intelectual', url: 'https://www.boe.es/buscar/act.php?id=BOE-A-1996-8930', color: 'slate' },
    { ley: 'Ley 10/2025', desc: 'Ley de Atención a la Clientela — Modifica LOPDGDD y Telecomunicaciones', url: 'https://www.boe.es/eli/es/l/2025/12/26/10', color: 'emerald' },
  ]

  return (
    <div className="space-y-6 max-w-5xl">

      {/* Cabecera */}
      <div className="bg-gradient-to-br from-slate-800 via-slate-900 to-primary-900 rounded-2xl px-8 py-10 relative overflow-hidden">
        <div className="absolute right-0 top-0 w-72 h-72 bg-white/5 rounded-full blur-3xl pointer-events-none"></div>
        <div className="absolute left-0 bottom-0 w-48 h-48 bg-primary-500/10 rounded-full blur-2xl pointer-events-none"></div>
        <div className="relative">
          <div className="inline-flex items-center space-x-2 px-3 py-1.5 bg-white/15 rounded-full mb-4">
            <span className="w-2 h-2 bg-green-400 rounded-full"></span>
            <span className="text-white text-xs font-bold tracking-wider">SmartAudits · Aviso Legal · Actualizado mayo 2026</span>
          </div>
          <h1 className="text-3xl font-bold text-white mb-2 leading-tight">Aviso Legal</h1>
          <p className="text-white/70 text-sm leading-relaxed max-w-2xl">
            En cumplimiento del Art. 10 de la Ley 34/2002, de Servicios de la Sociedad de la Información
            y del Comercio Electrónico (LSSI-CE), se pone a disposición del usuario la siguiente información
            sobre el titular de la plataforma SmartAudits.
          </p>
          <p className="text-white/40 text-xs mt-3">Última actualización: 20 de mayo de 2026</p>
        </div>
      </div>

      {/* Datos identificativos — card destacada */}
      <div className="card border-2 border-primary-100">
        <div className="flex items-center space-x-3 mb-5 pb-4 border-b border-slate-100">
          <div className="w-10 h-10 bg-primary-600 rounded-xl flex items-center justify-center flex-shrink-0">
            <svg className="w-5 h-5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4" />
            </svg>
          </div>
          <div>
            <h2 className="text-base font-bold text-slate-900">1. Datos identificativos del titular</h2>
            <p className="text-xs text-slate-500">Información exigida por el Art. 10 LSSI-CE</p>
          </div>
        </div>
        <div className="grid sm:grid-cols-2 gap-4">
          <div className="space-y-3">
            {[
              { label: 'Denominación del proyecto', valor: 'SmartAudits' },
              { label: 'Naturaleza jurídica', valor: 'Proyecto educativo — Trabajo de Fin de Ciclo (TFC)' },
              { label: 'Titulación', valor: 'Grado Superior en Desarrollo de Aplicaciones Web (DAW)' },
              { label: 'Centro educativo', valor: 'ESIC University, Madrid' },
              { label: 'Autor / Desarrollador', valor: 'Jesús Briones' },
            ].map((item, i) => (
              <div key={i} className="flex flex-col gap-0.5 py-2 border-b border-slate-50 last:border-0">
                <span className="text-xs font-semibold text-slate-400 uppercase tracking-wide">{item.label}</span>
                <span className="text-sm text-slate-800 font-medium">{item.valor}</span>
              </div>
            ))}
          </div>
          <div className="space-y-3">
            {[
              { label: 'Correo de contacto', valor: 'contacto@smartaudits.local' },
              { label: 'Actividad comercial', valor: 'Ninguna — proyecto estrictamente académico' },
              { label: 'Ámbito de aplicación', valor: 'España — legislación española y europea' },
              { label: 'Año de desarrollo', valor: '2025-2026' },
              { label: 'Curso académico', valor: '2025/2026' },
            ].map((item, i) => (
              <div key={i} className="flex flex-col gap-0.5 py-2 border-b border-slate-50 last:border-0">
                <span className="text-xs font-semibold text-slate-400 uppercase tracking-wide">{item.label}</span>
                <span className="text-sm text-slate-800 font-medium">{item.valor}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Objeto y descripción de la plataforma */}
      <div className="card">
        <h2 className="text-base font-bold text-slate-900 mb-4 pb-3 border-b border-slate-100">2. Objeto y descripción de la plataforma</h2>
        <p className="text-slate-600 text-sm leading-relaxed mb-4">
          SmartAudits es una plataforma web de auditoría legal automatizada desarrollada íntegramente como
          Trabajo de Fin de Ciclo del Grado Superior en Desarrollo de Aplicaciones Web (DAW) en ESIC University.
          Su finalidad es demostrar la integración de tecnologías web modernas en un contexto de cumplimiento normativo.
        </p>
        <div className="grid sm:grid-cols-2 gap-3 mb-4">
          {[
            { icono: '⚖️', titulo: 'Auditoría legal', desc: 'Análisis de textos legales (políticas de privacidad, avisos legales, cookies) frente a RGPD, LOPDGDD, LSSI-CE y normativa vigente.' },
            { icono: '🤖', titulo: 'Motor propio de IA', desc: 'El análisis se realiza mediante un motor legal desarrollado en Java con 19 reglas normativas. Sin dependencias de APIs externas de inteligencia artificial.' },
            { icono: '📊', titulo: 'Informes detallados', desc: 'Generación de informes con puntuación de riesgo, incidencias por severidad, recomendaciones y textos sugeridos.' },
            { icono: '🔒', titulo: 'Gestión de usuarios', desc: 'Sistema de autenticación con roles (CLIENTE/ADMIN), historial de auditorías y gestión de cuentas con baja lógica.' },
          ].map((item, i) => (
            <div key={i} className="flex items-start space-x-3 p-3 bg-slate-50 border border-slate-100 rounded-xl">
              <span className="text-xl flex-shrink-0">{item.icono}</span>
              <div>
                <p className="text-sm font-semibold text-slate-800 mb-1">{item.titulo}</p>
                <p className="text-xs text-slate-600 leading-relaxed">{item.desc}</p>
              </div>
            </div>
          ))}
        </div>
        <div className="p-3 bg-amber-50 border border-amber-200 rounded-xl">
          <p className="text-xs text-amber-800">
            <span className="font-bold">Aviso importante:</span> Los análisis e informes generados por SmartAudits tienen
            carácter <strong>meramente orientativo y educativo</strong>. No constituyen asesoramiento jurídico profesional
            ni sustituyen la consulta con un abogado especializado en protección de datos.
            Para cualquier cuestión legal, contacta con un profesional o con la{' '}
            <a href="https://www.aepd.es" target="_blank" rel="noopener noreferrer" className="underline hover:text-amber-900">AEPD (www.aepd.es)</a>.
          </p>
        </div>
      </div>

      {/* Stack tecnológico */}
      <div className="card">
        <h2 className="text-base font-bold text-slate-900 mb-4 pb-3 border-b border-slate-100">3. Stack tecnológico</h2>
        <p className="text-slate-600 text-sm leading-relaxed mb-4">
          SmartAudits ha sido desarrollado íntegramente con tecnologías de código abierto. A continuación se detalla
          el stack tecnológico utilizado, relevante a efectos de transparencia técnica.
        </p>
        <div className="grid sm:grid-cols-2 gap-3">
          {stackTecnologico.map((capa, i) => (
            <div key={i} className="p-4 bg-slate-50 border border-slate-200 rounded-xl">
              <p className="text-xs font-bold text-slate-700 uppercase tracking-wide mb-2">{capa.capa}</p>
              <div className="flex flex-wrap gap-1.5">
                {capa.tecnologias.map((tech) => (
                  <span key={tech} className="text-xs bg-white border border-slate-200 text-slate-700 px-2 py-0.5 rounded-full font-medium">{tech}</span>
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Propiedad intelectual */}
      <div className="card">
        <h2 className="text-base font-bold text-slate-900 mb-4 pb-3 border-b border-slate-100">4. Propiedad intelectual e industrial</h2>
        <div className="grid sm:grid-cols-2 gap-4">
          <div>
            <p className="text-slate-600 text-sm leading-relaxed mb-3">
              El código fuente, diseño, arquitectura, lógica de análisis, textos, logotipos y demás elementos
              de SmartAudits son obra original de su autor y están protegidos por la normativa de propiedad
              intelectual vigente (Real Decreto Legislativo 1/1996, de 12 de abril).
            </p>
            <p className="text-slate-600 text-sm leading-relaxed">
              Queda prohibida su reproducción total o parcial, distribución, transformación o comunicación
              pública sin autorización expresa del autor, salvo para fines estrictamente académicos,
              de evaluación o revisión del trabajo.
            </p>
          </div>
          <div className="space-y-2">
            <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-2">Usos permitidos</p>
            {[
              { permitido: true, texto: 'Revisión académica por tribunal evaluador de ESIC University' },
              { permitido: true, texto: 'Consulta con fines educativos o de aprendizaje' },
              { permitido: true, texto: 'Referencia en trabajos académicos con atribución al autor' },
              { permitido: false, texto: 'Reproducción o distribución sin autorización del autor' },
              { permitido: false, texto: 'Uso comercial o explotación económica del código o diseño' },
              { permitido: false, texto: 'Presentación como trabajo propio sin atribución' },
            ].map((item, i) => (
              <div key={i} className={`flex items-start space-x-2 p-2 rounded-lg text-xs ${item.permitido ? 'bg-green-50 text-green-800' : 'bg-red-50 text-red-800'}`}>
                <span className="flex-shrink-0 font-bold">{item.permitido ? '✓' : '✗'}</span>
                <span>{item.texto}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Limitación de responsabilidad */}
      <div className="card">
        <h2 className="text-base font-bold text-slate-900 mb-4 pb-3 border-b border-slate-100">5. Limitación de responsabilidad</h2>
        <div className="space-y-3">
          {[
            { titulo: 'Carácter orientativo de los análisis', desc: 'Los informes de auditoría generados por SmartAudits son el resultado de un motor de análisis automatizado basado en reglas. Tienen carácter meramente orientativo. El autor no garantiza la exhaustividad, exactitud o actualización de los resultados para ningún caso concreto.' },
            { titulo: 'No asesoramiento jurídico', desc: 'SmartAudits no presta servicios de asesoramiento jurídico. Los informes generados no son dictámenes legales ni pueden ser utilizados como tal. Para obtener asesoramiento jurídico, consulte con un profesional del derecho colegiado y especializado en protección de datos.' },
            { titulo: 'Disponibilidad del servicio', desc: 'SmartAudits es un proyecto académico sin compromisos de disponibilidad (SLA). El autor no garantiza el acceso continuo a la plataforma ni se responsabiliza de los perjuicios derivados de interrupciones del servicio.' },
            { titulo: 'Contenidos de terceros', desc: 'Los enlaces a fuentes oficiales externas (BOE, EUR-Lex, AEPD) se facilitan a título informativo. El autor no controla ni se responsabiliza de los contenidos de dichos sitios externos.' },
          ].map((item, i) => (
            <div key={i} className="p-4 bg-slate-50 border border-slate-100 rounded-xl">
              <p className="text-sm font-semibold text-slate-800 mb-1">{item.titulo}</p>
              <p className="text-xs text-slate-600 leading-relaxed">{item.desc}</p>
            </div>
          ))}
        </div>
      </div>

      {/* Legislación aplicable */}
      <div className="card">
        <h2 className="text-base font-bold text-slate-900 mb-4 pb-3 border-b border-slate-100">6. Legislación aplicable y jurisdicción</h2>
        <p className="text-slate-600 text-sm leading-relaxed mb-4">
          El presente Aviso Legal y el uso de la plataforma SmartAudits se rigen por la legislación española y europea.
          Para cualquier controversia derivada del acceso o uso de la plataforma, las partes se someten a los
          juzgados y tribunales de Madrid, con renuncia expresa a cualquier otro fuero que pudiera corresponder.
        </p>
        <div className="grid sm:grid-cols-2 gap-2">
          {normativaAplicable.map((item) => (
            <ExternalNormativeLink key={item.ley} ley={item.ley} desc={item.desc} url={item.url} />
          ))}
        </div>
      </div>

      {/* Modificaciones */}
      <div className="card">
        <h2 className="text-base font-bold text-slate-900 mb-3 pb-3 border-b border-slate-100">7. Modificaciones del Aviso Legal</h2>
        <p className="text-slate-600 text-sm leading-relaxed">
          El autor se reserva el derecho a modificar el presente Aviso Legal en cualquier momento, especialmente
          para adaptarlo a cambios legislativos, de funcionalidades de la plataforma o del contexto académico del proyecto.
          Las modificaciones serán efectivas desde su publicación en la plataforma.
        </p>
        <div className="mt-3 flex items-center space-x-2 p-3 bg-slate-50 border border-slate-200 rounded-xl">
          <svg className="w-4 h-4 text-slate-400 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
          </svg>
          <p className="text-xs text-slate-600"><span className="font-semibold">Última actualización:</span> 20 de mayo de 2026</p>
        </div>
      </div>

    </div>
  )
}

export default AvisoLegal
