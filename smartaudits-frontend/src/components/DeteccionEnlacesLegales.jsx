// Orden y nombres de las categorías que devuelve POST /auditorias/html/enlaces-legales.
export const CATEGORIAS_LEGALES = [
  ['POLITICA_PRIVACIDAD', 'Política de privacidad'],
  ['POLITICA_COOKIES', 'Política de cookies'],
  ['AVISO_LEGAL', 'Aviso legal'],
  ['TERMINOS_CONDICIONES', 'Términos y condiciones'],
  ['CONDICIONES_USO', 'Condiciones de uso'],
  ['CONDICIONES_COMPRA', 'Condiciones de compra'],
  ['DECLARACION_ACCESIBILIDAD', 'Declaración de accesibilidad'],
]

const CONTEXTOS = { footer: 'pie de página', nav: 'navegación', header: 'cabecera', aside: 'lateral', cuerpo: 'cuerpo' }

// Las URLs se muestran como texto, nunca como enlaces: son candidatos encontrados en el
// HTML pegado, no documentos descargados ni verificados.
const DeteccionEnlacesLegales = ({ resultado }) => {
  const porCategoria = new Map(CATEGORIAS_LEGALES.map(([clave]) => [clave, []]))
  for (const enlace of resultado.detectados || []) {
    porCategoria.get(enlace.categoria)?.push(enlace)
  }

  return (
    <section aria-labelledby="titulo-deteccion" className="border border-gray-200 rounded-lg p-4 space-y-3">
      <div>
        <h2 id="titulo-deteccion" className="text-base font-semibold text-gray-900">
          Documentos legales enlazados en el HTML
        </h2>
        <p className="text-sm text-gray-600">
          Enlaces encontrados en el HTML pegado. No se han descargado ni verificado: comprueba que cada
          documento existe y es el correcto.
        </p>
        {resultado.limiteAlcanzado && (
          <p role="status" className="text-sm text-amber-700 mt-1">
            Se alcanzó el límite de enlaces analizados o de resultados; puede haber más enlaces sin revisar.
          </p>
        )}
      </div>

      <ul className="space-y-1">
        {CATEGORIAS_LEGALES.map(([clave, nombre]) => {
          const enlaces = porCategoria.get(clave)
          const detectado = enlaces.length > 0
          return (
            <li key={clave} data-categoria={clave} className="py-2">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-sm font-medium text-gray-800">{nombre}</span>
                <span
                  className={`text-xs font-semibold px-2 py-0.5 rounded ${detectado
                    ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-600'}`}
                >
                  {detectado ? 'DETECTADO EN EL HTML' : 'NO DETECTADO EN EL HTML'}
                </span>
              </div>
              {detectado && (
                <ul className="mt-1 space-y-1">
                  {enlaces.map(enlace => (
                    <li key={enlace.url} className="text-sm text-gray-700">
                      <span>{enlace.texto || '(enlace sin texto)'}</span>
                      {' — '}
                      <code className="break-all text-xs bg-gray-50 px-1 rounded">{enlace.url}</code>
                      {enlace.externo === true && (
                        <span className="ml-2 text-xs font-semibold text-amber-700">Enlace externo</span>
                      )}
                      {enlace.relativa && (
                        <span className="ml-2 text-xs text-gray-500">Ruta relativa (sin URL de referencia)</span>
                      )}
                      <span className="ml-2 text-xs text-gray-500">en {CONTEXTOS[enlace.contexto] || enlace.contexto}</span>
                    </li>
                  ))}
                </ul>
              )}
            </li>
          )
        })}
      </ul>

      <p className="text-xs text-gray-500">
        {resultado.enlacesAnalizados} enlaces analizados · {resultado.enlacesDescartados} descartados por no ser
        HTTP(S) o no ser seguros.
      </p>
    </section>
  )
}

export default DeteccionEnlacesLegales
