# Fase 0.4: autenticación y JWT

Este documento complementa la fotografía inicial de `CURRENT_ARCHITECTURE.md`.
El alcance es autenticación, invalidación de tokens y protección administrativa.
No modifica el motor, informes, XSS ni la autorización específica de descarga.
Incluye únicamente el ajuste del interceptor frontend para distinguir 401 y 403.

## Estrategia

- `sub` identifica el ID numérico estable de `Usuario`; no contiene su email.
- Se conservan los claims `userId` y `role` por continuidad del formato. Se exige
  que `userId` coincida con `sub`. La autoridad efectiva procede del rol en BD,
  nunca del claim `role` ni del almacenamiento del navegador.
- El nuevo claim obligatorio `tokenVersion` debe coincidir con `usuarios.token_version`.
- Cada petición con Bearer valida firma, expiración, ID, existencia del usuario,
  estado activo y versión persistida. No se acepta `activo` nulo como activo.
- Login sigue usando email y contraseña. Spring Security comprueba el estado
  mediante `CustomUserDetails.isEnabled()`. El token se firma con el principal
  cuya contraseña acaba de verificarse, sin recargar otra identidad por email.
- Registro y edición de perfil mantienen el contrato `AuthResponse`; el frontend
  existente puede seguir guardando el token de la respuesta.

## Versión e invalidación

Se mantienen dos columnas BIGINT NOT NULL, con valor inicial/predeterminado 0:

- `row_version` (`rowVersion`): único campo `@Version`. Hibernate lo administra
  para detectar escrituras concurrentes obsoletas; nunca se incluye en el JWT.
- `token_version` (`tokenVersion`): campo normal persistido. `UsuarioService`
  lo incrementa explícitamente dentro de la transacción que cambia contraseña,
  email, rol, desactiva o reactiva la cuenta. Cambiar email y contraseña juntos
  produce una única revocación. El incremento utiliza `Math.incrementExact` para
  impedir el retorno silencioso a una versión anterior por desbordamiento.

Cambiar solo el nombre puede incrementar `row_version`, pero conserva
`token_version` y la validez de los tokens. Consultas, auditorías e historiales no
revocan sesiones. Solicitar el mismo rol, reactivar una cuenta ya activa o guardar
un perfil idéntico tampoco revoca. El registro y el bootstrap inicializan
`token_version` a 0; asignar la relación N:M durante el registro no es una
revocación. Los permisos actuales dependen del enum `role`, no de esa colección.
No se mantiene una tabla de sesiones ni una lista de tokens revocados.

Registro y perfil realizan `saveAndFlush` antes de emitir el token para firmar la
versión resultante, no la previa. La respuesta solo llega después de completarse
la transacción del servicio. Un cambio de contraseña fallido revierte la operación
y conserva la validez del token anterior. Reactivar una cuenta no resucita tokens
de antes de su baja.

El perfil, la baja y las operaciones administrativas reciben el ID del principal
autenticado. `AuditoriaController` e `HistorialAdminController` recargan el usuario
mediante `obtenerPorId`, incluyendo ownership, evaluación de rol y actor de los
historiales. Ningún controlador vuelve a resolver la identidad por email. Login
conserva el email como dato de acceso, antes de establecer el principal.
La reasignación concurrente de un email no puede sustituir el ID de la petición.

## Firma y contexto del token

La emisión fija **HS256**. El parser vacía su lista de algoritmos y admite solo
HS256, incluso cuando la clave tenga longitud suficiente para HS384 o HS512.
La clave sigue siendo `JWT_SECRET`, Base64 de al menos 32 bytes aleatorios; no se
cambia ni se publica su valor. Se mantiene la expiración `JWT_EXPIRATION`.

Se emiten y exigen estos claims, configurables con Spring Environment:

| Propiedad | Variable de entorno | Valor predeterminado seguro |
| --- | --- | --- |
| `jwt.issuer` | `JWT_ISSUER` | `smartaudits` |
| `jwt.audience` | `JWT_AUDIENCE` | `smartaudits-api` |

Son identificadores, no secretos. Configurarlos de forma consistente en las
instancias del mismo servicio; usar claves distintas entre entornos. No añaden
infraestructura ni dependencias. Cambiarlos invalida los JWT del contexto anterior.
No hay refresh tokens, OAuth o MFA.

## Respuestas HTTP y frontend

- Autenticación ausente, inválida, expirada o revocada: **401**.
- Principal autenticado sin permiso: **403**. Las restricciones administrativas
  usan `AccessDeniedException`.
- Conflicto de optimistic locking en los controladores de usuarios/autenticación:
  **409**, con un mensaje genérico para recargar y reintentar, sin datos internos.
  `UsuarioConflictHandler` trata las excepciones de Spring y de JPA; no captura
  indiscriminadamente otros errores ni constituye una normalización general.
- Axios elimina la sesión y redirige al login únicamente ante 401. Ante 403 o 409
  conserva la sesión y propaga el error a la pantalla. El acceso opcional a
  `import.meta.env` permite probar este mismo módulo con Node sin transformar código.

## Administradores y concurrencia

La cuenta protegida no puede darse de baja, ser desactivada ni degradada. La baja
propia y la baja administrativa comparten la comprobación del último ADMIN activo.
Los servicios administrativos comprueban también que el actor siga siendo ADMIN
y esté activo.

Las bajas, reactivaciones y cambios de rol toman un bloqueo pesimista de escritura
sobre la fila estable `roles.nombre = ADMIN`, creada por el bootstrap actual.
El bloqueo se conserva hasta el commit/rollback y serializa estas operaciones
entre procesos que usan la misma base de datos. No depende de un `synchronized`
local a una JVM. Si falta esa fila, la operación falla de forma cerrada.

Las transacciones administrativas usan READ_COMMITTED y consultan en BD el número
de administradores activos después de adquirir el bloqueo. Esto evita que dos
bajas o degradaciones concurrentes consuman simultáneamente al último ADMIN.
`@Version` protege además contra escrituras concurrentes sobre el mismo usuario.

La garantía preserva un administrador activo si ya existe uno y los cambios se
realizan mediante estos servicios. No crea automáticamente un administrador en
una instalación vacía ni recupera bases que ya no tengan ADMIN activo. El bootstrap
existente y su configuración siguen siendo necesarios. Las modificaciones SQL
directas pueden saltarse estas garantías.

## Actualización y compatibilidad

**Todos los JWT anteriores a esta fase se rechazan. Los usuarios deberán iniciar
sesión de nuevo tras desplegarla.** No hay fallback que acepte sujetos por email o
tokens sin versión, ya que reintroduciría el problema de identidad/revocación.

No se han ejecutado cambios contra la base real. Esquema requerido para `usuarios`:

- Desde antes de la Fase 0.4: añadir `token_version` y `row_version`, ambas
  BIGINT NOT NULL DEFAULT 0, inicializando las filas existentes.
- Si ya se aplicó la primera implementación de 0.4: conservar `token_version`
  y sus valores; añadir únicamente `row_version` BIGINT NOT NULL DEFAULT 0.
  No renombrar, borrar ni reiniciar las versiones de revocación existentes.

Con `ddl-auto=update`, Hibernate intentará añadir las columnas al arrancar. Antes
del despliegue debe validarse sobre una copia aislada de MariaDB la actualización
desde ambos estados, los valores de filas existentes y los bloqueos concurrentes.
Esa comprobación no se ha realizado aquí por el límite solicitado. Si la
actualización automática está desactivada, provisionar el esquema antes de arrancar.
No se introduce todavía un sistema de migraciones general.

Los JWT de la primera implementación 0.4 también carecen de issuer/audience y
se rechazan: tras desplegar estas correcciones se necesita iniciar sesión de nuevo.

No ejecutar simultáneamente instancias antiguas y nuevas durante la actualización:
las antiguas no comprueban ni incrementan la versión. Detener instancias antiguas,
asegurar el esquema compatible y desplegar la nueva versión. Una vuelta al código
anterior perdería estas garantías y requiere una decisión operativa explícita.

Los cambios administrativos invalidan la sesión del usuario afectado. La edición
correcta del perfil entrega un token nuevo para la sesión que realizó el cambio;
los tokens de otros dispositivos dejan de validar si se cambian credenciales,
pero siguen siendo válidos si únicamente cambia el nombre. Las pestañas que
comparten localStorage pueden adoptar el token nuevo. El ajuste mínimo 401/403
está incluido en esta fase por autorización explícita; no amplía otros permisos.

## Pruebas y alcance de la validación

Se añade H2 solo con scope `test`, versión gestionada por Spring Boot. Permite
comprobar el incremento real de `@Version`, rollback, bloqueo transaccional y
operaciones concurrentes sin mocks de persistencia y sin acceder a MariaDB real.
No modifica el driver ni las dependencias de producción.

`JwtSecurityTest` usa MockMvc, la cadena de seguridad y el filtro/JWT reales;
el repositorio y el servicio de negocio se simulan para aislar el acceso HTTP.
Cubre 17 casos: acceso activo, baja con token previo, login inactivo mediante el
proveedor de autenticación, versión obsoleta, permisos CLIENTE/ADMIN, rol de BD
frente al claim, identidad con email reutilizado, JWT antiguo/sin versión, IDs
contradictorios, expiración/firma incorrecta/formato inválido y usuario eliminado.
Los nuevos casos comprueban HS256 frente a firmas válidas HS384/HS512 con la misma
clave larga, issuer/audience configurados y obligatorios, claims incorrectos,
401/403, conflictos JPA/Spring con respuesta 409 y la identidad por ID en los
flujos de auditorías e historial tras simular la reasignación entre filtro y
controlador. La simulación es determinista, sin cambios de email por SQL real.

`UsuarioSecurityPersistenceTest` usa H2 efímera y servicios transaccionales reales.
Sus 16 casos cubren contraseña correcta/fallida, registro con versión persistida,
actualización obsoleta, cambio/reutilización de email, baja/reactivación, cuenta
protegida, último ADMIN, actor sin permisos, cambios de rol y dos escenarios
concurrentes: bajas propias y degradaciones cruzadas. Se añaden nombre sin
revocación y con avance de `rowVersion`, operaciones sin cambios, combinación
email/contraseña con rollback y una sola revocación, y login del servicio con el
proveedor real de contraseñas delegado desde el mock de AuthenticationManager.
No equivale a una prueba completa de login HTTP contra BD real.

`axios.test.mjs` usa el runner integrado de Node y el interceptor Axios real con
un adaptador sin red. Sus tres casos comprueban 401, 403 y 409 sin dependencias nuevas.

Comando de ejecución desde `smartaudits-backend`:

```text
mvn test
```

Con las dependencias ya descargadas puede ejecutarse `mvn -o test`. Resultado
verificado: 33 tests de backend, sin fallos ni errores. Desde `smartaudits-frontend`, ejecutar
`node --test src/api/axios.test.mjs` y `npm run build`. Empaquetar el backend con
`mvn -o package -DskipTests` después de pasar la suite y ejecutar `git diff --check`
desde la raíz. Han pasado también los 3 tests de Axios, la compilación Vite y el
empaquetado del backend. H2 sigue ausente del JAR de producción. `git diff --check`
no detecta errores de whitespace. Node/Vite requirieron ejecución fuera del sandbox
por un bloqueo EPERM de Windows al crear procesos; no se instalaron dependencias.
Estas pruebas no certifican una migración ni el comportamiento de bloqueo de una
instancia MariaDB concreta; esa comprobación de despliegue sigue pendiente.

## Riesgos y límites pendientes de autenticación

- Logout sigue borrando la sesión local; no revoca un token copiado. La revocación
  es global por usuario tras eventos de seguridad, no selectiva por dispositivo.
- Una petición autenticada antes de un cambio de seguridad puede estar ya en curso;
  el versionado rechaza peticiones posteriores, no cancela trabajos en ejecución.
- Cambios directos mediante SQL deben incrementar la versión si han de revocar
  tokens; no pasan por JPA ni por el bloqueo administrativo.
- Se mantienen el mínimo actual de contraseña, ausencia de limitación de intentos,
  verificación de email y recuperación de contraseña. Cambiar email no exige la
  contraseña actual cuando no se cambia contraseña; esto sigue pendiente.
- Los tokens permanecen en `localStorage`; el riesgo de robo por XSS continúa hasta
  la fase correspondiente. No se introduce una infraestructura de sesiones.
- Los conflictos optimistas de cuentas tienen respuesta 409, pero no se ha
  normalizado el resto de errores de negocio ni añadido reintentos automáticos.
- La rotación de secretos comprometidos indicada en la Fase 0.3 sigue siendo
  necesaria: el versionado no protege frente a quien conozca la clave de firma.

No se ha hecho commit ni se han ejecutado operaciones sobre datos reales.
