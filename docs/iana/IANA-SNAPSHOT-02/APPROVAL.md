# IANA-SNAPSHOT-02 — Aprobación del catálogo de destinos

| Campo | Valor |
| --- | --- |
| Proyecto | SmartAudits 2.0 |
| Fase | 4.1-A (política pura de destinos de adquisición) |
| Política | ADR-0002, aprobado — SHA-256 `E77382356F026F946E855B3FDDDA10006ED06A7DA52CA2D57DCB99AB9146C717` |
| Gate | IANA-SNAPSHOT-02 |
| Run | `20261021T135838Z-a273b428` (sucesor de IANA-SNAPSHOT-01, run `20261021T000128Z-fef62290`) |
| Base del repositorio | `public-main`, commit `04367014e86b77660c87772a3d1b7bebfadfcd6d` |
| Veredicto | **Aprobado por Astra con reservas no bloqueantes** (2026-10-21) |
| Bloqueos pendientes | 0 |

## Archivos de este directorio

| Archivo | SHA-256 | Origen |
| --- | --- | --- |
| `catalog.tsv` | `75987A6CB1B520F172B454E7E1D8646ECDA08A95908B7C4D392ADFFC9CDF6F6D` | copia byte a byte de `candidate/catalog.tsv` del run auditado (362 filas de datos, 55 420 bytes) |
| `SERIALIZATION.md` | `A909C04381E76336692C5E58AE57D8BDEC82557EFC588FEE5523C6711E647581` | copia byte a byte de `manifest/SERIALIZATION.md`, revisión 2 |
| `APPROVAL.md` | — | este documento; no forma parte del paquete auditado |

`catalog.tsv` y `SERIALIZATION.md` son el paquete exactamente auditado: no se corrigen ni se reformatean (UTF-8 sin BOM,
finales LF). Cualquier cambio exige una revisión nueva. Las rutas que cita `SERIALIZATION.md` (`tools/build.py`,
`tools/verify.py`, `checks/...`) pertenecen al run externo, no a este repositorio.

Inventario completo del run (`manifest/SHA256SUMS`, 47 archivos, 46 entradas):
SHA-256 `A8E7FAC22A2DAE6F808A0962F30A4446D9577129B6E525B65D3A2D58AA0142CF`.

## Resultado de la revisión

| Hallazgo de IANA-SNAPSHOT-01 | Severidad | Estado |
| --- | --- | --- |
| M1 — escapado anidado de atributos especificado de forma incompleta | MEDIUM | **RESUELTO** (SERIALIZATION.md revisión 2; formato y bytes sin cambios) |
| M2 — el verificador admitía falsos PASS y devolvía 0 con FAIL | MEDIUM | **RESUELTO** (verificador reforzado) |

Severidades abiertas: 0 CRITICAL, 0 HIGH, 0 MEDIUM. Una reserva LOW (EX14, abajo).

Evidencia del run (Opus, flujo de desarrollo):

- Verificador reforzado: **74/74 comprobaciones obligatorias PASS**, exit 0, informe determinista.
- **41 sondas adversarias** sobre copias desechables con el comportamiento esperado (41/41), incluidas las cuatro
  situaciones señaladas por Astra; el árbol del run no cambia.
- Los siete TSV son byte a byte idénticos a IANA-SNAPSHOT-01 y a una reconstrucción nueva desde las fuentes.

Validación adicional independiente de Astra sobre IANA-SNAPSHOT-02:

- los 22 ejemplos normativos decodificados de forma independiente;
- 11 111 valores y 11 111 diccionarios generados, 0 discrepancias;
- 1 555 entradas diferenciales por tipo y las 27 entradas malformadas originales rechazadas;
- 776 filas reales reconstruidas; las dos secuencias `BS BS ;` localizadas (reglas `224.0.0.0/4` y `::/96`);
- 66 sondas de direcciones literales (48 prohibidas, 18 admisibles);
- solapamientos recalculados: 21 pares negativos anidados, 23 negativos contenidos en positivos, 33 externos,
  16 retiradas que cambian una decisión (con dirección testigo para cada una);
- informe base byte a byte idéntico y las 41 sondas reejecutadas con registros idénticos;
- 10 sondas propias adicionales; ambos runs originales sin cambios.

Las evidencias de Astra se conservan fuera del repositorio. Hashes de sus resultados principales:
`audit-results.json` de la revisión de IANA-SNAPSHOT-01 `4F6F738C4AF9372CCE3CDF739A1C13935CEACFF7D50C3427C1F1E560C6107EB3`;
`audit-results.json` de la revisión de IANA-SNAPSHOT-02 `813FFC1DCD1C58E5BE8140C7A66D8B2F819E456207D608077DB0F08F0C28107C`.

**Naturaleza del veredicto.** Procede de una revisión técnica independiente dentro del flujo de desarrollo del
proyecto. No es una certificación externa de ciberseguridad ni una auditoría de terceros acreditados.

## Reserva LOW — errata explicativa EX14

En `SERIALIZATION.md`, el paso 5 de la traza del ejemplo EX14 lleva el texto genérico
"(scan: `BS ;` is a literal `;`, not a separator)". Aplicado a EX14 es incorrecto:

- texto de nivel A de EX14: `a=` `BS BS` `;b=x`;
- el par `BS BS` representa **una barra invertida literal**;
- el `;` siguiente **separa los atributos**; no es un `;` escapado;
- resultado correcto: `a` = una barra invertida, `b` = `x`.

Los bytes hexadecimales de EX14 (`613d5c5c5c5c3b623d78`), la tabla de ejemplos, el resultado final de la traza y los
decodificadores son correctos. La errata no afecta al catálogo: sus únicos backslashes son las dos secuencias
`BS BS ;` de `;` escapado (tipo EX17), no el caso EX14.

La copia de este directorio no se ha modificado. Si más adelante se corrige el texto, se publicará como una revisión
nueva e identificada de `SERIALIZATION.md`, con hash nuevo y su propia revisión.

## Alcance y limitaciones

- El catálogo representa la política de destinos aprobada para la Fase 4.1-A (ADR-0002 §11–§12).
- Del procedimiento de congelación del ADR-0002 §12 están completados los pasos 1–8. Quedan pendientes los pasos 9–10
  (constantes Java y tests de integridad); el hash del catálogo todavía no figura en el código ni en una nota del ADR,
  y la cabecera de estado del ADR no se ha modificado en este gate.
- SmartAudits **no** tiene todavía un crawler ni peticiones HTTP automáticas.
- **No** se ha implementado DNS real.
- **No** existe protección SSRF completa en ejecución.
- No hay Java nuevo, migraciones nuevas ni endpoints nuevos.
- El futuro fetcher (Fase 4.1-B) deberá cumplir las exigencias de seguridad del ADR-0002 (§17 y concordantes).
- "Admisible" en el catálogo solo significa no excluido por él: nunca asignado, enrutado, alcanzable ni seguro.

## Evidencia externa

Se conservan fuera del repositorio, en el archivo de evidencias del mantenedor, sin cachés ni datos temporales:

| Elemento | Identificador / hash |
| --- | --- |
| Run IANA-SNAPSHOT-01 completo (40 archivos) | `manifest/SHA256SUMS` `CADB93388BE75AE81931A7492C9146CD79EA72824910F714273A5BA6A18EF396` |
| Run IANA-SNAPSHOT-02 completo (47 archivos: fuentes XML/CSV/HTML, TSV parciales, herramientas, sondas, manifiestos) | `manifest/SHA256SUMS` `A8E7FAC22A2DAE6F808A0962F30A4446D9577129B6E525B65D3A2D58AA0142CF` |
| Evidencias de las revisiones de Astra (167 + 488 archivos) | inventario `E536E65075B25A3DB3016804E9BA11BAEDB9FD5DCC9AA4C42A9C2FE34C380E97` |
