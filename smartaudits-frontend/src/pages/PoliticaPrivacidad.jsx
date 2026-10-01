import ExternalNormativeLink from '../components/ExternalNormativeLink'
import { useState } from 'react'

const PoliticaPrivacidad = () => {
  const [tabActiva, setTabActiva] = useState('tratamientos')

  const tratamientos = [
    {
      dato: 'Nombre y correo electrónico',
      finalidad: 'Identificación, autenticación y comunicaciones relacionadas con la cuenta',
      base: 'Art. 6.1.b RGPD — Ejecución del contrato/servicio',
      plazo: 'Mientras la cuenta esté activa. Tras la baja: bloqueados durante el tiempo legalmente exigible.',
      destinatarios: 'Ninguno. No se ceden a terceros.',
      obligatorio: true,
    },
    {
      dato: 'Contraseña (hash BCrypt)',
      finalidad: 'Autenticación segura en la plataforma',
      base: 'Art. 6.1.b RGPD — Ejecución del contrato/servicio',
      plazo: 'Mientras la cuenta esté activa.',
      destinatarios: 'Ninguno. Se almacena únicamente en formato hash irreversible.',
      obligatorio: true,
    },
    {
      dato: 'Textos legales introducidos para auditoría',
      finalidad: 'Análisis de cumplimiento normativo mediante el motor legal de SmartAudits',
      base: 'Art. 6.1.b RGPD — Ejecución del servicio solicitado',
      plazo: 'Conservados vinculados al historial de la cuenta mientras esta esté activa.',
      destinatarios: 'Ninguno. El análisis se realiza en el propio servidor sin envío a APIs externas.',
      obligatorio: true,
    },
    {
      dato: 'Historial de auditorías e informes',
      finalidad: 'Consulta, revisión y seguimiento del historial de auditorías del usuario',
      base: 'Art. 6.1.f RGPD — Interés legítimo del responsable y del usuario en la trazabilidad',
      plazo: 'Conservados mientras la cuenta esté activa. Conservados tras baja para trazabilidad del sistema.',
      destinatarios: 'Accesibles por el propio usuario y, en su caso, por administradores del sistema.',
      obligatorio: false,
    },
    {
      dato: 'Rol de usuario (CLIENTE / ADMIN)',
      finalidad: 'Control de acceso y gestión de permisos dentro de la plataforma',
      base: 'Art. 6.1.b RGPD — Ejecución del contrato/servicio',
      plazo: 'Mientras la cuenta esté activa.',
      destinatarios: 'Ninguno externo. Accesible por administradores de la plataforma.',
      obligatorio: true,
    },
    {
      dato: 'Fecha y hora de acceso / Token JWT',
      finalidad: 'Seguridad, control de sesiones activas y prevención de accesos no autorizados',
      base: 'Art. 6.1.f RGPD — Interés legítimo en la seguridad del sistema',
      plazo: 'El token JWT expira a las 24 horas. Los logs de acceso se conservan según necesidad técnica.',
      destinatarios: 'Ninguno externo.',
      obligatorio: false,
    },
  ]

  const derechos = [
    { letra: 'A', nombre: 'Acceso', articulo: 'Art. 15 RGPD', color: 'bg-blue-500', desc: 'Obtener confirmación de si tratamos tus datos y acceder a ellos, junto con información sobre finalidades, categorías, destinatarios y plazos.', como: 'Solicítalo por email a contacto@smartaudits.local indicando "Derecho de Acceso". Responderemos en máximo 1 mes.' },
    { letra: 'R', nombre: 'Rectificación', articulo: 'Art. 16 RGPD', color: 'bg-green-500', desc: 'Corregir datos inexactos o completar datos incompletos. Por ejemplo, cambiar tu nombre o correo electrónico.', como: 'Puedes modificar tu nombre directamente desde tu perfil en SmartAudits. Para el email, contacta con nosotros.' },
    { letra: 'S', nombre: 'Supresión', articulo: 'Art. 17 RGPD', color: 'bg-red-500', desc: 'Solicitar la eliminación de tus datos cuando ya no sean necesarios, retires el consentimiento o se hayan tratado ilícitamente.', como: 'Puedes darte de baja desde el menú lateral (baja lógica). Para supresión completa, contacta a contacto@smartaudits.local.' },
    { letra: 'U', nombre: 'Limitación', articulo: 'Art. 18 RGPD', color: 'bg-yellow-500', desc: 'Solicitar que suspendamos el tratamiento de tus datos mientras se resuelve una impugnación de exactitud o una oposición.', como: 'Solicítalo por email indicando "Derecho de Limitación" y el motivo. Responderemos en máximo 1 mes.' },
    { letra: 'L', nombre: 'Portabilidad', articulo: 'Art. 20 RGPD', color: 'bg-purple-500', desc: 'Recibir tus datos en un formato estructurado, de uso común y lectura mecánica (JSON/CSV) para transmitirlos a otro responsable.', como: 'Solicítalo por email indicando "Derecho de Portabilidad". Prepararemos un archivo con tus datos en formato JSON.' },
    { letra: 'I', nombre: 'Oposición', articulo: 'Art. 21 RGPD', color: 'bg-orange-500', desc: 'Oponerte al tratamiento basado en interés legítimo (logs de seguridad, historial). El responsable puede continuar si acredita motivos imperiosos.', como: 'Solicítalo por email indicando "Derecho de Oposición" y el tratamiento concreto al que te opones.' },
  ]

  const medidas = [
    { categoria: 'Autenticación y acceso', nivel: 'Alto', items: ['Contraseñas cifradas con BCrypt (factor de coste 10)', 'Tokens JWT con firma HMAC-SHA256 y expiración a 24 horas', 'Control de acceso basado en roles (RBAC): CLIENTE / ADMIN', 'No se almacenan contraseñas en texto plano en ningún momento'] },
    { categoria: 'Almacenamiento seguro', nivel: 'Alto', items: ['Base de datos MariaDB aislada en contenedor Docker', 'Acceso a la base de datos solo desde el backend (red interna Docker)', 'Sin exposición directa del puerto de base de datos al exterior', 'Credenciales de base de datos gestionadas por variables de entorno'] },
    { categoria: 'Comunicaciones', nivel: 'Medio', items: ['API REST con validación de tokens en cada petición', 'CORS configurado para permitir solo el origen del frontend', 'HTTPS habilitado en entornos de producción', 'No se exponen datos sensibles en URLs ni logs visibles'] },
    { categoria: 'Minimización y calidad de datos', nivel: 'Alto', items: ['Solo se recogen los datos estrictamente necesarios', 'Los textos de auditoría se analizan localmente sin envío externo', 'Baja lógica (soft delete) para conservar trazabilidad sin exponer datos', 'Separación de roles: los admins ven historial pero no contraseñas'] },
  ]

  const tabs = [
    { id: 'tratamientos', label: 'Qué datos tratamos', icono: '📊' },
    { id: 'derechos', label: 'Tus derechos', icono: '🛡️' },
    { id: 'seguridad', label: 'Seguridad', icono: '🔒' },
  ]

  return (
    <div className="space-y-6 max-w-5xl">

      {/* Cabecera */}
      <div className="bg-gradient-to-br from-primary-600 via-primary-800 to-slate-900 rounded-2xl px-8 py-10 relative overflow-hidden">
        <div className="absolute right-0 top-0 w-72 h-72 bg-white/5 rounded-full blur-3xl pointer-events-none"></div>
        <div className="absolute left-0 bottom-0 w-48 h-48 bg-primary-400/10 rounded-full blur-2xl pointer-events-none"></div>
        <div className="relative">
          <div className="inline-flex items-center space-x-2 px-3 py-1.5 bg-white/15 rounded-full mb-4">
            <span className="w-2 h-2 bg-green-400 rounded-full"></span>
            <span className="text-white text-xs font-bold tracking-wider">SmartAudits · Política de Privacidad · Actualizada mayo 2026</span>
          </div>
          <h1 className="text-3xl font-bold text-white mb-2 leading-tight">Política de Privacidad</h1>
          <p className="text-white/70 text-sm leading-relaxed max-w-2xl">
            Información completa sobre el tratamiento de tus datos personales conforme al
            Reglamento General de Protección de Datos (RGPD — UE 2016/679) y la Ley Orgánica
            3/2018 de Protección de Datos y Garantía de Derechos Digitales (LOPDGDD).
          </p>
          <p className="text-white/40 text-xs mt-3">Última actualización: 20 de mayo de 2026</p>
        </div>
      </div>

      {/* Responsable del tratamiento */}
      <div className="card border-2 border-primary-100">
        <div className="flex items-center space-x-3 mb-5 pb-4 border-b border-slate-100">
          <div className="w-10 h-10 bg-primary-600 rounded-xl flex items-center justify-center flex-shrink-0">
            <svg className="w-5 h-5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
            </svg>
          </div>
          <div>
            <h2 className="text-base font-bold text-slate-900">Responsable del tratamiento</h2>
            <p className="text-xs text-slate-500">Información exigida por el Art. 13.1.a RGPD</p>
          </div>
        </div>
        <div className="grid sm:grid-cols-2 gap-4">
          <div className="space-y-3">
            {[
              { label: 'Responsable', valor: 'Jesús Briones (SmartAudits)' },
              { label: 'Naturaleza', valor: 'Proyecto educativo — TFC DAW, ESIC University' },
              { label: 'Contacto', valor: 'contacto@smartaudits.local' },
            ].map((item, i) => (
              <div key={i} className="flex flex-col gap-0.5 py-2 border-b border-slate-50 last:border-0">
                <span className="text-xs font-semibold text-slate-400 uppercase tracking-wide">{item.label}</span>
                <span className="text-sm text-slate-800 font-medium">{item.valor}</span>
              </div>
            ))}
          </div>
          <div className="space-y-3">
            {[
              { label: 'DPO (Delegado de Protección de Datos)', valor: 'No procede la designación obligatoria (Art. 37 RGPD). Proyecto académico de pequeña escala sin tratamientos a gran escala ni categorías especiales de datos.' },
              { label: 'Autoridad de control', valor: 'AEPD — Agencia Española de Protección de Datos (www.aepd.es)' },
            ].map((item, i) => (
              <div key={i} className="flex flex-col gap-0.5 py-2 border-b border-slate-50 last:border-0">
                <span className="text-xs font-semibold text-slate-400 uppercase tracking-wide">{item.label}</span>
                <span className="text-sm text-slate-800 font-medium">{item.valor}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Declaraciones clave */}
      <div className="grid sm:grid-cols-3 gap-3">
        {[
          { icono: '🚫', titulo: 'Sin cesión a terceros', desc: 'Tus datos no se venden, alquilan ni ceden a ningún tercero bajo ningún concepto.' },
          { icono: '🌍', titulo: 'Sin transferencias internacionales', desc: 'Todos los datos se almacenan localmente. No hay transferencias fuera del EEE.' },
          { icono: '🤖', titulo: 'Sin IA externa', desc: 'El análisis legal se realiza con motor propio. Ningún texto se envía a APIs de IA externas.' },
        ].map((item, i) => (
          <div key={i} className="p-4 bg-green-50 border border-green-200 rounded-xl">
            <span className="text-2xl mb-2 block">{item.icono}</span>
            <p className="text-sm font-bold text-green-900 mb-1">{item.titulo}</p>
            <p className="text-xs text-green-700 leading-relaxed">{item.desc}</p>
          </div>
        ))}
      </div>

      {/* Tabs */}
      <div className="card !p-0 overflow-hidden">
        <div className="flex overflow-x-auto border-b border-slate-100">
          {tabs.map((tab) => (
            <button key={tab.id} onClick={() => setTabActiva(tab.id)}
              className={`flex items-center space-x-2 px-5 py-3.5 text-sm font-medium whitespace-nowrap transition-colors border-b-2 -mb-px ${tabActiva === tab.id ? 'border-primary-600 text-primary-700 bg-primary-50/50' : 'border-transparent text-slate-500 hover:text-slate-700 hover:bg-slate-50'}`}>
              <span>{tab.icono}</span><span>{tab.label}</span>
            </button>
          ))}
        </div>
        <div className="p-6">

          {tabActiva === 'tratamientos' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">
                SmartAudits trata únicamente los datos estrictamente necesarios para el funcionamiento de la plataforma.
                A continuación se detalla cada actividad de tratamiento conforme al Art. 13 RGPD.
              </p>
              {tratamientos.map((t, i) => (
                <div key={i} className="rounded-xl border border-slate-200 overflow-hidden">
                  <div className="bg-slate-50 px-4 py-3 flex items-center justify-between gap-3">
                    <div className="flex items-center space-x-2">
                      <p className="text-sm font-bold text-slate-900">{t.dato}</p>
                      {t.obligatorio && <span className="text-xs bg-red-100 text-red-700 px-2 py-0.5 rounded-full font-medium">Obligatorio</span>}
                    </div>
                  </div>
                  <div className="p-4 grid sm:grid-cols-2 gap-3">
                    {[
                      { label: 'Finalidad', valor: t.finalidad, color: 'primary' },
                      { label: 'Base legal', valor: t.base, color: 'green' },
                      { label: 'Plazo de conservación', valor: t.plazo, color: 'amber' },
                      { label: 'Destinatarios', valor: t.destinatarios, color: 'slate' },
                    ].map((campo, j) => (
                      <div key={j} className={`p-2.5 rounded-lg ${campo.color === 'primary' ? 'bg-primary-50 border border-primary-100' : campo.color === 'green' ? 'bg-green-50 border border-green-100' : campo.color === 'amber' ? 'bg-amber-50 border border-amber-100' : 'bg-slate-50 border border-slate-100'}`}>
                        <p className={`text-xs font-semibold mb-1 ${campo.color === 'primary' ? 'text-primary-700' : campo.color === 'green' ? 'text-green-700' : campo.color === 'amber' ? 'text-amber-700' : 'text-slate-600'}`}>{campo.label}</p>
                        <p className="text-xs text-slate-700 leading-relaxed">{campo.valor}</p>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}

          {tabActiva === 'derechos' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">
                Conforme a los Arts. 15-22 RGPD y la LOPDGDD, tienes los siguientes derechos sobre tus datos personales.
                El plazo máximo de respuesta es de <strong>1 mes</strong> (prorrogable 2 meses en casos complejos).
                La respuesta y el ejercicio de derechos son <strong>gratuitos</strong>.
              </p>
              <div className="space-y-3">
                {derechos.map((d) => (
                  <div key={d.letra} className="p-4 bg-slate-50 rounded-xl border border-slate-100 hover:border-slate-300 transition-colors">
                    <div className="flex items-start space-x-4">
                      <div className={`w-10 h-10 ${d.color} text-white rounded-xl flex items-center justify-center font-bold text-base flex-shrink-0`}>{d.letra}</div>
                      <div className="flex-1">
                        <div className="flex items-center space-x-2 mb-1">
                          <p className="text-sm font-bold text-slate-900">{d.nombre}</p>
                          <span className="text-xs text-primary-600 bg-primary-50 px-2 py-0.5 rounded-full font-medium">{d.articulo}</span>
                        </div>
                        <p className="text-xs text-slate-600 leading-relaxed mb-2">{d.desc}</p>
                        <div className="p-2.5 bg-primary-50 border border-primary-100 rounded-lg">
                          <p className="text-xs text-primary-800"><span className="font-semibold">Cómo ejercerlo:</span> {d.como}</p>
                        </div>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
              <div className="p-4 bg-amber-50 border border-amber-200 rounded-xl mt-4">
                <p className="text-sm font-bold text-amber-900 mb-1">Derecho a reclamar ante la AEPD</p>
                <p className="text-xs text-amber-800 leading-relaxed">
                  Si consideras que el tratamiento de tus datos no es adecuado o que tus derechos no han sido atendidos
                  correctamente, tienes derecho a presentar una reclamación ante la{' '}
                  <a href="https://www.aepd.es" target="_blank" rel="noopener noreferrer" className="underline font-medium">
                    Agencia Española de Protección de Datos (www.aepd.es)
                  </a>.
                  Es gratuito y el plazo de resolución es de aproximadamente 9 meses.
                </p>
              </div>
            </div>
          )}

          {tabActiva === 'seguridad' && (
            <div className="space-y-4">
              <p className="text-sm text-slate-500 mb-4">
                SmartAudits aplica medidas técnicas y organizativas apropiadas conforme al Art. 32 RGPD
                para garantizar un nivel de seguridad adecuado al riesgo del tratamiento.
              </p>
              {medidas.map((m, i) => (
                <div key={i} className="rounded-xl border border-slate-200 overflow-hidden">
                  <div className="bg-slate-50 px-4 py-3 flex items-center justify-between">
                    <p className="text-sm font-bold text-slate-900">{m.categoria}</p>
                    <span className={`text-xs font-bold px-2 py-0.5 rounded-full ${m.nivel === 'Alto' ? 'bg-green-100 text-green-700' : 'bg-amber-100 text-amber-700'}`}>
                      Nivel {m.nivel}
                    </span>
                  </div>
                  <div className="p-4 grid sm:grid-cols-2 gap-1.5">
                    {m.items.map((item, j) => (
                      <div key={j} className="flex items-start space-x-2 p-2 bg-slate-50 rounded-lg">
                        <svg className="w-3.5 h-3.5 text-green-500 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                        </svg>
                        <span className="text-xs text-slate-600">{item}</span>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
              <div className="p-4 bg-primary-50 border border-primary-100 rounded-xl">
                <p className="text-xs font-semibold text-primary-800 mb-1">Notificación de brechas de seguridad — Art. 33-34 RGPD</p>
                <p className="text-xs text-primary-700 leading-relaxed">
                  En caso de detectarse una brecha de seguridad que suponga un riesgo para los derechos y libertades
                  de los interesados, se notificará a la AEPD en el plazo máximo de 72 horas desde que se tenga
                  conocimiento de ella. Si la brecha supone un alto riesgo para los afectados, también se les comunicará
                  directamente sin dilación indebida.
                </p>
              </div>
            </div>
          )}

        </div>
      </div>

      {/* Normativa de referencia */}
      <div className="card">
        <h2 className="text-base font-bold text-slate-900 mb-4 pb-3 border-b border-slate-100">Normativa de referencia</h2>
        <div className="grid sm:grid-cols-2 gap-2">
          {[
            { ley: 'RGPD', desc: 'Reglamento (UE) 2016/679 — Marco general de protección de datos', url: 'https://eur-lex.europa.eu/legal-content/ES/TXT/?uri=CELEX%3A32016R0679' },
            { ley: 'LOPDGDD', desc: 'Ley Orgánica 3/2018 — Adaptación española del RGPD', url: 'https://www.boe.es/buscar/act.php?id=BOE-A-2018-16673' },
            { ley: 'LSSI-CE', desc: 'Ley 34/2002 — Servicios de la Sociedad de la Información', url: 'https://www.boe.es/buscar/act.php?id=BOE-A-2002-13758' },
            { ley: 'Ley 10/2025', desc: 'Ley de Atención a la Clientela — Modifica LOPDGDD', url: 'https://www.boe.es/eli/es/l/2025/12/26/10' },
            { ley: 'AEPD', desc: 'Agencia Española de Protección de Datos', url: 'https://www.aepd.es' },
            { ley: 'ePrivacy', desc: 'Directiva 2002/58/CE — Privacidad en comunicaciones', url: 'https://eur-lex.europa.eu/legal-content/ES/TXT/?uri=CELEX%3A32002L0058' },
          ].map((item) => (
            <ExternalNormativeLink key={item.ley} ley={item.ley} desc={item.desc} url={item.url} />
          ))}
        </div>
      </div>

    </div>
  )
}

export default PoliticaPrivacidad
