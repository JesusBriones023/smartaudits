# ADR-0001 — SmartAudits 2.0 Core Data Model

**Status:** Accepted for implementation

**Date:** 2026-10-09

## 1. Context

**Estado actual.** La base de esta decisión es `public-main`, commit
`cc78d6b`. SmartAudits analiza textos manuales mediante el modelo legacy:
`usuarios`, `auditorias`, `incidencias`, `resultados`,
`historial_auditorias`, `historial_acciones_admin`, `roles` y
`usuarios_roles`. Las migraciones existentes son V1/V2/V3.

`auditorias.resultado_json` conserva el snapshot del resultado y la puntuación
superior es el score canónico presentado. `resultados` permanece como tabla
legacy sin nuevas escrituras. `AuditReportModel` es un modelo frontend de
presentación, no una entidad persistente. La autorización legacy se basa en
propietario y ADMIN global; no existe todavía el núcleo organizativo 2.0.

**Decisión futura aprobada.** Se necesita una frontera de datos y autorización
para futuras adquisiciones web, ejecuciones y resultados, sin reinterpretar
históricos ni cambiar los contratos actuales. Este ADR registra la decisión
final de Fase 3.1, posterior a la revisión adversarial de Fase 3-A. Sustituye las
opciones preliminares incompatibles con lo aquí acordado; no autoriza ampliar
el alcance durante su implementación.

Este documento no crea tablas, código productivo, endpoints ni recursos de
infraestructura. Todas las capacidades 2.0 descritas son futuras.

## 2. Decision

SmartAudits 2.0 se implementará en **tablas nuevas**. La única tabla física de
negocio compartida con legacy será `usuarios`, reutilizada como identidad sin
alterar su esquema. La infraestructura técnica de Flyway no constituye un
modelo de negocio compartido.

No se modificarán las ocho tablas legacy enumeradas en el contexto. El análisis
manual actual seguirá escribiendo y leyendo su modelo legacy. La coexistencia
de ambos modelos será indefinida hasta una decisión futura explícita.

El código nuevo tendrá una frontera de paquete separada, conceptualmente
`com.smartaudits.v2`, con los subpaquetes que requiera la implementación
(`model`, `repository`, `service`, etc.). No se crean en Fase 3.1.

El núcleo podrá reutilizar explícitamente `Usuario` como identidad y, cuando
corresponda en fases futuras, `AnalizadorLegal` / `EntradaAnalisis`. Esa
reutilización no podrá provocar escrituras secundarias en `auditorias`,
`incidencias` ni `resultado_json` legacy.

## 3. Invariants

- No backfill de usuarios ni auditorías históricas.
- No dual-write entre legacy y 2.0.
- No Organization histórica inventada, Site legacy, Scan sintético, Document
  derivado retrospectivamente, Finding desde Incidencia ni RuleVersion
  retrospectiva.
- No reinterpretar ni reserializar snapshots históricos para adaptarlos a 2.0.
- V1/V2/V3 permanecen inmutables; ninguna migración nueva modifica tablas legacy.
- La identidad del actor no concede acceso: `created_by_user_id` registra quién
  actuó, no ownership ni autorización.
- Una operación sobre recursos 2.0 requiere Membership ACTIVE y una carga
  acotada por tenancy. ADMIN global no es una excepción implícita.
- Las FK protegen la integridad de las escrituras; la autorización protege las
  lecturas. Ninguna sustituye a la otra.
- Audit y su primer Scan se crean atómicamente. No debe quedar una Audit sin
  al menos un Scan.
- Como máximo puede existir un Scan no terminal por Audit. Un Scan terminal es
  inmutable.
- Fase 3 no expone endpoints públicos 2.0 ni inicia adquisición web.

## 4. Phase 3 scope

Fase 3 implementará únicamente estas **cinco entidades/tablas nuevas**:

| Entidad | Tabla futura | Responsabilidad |
| --- | --- | --- |
| Organization | `organizations` | Frontera organizativa de los recursos 2.0 |
| OrganizationMembership | `organization_memberships` | Pertenencia y autorización de Usuario en Organization |
| Site | `sites` | Objetivo web registrado, todavía sin identidad canónica por URL |
| Audit | `audits` | Solicitud o intención lógica de evaluación |
| Scan | `scans` | Intento concreto de ejecutar una Audit |

Se reutiliza Usuario existente. No se crea una segunda entidad de identidad ni
se modifica su tabla. Los servicios y repositorios internos futuros podrán
probar las invariantes sin exponer API pública.

Organization no necesita estado de archivo en Fase 3. No se incorpora ARCHIVED
a Organization por analogía con Site.

## 5. Deferred concepts

| Fase | Conceptos y alcance diferido |
| --- | --- |
| F4 | Page y Capture; identidad de URL y adquisición real |
| F6 | Document, Finding y Evidence; extracción y evaluación de contenido adquirido |
| F7 | Cookie y Tracker como observaciones estructuradas |
| F8 | ConsentTest, incluyendo pruebas satisfactorias sin findings |
| F9 | Form |
| F10 | AccessibilityIssue |
| F11 / detector correspondiente | SecurityFinding y Technology |
| F13 | Report persistente |
| F15 | MonitoringSchedule |
| F16 | Alert |
| F19 | MEMBER, invitaciones y gestión ampliada de pertenencias |
| Decisión funcional posterior | Rule / RuleVersion persistentes |

Ninguno de estos conceptos se implementa como entidad o tabla en Fase 3.
Tampoco se crean tablas vacías ni FK de reserva. Las definiciones conceptuales
de este ADR permiten su incorporación posterior sin anticipar su implementación.

## 6. Tenancy and authorization

### Organization y Membership

La relación es **User N:M Organization mediante OrganizationMembership**.
No se asignan organizaciones a usuarios existentes mediante backfill.

La organización personal se provisionará de forma lazy cuando ocurra la
primera operación 2.0 que necesite tenancy. Se espera que la primera operación
real sea la creación de Site en F4, después de fijar su identidad por URL.

Organization podrá tener `personal_owner_user_id` nullable y UNIQUE únicamente
para hacer idempotente esa provisión personal. Este campo no concede acceso:
incluso su usuario referenciado necesita Membership ACTIVE.

En Fase 3, Membership tendrá exclusivamente el rol **OWNER**. MEMBER e
invitaciones quedan para F19. Sus estados serán ACTIVE y REVOKED. No se podrá
revocar el último OWNER activo; la implementación deberá preservar esta
invariante también ante concurrencia.

La pertenencia no se almacenará dentro del JWT. Su revocación debe cortar el
acceso en la siguiente petición. No basta con haber creado el recurso ni con
haber tenido pertenencia al emitir el token.

### ADMIN y respuestas sin acceso

ADMIN legacy conserva exactamente su comportamiento para recursos legacy.
ADMIN global no obtiene acceso automático a recursos 2.0. Una operación 2.0
requiere Membership ACTIVE y, si el usuario no tiene acceso al recurso, la
respuesta esperada es **404**, evitando revelar su existencia.

Un futuro soporte transversal requeriría concesión explícita y auditoría. No se
diseña ni implementa en Fase 3.

### Estrategia híbrida

Las raíces consultables `sites`, `audits` y `scans` tendrán
`organization_id NOT NULL` directamente. Membership identifica explícitamente
a su organización y usuario.

Las relaciones incluirán:

```text
audits(organization_id, site_id)
  → sites(organization_id, id)

scans(organization_id, audit_id)
  → audits(organization_id, id)
```

Se introducirán las claves UNIQUE auxiliares requeridas para estas referencias.

En futuros hijos de Scan, la organización se derivará de Scan. No se duplicará
`organization_id` automáticamente en Page, Capture, Document, Finding o
Evidence. Cuando una fila tenga dos caminos hacia Scan, una FK compuesta deberá
asegurar que ambos caminos pertenecen al mismo Scan.

Ningún recurso 2.0 podrá cargarse por un ID recibido de API sin acotar también
por organización/tenancy, directamente en una raíz o mediante su Scan. Esta
regla alcanza repositorios, servicios y las futuras capacidades públicas.

## 7. Site identity

Fase 3 define Site estructuralmente, sin decidir aún su identidad canónica por
URL. Los campos conceptuales iniciales son:

- id y organization;
- name;
- baseUrl tal como fue introducida;
- status: ACTIVE o ARCHIVED;
- createdBy, solo como actor;
- createdAt y updatedAt;
- optimistic version.

No se añaden URL normalizada, host canónico, URL hash ni unicidad por URL o
dominio. Tampoco DNS, redirects, SSRF o canonicalización. Esas decisiones
pertenecen a F4.1/F4.5, incluyendo cómo tratar variantes de URL y dominio.

**No se expondrá un endpoint productivo para crear Sites hasta que F4 fije la
identidad por URL.** Las pruebas internas de Fase 3 no equivalen a activar esa
capacidad.

Las futuras columnas de URL/hash usarán semántica binaria apropiada; no
dependerán de `utf8mb4_general_ci` para comparar identidades.

## 8. Audit and Scan

Audit representa una solicitud/intención lógica de evaluación. Scan representa
un intento concreto de ejecutarla. La relación es **Audit 1:N Scan**.

- Nueva evaluación intencional: nuevo Audit.
- Reintento técnico: nuevo Scan del mismo Audit.
- Monitoring futuro: nuevo Audit por ejecución.
- Audit y su primer Scan se crean en la misma transacción.
- No se permite una Audit sin al menos un Scan.
- Audit no necesita estado propio inicialmente: el estado visible se deriva
  de sus Scans.
- No se introduce una relación circular `current_scan_id`.

Los estados y transiciones de Scan quedan fijados:

| Origen | Destinos permitidos |
| --- | --- |
| PENDING | RUNNING, CANCELLED |
| RUNNING | SUCCEEDED, FAILED, CANCELLED |
| SUCCEEDED | Ninguno |
| FAILED | Ninguno |
| CANCELLED | Ninguno |

Los estados terminales son inmutables. Solo puede existir un Scan no terminal
por Audit. Un reintento requiere que el intento anterior haya terminado en
FAILED o CANCELLED; no se reintenta una evaluación SUCCEEDED dentro de la misma
Audit. Las invariantes de creación, reintento y exclusión de intentos activos
deberán probarse transaccionalmente y ante concurrencia.

Scan tendrá optimistic version. Cuando exista ejecución real, se introducirán
`failure_code` y detalle saneado. Un HTTP 4xx/5xx futuro es un dato adquirido de
Page, no por sí mismo un Scan FAILED. FAILED representa fallo técnico o de
política de adquisición, por ejemplo DNS, timeout, TLS o SSRF.

La recuperación de un Scan que quede en RUNNING por caída del proceso no se
implementa en Fase 3, porque todavía no se ejecutan Scans. Cuando exista
ejecución real en Fase 4, un Scan RUNNING abandonado deberá poder pasar a FAILED
con un `failure_code` equivalente a INTERRUPTED mediante una política explícita
de recuperación. Esta recuperación queda diferida junto con `failure_code`, el
detalle saneado de fallo y la lógica real de ejecución.

## 9. Future acquisition/content model

Esta sección congela conceptos, **no tablas para Fase 3**.

### F4: Page y Capture

Page es un recurso web adquirido dentro de Scan. Puede tener una Capture de
adquisición.

Capture es contenido adquirido inmutable: bytes/contenido, tipo, tamaño, hash,
origen, timestamp y headers seleccionados. Puede existir sin Page para upload,
texto manual 2.0 o entrada por API. Esto no cambia el flujo manual legacy.

### F6: Document, Finding y Evidence

Document es una representación preparada por un extractor para análisis.
**Procede de Capture, no de Evidence.** Una Capture puede producir varios
Documents cuando existan diferentes extractores o versiones.

Finding conservará inmutablemente engine, engineVersion, ruleId, rulesVersion,
evaluatedAt, título, descripción, severidad, recomendación/acción e impacto,
además de su scope y evidencias. R01–R19 y G01–G05 continúan identificados mediante
la procedencia del motor, sin crear ahora un catálogo persistente Rule/RuleVersion.

Evidence es un localizador/prueba ligera de un Finding dentro de Document o
Capture. La relación es **Finding 1:N Evidence**, no N:M. El contenido pesado
compartido reside en Capture/Document y no se duplica dentro de Evidence.

La organización de estos hijos se deriva de Scan, con integridad compuesta
cuando existan varios caminos al mismo Scan, según la sección 6.

## 10. IDs

Las PK internas 2.0 serán **BIGINT AUTO_INCREMENT** por coherencia con JPA/MariaDB
actuales, tamaño compacto y ausencia de generación distribuida.

No se confía en la opacidad de un ID para autorizar. Si en el futuro se requiere
un identificador público opaco, se podrá añadir `public_id` UUIDv7 generado por
la aplicación sin sustituir la PK.

En tablas de alto volumen de F4 se podrá evaluar SEQUENCE manteniendo BIGINT si
el batching lo requiere. No cambia la decisión de las cinco tablas de Fase 3
ni los IDs legacy.

## 11. Time model

Para 2.0 se fija:

| Capa | Convención |
| --- | --- |
| Java | `Instant` |
| BD | `DATETIME(6)` con valor UTC |
| Generación | `Clock` inyectable en UTC |
| Precisión | Truncar a microsegundos antes de persistir |
| API 2.0 futura | ISO-8601 con `Z` |

`CURRENT_TIMESTAMP` no será la autoridad de timestamps de dominio. No se
activará globalmente `hibernate.jdbc.time_zone`, porque podría alterar legacy.
El mapeo 2.0 deberá resolver y verificar la conversión sin cambiar la semántica
temporal del modelo anterior.

Antes de aceptar V4 es obligatorio un test con MariaDB real y JVM en una zona
distinta de UTC, idealmente `Asia/Kathmandu`, que demuestre el valor UTC mediante
lectura SQL raw y un round-trip exacto a la precisión acordada. El test debe
verificar la truncación y no limitarse a comparar dos conversiones Java iguales.

Las fechas de creación, actualización e inicio/fin de ejecución se distinguirán
según su significado. No se reinterpretan fechas históricas.

## 12. Enums / charset / collation

Convención exclusiva del modelo nuevo:

- Java: `@Enumerated(STRING)`.
- JDBC: VARCHAR, con mapeo explícito cuando sea necesario para impedir ENUM nativo.
- BD: VARCHAR y CHECK con comparación binaria apropiada.
- No usar ENUM nativo MariaDB ni depender de una collation case-insensitive para
  aceptar los valores del enum.

El texto nuevo debe soportar Unicode mediante `utf8mb4`. La comparación de
identificadores técnicos y de las futuras URL/hash tendrá semántica binaria
apropiada. La collation de texto descriptivo no determina identidad técnica.
No se cambia globalmente charset/collation ni se modifica legacy. La collation
concreta de cada nueva columna se declarará y probará con su migración.

## 13. Delete / retention

Las relaciones con valor histórico usarán RESTRICT. No se generalizará
`CascadeType.ALL`, no habrá `orphanRemoval` sobre el grafo 2.0 y nunca se aplicará
cascade REMOVE sobre datos históricos.

- Site: archivo lógico mediante ARCHIVED.
- Membership: revocación mediante REVOKED, respetando el último OWNER activo.
- Organization: no necesita estado de archivo todavía.
- Purga física: requiere una fase y decisión posteriores explícitas.

Un cascade técnico podría justificarse en futuros recursos efímeros sin valor
histórico, como colas o bloqueos internos del crawler. No aplica a ninguna de
las cinco entidades de F3.

Las FK legacy, incluida la de `resultados` que puede impedir borrar auditorías,
no se modifican. Este ADR no implementa ni presupone una purga de históricos.

## 14. Migration strategy

**Una tabla por migración**, en este orden:

| Migración | Tabla |
| --- | --- |
| V4 | `organizations` |
| V5 | `organization_memberships` |
| V6 | `sites` |
| V7 | `audits` |
| V8 | `scans` |

Cada migración V4–V8 será aditiva y contendrá **una única sentencia
`CREATE TABLE`**. La PK, FK, UNIQUE, CHECK e índices necesarios para esa tabla
se declararán dentro de esa misma sentencia siempre que MariaDB lo permita.
No se incluirán un segundo CREATE TABLE, un ALTER TABLE posterior, un
CREATE INDEX separado, DML ni cambios sobre tablas legacy. Si una necesidad
no puede expresarse dentro de esa sentencia, no se añadirá una segunda operación
DDL a la migración. V1/V2/V3 son inmutables.

El objetivo es evitar que el fallo de una sentencia deje parcialmente aplicada
una migración compuesta por varias operaciones DDL. Se mantiene la regla de
una tabla por migración.

El esquema puede desplegarse incrementalmente, pero el servicio de creación de
Audit no se activará antes de disponer de Scan y de su creación atómica. No se
relaja la invariante para cubrir el intervalo entre V7 y V8.

La estrategia es EXPAND para el esquema nuevo y adopción funcional posterior
para nuevos datos. No existe un MIGRATE de históricos ni un CONTRACT programado:
la coexistencia es indefinida hasta otra decisión. No hay backfill ni dual-write.

Después de desplegar V4+, los hotfixes se realizarán hacia delante mediante
nuevas migraciones; no se reutilizan números antiguos ni se editan migraciones
ya desplegadas.

## 15. Rollback strategy

Rollback significa volver al **último release que haya estado desplegado en
producción**, anterior al despliegue que se revierte, **manteniendo el esquema
y los datos nuevos**. No significa DROP automático ni rollback transaccional de
toda una secuencia DDL.

En el estado actual, antes de publicar cualquier release con SmartAudits 2.0,
el baseline operativo es `cc78d6b`. Si posteriormente se publica un release 2.0
y después se despliega otro, el baseline de rollback de ese nuevo despliegue
pasará a ser el último release efectivamente desplegado y validado en producción.
No se asume que cada migración individual tenga su propio release de producción.
La validación de cada migración utilizará el baseline operativo aplicable.

Antes de volver atrás se detendrán los escritores 2.0 que el release de rollback
no pueda gestionar; los datos nuevos se conservarán aunque no sean accesibles
desde ese release.

Debe verificarse con migraciones futuras presentes:

- Hibernate validate y arranque Spring.
- Flyway enabled y Flyway disabled, como escenarios de prueba separados.
- E2E legacy 4/4.
- Datos 2.0 intactos e historial Flyway sin mutaciones causadas por el rollback.
- Vuelta al binario nuevo con validate/migrate estable.

No se modificará `ignoreMigrationPatterns` sin conservar el comportamiento
necesario para migraciones future. No se usará Flyway repair ni se desactivará
la validación para fingir compatibilidad. El escenario Flyway OFF no sustituye
al escenario ON ni permite desactivar Hibernate validate.

La compatibilidad no se presume por añadir solamente tablas: debe demostrarse
con los releases exactos. El despliegue real requerirá backup consistente y
restauración ensayada; restaurar un backup anterior puede perder escrituras
posteriores y no sustituye al rollback de aplicación aquí definido.

## 16. Test strategy

### Infraestructura aprobada

**No añadir Testcontainers ahora.** Fase 3.2 ampliará la infraestructura existente
PowerShell/Docker con MariaDB 11.2 mediante un harness específico 2.0, separado
conceptualmente del E2E normal.

Se aprovecha infraestructura que ya funciona, sin dependencia nueva, para probar
migraciones, constraints, timestamps y rollback sobre MariaDB real. El harness
usará recursos temporales aislados de su propiedad, sin acceder a la BD habitual,
y verificará su cleanup. Fase 3.1 no crea ni ejecuta ese harness.

### Matriz obligatoria antes de aceptar cualquier Vn

1. Fresh V1 → Vn.
2. Esquema histórico incorporado mediante BASELINE/V1 → Vn, sin ejecutar V1
   sobre tablas históricas ya pobladas.
3. Datos centinela legacy intactos.
4. Constraints válidas e inválidas comprobadas en MariaDB.
5. Timestamps UTC comprobados, incluyendo la prueba no UTC obligatoria desde V4.
6. Creación de datos 2.0 correspondientes al esquema disponible.
7. Arranque del release exacto del baseline operativo de rollback definido en
   la sección 15, con Flyway ON y con Flyway OFF.
8. E2E legacy 4/4.
9. `flyway_schema_history` sin mutación por el rollback.
10. Datos 2.0 con los mismos counts/hashes antes y después del rollback.
11. Vuelta al binario nuevo.
12. Validate/migrate estable.
13. Static check que impida ALTER/DROP/RENAME/DML sobre legacy; las nuevas
    migraciones no contienen DML de ningún tipo.
14. Hashes de V1–V(n-1) congelados.
15. Cleanup completo de recursos temporales.

En V7 no se crean Audits huérfanas como datos de prueba: se verifica el esquema
de `audits` sin activar su servicio y se usan datos de las entidades anteriores
para el ensayo de conservación. Desde V8 se prueba la creación Audit/Scan conjunta.

Las pruebas de servicios e integración cubrirán tenancy en lecturas y escrituras,
404 sin acceso, ADMIN sin Membership, revocación efectiva en la siguiente petición,
provisión personal idempotente, último OWNER ante concurrencia y los estados,
reintentos y exclusión de Scans no terminales. Se comprobará la ausencia de
cascadas destructivas y de escrituras 2.0 en legacy.

No se anticipan pruebas funcionales de crawler, extracción, consentimiento,
informes persistentes o scheduler antes de sus respectivas fases.

## 17. API / frontend compatibility

Fase 3 no expone endpoints públicos 2.0 y no crea `/v2` por anticipación.
Servicios/repositorios internos pueden existir para probar invariantes.
La primera capacidad pública llegará cuando una fase funcional la necesite;
Site no se expondrá antes de fijar su identidad en F4.

La API y el frontend legacy permanecen intactos. `AuditReportModel` sigue siendo
frontend/legacy. No se escriben datos 2.0 en `resultado_json` legacy ni se crea
una tabla Report en F3. El Report persistente se difiere hasta F13, con la
adaptación de presentación que entonces corresponda.

## 18. Consequences

### Ventajas

- Los históricos no se reinterpretan ni requieren datos inventados.
- El rollback de aplicación es más seguro al separar los datos nuevos.
- La frontera de tenancy queda explícita, sin heredar el bypass ADMIN legacy.
- El futuro crawler no contamina los datos ni snapshots legacy.
- El modelo evoluciona por fases funcionales, sin tablas especulativas.

### Costes y obligaciones

- Coexistencia indefinida de legacy y 2.0 y mantenimiento de dos caminos de datos.
- Adaptación futura de informes y presentación del modelo nuevo.
- Mayor disciplina de autorización, especialmente en consultas por ID y en hijos
  cuyo tenant se deriva de Scan.
- Pruebas obligatorias en MariaDB, incluidos timestamps, concurrencia y rollback.
- El release de rollback puede no presentar los nuevos datos 2.0, aunque deba
  conservarlos intactos.
- Fase 3 entrega una base interna, no una nueva capacidad pública de crawling.

## 19. Rejected alternatives

| Alternativa rechazada | Motivo |
| --- | --- |
| Transformar tablas legacy | Mezcla semánticas y autorización; compromete históricos y rollback |
| Backfill inmediato | Obliga a inferir relaciones y hechos que no constan en los históricos |
| Dual-write | Introduce divergencia y dos fuentes de verdad para una operación |
| Nueve entidades nuevas en F3 | Adelanta adquisición y contenido antes de sus fases funcionales; el MVP final tiene cinco |
| Repetir organization_id en todas las tablas | Duplica información en hijos; se adopta tenancy híbrido con integridad hacia Scan |
| Site único por URL/hash antes de F4 | Congela una identidad aún no definida y sus equivalencias |
| Finding–Evidence N:M | La prueba ligera pertenece a Finding; el contenido compartido reside en Capture/Document |
| Persistir Rule/RuleVersion ya | No existe necesidad actual de catálogo persistente; se conserva provenance del motor |
| Persistir Report ya | El informe actual es una proyección frontend/legacy; el artefacto persistente corresponde a F13 |
| Añadir Testcontainers ahora | La infraestructura PowerShell/Docker existente permite probar MariaDB real sin nueva dependencia |
| UUID como PK desde F3 | No hay generación distribuida que justifique sustituir BIGINT; public_id futuro sería adicional |

La implementación deberá respetar esta decisión final: cinco tablas en F3,
Membership solo OWNER, Organization sin ARCHIVED, Site sin identidad única por
URL y ningún endpoint público 2.0. Page/Capture y Document/Finding/Evidence
siguen diferidos. No hay backfill ni dual-write.
