# CI — Fase 1.5

`.github/workflows/ci.yml` ejecuta en push y pull request, sobre Ubuntu 24.04,
Java 17 y Node 22 LTS, con permisos `contents: read` y cachés Maven/npm:

- **backend:** `mvn --batch-mode test` (H2/dobles de prueba).
- **frontend:** `npm ci`, `npm test`, `npm run build`, `npm run lint` y
  `npm run audit:dependencies`. La auditoría consulta npm y bloquea high/critical,
  incluidas dependencias de desarrollo; los errores no se ignoran.
- **migrations:** construye el JAR y ejecuta `pwsh -File scripts/Validate-CiMigrations.ps1`.
- **e2e:** prepara Maven, instala Chromium y ejecuta `npm run test:e2e`.
- **dependency-review:** en PR, bloquea dependencias nuevas/cambiadas con avisos
  high/critical reconocidos por el grafo de GitHub, incluido Maven.

La revisión de dependencias requiere el grafo habilitado y un repositorio
público o la licencia correspondiente para privados, según
[Dependency Review](https://github.com/actions/dependency-review-action).
No certifica que el inventario Maven existente esté libre de vulnerabilidades
ni sustituye un análisis completo de transitivas no resueltas por ese grafo.
`.github/dependabot.yml` añade actualización semanal Maven/npm/actions; activar
también Dependabot alerts/security updates en la configuración del repositorio.
No se incorpora Dependency-Check/NVD ni una API key adicional. La comprobación
GitHub queda pendiente hasta publicar los archivos y ejecutar la CI.

Las correcciones compatibles de npm se fijan en el lockfile. Vite pasa de 5 a
6.4.3 para corregir el aviso high restante, sin cambiar componentes React.
Permanecen cuatro entradas moderate (Vitest/mocker y React Router); el umbral
high es explícito, no una excepción ni `continue-on-error`. Actualizar esas
ramas mayores se mantiene fuera de esta fase. ESLint comprueba JS/JSX y reglas
React sin imponer PropTypes o una limpieza de variables históricas. Solo se
exceptúa `no-control-regex` en DetalleAuditoria: su filtro de URL usa esos
caracteres intencionalmente.

## Ejecución local equivalente

Desde backend: `mvn test` y `mvn -B -DskipTests package`.
Desde frontend, establecer primero `CI=true` (PowerShell: `$env:CI = 'true'`):
Vite omite los archivos `.env` en ese modo, también al ejecutar Vitest.
Después: `npm ci`, `npm test`, `npm run build`, `npm run lint`,
`npm run audit:dependencies`, `npx playwright install chromium`,
`npm run test:e2e`. Desde la raíz:
`powershell -NoProfile -File scripts/Validate-CiMigrations.ps1` en Windows o
`pwsh -File scripts/Validate-CiMigrations.ps1` en Linux.

Solo migraciones y E2E requieren Docker y la imagen local `mariadb:11.2`
(`docker pull mariadb:11.2` para prepararla). En Linux Playwright instala sus
bibliotecas con `npx playwright install --with-deps chromium`.

Todas las BDs son temporales, con nombres/labels propios y cleanup verificado
en éxito o fallo. Nunca se usa `.env`, credenciales reales ni la BD habitual.
No se ejecuta `-AdoptLegacySchema`. El baseline se prueba exclusivamente en la
BD temporal que representa el esquema histórico.

## Portabilidad y alcance de migraciones

El wrapper reutiliza todas las comprobaciones de Fase 0.8: instalación vacía,
rechazo sin baseline, baseline sin ejecutar V1, segundo arranque, sentinelas
sensibles a mayúsculas/espacios y cleanup. `legacy-schema.sql` es una fixture
DDL independiente y congelada, extraída de las ocho definiciones del esquema
aprobado; no contiene filas, contadores históricos ni metadata de dump. Su
SHA-256 está fijado y Git conserva LF. No se regenera desde V1 en cada ejecución.
El snapshot externo y hash por defecto del validador original permanecen intactos.

Los únicos ajustes del validador son separadores de ruta portables, directorio
temporal de .NET y ventana oculta solo en Windows. El E2E admite la ausencia del
contenedor habitual (y verifica que no aparezca); si existe sigue vigilando su
metadata. En Linux detiene el grupo de procesos propio, incluido Vite/esbuild.

No se suben dumps, logs ni traces con tokens temporales como artifacts. Los
diagnósticos E2E permanecen locales/efímeros e ignorados por Git. Las validaciones
locales y actionlint no equivalen a haber ejecutado GitHub Actions.
