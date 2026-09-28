# E2E — Fase 1.4

Desde `smartaudits-frontend`:

```powershell
npm ci
npx playwright install chromium
npm run test:e2e
```

Requisitos: Node 22, Java 17, Maven y Docker con contenedores Linux.
La imagen `mariadb:11.2` debe estar disponible localmente (`docker pull mariadb:11.2`
como preparación si falta). Las dependencias Maven deben estar en la caché local:
el runner construye el JAR actual con `mvn -B -o -DskipTests package`.
La instalación inicial puede necesitar Internet; la ejecución E2E no descarga
imágenes, navegadores ni dependencias. Las peticiones del navegador fuera del
origen local de prueba se bloquean, sin simular respuestas de API.

Arquitectura: Chromium headless → Vite/React real → proxy `/api` → Spring Boot
real → MariaDB 11.2 temporal. Los tres puertos se asignan dinámicamente y solo
escuchan en `127.0.0.1`. No se reutilizan servidores existentes.
El proxy conserva `Host`: navegador y API comparten el mismo origen público,
sin ampliar ni desactivar la lista CORS de producción.

El runner genera credenciales y JWT exclusivos, crea un volumen vacío y un
contenedor `sa14_*`, ambos con label `smartaudits.e2e=<id único>`. Arranca el
backend con Flyway habilitado, baseline deshabilitado y Hibernate `validate`;
comprueba el esquema vacío antes del arranque y la única entrada SQL V1 después.
El ADMIN temporal se crea mediante el bootstrap existente. No cambia código
backend, migraciones ni reglas de autenticación.

No carga `.env` ni hereda variables de configuración de la aplicación. Vite usa
`envDir: false` y Spring solo la configuración incluida en el JAR. Se consulta
únicamente metadata del contenedor habitual `smartaudits_mariadb` antes/después
(si existe; en CI se verifica que siga ausente), sin conexión SQL, reinicio, montaje ni modificación.
Para `.env` se compara metadata del archivo, sin leer su contenido.

Los cuatro tests cubren registro/login/auditoría/resultado persistido, logout,
token inválido con 401 real y administración con 403 real para un CLIENTE que
manipula su rol local. Cada test tiene contexto de navegador propio. Hay tres
registros por ejecución, un worker y cero reintentos para respetar el rate limit
real. No se reutiliza estado entre ejecuciones. Ejecutar siempre el comando
completo; `playwright test` directamente no provisiona el entorno.

En éxito, fallo o SIGINT/SIGTERM, el runner detiene sus procesos y elimina solo
el contenedor/volumen de esta ejecución, verificando nombre y label antes de
borrar. Nunca usa `prune` ni elimina recursos de otras ejecuciones. Solo anuncia
`PHASE 1.4 E2E PASSED` si tests y cleanup pasan. Una terminación forzada del
runner o del equipo no permite garantizar un `finally`: cualquier recurso
remanente se identifica por su nombre y label y debe revisarse individualmente.

Los logs locales en `.e2e-runtime/<id>/` ocultan las credenciales generadas.
Playwright conserva capturas/traces únicamente al fallar, en `test-results/`;
pueden contener datos/tokens temporales. Ambos directorios están ignorados por
Git. No se generan credenciales persistentes ni se usan cuentas habituales.

La suite de componentes sigue independiente: `npm test` solo recoge tests de
`src/`. Después del E2E ejecutar `npm test`, `npm run build` y, desde backend,
`mvn test`.
