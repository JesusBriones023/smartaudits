# SmartAudits: arquitectura y estado actual

Fecha del análisis: 6 de septiembre de 2026.

## 1. Alcance y método

Este documento registra el estado actual del repositorio antes de comenzar SmartAudits 2.0. Se basa en la lectura inicial de `README.md` y la inspección estática de la estructura, los archivos relevantes de los 91 archivos versionados, las dependencias, la configuración y los flujos de código.

No se arrancó la aplicación, no se ejecutaron compilaciones ni pruebas, no se consultó la base de datos real y no se realizaron ataques o cambios en código, configuración o dependencias. Las versiones de imágenes y los modelos descritos representan lo configurado en el repositorio, no una certificación del despliegue existente. Tampoco se verificaron vulnerabilidades de dependencias mediante bases de datos de CVE ni la validez jurídica de las reglas.

Los hallazgos distinguen comportamientos comprobables por inspección de código de riesgos condicionados por el despliegue o pendientes de reproducción. No se incluyen valores de secretos, contraseñas o claves.

## 2. Qué hace actualmente SmartAudits

SmartAudits es una aplicación de análisis orientativo de textos legales introducidos manualmente. Permite registrar usuarios, iniciar sesión, analizar un documento, consultar el historial y visualizar, copiar o imprimir un informe. Incluye gestión administrativa de usuarios y trazabilidad de determinadas acciones.

El motor es propio y determinista: aplica reglas de palabras clave en Java. No utiliza IA, modelos de lenguaje ni APIs externas para analizar el contenido, aunque algunos nombres y textos de la aplicación incluyen «IA».

La URL es un dato opcional de referencia. No se visita, descarga ni analiza. Cada auditoría representa un bloque de texto, no un dominio ni un conjunto estructurado de páginas.

No existen descubrimiento de páginas legales, crawler, navegador automatizado, inspección de cookies o trackers reales, comprobación de consentimiento, análisis de formularios ni evaluación de accesibilidad.

## 3. Arquitectura general y componentes

El repositorio contiene una SPA React y un backend monolítico Spring Boot organizado por capas. Se comunican mediante HTTP y JSON. MariaDB proporciona persistencia relacional.

```text
smartaudits/
├── README.md
├── CURRENT_ARCHITECTURE.md
├── docker-compose.yml            MariaDB y Adminer
├── smartaudits-backend/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/smartaudits/
│       │   ├── SmartAuditsApplication.java
│       │   ├── config/            Seguridad, CORS y bootstrap
│       │   ├── controller/        Endpoints REST
│       │   ├── security/          JWT y adaptación de usuarios
│       │   ├── service/           Casos de uso e historiales
│       │   │   └── motor/         MotorAnalisisLegal
│       │   ├── repository/        Repositorios Spring Data JPA
│       │   └── model/
│       │       └── dto/           Contratos de entrada y salida
│       └── resources/             Propiedades de configuración
└── smartaudits-frontend/
    ├── package.json
    ├── package-lock.json
    ├── vite.config.js
    ├── tailwind.config.js
    ├── postcss.config.js
    └── src/
        ├── main.jsx
        ├── App.jsx                Enrutamiento
        ├── api/                   Cliente Axios
        ├── context/               Estado de autenticación
        ├── components/            Layouts y navegación
        ├── pages/                 Pantallas y contenido normativo
        └── utils/                 Formato y clasificación visual
```

Flujo entre capas:

```text
React → Axios → Filtro JWT → Controller → Service → Repository/JPA → MariaDB
                                            │
                                            └── MotorAnalisisLegal
```

El análisis es síncrono: se ejecuta durante la petición HTTP de creación. No hay colas, workers ni tareas de auditoría en segundo plano. Aunque la entidad comenta estados adicionales, el flujo implementado guarda las auditorías correctas como `COMPLETADA`; no persiste un ciclo de trabajo con progreso o fallos.

Docker Compose configura solo MariaDB y Adminer, con volumen persistente y red bridge. Frontend y backend se arrancan por separado según el README. No hay configuración versionada de despliegue productivo, TLS, copias de seguridad o restauración.

## 4. Flujo completo actual

```text
Usuario entra en /
    │
    └── Redirección a /dashboard
            │
            ├── Sin sesión local → /login o /register
            │                         │
            │                         └── POST /auth/login o /auth/register
            │                                  │
            │                                  └── JWT + datos de usuario
            │                                       → localStorage y AuthContext
            │
            └── Dashboard
                    │
                    └── /crear-auditoria
                            ├── Título
                            ├── Tipo de documento
                            ├── Texto pegado manualmente
                            └── URL opcional de referencia
                                    │
                                    ▼
                            POST /auditorias
                                    │
                            Filtro JWT + validación DTO
                                    │
                            AuditoriaService
                                    │
                            MotorAnalisisLegal
                            Normalización → 19 reglas → riesgos globales
                                    │
                            ResultadoAuditoria
                                    │
                            Transacción de persistencia
                            Auditoría + JSON + Incidencias
                            + registro de CREACION
                                    │
                            HTTP 201 con ID y resultado
                                    │
                            Navegación a /auditoria/:id
                                    │
                            GET /auditorias/{id}
                            Comprueba propietario o ADMIN
                            Registra CONSULTA y deserializa JSON guardado
                                    │
                            Detalle del informe
                                    ├── Copiar informe
                                    ├── Copiar textos sugeridos
                                    └── Descargar PDF
                                            ├── POST /auditorias/{id}/descarga
                                            └── HTML en ventana nueva
                                                 → diálogo de impresión
                                                 → guardar PDF en navegador

Historial → GET /auditorias/mias para CLIENTE
         → GET /auditorias para ADMIN
         → selección de una auditoría → mismo flujo de detalle
```

Consultar un resultado histórico no vuelve a analizar el documento. La creación devuelve el resultado completo, pero el frontend navega por ID y solicita de nuevo el detalle.

## 5. Tecnologías y versiones

### Backend e infraestructura

| Tecnología | Versión o fuente |
|---|---|
| Java | 17 en `pom.xml` |
| Spring Boot | 3.2.1 |
| Spring Web, Security, Data JPA y Validation | Gestionadas por Spring Boot |
| JJWT | 0.12.3 |
| Hibernate, Jackson, Lombok y controlador MariaDB | Dependencias gestionadas por Maven/Spring Boot |
| Testing backend | Spring Boot Starter Test y Spring Security Test declarados |
| MariaDB | Imagen `mariadb:11.2` en Compose |
| Adminer | Imagen `adminer:4.8.1` en Compose |
| Construcción backend | Maven; versión de la herramienta no fijada en el repositorio |

No se resolvieron dependencias Maven durante el análisis. Por ello no se atribuyen versiones transitivas que no se hayan comprobado.

### Frontend

Las versiones bloqueadas provienen de `package-lock.json`; no deben confundirse con los mínimos declarados ni con una inspección de cada paquete instalado localmente.

| Dependencia | Declarada | Bloqueada |
|---|---|---|
| React / React DOM | ^18.2.0 | 18.3.1 |
| React Router DOM | ^6.21.1 | 6.30.3 |
| Axios | ^1.6.5 | 1.13.5 |
| Vite | ^5.0.8 | 5.4.21 |
| Plugin React de Vite | ^4.2.1 | 4.7.0 |
| Tailwind CSS | ^3.4.0 | 3.4.19 |
| PostCSS | ^8.4.32 | 8.5.6 |
| Autoprefixer | ^10.4.16 | 10.4.24 |
| Tipos React | ^18.2.43 | 18.3.28 |
| Tipos React DOM | ^18.2.17 | 18.3.7 |

El frontend usa JavaScript y JSX, no TypeScript. Los scripts disponibles son `dev`, `build` y `preview`. No se fija una versión de Node.js en el manifiesto inspeccionado.

Referencias: [pom.xml](smartaudits-backend/pom.xml), [package.json](smartaudits-frontend/package.json), [package-lock.json](smartaudits-frontend/package-lock.json), [docker-compose.yml](docker-compose.yml).

## 6. Frontend y comunicación con la API

`main.jsx` monta la aplicación con React StrictMode. `App.jsx` define las rutas y envuelve la aplicación con `AuthProvider`.

| Grupo | Pantallas y responsabilidad |
|---|---|
| Autenticación | `Login`, `Register` |
| Aplicación | `Dashboard`, `CrearAuditoria`, `HistorialAuditorias`, `DetalleAuditoria`, `Perfil` |
| Administración | `GestionUsuarios`, `HistorialAdmin` |
| Legales públicas | `AvisoLegal`, `PoliticaPrivacidad`, `PoliticaCookies`, `CondicionesUso` |
| Normativa privada | RGPD, LOPDGDD, LSSI, ePrivacy, Ley 10/2025 y guía AEPD |

Las páginas normativas son contenido estático con navegación y pestañas; no consultan legislación ni actualizan reglas automáticamente. El dashboard es principalmente navegación e información.

`PrivateRoute` exige una sesión local y muestra un `Outlet`. `Layout` aporta sidebar y pie legal; `PublicLayout` sirve a las páginas públicas. Las rutas administrativas no tienen un guard específico por rol en React: el sidebar oculta enlaces y el backend aplica la autorización.

`AuthContext` guarda el usuario en memoria y restaura las entradas `user` y `token` de `localStorage`. No valida el token con el servidor al arrancar. El JSON local se parsea sin manejo de errores. El estado de rol del frontend puede quedar desactualizado respecto a la base de datos.

`api/axios.js` configura:

- Base URL desde `VITE_API_URL`, con dirección local del backend como alternativa.
- JSON como formato de comunicación.
- Cabecera Bearer obtenida de `localStorage` en cada petición.
- Borrado de sesión y redirección al login ante cualquier 401 o 403.

Las llamadas están en las páginas y el contexto, sin servicios frontend por dominio. Los listados se reciben completos y se filtran en memoria. No se configura un timeout explícito en el cliente Axios.

Vite dispone de un proxy `/api` que elimina ese prefijo y reenvía al backend. La base URL predeterminada de Axios apunta directamente al backend y no utiliza ese proxy. CORS está configurado en MVC para los orígenes locales de desarrollo, pero su integración con la cadena de seguridad requiere comprobación en ejecución.

Referencias: [App.jsx](smartaudits-frontend/src/App.jsx), [AuthContext.jsx](smartaudits-frontend/src/context/AuthContext.jsx), [axios.js](smartaudits-frontend/src/api/axios.js), [vite.config.js](smartaudits-frontend/vite.config.js).

## 7. Backend, endpoints y servicios

### Endpoints existentes

| Método y ruta | Función | Acceso implementado |
|---|---|---|
| `POST /auth/register` | Crear CLIENTE y emitir token | Público |
| `POST /auth/login` | Autenticar y emitir token | Público |
| `DELETE /auth/baja` | Desactivar cuenta propia | Autenticado |
| `PUT /usuarios/perfil` | Cambiar nombre, email y contraseña | Autenticado |
| `GET /usuarios` | Listar usuarios | ADMIN |
| `PATCH /usuarios/{id}/rol` | Cambiar rol | ADMIN |
| `PATCH /usuarios/{id}/desactivar` | Baja administrativa | ADMIN |
| `PATCH /usuarios/{id}/reactivar` | Reactivar cuenta | ADMIN |
| `GET /admin/historial` | Listar acciones administrativas | ADMIN |
| `POST /auditorias` | Analizar y persistir documento | Autenticado |
| `GET /auditorias/mias` | Listar auditorías propias | Autenticado |
| `GET /auditorias` | Listar todas las auditorías | ADMIN |
| `GET /auditorias/{id}` | Recuperar detalle | Propietario o ADMIN |
| `POST /auditorias/{id}/descarga` | Registrar evento de descarga | Autenticado; falta autorización sobre la auditoría |

No hay endpoints de edición, eliminación o reanálisis de auditorías, descarga de PDF binario, refresh token o cierre de sesión en el servidor. El historial de auditorías de la interfaz es una lista de auditorías, no un visor de todos los eventos de acceso.

### Servicios principales

| Servicio | Responsabilidad |
|---|---|
| `UsuarioService` | Registro, login, perfil, activación y roles |
| `AuditoriaService` | Orquestación del análisis, persistencia, listados y detalle |
| `MotorAnalisisLegal` | Evaluación determinista del texto |
| `HistorialService` | Eventos CREACION, CONSULTA y DESCARGA |
| `HistorialAdminService` | Registro y consulta de acciones administrativas |
| `DataInitializer` | Creación de roles y bootstrap opcional del administrador |

Los repositorios heredan de `JpaRepository` y usan métodos derivados. No hay paginación de los listados. Los errores de negocio, inexistencia y permisos utilizan principalmente `RuntimeException`, sin un manejador global que establezca un contrato HTTP coherente.

Referencias: [controladores](smartaudits-backend/src/main/java/com/smartaudits/controller/), [servicios](smartaudits-backend/src/main/java/com/smartaudits/service/), [repositorios](smartaudits-backend/src/main/java/com/smartaudits/repository/).

## 8. Base de datos, entidades y relaciones

MariaDB se accede mediante JPA/Hibernate con `ddl-auto=validate`. Flyway conserva las migraciones V1/V2/V3. En 2.4E no se modifica el esquema: `resultados` permanece como tabla legacy aunque ya no tiene entidad ni repositorio JPA activos. El validador aislado comprueba que esa tabla adicional no impide el arranque con `validate`.

| Tabla | Información principal |
|---|---|
| `usuarios` | ID, nombre, email único, hash BCrypt, enum de rol, activo, protegido y fecha de registro |
| `roles` | ID, nombre único y descripción |
| `usuarios_roles` | Unión N:M entre usuarios y roles |
| `auditorias` | Propietario, título, tipo, texto original, URL opcional, fecha, estado, puntuación y resultado JSON |
| `resultados` | Legacy sin mapeo JPA ni nuevas escrituras: conserva auditoría única, resumen, recomendaciones concatenadas, puntuación y fecha históricas |
| `incidencias` | Hallazgos estructurados: auditoría, categoría, severidad, descripción, recomendación, evidencia, impacto y procedencia `rule_id`/`motor`/`version` |
| `historial_auditorias` | Auditoría, usuario actor, fecha, acción e IP |
| `historial_acciones_admin` | Administrador, objetivo, acción, detalles, fecha y snapshots de nombres/emails |

```text
Usuario 1 ───────── N Auditoría
Usuario N ───────── M Rol          mediante usuarios_roles

Auditoría 1 ─────── 0..1 resultados (solo tabla legacy, sin relación JPA)
Auditoría 1 ─────── N Incidencia
Auditoría 1 ─────── N HistorialAuditoría
Usuario   1 ─────── N HistorialAuditoría

Usuario administrador 1 ── N HistorialAccionAdmin
Usuario objetivo      1 ── N HistorialAccionAdmin
```

Detalles relevantes:

- Hay seis entidades persistentes activas, una tabla de unión y la tabla legacy `resultados`. `Role` y `TipoAccionAdmin` son enums, no tablas independientes. `NivelRiesgo` se eliminó en 2.4C; `Resultado` y `ResultadoRepository` se retiran en 2.4E.
- `resultados.auditoria_id` conserva NOT NULL, UNIQUE y su FK hacia `auditorias` con comportamiento RESTRICT. Una futura eliminación física de auditorías debe considerar esas filas aunque no estén mapeadas por JPA. Eliminar físicamente la tabla requiere una decisión posterior; 2.4E no crea V4.
- Auditoría conserva cascada y `orphanRemoval` sobre incidencias e historial. Usuario los tiene sobre auditorías. La baja lógica no activa estas eliminaciones.
- `auditorias.resultado_json` es el snapshot de la salida del motor y la fuente de `AuditoriaResponse.resultado` en el detalle. El contexto y la procedencia superior se conservan en `auditorias`.
- Al crear, `auditorias.puntuacion_riesgo == resultado_json.puntuacionRiesgo`: ambos proceden del mismo `ResultadoAuditoria` en memoria y se persisten en la misma transacción. En lectura, la columna `auditorias.puntuacion_riesgo` es la puntuación canónica servida por API y utilizada por listado, detalle, copia y PDF. No hay reconciliación posterior ni corrección de históricos divergentes.
- La lectura del detalle deserializa el JSON; no reconstruye el informe desde tablas. Un JSON NULL o corrupto sigue haciendo fallar la lectura, sin fallback a `resultados`.
- `incidencias` conserva los findings estructurados. Aunque actualmente no tiene consumidores productivos de lectura, preserva la procedencia V3: las filas históricas pueden tener `ruleId`/`motor`/`version` ausentes del JSON original y no son regenerables de forma segura desde él.
- `resultados` conserva todas sus filas históricas, `fecha_resultado` y posibles valores divergentes; las nuevas auditorías no crean filas. La concatenación histórica de recomendaciones perdió estructura de lista y no se intenta invertir. No se actualizan históricos, no se reanalizan y no se reserializa su JSON.
- Compatibilidad de vuelta a `2be8a63`: una prueba aislada sobre sus fuentes exactas con H2 comprueba que el modelo anterior tolera `Resultado == null` y lee detalle/listados de una auditoría con snapshot, puntuación e incidencias sin fila legacy. No equivale a un rollback operativo de MariaDB. Una versión anterior volvería a crear filas `resultados` para nuevas auditorías; no se rellenan automáticamente las ausentes.
- El enum `usuarios.role` es la autoridad efectiva de seguridad; la relación N:M duplica esa información.
- Los textos originales y resultados JSON son columnas `TEXT`. El DTO no limita el texto de entrada conforme a la capacidad de almacenamiento.
- Las fechas usan `LocalDateTime`, sin zona horaria explícita en el modelo.
- El historial administrativo guarda snapshots para mantener la atribución legible tras cambios de perfil. No guarda una IP, pese a comentarios que sugieren trazabilidad del origen.
- Las bajas conservan usuarios, hashes, textos, informes e historiales. No hay purga, anonimización o retención automatizada.

Referencias: [modelos](smartaudits-backend/src/main/java/com/smartaudits/model/), [AuditoriaService.java](smartaudits-backend/src/main/java/com/smartaudits/service/AuditoriaService.java).

## 9. Entrada de URL y textos, y procesamiento

`CrearAuditoria` contiene un formulario con título obligatorio, selector de tipo, URL opcional y un único textarea obligatorio.

Tipos ofrecidos: Política de Privacidad, Aviso Legal, Política de Cookies, Términos y Condiciones, Condiciones de Uso y Documento Completo. «Documento Completo» sigue siendo un único texto pegado: no separa ni relaciona documentos.

La URL usa un input HTML de tipo `url`. En el servidor solo se limita su longitud; no se valida esquema ni se realiza ninguna conexión. No hay adjuntos, lectura de PDF o DOCX, extracción de HTML ni descubrimiento automático.

Validaciones del DTO:

| Campo | Validación backend |
|---|---|
| `titulo` | No vacío; máximo 200 caracteres |
| `tipoDocumento` | Máximo 50; no obligatorio y sin catálogo cerrado |
| `textoOriginal` | No vacío; sin máximo declarado |
| `urlOpcional` | Máximo 500; sin validación de URL |

Secuencia de procesamiento:

1. Axios envía los cuatro campos a `POST /auditorias` con el JWT.
2. Spring valida el DTO y el controlador recupera al usuario autenticado.
3. `AuditoriaService.crearAuditoria`, transaccional, invoca `analyze(textoOriginal, tipoDocumento)`.
4. El motor devuelve un `ResultadoAuditoria` en memoria.
5. El servicio crea la auditoría con texto, metadatos, puntuación y estado `COMPLETADA`.
6. Jackson serializa el resultado en JSON, y se guarda la auditoría.
7. Se crea una `Incidencia` por error y se persiste mediante cascada. No se crea ninguna fila en `resultados`; se mantienen los dos `save` y la transacción existente.
8. Se intenta registrar CREACION con usuario e IP.
9. Se devuelve `AuditoriaResponse` completo con HTTP 201.

Cada envío crea una auditoría independiente. No hay idempotencia implementada, asociación por dominio ni versionado de documentos.

Referencias: [CrearAuditoria.jsx](smartaudits-frontend/src/pages/CrearAuditoria.jsx), [AuditoriaRequest.java](smartaudits-backend/src/main/java/com/smartaudits/model/dto/AuditoriaRequest.java), [AuditoriaService.java](smartaudits-backend/src/main/java/com/smartaudits/service/AuditoriaService.java).

## 10. Funcionamiento exacto del motor actual

### Normalización y detección

`MotorAnalisisLegal.analyze(String texto, String tipoDocumento)` recibe el texto y su tipo. El tipo no se utiliza en la evaluación.

Para texto nulo o en blanco devuelve un resultado especial con puntuación cero y mensaje de entrada vacía. El endpoint normal exige texto no vacío, por lo que ese caso actúa como defensa interna del motor.

La normalización aplica Unicode NFD, elimina diacríticos, convierte a minúsculas con locale español, sustituye caracteres fuera del conjunto permitido y colapsa espacios. Las palabras clave y patrones se normalizan también. La comparación utiliza `String.contains`, sin límites de palabra ni interpretación semántica.

### Catálogo de reglas

Cada regla contiene ID, título, descripción, severidad, peso, palabras clave, patrones de riesgo, impacto, acción, referencia legal, texto de cumplimiento sugerido y campo faltante.

| Regla | Tema | Severidad base | Peso |
|---|---|---|---:|
| R01 | Identidad del responsable | ALTA | 8 |
| R02 | Datos de contacto | ALTA | 6 |
| R03 | Delegado de protección de datos | MEDIA | 4 |
| R04 | Finalidades | ALTA | 8 |
| R05 | Base jurídica | ALTA | 8 |
| R06 | Derechos del interesado | ALTA | 8 |
| R07 | Conservación | MEDIA | 5 |
| R08 | Destinatarios y cesiones | MEDIA | 5 |
| R09 | Transferencias internacionales | MEDIA | 4 |
| R10 | Cookies | ALTA | 7 |
| R11 | Reclamación ante autoridad de control | MEDIA | 4 |
| R12 | Consentimiento | ALTA | 6 |
| R13 | Menores | BAJA | 3 |
| R14 | Categorías especiales de datos | BAJA | 3 |
| R15 | Medidas de seguridad | MEDIA | 4 |
| R16 | Aviso legal | ALTA | 7 |
| R17 | Decisiones automatizadas y perfilado | MEDIA | 4 |
| R18 | Brechas de seguridad | MEDIA | 3 |
| R19 | Comunicaciones comerciales | MEDIA | 3 |
| **Total** | | | **100** |

### Evaluación y puntuación

La puntuación comienza en cero. Para cada una de las 19 reglas:

1. Si no aparece ninguna palabra clave, aporta cero puntos y genera error, riesgo, recomendación, texto sugerido, campo faltante y referencia específica.
2. Si aparece al menos una palabra clave, se buscan los patrones de riesgo de esa regla en todo el texto, no en una cláusula delimitada.
3. Si aparece un patrón, se añade una incidencia ALTA y se concede `peso / 2` mediante división entera; se detiene la búsqueda de patrones de esa regla.
4. Si no aparece un patrón problemático, se concede el peso completo.

Después se evalúan cinco grupos globales: cesión de datos, conservación indefinida, consentimiento tácito, exclusión de responsabilidad en seguridad y tecnologías de seguimiento invasivo. Cada grupo detectado genera una incidencia y resta dos puntos, con una sola coincidencia contabilizada por grupo. La penalización máxima global es diez puntos.

La puntuación final se limita a 0–100. Se añaden siempre tres recomendaciones generales: revisión periódica, información en dos capas en formularios y formación/procedimientos de brechas.

| Puntuación | Resumen de riesgo | Etiqueta frontend |
|---|---|---|
| 85–100 | BAJO RIESGO | Cumplimiento Alto |
| 65–84 | RIESGO MODERADO | Cumplimiento Medio |
| 40–64 | RIESGO ALTO | Cumplimiento Bajo |
| 0–39 | RIESGO MUY ALTO | Cumplimiento Crítico |

El campo `puntuacionRiesgo` representa en realidad una puntuación de cumplimiento: cuanto mayor, mejor. La clasificación depende de la puntuación, no de una condición independiente que impida bajo riesgo cuando exista alguna incidencia ALTA.

### Resultado y evidencia

El DTO devuelve resumen, puntuación, riesgos, errores, recomendaciones, textos sugeridos, referencias legales y faltantes. Cada error incluye título, descripción, severidad, evidencia, impacto y acción.

Las evidencias son mensajes de ausencia o el patrón detectado. No se guardan fragmentos con contexto, posiciones en el documento, URL de evidencia, versión del motor ni ID de regla por incidencia. Los textos sugeridos son plantillas estáticas con marcadores, no redacciones adaptadas a la empresa. Los problemas de redacción y riesgos globales incluyen acción en el error, pero no reciben el mismo conjunto de sugerencias y faltantes que una regla ausente.

### Límites de interpretación

- Todas las reglas se aplican a todos los tipos de documento, sin estados de «no aplica».
- Una sola palabra puede satisfacer una obligación compuesta: «cookies» satisface la detección de R10 y `https` puede satisfacer R15.
- Una mención a un derecho puede satisfacer la regla que pretende cubrir un conjunto de derechos.
- No se verifica que contactos, plazos, garantías o medidas descritas existan o sean correctos.
- No hay tratamiento general de negaciones: «no conservamos datos indefinidamente» coincide con un patrón global problemático.
- Una expresión puede generar penalización de regla y global simultáneamente.
- No se comprueba ningún comportamiento real de la web ni se distingue evidencia ausente de incumplimiento demostrado.
- Las referencias normativas, fechas y afirmaciones jurídicas están codificadas. No se actualizan desde fuentes oficiales y no constituyen una validación completa de las normas mencionadas.

Referencia: [MotorAnalisisLegal.java](smartaudits-backend/src/main/java/com/smartaudits/service/motor/MotorAnalisisLegal.java).

## 11. Autenticación y autorización

Spring Security utiliza JWT y una política de sesión `STATELESS`. CSRF está deshabilitado. Las contraseñas se almacenan con BCrypt, coste 12. El registro crea CLIENTE activo y devuelve un token inmediatamente.

El JWT contiene email como sujeto, fecha de emisión y expiración, rol e ID de usuario. La configuración fija una duración de 24 horas. La firma usa una clave HMAC decodificada desde Base64; el código deja que JJWT seleccione el algoritmo compatible al firmar.

El filtro extrae el email, carga al usuario desde la base de datos, valida firma y expiración y construye la autenticación con el rol actual de ese usuario. No utiliza el rol local del navegador como autoridad. Tampoco contrasta el `userId` del token con el usuario recuperado por email.

Aspectos funcionales y límites:

- Login comprueba `activo`, pero `CustomUserDetails.isEnabled()` devuelve siempre `true` y el filtro JWT no comprueba la baja.
- Logout elimina únicamente `token` y `user` del navegador.
- No hay revocación, refresh tokens, MFA, verificación de email, recuperación de contraseña ni limitación de intentos implementada.
- El cambio de contraseña exige la actual, pero no invalida tokens anteriores.
- El cambio de email no exige contraseña actual cuando no se cambia contraseña y emite un nuevo token.
- La autorización ADMIN se comprueba manualmente en controladores. El detalle comprueba propietario o ADMIN en el servicio.
- El endpoint de descarga no comprueba propietario ni ADMIN.
- Las acciones administrativas impiden modificar al usuario protegido. Las bajas (incluida la propia), reactivaciones y cambios de rol conservan el bloqueo pesimista de la fila `roles.nombre = ADMIN` durante la transacción; el último ADMIN activo se comprueba mediante `Usuario.role` después de adquirir ese bloqueo.

`DataInitializer` crea los roles si faltan. Si el bootstrap está habilitado, no hay ningún ADMIN y se suministran credenciales explícitas válidas, crea un administrador protegido. No tiene credenciales de administrador predeterminadas. Su comprobación de existencia incluye administradores inactivos: no garantiza recuperar un sistema que haya quedado sin ADMIN activo.

Referencias: [SecurityConfig.java](smartaudits-backend/src/main/java/com/smartaudits/config/SecurityConfig.java), [seguridad](smartaudits-backend/src/main/java/com/smartaudits/security/), [UsuarioService.java](smartaudits-backend/src/main/java/com/smartaudits/service/UsuarioService.java), [DataInitializer.java](smartaudits-backend/src/main/java/com/smartaudits/config/DataInitializer.java).

## 12. Generación del informe

El backend genera un resultado estructurado y lo persiste. La presentación y exportación se implementan en `DetalleAuditoria.jsx`.

La pantalla muestra puntuación, resumen, riesgos, errores con evidencia/impacto/acción, recomendaciones, textos sugeridos, faltantes, referencias legales y texto original. Las referencias enlazan a páginas normativas internas.

«Copiar Informe Completo» compone texto para el portapapeles. Los textos sugeridos también pueden copiarse individualmente.

«Descargar PDF»:

1. Intenta registrar DESCARGA en el backend.
2. Continúa aunque falle ese registro.
3. Construye un documento HTML mediante interpolación de cadenas.
4. Abre una ventana con `window.open`, escribe mediante `document.write` y llama a `print()` tras una espera fija.
5. El usuario imprime o guarda como PDF desde el navegador.

No se genera un PDF binario en el servidor ni se almacena un archivo exportado. No hay firma del informe. El evento DESCARGA acredita el intento, no que la impresión o el guardado hayan terminado.

El informe copiado y el imprimible omiten textos sugeridos y texto original, presentes en pantalla. El HTML incluye la etiqueta «URL analizada» aunque la URL solo sea una referencia. Las plantillas no escapan los campos introducidos por el usuario. Tampoco se comprueba si la apertura de ventana devuelve `null` por bloqueo de popups.

Referencia: [DetalleAuditoria.jsx](smartaudits-frontend/src/pages/DetalleAuditoria.jsx).

## 13. Variables de entorno y servicios externos

| Variable | Uso |
|---|---|
| `VITE_API_URL` | Dirección de la API |
| `DB_URL` | Conexión a MariaDB |
| `DB_USERNAME` | Usuario de base de datos |
| `DB_PASSWORD` | Contraseña de base de datos |
| `JWT_SECRET` | Clave de firma JWT |
| `SMARTAUDITS_ADMIN_EMAIL` | Email de bootstrap |
| `SMARTAUDITS_ADMIN_PASSWORD` | Contraseña inicial de bootstrap |
| `SMARTAUDITS_ADMIN_NOMBRE` | Nombre del administrador inicial |

Además hay propiedades para habilitar el bootstrap y fijar expiración JWT, puerto, logging y comportamiento JPA. En Compose se declaran las variables de inicialización de MariaDB `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD` y `MYSQL_ROOT_PASSWORD`.

Se detectaron valores sensibles de respaldo versionados en la configuración de conexión y firma JWT, y contraseñas literales en Compose. No se reproducen en este documento. Su existencia no demuestra que un despliegue use esos valores, pero hace inseguro utilizarlos como respaldo.

El motor no envía textos a APIs externas. El CSS activo sí importa una fuente desde Google Fonts, por lo que el frontend tiene una dependencia de red externa. BOE, EUR-Lex y AEPD aparecen como enlaces documentales, no como integraciones de análisis. No hay correo transaccional, proveedor de IA, almacenamiento externo de informes o analítica integrada identificados.

La configuración base activa SQL visible y logging DEBUG de aplicación y Spring Security. Los logs del código incluyen emails en diversas acciones. No se ha inspeccionado la política real de acceso o conservación de logs del despliegue.

## 14. Tests y cobertura

No existen tests fuente versionados de backend o frontend, suites end-to-end ni informes de cobertura. El directorio local de clases de test inspeccionado estaba vacío.

El backend declara dependencias de pruebas; eso no equivale a disponer de casos de prueba. El frontend no define script de test ni framework específico de pruebas. No se identificó pipeline de CI versionado.

La cobertura automatizada aportada por los tests del repositorio es, en términos prácticos, 0 %. No es una medición instrumental de cobertura de líneas. No se ejecutaron pruebas ni compilaciones durante esta revisión.

## 15. Riesgos y problemas por criticidad

La criticidad es una priorización técnica, no una puntuación CVSS. «Confirmado por código» identifica la ruta o carencia comprobada; no significa que se haya explotado en ejecución.

| ID | Criticidad | Hallazgo y evidencia | Impacto y condiciones |
|---|---|---|---|
| SEC-01 | Crítica condicionada | Respaldo de clave JWT versionado en `application.properties` | Si se utiliza, permite a quien conozca la clave falsificar tokens para cuentas existentes. Confirmada la configuración, no su uso real. |
| SEC-02 | Alta | Interpolación sin escape en `DetalleAuditoria.jsx` y `document.write` | Ruta de XSS persistente al imprimir contenido introducido por usuarios. Puede afectar a un administrador que exporte una auditoría ajena y exponer la sesión accesible desde JavaScript. Sin explotación ejecutada. |
| SEC-03 | Alta | `isEnabled()` siempre verdadero; filtro JWT no comprueba `activo` | Las bajas no invalidan el acceso con tokens aún vigentes. Confirmado por código. |
| SEC-04 | Alta | Cambio de contraseña sin revocación o versión de sesión | Un token robado sigue siendo válido hasta expirar aunque se cambie la contraseña. |
| SEC-05 | Alta condicionada | Sujeto JWT basado en email mutable; `userId` no contrastado | Si se libera y reasigna un email antes de expirar un token antiguo, puede autenticarse contra la nueva cuenta. Escenario derivado del código, pendiente de reproducción. |
| SEC-06 | Alta condicionada | Credenciales de BD versionadas; puertos MariaDB/Adminer publicados en Compose | Riesgo de acceso a datos si se usan esas credenciales y los servicios son alcanzables desde redes no confiables. La exposición efectiva depende del entorno. |
| SEC-07 | Media | `POST /auditorias/{id}/descarga` sin control de propiedad | Permite registrar eventos sobre auditorías ajenas. El endpoint no devuelve el contenido de la auditoría. |
| SEC-08 | Media | Sin límites de intentos, cuotas ni máximo de texto en la aplicación | Posible abuso del registro, autenticación y análisis o consumo excesivo de recursos. Pueden existir límites externos no visibles en el repositorio. |
| SEC-09 | Media | Confianza directa en `X-Forwarded-For` | IP de historial falsificable si el proxy no elimina o controla la cabecera. |
| FUN-01 | Alta | Motor acepta subcadenas y aplica todas las reglas siempre | Falsos positivos y negativos; no permite interpretar la puntuación como verificación integral de cumplimiento. |
| FUN-02 | Alta operativa | Baja propia sin protección del último ADMIN; bootstrap cuenta inactivos | Puede quedar el sistema sin administrador activo y sin recuperación automática. |
| FUN-03 | Media, pendiente | CORS MVC sin activación explícita en Security; OPTIONS no exento | Puede bloquear preflight en el flujo directo entre frontend y backend. Requiere verificación de ejecución. |
| FUN-04 | Media | Texto ilimitado y columnas `TEXT` | Posibles fallos de persistencia al superar capacidad, además de consumo de memoria y CPU. |
| FUN-05 | Media | Listados completos y navegación de relaciones JPA | Escalabilidad limitada y posibles consultas adicionales. |
| FUN-06 | Media | Excepciones genéricas sin contrato de errores | Permisos, conflictos y recursos ausentes pueden producir respuestas HTTP inadecuadas o mensajes genéricos. |
| FUN-07 | Media | Axios elimina sesión también ante 403 | Una denegación de permisos expulsa a un usuario correctamente autenticado. |
| DAT-01 | Media | `ddl-auto=update` sin migraciones | Evolución del esquema difícil de revisar, reproducir y revertir. |
| FUN-08 | Baja | `window.open` sin comprobación de resultado | Popups bloqueados pueden romper la exportación y dejar estado de carga inconsistente. |
| FUN-09 | Baja | `JSON.parse` de usuario local sin captura | Almacenamiento local corrupto puede impedir inicialización normal de la interfaz. |

Otros aspectos a revisar: contraseñas con mínimo de seis caracteres, falta de reautenticación para cambiar email, logs con datos personales y ausencia de mecanismos implementados de retención o supresión. No se concluye que el despliegue carezca de todos los controles externos, ni que existan CVE concretas por la antigüedad de una versión.

Puntos positivos existentes: BCrypt, validación de firma y expiración, DTOs que evitan devolver hashes, comprobación de propietario en detalle, roles administrativos comprobados contra la BD y bootstrap de administrador sin credenciales predeterminadas.

## 16. Deuda técnica y partes frágiles

### Duplicación y contratos

- `PrivateRoute.jsx` y `PrivateRoutes.jsx` duplican el mismo componente; se utiliza el primero.
- Los umbrales 85/65/40 tienen una implementación activa por capa: `MotorAnalisisLegal.generarResumen` y `helpers.js`. El enum sin consumidores `NivelRiesgo` se eliminó en la fase 2.4C. Los tests de ambas capas verifican las mismas fronteras mediante `smartaudits-backend/src/test/resources/motor/risk-boundaries.json`, sin compartir configuración de producción.
- `Usuario.role` concede las authorities y determina el conteo del último ADMIN; la membresía N:M no autoriza. En la fase 2.4D, registro, bootstrap y cambio administrativo sincronizan ambas representaciones mediante `RolUsuarioService`, que exige la transacción del llamador y falla si falta el catálogo requerido. Solicitar el mismo rol repara solo la membresía del usuario objetivo, sin revocar tokens ni registrar un cambio de rol ficticio. No se reparan datos históricos en bloque. `roles`, `usuarios_roles` y el bloqueo ADMIN se conservan; el frontend centraliza los identificadores en `utils/roles.js` y mantiene `AuthContext.isAdmin` para sus consumidores.
- En 2.4E se retira la duplicación activa de `resultados`, conservando la tabla legacy y sus datos. El contenido del detalle procede del snapshot JSON y la puntuación presentada de `auditorias.puntuacion_riesgo`; incidencias conserva findings y procedencia histórica. Las pruebas cubren igualdad al crear, divergencias históricas sin reparación, JSON inválido sin fallback y ausencia de SQL JPA a `resultados`.
- Pantalla, copia e impresión tienen plantillas y contenidos distintos.
- La auditoría conserva versión de motor/reglas y las incidencias conservan procedencia; el JSON no tiene una versión propia de esquema. Los errores históricos sin los campos de procedencia siguen leyéndose sin inventarlos.
- Estado de auditoría, tipo documental y algunas clasificaciones son cadenas sin un contrato cerrado uniforme.

### Organización y persistencia

- El motor concentra reglas, mensajes, referencias y puntuación en más de 800 líneas.
- Páginas extensas mezclan peticiones, estado, presentación y exportación.
- Los listados no paginan; algunos recuentos cargan usuarios o colecciones completas.
- El acceso a relaciones fuera de métodos transaccionales explícitos puede depender de sesión JPA abierta durante la petición.
- Algunas entidades bidireccionales usan Lombok `@Data`, con riesgo de cargas o recursión en métodos generados.
- Capturar excepciones en `HistorialService` no garantiza que un fallo de persistencia no marque la transacción como rollback-only o aparezca al hacer flush/commit.
- Consultar detalle provoca una escritura de historial. StrictMode puede duplicar efectos y consultas en desarrollo, generando eventos adicionales.
- El control del último administrador mediante conteo no garantiza integridad ante operaciones concurrentes.

### Configuración y presentación

- `application-dev.properties` contiene restos de Markdown y contenido destinado a `.gitignore`.
- `.env.example` también contiene restos de Markdown y reglas de `.gitignore`.
- Hay dos `index.css`; `main.jsx` solo importa la de `src`. La fuente descargada y la configurada en Tailwind no coinciden.
- Sidebar fijo y margen permanente requieren revisión en móvil.
- El favicon referenciado no está en el directorio público inspeccionado.
- Documentación pública indica BCrypt coste 10, pero el código usa 12; también declara propiedades de producción no demostradas por la configuración versionada.
- Expirar el JWT no elimina automáticamente las entradas de `localStorage`, pese a descripciones de duración de la sesión en páginas públicas.
- Denominaciones y fechas normativas difieren entre páginas y motor. Necesitan revisión editorial y jurídica separada.
- El texto «URL analizada» y algunos usos de «IA» sobrestiman el alcance implementado.

## 17. Limitaciones actuales respecto a una auditoría automática

La unidad de entrada es un texto manual. No hay modelo de dominio con páginas descubiertas, documentos relacionados, ejecución de navegador, recursos cargados o evidencias técnicas. El sistema no verifica que el texto corresponda a la URL ni que siga publicado.

No se inspeccionan scripts, peticiones de red, cookies almacenadas, trackers, banners, botones, formularios, navegación por teclado o accesibilidad. Las menciones de esos temas en reglas y páginas informativas no representan capacidades de inspección.

Tampoco hay ejecución asíncrona, progreso, reintentos de auditoría, estados de fallo persistidos, comparación entre ejecuciones o corpus de validación de resultados. Los informes no preservan versión de reglas o evidencias localizadas que permitan reproducir una evaluación anterior con precisión.

Estas son limitaciones del estado actual; este documento no diseña las funcionalidades futuras.

## 18. Componentes reutilizables para SmartAudits 2.0

| Componente | Valor reutilizable | Condición o límite |
|---|---|---|
| SPA y API separadas | Evolución progresiva de interfaz y backend | Asegurar contratos, CORS y configuración |
| Capas Controller/Service/Repository | Organización clara de responsabilidades | Evitar acoplar nuevas comprobaciones a controladores |
| Usuarios y roles | Base de identidad y administración | Corregir JWT, bajas, permisos y duplicidad de roles |
| Auditorías por usuario | Historial y asociación de resultados | Actualmente representan un texto, no un dominio |
| Incidencias | Estructura descriptiva de hallazgos | Carecen de ID de regla persistido, contexto y evidencia técnica |
| Historial administrativo con snapshots | Atribución de acciones | Completar garantías y alcance de trazabilidad |
| Historial y detalle frontend | Navegación y visualización aprovechables | Unificar formatos y corregir exportación |
| Motor textual | Comprobación heurística complementaria | Explicitar alcance, aplicabilidad y validar reglas |
| Contenido normativo | Material inicial de documentación | Revisar exactitud, consistencia y mantenimiento |
| Compose local | Entorno de desarrollo | Revisar credenciales y exposición de servicios |

El modelo de auditoría textual debe seguir siendo reconocible al evolucionar el producto. Reutilizar estos componentes no implica que el motor actual pueda verificar por sí solo una web completa.

## 19. Recomendaciones previas a comenzar SmartAudits 2.0

Estas recomendaciones son prioridades preparatorias, no cambios autorizados ni un diseño de las funciones futuras.

1. Corregir exportación HTML, autorización de descargas, validación de cuentas activas y ciclo de vida de tokens, incluida identidad estable y revocación.
2. Retirar respaldos sensibles y rotar los secretos que se hayan usado; revisar exposición de MariaDB y Adminer.
3. Verificar el arranque reproducible, CORS y flujo completo; limpiar los archivos de configuración contaminados.
4. Incorporar pruebas de regresión del motor, aislamiento entre usuarios, bajas, roles y generación de informes.
5. Definir el significado de la puntuación y distinguir «no detectado» de «incumplimiento demostrado»; validar jurídicamente el catálogo.
6. Establecer una fuente de verdad para roles y resultados, migraciones de esquema y criterios de compatibilidad histórica.
7. Limitar entradas, paginar listados y normalizar errores y trazabilidad.
8. Alinear pantalla, copia, impresión y documentación pública con el comportamiento real.
9. Conservar esta descripción como referencia del estado inicial antes de ampliar el alcance del producto.

## 20. Referencias principales de implementación

| Área | Archivos |
|---|---|
| Descripción original | [README.md](README.md) |
| Rutas y sesión frontend | [App.jsx](smartaudits-frontend/src/App.jsx), [AuthContext.jsx](smartaudits-frontend/src/context/AuthContext.jsx) |
| Transporte HTTP | [axios.js](smartaudits-frontend/src/api/axios.js) |
| Entrada y salida | [CrearAuditoria.jsx](smartaudits-frontend/src/pages/CrearAuditoria.jsx), [DetalleAuditoria.jsx](smartaudits-frontend/src/pages/DetalleAuditoria.jsx) |
| API de auditorías | [AuditoriaController.java](smartaudits-backend/src/main/java/com/smartaudits/controller/AuditoriaController.java) |
| Orquestación | [AuditoriaService.java](smartaudits-backend/src/main/java/com/smartaudits/service/AuditoriaService.java) |
| Reglas | [MotorAnalisisLegal.java](smartaudits-backend/src/main/java/com/smartaudits/service/motor/MotorAnalisisLegal.java) |
| Contrato de resultados | [ResultadoAuditoria.java](smartaudits-backend/src/main/java/com/smartaudits/model/dto/ResultadoAuditoria.java) |
| Usuarios | [UsuarioService.java](smartaudits-backend/src/main/java/com/smartaudits/service/UsuarioService.java) |
| JWT | [JwtUtil.java](smartaudits-backend/src/main/java/com/smartaudits/security/JwtUtil.java), [JwtAuthenticationFilter.java](smartaudits-backend/src/main/java/com/smartaudits/security/JwtAuthenticationFilter.java), [CustomUserDetails.java](smartaudits-backend/src/main/java/com/smartaudits/security/CustomUserDetails.java) |
| Seguridad y CORS | [SecurityConfig.java](smartaudits-backend/src/main/java/com/smartaudits/config/SecurityConfig.java), [CorsConfig.java](smartaudits-backend/src/main/java/com/smartaudits/config/CorsConfig.java) |
| Bootstrap | [DataInitializer.java](smartaudits-backend/src/main/java/com/smartaudits/config/DataInitializer.java) |
| Historiales | [HistorialService.java](smartaudits-backend/src/main/java/com/smartaudits/service/HistorialService.java), [HistorialAdminService.java](smartaudits-backend/src/main/java/com/smartaudits/service/HistorialAdminService.java) |

