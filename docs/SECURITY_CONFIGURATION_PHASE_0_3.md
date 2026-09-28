# Configuración y rotación de secretos — Fase 0.3

Fecha: 8 de septiembre de 2026. Base de código: `25897e2`, rama
`phase-0/security-hardening`. Cambios de este cierre sin commit.

## Estado y alcance

Las credenciales de MariaDB se han rotado en la instancia habitual sin recrear
contenedores, red o volumen. Se ha generado una clave JWT nueva y establecido la
configuración local y de producción. No se ha cambiado autenticación, ownership,
motor ni frontend funcional. Se aplicó únicamente la actualización de esquema
de compatibilidad de 0.4 autorizada posteriormente para completar el arranque.

**Estado final: Fase 0.3 DONE operativo, sin commit por instrucción del usuario.**
La BD habitual carecía de `usuarios.row_version` y `usuarios.token_version`,
requeridas por 0.4. Tras autorización explícita se añadieron ambas, se verificaron
definición y conteos y el backend arrancó con `ddl-auto=validate`. No se utilizó
`update`, no se repitieron carreras ni la validación aislada de 0.4 y no se
implementó Flyway/Liquibase. La Fase 0.8 permanece pendiente.

## Inventario sin valores sensibles

| Variable | ¿Secreto? | Uso y necesidad |
| --- | --- | --- |
| `DB_URL` | No, salvo que se incrusten credenciales (prohibido) | JDBC del backend; obligatorio en prod y en el lanzador local |
| `DB_USERNAME` | Identificador de configuración | Usuario de aplicación; obligatorio para Compose/backend |
| `DB_PASSWORD` | Sí | Contraseña real de esa cuenta; obligatoria |
| `DB_ROOT_PASSWORD` | Sí | Administración y configuración inicial de MariaDB; nunca pasar al backend |
| `JWT_SECRET` | Sí | Clave Base64 de firma; obligatoria, sin respaldo literal |
| `JWT_ISSUER`, `JWT_AUDIENCE` | No | Contexto del token; `smartaudits` y `smartaudits-api` localmente |
| `SMARTAUDITS_ADMIN_EMAIL` | Dato personal, no contraseña | Solo aprovisionamiento inicial |
| `SMARTAUDITS_ADMIN_PASSWORD` | Sí, cuando se suministra | Solo creación inicial del ADMIN; vacía en esta instalación |
| `SMARTAUDITS_ADMIN_NOMBRE` | Dato de perfil | Opcional, solo creación inicial |
| `APP_ADMIN_BOOTSTRAP_ENABLED` | No | `false` para la instalación existente |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | No | `validate` para esquema existente; no migra |
| `SPRING_PROFILES_ACTIVE` | No | `prod` para despliegue; no mezclar con `dev` |
| `VITE_API_URL` | No | Dirección pública de API; única configuración local del frontend |

Las cuentas `healthcheck` y `mariadb.sys` son internas de MariaDB. No se encontró
evidencia de exposición de sus credenciales y no se modificaron sus permisos ni
plugins. Host, puerto, nombre de base, red y volumen no son contraseñas.

## Desarrollo local

- `.env` en la raíz es la fuente vigente. Está ignorado explícitamente por Git.
  Contiene las nuevas credenciales, nunca las antiguas.
- Se creó vacío, se restringió su ACL a usuario actual y SYSTEM, sin herencia,
  y solo después se escribieron secretos. Mantener esos permisos al editar/copiar.
- `.env.example` contiene únicamente campos vacíos y valores de configuración
  seguros. No copiar `.env` al frontend, a una imagen o a documentación.
- El formato del cargador es `NOMBRE=valor`, una línea por variable, sin comillas,
  interpolación, comentarios al final ni instrucciones ejecutables.
- Compose toma `.env` automáticamente. `name: smartaudits-ia` fija los nombres
  habituales; se recomienda además el argumento explícito en operaciones.
- Variables de shell de Compose prevalecen sobre `.env`: usar un proceso limpio
  o eliminar overrides obsoletos. No exportar globalmente secretos de desarrollo.

```powershell
docker compose --project-name smartaudits-ia config --quiet
powershell -NoProfile -File scripts/Start-LocalBackend.ps1 -Check
# Tras compilar con mvn package y disponer del esquema compatible:
powershell -NoProfile -File scripts/Start-LocalBackend.ps1
```

`-Check` no conecta ni arranca el backend. El lanzador usa una lista explícita de
variables, comprueba campos requeridos y formato/longitud de JWT, elimina root del
entorno del hijo y restaura el entorno del proceso al finalizar. No usar `-Verbose`,
trazas de shell ni volcar el entorno para diagnosticar secretos.

No hace falta mantener una plataforma externa de gestión de secretos para este
entorno local. El `.env` es texto sensible protegido por permisos, no una bóveda
cifrada: proteger también la cuenta del SO y el disco.

## Configuración de producción

La interfaz es el entorno del proceso del backend. El despliegue debe suministrar
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` y activar `prod`. El perfil
`application-prod.properties` exige URL explícita, usa `validate`, desactiva
bootstrap y SQL/debug. Las propiedades externas pueden sobreescribir Spring:
revisar overrides como parte del despliegue, no asumir que el perfil los impide.

Usar secretos exclusivos por entorno, inyectados por el servicio supervisor o
el mecanismo seguro de la plataforma. Evitar argumentos CLI, archivos de imagen,
capas Docker, artefactos públicos, variables `VITE_*`, logs y salida de CI. La
cuenta de aplicación no debe recibir root. No habilitar el perfil dev en prod.

Este cambio prepara la configuración; no implementa infraestructura productiva,
TLS, migraciones, RBAC nuevo ni un gestor externo. La base productiva debe estar
aprovisionada y respaldada antes de arrancar. Adminer es una herramienta local,
no un servicio que deba publicarse en Internet.

## Rotación efectuada

Se confirmó el contenedor, `DATABASE()`, hostname, volumen, red, backup previo,
cuentas seleccionadas y ausencia de sesiones cliente. General log, slow log y
binary log estaban desactivados y no había plugin de auditoría activo.

Se generaron secretos independientes mediante CSPRNG: 32 bytes aleatorios para
cada contraseña de BD y 64 bytes para JWT, codificados en Base64. Se guardaron
primero en el `.env` protegido para disponer del destino de configuración antes
de modificar cuentas. Ningún secreto se pasó como argumento de un proceso.

| Credencial | Operación y comprobación |
| --- | --- |
| Cuenta de aplicación `@%` | `SET PASSWORD`; nueva contraseña acepta SELECT, anterior rechazada con 1045 |
| `root@%` | `SET PASSWORD`; validación por TCP con `CURRENT_USER()`, nueva aceptada y anterior rechazada con 1045 |
| `root@localhost` | `SET PASSWORD`; validación por socket con `CURRENT_USER()`, nueva aceptada y anterior rechazada con 1045 |
| JWT | Nueva clave configurada y utilizada por el backend; lectura autenticada con JWT de prueba en memoria devuelve 200 |

En esta instancia TCP a `127.0.0.1` selecciona `root@%`; el socket selecciona
`root@localhost`. Se comprobaron ambas: no se confundió éxito por TCP con prueba
de la cuenta local. Ambos usan contraseñas `mysql_native_password`, sin alternancia
`unix_socket`; una contraseña incorrecta por socket también es rechazada.

Se utilizó entrada estándar binaria con LF al invocar el cliente MariaDB, evitando
que la traducción CRLF de Windows alterase la contraseña. Los errores se trataron
sin imprimir consultas, credenciales o hashes. `SET PASSWORD` se aplicó solo a
cuentas existentes y no cambió roles, privilegios ni datos de negocio.
Referencia: [SET PASSWORD de MariaDB](https://mariadb.com/docs/server/reference/sql-statements/account-management-sql-statements/set-password).

**Las variables MYSQL_* almacenadas en el contenedor no se actualizaron.** Docker
no permite cambiar su configuración de entorno sin recrearlo, y esa recreación
estaba excluida. Sus contraseñas antiguas ya no autentican. No utilizarlas para
futuras operaciones. Una recreación futura autorizada usará el `.env` vigente,
preservará volumen/red y comprobará montajes antes de arrancar; no es una nueva
rotación ni es necesaria para que MariaDB use las contraseñas ya cambiadas.

## ADMIN y bootstrap

`DataInitializer` omite el bootstrap si existe cualquier usuario con rol ADMIN,
incluso si estuviera inactivo. No actualiza contraseñas existentes. Aquí existen
dos ADMIN, uno protegido; no se necesita aprovisionar otro.

No había variables bootstrap en el entorno local inspeccionado ni credenciales
bootstrap literales identificadas en las configuraciones de los dos commits
alcanzables. Se compararon en memoria con BCrypt las contraseñas persistidas de
ambos ADMIN contra las credenciales de BD retiradas: ninguna coincidió. Esto
descarta esa reutilización concreta; no prueba ausencia de filtraciones externas
desconocidas. No se ha cambiado ninguna contraseña ADMIN sin evidencia de que
corresponda. La variable bootstrap queda vacía y el bootstrap desactivado.

Si posteriormente se acredita exposición de una contraseña ADMIN, usar el cambio
de contraseña de la aplicación con esquema compatible: conserva identidad y
revoca JWT mediante `tokenVersion`. Cambiar solo una variable bootstrap no sirve.

## JWT y futuras rotaciones

El backend ya arranca con la nueva clave: los JWT firmados con otra clave dejan
de validar y es necesario iniciar sesión de nuevo. No hay fallback.
La clave histórica no se encontró en el entorno actual; no se afirma haber probado
un JWT real antiguo. Las pruebas de JWT cubren rechazo de firmas incorrectas.
No mantener instancias con claves distintas sirviendo tráfico durante el cambio.

Para futuras rotaciones: confirmar identidad y backup; generar secretos nuevos;
guardar configuración local protegida; cambiar cada cuenta existente; comprobar
por cada ruta de autenticación aceptación nueva y rechazo anterior; actualizar
consumidores; verificar datos y arranque. Si se interrumpe una rotación, determinar
qué cuenta usa cada contraseña antes de reanudar; nunca generar otra tanda a ciegas.
No enviar contraseñas por argumentos CLI ni activar registros de consultas.

## Datos, backup y recuperación

Se conservan las ocho tablas, 11 usuarios y 48 auditorías. La rotación no modificó
contraseñas ni perfiles de los usuarios de la aplicación. La actualización de
compatibilidad añadió las dos columnas con valor inicial cero para los 11 usuarios.
Los conteos y lecturas de aplicación se verificaron después. Estas comprobaciones
no equivalen a una certificación integral de integridad del almacenamiento.

## Actualización puntual de compatibilidad autorizada

El preflight confirmó `DATABASE() = smartaudits_db`, hostname del contenedor,
tabla `usuarios`, ausencia de ambas columnas y cero otras sesiones cliente y
transacciones InnoDB activas. El backup previo permanecía disponible.

Única sentencia DDL ejecutada:

```sql
ALTER TABLE usuarios
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
```

La lectura posterior de `information_schema` confirmó `BIGINT`, `IS_NULLABLE=NO`
y `COLUMN_DEFAULT=0` en ambas columnas; los 11 usuarios tenían cero en ambas.
No se ejecutó DDL sobre otras tablas, índices o columnas, ni DML sobre datos de
negocio. No repetir este ALTER en esta instancia: ambas columnas ya existen.
Si se actualiza otra instalación, comprobar primero su estado; una sola columna
existente o una definición inesperada requiere revisión, no una adaptación ciega.

Backup físico previo conservado y ya verificado:
`%LOCALAPPDATA%\SmartAuditsBackups\phase03-20260908-033149`.
No se repitió. Contiene también imágenes y metadatos. Su copia física incluye las
credenciales de BD anteriores; los metadatos exportados no incluyen Env. No
publicarlo ni borrar el volumen para recuperar. No se ha hecho una restauración
de prueba en esta tarea.

Una restauración de ese backup devuelve las contraseñas anteriores de BD y el
esquema anterior a las dos columnas: exige aislamiento, actualización compatible
y nueva rotación antes de abrir servicio. El `.env` vigente no debe
sobrescribirse con configuración histórica. Conservar una copia protegida del
`.env` vigente bajo la política de backup del operador; no guardarla en Git.

Bindings comprobados: MariaDB exclusivamente `127.0.0.1:3306` y Adminer
exclusivamente `127.0.0.1:8081`. Volumen `smartaudits-ia_mariadb_data` y red
`smartaudits-ia_smartaudits_network` conservados. No se repitió recreación Docker.

## Verificaciones y criterio de cierre

| Comprobación ejecutada | Resultado |
| --- | --- |
| `mvn -o package` | PASS: 49 tests, cero fallos/errores; JAR generado |
| `node --test src/api/axios.test.mjs` | PASS: 3 tests; requirió permiso fuera del sandbox por `spawn EPERM` |
| `npm run build` | PASS: build Vite; aviso no bloqueante de datos Browserslist antiguos |
| `Start-LocalBackend.ps1 -Check` | PASS: configuración válida sin divulgar valores |
| Credenciales de aplicación y ambas cuentas root | PASS: nuevas aceptadas y anteriores rechazadas con 1045 |
| Datos por SELECT | PASS: conteos de las ocho tablas iguales al backup |
| Actualización puntual de compatibilidad | PASS: ambas columnas BIGINT NOT NULL DEFAULT 0; 11 usuarios inicializados; 8 tablas, 11 usuarios, 48 auditorías |
| Arranque mediante `Start-LocalBackend.ps1` | PASS después del ALTER autorizado: pool JDBC conectado y aplicación iniciada con `validate` |
| Lectura HTTP autenticada | PASS: GET `/auditorias/mias` devuelve 200 con JWT de prueba firmado en memoria con la nueva clave; sin token devuelve 401 |
| Logs del arranque final | PASS: sin coincidencias con los secretos nuevos conocidos |

El fallo inicial por ausencia de `row_version` quedó resuelto con el ALTER
autorizado. El backend queda disponible localmente en `127.0.0.1:8080`. La prueba
HTTP usó un JWT sintético de corta duración, no una contraseña ADMIN desconocida;
no se cambió ninguna cuenta para probar login. Los tests H2 no
demuestran por sí solos compatibilidad de la BD habitual. El chequeo de secretos incluye
archivos tracked y archivos nuevos propuestos para commit, sin imprimir valores.
No usar `docker compose config` sin `--quiet` en una salida compartida.

La rotación local, configuración, compatibilidad y arranque están verificados.
Los tests/build previamente aprobados no se repitieron tras el ALTER: esta última
operación solo cambió esas columnas y documentación, sin cambios de código o
configuración adicionales. Otras instancias que hubieran reutilizado secretos
necesitarán su propia rotación. El commit queda pendiente por instrucción expresa.
