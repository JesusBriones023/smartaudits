# SmartAudits - Sistema de Auditoría Legal Automatizada

Sistema completo de auditoría legal para analizar textos según RGPD, LOPDGDD y LSSI-CE mediante motor de análisis propio.

## 🚀 Stack Tecnológico

**Backend:**
- Java 17+ con Spring Boot
- Spring Security + JWT
- Spring Data JPA + MariaDB
- Motor de análisis legal propio (sin dependencias de APIs externas)

**Frontend:**
- React 18 + Vite
- Tailwind CSS
- React Router
- Axios

## 📦 Instalación y Ejecución

### 0️⃣ Configurar el entorno (Fase 0.3)

Requisitos: Java 17, Maven, Node.js/npm y Docker Compose. La configuración local
reside en `.env`, ignorado por Git y con permisos restringidos. No sobrescribas un
archivo existente: en este entorno contiene las credenciales rotadas el 8 de
septiembre de 2026. `.env.example` es únicamente una plantilla sin secretos.

Consulta [Configuración, rotación y estado de 0.3](docs/SECURITY_CONFIGURATION_PHASE_0_3.md)
antes de configurar otra instalación. No reutilices credenciales de contenedores
antiguos, backups ni ejemplos. El proyecto Compose se fija como `smartaudits-ia`
para conservar los nombres del volumen y la red habituales.

| Variable | Uso | Obligatoria |
|---|---|---|
| `DB_URL` | JDBC exacto del backend; no tiene destino implícito | Sí |
| `DB_USERNAME` | Usuario de aplicación compartido por backend y Compose | Sí |
| `DB_PASSWORD` | Contraseña del usuario de aplicación | Sí |
| `DB_ROOT_PASSWORD` | Contraseña root de MariaDB; solo Compose | Sí para Compose |
| `JWT_SECRET` | Clave aleatoria codificada en Base64, al menos 32 bytes antes de codificar | Sí para backend |
| `SMARTAUDITS_ADMIN_EMAIL` | Email de bootstrap del administrador | Solo si se desea crear el primer ADMIN |
| `SMARTAUDITS_ADMIN_PASSWORD` | Contraseña de bootstrap | Junto con el email; nunca usar una contraseña de ejemplo |
| `SMARTAUDITS_ADMIN_NOMBRE` | Nombre inicial | No |
| `APP_ADMIN_BOOTSTRAP_ENABLED` | Creación inicial de ADMIN; `false` aquí | Definir explícitamente |
| `APP_DATA_INITIALIZER_ENABLED` | Permite la inicialización de roles/bootstrap; se desactiva durante adopción histórica | No; `true` por defecto |
| `FLYWAY_ENABLED` | Activa las migraciones versionadas | Explícita según entorno |
| `FLYWAY_BASELINE_ON_MIGRATE` | Solo para adopción extraordinaria de una BD histórica | `false` en operación normal |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | Validación JPA del esquema; usar `validate` | `validate` |
| `JWT_ISSUER`, `JWT_AUDIENCE` | Contexto del JWT, no secretos | Predeterminados documentados |

Genera los secretos con un gestor de contraseñas o un generador criptográfico.
Utiliza contraseñas distintas para el usuario de aplicación y root. No reutilices
los valores que estuvieron versionados. Para el procedimiento PowerShell de abajo,
usa valores de una sola línea, sin comillas, comentarios al final ni interpolación;
Base64 es un formato apropiado para los secretos generados y evita diferencias de
interpretación entre el cargador y Compose. No introduzcas secretos en comandos
que queden guardados en el historial del terminal.

Compose lee automáticamente el `.env` de la raíz. Spring Boot **no lo carga
automáticamente**. El script local importa una lista limitada de variables al
proceso, excluye la contraseña root y restaura el entorno al terminar:

```powershell
powershell -NoProfile -File scripts/Start-LocalBackend.ps1 -Check
```

`-Check` verifica la configuración sin arrancar Java ni conectarse a BD. El script
no ejecuta el contenido de `.env`. Las variables ya definidas en el shell tienen
precedencia sobre `.env` para Compose: evita valores contradictorios entre terminales.

El frontend tiene su propia plantilla `smartaudits-frontend/.env.example`.
Cópiala a `smartaudits-frontend/.env` solo si no existe y configura `VITE_API_URL`.
Las variables `VITE_*` son públicas en el navegador: nunca pongas credenciales o
claves allí. No copies el `.env` del backend dentro del frontend.

### 1️⃣ Levantar Base de Datos
```bash
docker compose --project-name smartaudits-ia config --quiet
docker compose --project-name smartaudits-ia up -d mariadb adminer
```

MariaDB se publica únicamente en `127.0.0.1:3306`, para el backend local.
Adminer continúa disponible en `http://127.0.0.1:8081`, sin publicación en todas
las interfaces. En Adminer, el servidor de base de datos es `mariadb`.
No se cambian el nombre de la base, el volumen ni las versiones de las imágenes.

**Base de datos existente:** las variables de inicialización de MariaDB no cambian
las contraseñas de un volumen ya inicializado. Para conservar el acceso necesitas
credenciales que coincidan con las de esa instancia. Coordina la rotación manual
en MariaDB y la actualización del entorno del backend/Compose. No elimines el
volumen para aplicar este cambio. Las contraseñas de aplicación y de ambas cuentas
root ya se rotaron en la instancia local. Las variables de los contenedores
existentes permanecen obsoletas deliberadamente: no se recrearon durante la
rotación y sus valores anteriores ya no autentican. La fuente vigente es `.env`.

### 2️⃣ Arrancar Backend
```bash
cd smartaudits-backend
mvn package
cd ..
powershell -NoProfile -File scripts/Start-LocalBackend.ps1
```
### Migraciones de base de datos

SmartAudits utiliza Flyway para versionar el esquema y Hibernate
`ddl-auto=validate` para comprobar que la base resultante coincide con las
entidades de la aplicación.

Las migraciones están en:

`smartaudits-backend/src/main/resources/db/migration`

`V1__initial_schema.sql` representa el esquema base y queda congelada una vez
adoptada. Los futuros cambios de esquema deben implementarse mediante nuevas
versiones (`V2__...`, `V3__...`, etc.).

Una instalación nueva debe ejecutar Flyway con baseline desactivado.

Una instalación histórica compatible se incorpora una sola vez mediante el
modo extraordinario:

```powershell
powershell -NoProfile -File scripts/Start-LocalBackend.ps1 -AdoptLegacySchema
```

No utilizar este modo como arranque normal.

El modo `-AdoptLegacySchema` está endurecido específicamente para la adopción
histórica:

- obliga a Hibernate a utilizar `ddl-auto=validate`;
- utiliza explícitamente el mismo datasource para Hibernate y Flyway;
- desactiva `DataInitializer` y el bootstrap administrativo;
- no entrega `DB_ROOT_PASSWORD` al backend;
- exige un artefacto que contenga exclusivamente
  `V1__initial_schema.sql` en el directorio de migraciones;
- utiliza `spring.flyway.target=1`;
- por tanto, no puede ejecutar accidentalmente una futura V2+ durante la
  operación de baseline.

El lanzador local `scripts/Start-LocalBackend.ps1` aplica además una
configuración controlada y rechaza variables `SPRING_*` heredadas, así como
`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS`, porque podrían
alterar el datasource o el comportamiento de Spring, Hibernate o Flyway.

La configuración Spring utilizada por este lanzamiento se restringe al
`application.properties` empaquetado.

Tras una adopción correcta, la operación normal utiliza:

```text
FLYWAY_ENABLED=true
FLYWAY_BASELINE_ON_MIGRATE=false
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
```

`baseline-on-migrate=true` no debe conservarse como configuración normal.

La validación reproducible de ambos caminos se encuentra en:

`scripts/Validate-FlywayPhase08.ps1`

Este validador crea únicamente recursos temporales y aislados, comprueba el
camino de instalación nueva y el de instalación histórica, verifica datos
centinela y elimina únicamente los procesos y contenedores cuya propiedad puede
confirmar.

Consulta el procedimiento completo, las pruebas realizadas y la estrategia de
recuperación en:

[docs/DATABASE_MIGRATIONS_PHASE_0_8.md](docs/DATABASE_MIGRATIONS_PHASE_0_8.md)

No activar Flyway sobre una base histórica real sin backup completo actual,
restauración previamente ensayada, validación final de la fase y revisión
adversarial.

El backend estará en: http://localhost:8080

**Compatibilidad aplicada en la BD habitual:** se añadieron de forma explícita y
autorizada `usuarios.token_version` y `usuarios.row_version`, ambas
`BIGINT NOT NULL DEFAULT 0`. Los 11 usuarios existentes quedaron inicializados a
0 y el backend arrancó con `validate`, sin actualización automática del esquema.
Otras instalaciones deben comprobar su esquema antes del despliegue; véase
[compatibilidad de 0.4](docs/AUTHENTICATION_PHASE_0_4.md).

Sin `DB_USERNAME`, `DB_PASSWORD` o `JWT_SECRET` correctamente configuradas, el
backend no dispone de credenciales utilizables: ya no hay secretos de respaldo.
Se mantiene la expiración JWT y la lógica de autenticación actuales.

La configuración base usa logging INFO y no imprime SQL. Para recuperar el
diagnóstico de desarrollo durante un arranque Maven directo, puede utilizarse el
perfil `dev` mediante `SPRING_PROFILES_ACTIVE=dev`.

Esta indicación se aplica únicamente a un arranque Maven directo. No definas
variables `SPRING_*` en la sesión desde la que se utilice
`scripts/Start-LocalBackend.ps1`, ya que el lanzador endurecido rechaza
deliberadamente esos overrides heredados.

No actives el perfil `dev` en producción ni compartas logs que puedan contener
información personal. Producción debe inyectar sus propios secretos y
configuración mediante su entorno de despliegue controlado y utilizar
exclusivamente el perfil productivo correspondiente. Ese perfil exige `DB_URL`,
valida el esquema, desactiva bootstrap y evita SQL/debug. Este Compose es local.
La configuración base utiliza `validate` y exige `DB_URL` explícita. La evolución
del esquema se gestiona mediante Flyway; Hibernate no debe modificar
automáticamente las tablas. La adopción de la base histórica mediante Flyway
permanece pendiente hasta completar todas las validaciones y el procedimiento de
recuperación de la Fase 0.8.

### 3️⃣ Arrancar Frontend
```bash
cd smartaudits-frontend
npm install
npm run dev
```

El frontend estará en: http://localhost:5173

Para una instalación reproducible de dependencias ya bloqueadas, utiliza
`npm ci` en lugar de `npm install`.

### Comprobaciones de configuración sin acceder a datos

`docker compose config --quiet` valida la sintaxis y las variables requeridas sin
arrancar contenedores. No uses `docker compose config` sin `--quiet` con secretos
reales si la salida se va a compartir: muestra la configuración interpolada.
`git diff --check` comprueba errores de whitespace. `.env`, sus variantes,
configuración `application-local.*` y archivos de claves están ignorados; las
plantillas `.env.example` siguen versionadas.

### Rotaciones manuales y riesgos pendientes de configuración

- Estado local: contraseñas de aplicación y root (`localhost` y `%`) rotadas;
  credenciales anteriores rechazadas. JWT nuevo configurado y utilizado por el
  backend, comprobado mediante una lectura autenticada. Los JWT firmados con la
  clave anterior ya no validan: los usuarios deben iniciar sesión de nuevo.
- Bootstrap no cambia contraseñas persistidas. No se identificó una contraseña
  bootstrap expuesta activa ni coincidencias de los ADMIN con las contraseñas
  de BD retiradas; sus contraseñas no se han cambiado arbitrariamente.
- El backup previo conserva el estado anterior de credenciales y datos. Está
  protegido y no debe publicarse. Restaurarlo exige volver a rotar antes de
  reabrir el servicio. No se reescribe el historial Git ni se eliminan backups.
- Las rotaciones aquí verificadas solo cubren esta instancia local. Otras copias
  que hayan reutilizado credenciales necesitan su propia rotación.
- `.gitignore` no protege archivos añadidos con `git add -f` ni los permisos del
  sistema de archivos. Protege el acceso al `.env` y evita compartirlo.
- Restringir los puertos a loopback protege el acceso remoto directo al publicarlos;
  no sustituye la autenticación de MariaDB/Adminer ni los controles del host.
- La conexión con credenciales nuevas, validación del esquema y arranque completo
  del backend se verificaron contra MariaDB habitual. TLS y la infraestructura
  general de migraciones no se amplían aquí.

## 🧪 Probando la Aplicación

1. Abre http://localhost:5173
2. Regístrate con tus datos
3. Ve a "Nueva Auditoría"
4. Pega un texto legal y analiza

## 🔧 Comandos Útiles

### Docker
```bash
docker compose --project-name smartaudits-ia ps
docker compose --project-name smartaudits-ia config --quiet
```

### Backend
```bash
mvn clean spring-boot:run
mvn clean install -DskipTests
```

### Frontend
```bash
npm run build
npm run preview
```

## 🎯 Características

✅ Autenticación JWT completa  
✅ Roles (CLIENTE/ADMIN)  
✅ Auditorías con motor legal propio  
✅ Historial por usuario  
✅ Detalle completo con errores, recomendaciones y textos sugeridos  
✅ Gestión de usuarios (admin)  
✅ Baja lógica de cuentas  
✅ Responsive design con Tailwind  

## 📄 Licencia

Proyecto educativo - SmartAudits 2025
