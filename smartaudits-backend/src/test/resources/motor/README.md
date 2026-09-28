# Corpus de caracterización del motor actual — Fase 1.2

`current-engine-v1.json` fija el comportamiento del motor en `4002e44`.
Los textos son sintéticos; no se consulta Internet ni se requiere una base de datos.
No certifican cumplimiento jurídico. `knownLimitation=false` significa que el caso
no pretende documentar un defecto conocido, no que el documento sea legalmente válido.

## Formato y expectativas

- `schemaVersion`: versión del formato; `baselineCommit`: referencia del motor observado.
- `rules`: catálogo fijo R01–R19 con título, severidad, peso y campo faltante.
- `globalRisks`: identificadores del corpus G01–G05 (no IDs públicos del motor).
- Cada caso contiene ID único, categoría, descripción, tipo de documento, texto
  completo, score exacto, marcador de resumen, incidencias, tamaños de las listas,
  notas y `knownLimitation`. Cuando es `true`, `limitation` explica qué debe revisarse.
- `Rxx:MISSING` representa una cláusula no detectada; `Rxx:RISK`, una cláusula
  detectada con redacción problemática; `Gxx`, una incidencia global.

Se fija una proyección de la salida: score, nivel del resumen, conjunto completo
de incidencias con severidades, campos faltantes y tamaños de las listas.
También se comprueba que los campos del resultado y de cada incidencia estén completos.
No se fija el orden de las colecciones ni toda la redacción editorial de las recomendaciones.
Las incidencias ausentes también importan: permiten detectar los falsos negativos fijados.
Todos los casos, incluidas las limitaciones, ejecutan el motor real y las mismas aserciones.

## Algoritmo caracterizado

R01–R19 cubren identidad, contacto, DPO, finalidad, base legal, derechos,
conservación, destinatarios, transferencias, cookies, reclamación, consentimiento,
menores, datos especiales, seguridad, aviso legal, perfilado, brechas y publicidad.
Sus pesos suman 100. Basta una coincidencia de subcadena normalizada para sumar
el peso; una coincidencia de riesgo local aporta la mitad entera y una incidencia ALTA.
Cada grupo global resta 2 una sola vez, aunque coincidan varios patrones del grupo.
Los cinco grupos son cesión, conservación indefinida, consentimiento tácito,
exclusión de seguridad y seguimiento. El score se limita a 0–100.
Un score mayor representa mayor cumplimiento según este algoritmo, pese al nombre
`puntuacionRiesgo`. Los umbrales del resumen son 40, 65 y 85.
El motor actual no utiliza `tipoDocumento` para seleccionar reglas.
La entrada vacía devuelve un resultado especial sin incidencias, con score 0.

## Actualización consciente

Las expectativas están versionadas: el test no las genera ni las recalcula desde
las reglas de producción. Un fallo exige revisar la diferencia, no regenerar el
corpus automáticamente. Para Motor 2.0, revisar texto, expectativas y justificación
de cada limitación; retirar su etiqueta solo cuando se haya corregido y validado.
Los casos de negación, ambigüedad, cookies, HTTPS, sinónimo de contacto y denegación
de derechos registran limitaciones actuales, no resultados jurídicamente correctos.
