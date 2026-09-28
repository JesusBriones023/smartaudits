# SmartAudits 2.0 — Roadmap Maestro

**Fecha de referencia:** 6 de septiembre de 2026  
**Estado:** En desarrollo  
**Objetivo:** evolucionar SmartAudits desde un analizador manual de textos legales hacia una plataforma SaaS de cumplimiento continuo para webs, empresas, agencias y sistemas de IA.

---

# 0. Principios del proyecto

## 0.1. No romper lo que ya funciona
SmartAudits actual debe seguir funcionando mientras se construyen las nuevas capacidades.

## 0.2. Desarrollo incremental
Cada funcionalidad nueva debe:
1. diseñarse,
2. implementarse en una rama,
3. probarse,
4. revisarse,
5. documentarse,
6. hacer commit,
7. integrarse solo si pasa los criterios de aceptación.

## 0.3. Mantener el análisis manual como fallback
Aunque exista análisis automático por URL, se conservará la opción de pegar manualmente:
- política de privacidad,
- política de cookies,
- aviso legal,
- términos y condiciones,
- otros documentos.

## 0.4. Separar hechos técnicos de conclusiones jurídicas
SmartAudits debe distinguir entre:
- **Hallazgo técnico comprobado**
- **Posible riesgo de cumplimiento**
- **Revisión jurídica recomendada**

Nunca afirmar automáticamente “esta web incumple el RGPD” si el sistema solo dispone de indicios técnicos.

## 0.5. Evidencia primero
Cada hallazgo importante debe guardar:
- qué se detectó,
- dónde,
- cuándo,
- cómo se detectó,
- evidencia técnica,
- regla o control aplicado,
- versión del motor que lo produjo.

## 0.6. Todo cambio importante debe tener tests
No se ampliará el producto sobre una base sin pruebas de regresión.

---

# 1. Estado actual confirmado

SmartAudits actualmente:
- tiene frontend React/Vite;
- tiene backend Java 17 + Spring Boot;
- usa MariaDB;
- usa JWT;
- permite registro, login, usuarios y administración;
- permite crear auditorías manuales;
- recibe un texto pegado por el usuario;
- almacena una URL opcional solo como referencia;
- ejecuta un motor heurístico de 19 reglas por palabras clave;
- genera puntuación, incidencias, recomendaciones y textos sugeridos;
- guarda auditorías e historial;
- permite imprimir/guardar el informe desde el navegador.

SmartAudits actualmente **no**:
- visita URLs;
- rastrea webs;
- descubre páginas legales;
- detecta cookies reales;
- detecta trackers reales;
- prueba banners de consentimiento;
- analiza formularios reales;
- ejecuta auditorías de accesibilidad;
- ejecuta análisis de seguridad web;
- monitoriza cambios;
- genera alertas;
- tiene versión Agency;
- tiene módulo AI Act;
- tiene tests automatizados relevantes.

---

# 2. Flujo objetivo final

```text
USUARIO / AGENCIA
       │
       ▼
  Añadir dominio
       │
       ▼
SMART CRAWLER
       │
       ├── Descubre páginas
       ├── Extrae contenido
       ├── Detecta tecnologías
       ├── Ejecuta navegador real
       └── Recoge evidencias
       │
       ▼
MOTORES ESPECIALIZADOS
       │
       ├── Legal Content Engine
       ├── Cookie & Tracker Engine
       ├── Consent Engine
       ├── Forms Engine
       ├── Accessibility Engine
       ├── Security Engine
       ├── AI Compliance Engine
       └── Vendor Compliance Engine
       │
       ▼
COMPLIANCE ENGINE
       │
       ├── Hallazgos
       ├── Evidencias
       ├── Riesgos
       ├── Score
       ├── Recomendaciones
       └── Fixes
       │
       ▼
INFORME + DASHBOARD
       │
       ▼
MONITORIZACIÓN CONTINUA
       │
       ├── Reescaneos
       ├── Cambios
       ├── Alertas
       └── Histórico
```

---

# 3. Estrategia de niveles Astra/Codex

## LIGHT
Usar para:
- leer archivos;
- documentar;
- cambios pequeños;
- refactors triviales;
- tests sencillos;
- ajustes UI pequeños;
- tareas muy acotadas y reversibles.

## MEDIUM
Usar para:
- cambios de backend con varias clases;
- autenticación/autorización;
- nuevas entidades;
- migraciones;
- crawler inicial;
- integración de herramientas;
- lógica de dominio moderada;
- cambios que afecten frontend + backend.

## HIGH / MAX
Usar para:
- decisiones arquitectónicas;
- seguridad crítica;
- rediseños de modelo de datos;
- crawler/navegador complejo;
- motor de consentimiento;
- monitorización distribuida;
- diseño multi-tenant Agency;
- AI Act / GRC;
- cambios con riesgo de regresión alto.

**Regla operativa:** antes de cada prompt se decidirá expresamente el nivel recomendado.

---

# FASE 0 — ESTABILIZACIÓN Y SEGURIDAD

## 0.0. Punto de restauración Git
**Estado:** COMPLETADO

Commit:
`Initial stable SmartAudits state before 2.0 development`

Objetivo:
tener una versión recuperable antes de cualquier cambio.

---

## 0.1. Documentar arquitectura actual
**Estado:** EN PROGRESO / CASI COMPLETADO

Archivo:
`CURRENT_ARCHITECTURE.md`

Debe contener:
- arquitectura actual;
- flujo de usuario;
- frontend;
- backend;
- base de datos;
- motor;
- seguridad;
- informes;
- deuda técnica;
- vulnerabilidades;
- componentes reutilizables.

**Astra recomendado:** LIGHT

### Criterio de salida
- documento creado;
- Git limpio después del commit.

---

## 0.2. Crear documentación estructural del proyecto
Crear:

```text
docs/
├── CURRENT_ARCHITECTURE.md
├── SMARTAUDITS_ROADMAP.md
├── SECURITY.md
├── ARCHITECTURE.md
├── DECISIONS.md
└── TESTING.md
```

Opcionalmente mover `CURRENT_ARCHITECTURE.md` a `docs/` más adelante con commit independiente.

**Astra recomendado:** LIGHT

---

## 0.3. Secretos y configuración
**Estado comprobado 2026-09-08: DONE operativo, cambios sin commit.** Credenciales
locales de aplicación y ambas cuentas root rotadas; JWT nuevo activo, configuración
local/prod y bindings seguros verificados. Se aplicó la actualización puntual
autorizada de las dos columnas requeridas por 0.4 y el backend arrancó con
`ddl-auto=validate`. Esto no implementa la infraestructura de migraciones de 0.8.
Detalle: [configuración y rotación](SECURITY_CONFIGURATION_PHASE_0_3.md).

Problemas a corregir:
- secretos JWT de respaldo versionados;
- credenciales de BD versionadas;
- valores sensibles en configuración;
- Compose con credenciales literales;
- exposición innecesaria de MariaDB/Adminer;
- archivos de configuración con contenido extraño.

Tareas:
- eliminar valores sensibles del código versionado;
- usar variables de entorno;
- mantener `.env.example` sin secretos;
- documentar configuración;
- rotar claves ya usadas;
- revisar `.gitignore`;
- revisar puertos expuestos;
- separar configuración dev/prod.

**Astra recomendado:** MEDIUM

### Criterio de salida
- ningún secreto real en Git;
- configuración reproducible;
- aplicación arranca con variables documentadas.

---

## 0.4. Corregir autenticación y JWT
Tareas:
- impedir acceso con usuario `activo=false`;
- invalidar sesiones/tokens tras cambios críticos;
- definir estrategia de revocación;
- revisar uso de email mutable como identidad del JWT;
- evitar reasignaciones peligrosas;
- revisar expiración;
- revisar cierre de sesión;
- evaluar refresh tokens para etapa posterior;
- asegurar último administrador activo.

**Astra recomendado:** HIGH

### Criterio de salida
Tests que demuestren:
- usuario desactivado no puede operar;
- cambio de contraseña invalida acceso según estrategia definida;
- no se puede dejar el sistema sin admin válido;
- no hay escalada de permisos.

---

## 0.5. Corregir autorización
Tareas:
- comprobar propiedad de auditoría en descarga;
- revisar todos los endpoints por ID;
- revisar rol ADMIN;
- normalizar respuestas 401/403;
- evitar que frontend cierre sesión por cualquier 403.

**Astra recomendado:** MEDIUM

---

## 0.6. Corregir XSS e informes
Tareas:
- escapar/sanitizar contenido dinámico;
- revisar `window.open`;
- evitar interpolación HTML insegura;
- unificar representación del informe;
- comprobar popup bloqueado;
- garantizar que contenido pegado por usuarios no ejecuta scripts.

**Astra recomendado:** HIGH

---

## 0.7. Límites y robustez
Tareas:
- máximo de texto;
- máximo de título;
- validación real de URL;
- paginación;
- límites de login;
- límites de registro;
- cuotas de auditoría;
- errores HTTP normalizados;
- manejo seguro de JSON corrupto en localStorage.

**Astra recomendado:** MEDIUM

---

## 0.8. Migraciones de base de datos
Sustituir dependencia exclusiva de:
`ddl-auto=update`

por migraciones versionadas:
- Flyway o Liquibase.

Tareas:
- baseline del esquema;
- primera migración;
- estrategia dev/prod;
- rollback operativo documentado.

**Astra recomendado:** MEDIUM

---

# FASE 1 — TESTS Y CALIDAD

## 1.1. Tests backend básicos
Añadir pruebas para:
- registro;
- login;
- permisos;
- roles;
- baja;
- reactivación;
- auditorías;
- propiedad;
- historial;
- motor legal.

**Astra recomendado:** MEDIUM

## 1.2. Tests de regresión del motor actual
Crear corpus fijo de textos:
- casos válidos;
- palabras ambiguas;
- negaciones;
- falsos positivos;
- falsos negativos;
- documentos incompletos.

Guardar salida esperada.

**Astra recomendado:** MEDIUM

## 1.3. Tests frontend
Añadir:
- login;
- rutas privadas;
- creación de auditoría;
- detalle;
- permisos admin;
- errores de API.

**Astra recomendado:** MEDIUM

## 1.4. E2E
Añadir Playwright o equivalente:
- registro → login → auditoría → resultado;
- admin;
- logout;
- sesión expirada.

**Astra recomendado:** MEDIUM/HIGH

## 1.5. CI
Pipeline:
- backend tests;
- frontend tests;
- build;
- lint;
- auditoría dependencias;
- migraciones verificadas.

**Astra recomendado:** MEDIUM

---

# FASE 2 — LIMPIEZA DE ARQUITECTURA ACTUAL

## 2.1. Separar motor del origen del texto
Objetivo:
que el motor reciba contenido sin saber si viene de:
- usuario;
- crawler;
- fichero;
- API futura.

Crear una interfaz clara de análisis.

**Astra recomendado:** HIGH

## 2.2. Versionar el motor
Cada auditoría debe guardar:
- versión del motor;
- versión de reglas;
- fecha;
- tipo de fuente.

## 2.3. Identificadores de reglas
Cada incidencia debe guardar:
- `ruleId`;
- categoría;
- severidad;
- evidencia;
- motor;
- versión.

## 2.4. Eliminar duplicidades
Revisar:
- roles duplicados;
- resultados JSON vs tablas;
- thresholds duplicados;
- componentes duplicados;
- CSS duplicado.

**Astra recomendado:** HIGH

## 2.5. Unificar informes
Pantalla, copia, impresión y PDF deben proceder del mismo DTO/modelo de informe.

---

# FASE 3 — MODELO DE DATOS SMARTAUDITS 2.0

El modelo actual “Auditoría = texto” no basta.

Crear conceptos:

```text
Organization
User
Site
Audit
Scan
Page
Document
Finding
Evidence
Cookie
Tracker
Form
ConsentTest
AccessibilityIssue
SecurityFinding
Technology
Rule
RuleVersion
Report
Alert
MonitoringSchedule
```

Relaciones aproximadas:

```text
Organization
 ├── Users
 └── Sites
      ├── Audits
      │    ├── Pages
      │    ├── Findings
      │    ├── Evidence
      │    └── Reports
      └── Monitoring
```

**Astra recomendado:** HIGH

### Requisito
Mantener compatibilidad con auditorías históricas actuales.

---

# FASE 4 — SMART CRAWLER FOUNDATION

## 4.1. Entrada por dominio
El usuario introduce:
`https://empresa.com`

Validar:
- protocolo;
- dominio;
- redirects;
- DNS;
- timeout;
- URLs privadas;
- SSRF;
- localhost;
- redes internas.

**Seguridad SSRF obligatoria.**

**Astra recomendado:** HIGH

## 4.2. Descarga HTML inicial
Obtener:
- status;
- URL final;
- headers;
- content-type;
- HTML;
- timestamp.

## 4.3. Descubrimiento de enlaces
Buscar:
- enlaces internos;
- footer;
- navegación;
- sitemap.xml;
- robots.txt.

## 4.4. Límites
Definir:
- máximo de páginas;
- profundidad;
- tiempo;
- tamaño;
- concurrencia;
- dominios permitidos.

## 4.5. Normalización de URLs
Evitar:
- duplicados;
- hashes;
- tracking parameters;
- loops;
- URLs infinitas.

## 4.6. Evidencia
Guardar:
- URL;
- fecha;
- status;
- contenido relevante;
- hash;
- origen del descubrimiento.

---

# FASE 5 — DESCUBRIMIENTO AUTOMÁTICO DE PÁGINAS LEGALES

Clasificar páginas por:
- URL;
- anchor text;
- título;
- H1;
- contenido.

Detectar:
- privacidad;
- cookies;
- aviso legal;
- términos;
- condiciones de compra;
- condiciones de uso;
- accesibilidad.

Resultado:

```text
Política de privacidad   ✓
Política de cookies      ✓
Aviso legal              ✓
Términos                 ?
Accesibilidad            ✗
```

**Astra recomendado:** MEDIUM/HIGH

---

# FASE 6 — EXTRACCIÓN Y LEGAL CONTENT ENGINE 2.0

## 6.1. Extraer texto limpio
Eliminar:
- navegación;
- scripts;
- estilos;
- banners repetidos;
- basura visual.

Conservar:
- encabezados;
- párrafos;
- listas;
- enlaces;
- tablas cuando sean relevantes.

## 6.2. Reutilizar motor actual
Primera iteración:
crawler → texto → motor existente.

## 6.3. Motor 2.0
Posteriormente:
- reglas por tipo de documento;
- aplicabilidad;
- contexto;
- negaciones;
- evidencia exacta;
- severidad;
- referencias.

## 6.4. Motor semántico
Evaluar más adelante IA/LLM como capa complementaria, nunca como única fuente de decisión.

**Astra recomendado:** HIGH

---

# FASE 7 — COOKIE & TRACKER ENGINE

## 7.1. Navegador automatizado
Incorporar Playwright u otra tecnología equivalente.

## 7.2. Registrar estado antes de consentir
Detectar:
- cookies;
- localStorage;
- sessionStorage;
- requests;
- scripts;
- iframes;
- pixels.

## 7.3. Catálogo de trackers
Ejemplos:
- Google Analytics;
- Google Ads;
- Meta Pixel;
- TikTok;
- Hotjar;
- Microsoft Clarity;
- YouTube;
- LinkedIn;
- otros.

## 7.4. Clasificación
- necesarios;
- analítica;
- marketing;
- funcionales;
- desconocidos.

## 7.5. Contrastar política vs realidad
Ejemplo:
política declara Google Analytics,
pero runtime detecta Meta Pixel.

Crear hallazgo de discrepancia.

**Astra recomendado:** HIGH

---

# FASE 8 — CONSENT ENGINE

Automatizar interacción con banners.

Casos:
- carga inicial;
- aceptar;
- rechazar;
- configurar;
- retirar consentimiento;
- reabrir preferencias.

Comprobar:
- cookies antes de consentir;
- diferencia aceptar/rechazar;
- accesibilidad de rechazo;
- persistencia;
- categorías;
- consentimiento previo;
- trackers bloqueados correctamente.

Guardar screenshots/evidencias cuando proceda.

**Astra recomendado:** HIGH/MAX

---

# FASE 9 — FORMS ENGINE

Detectar formularios:
- contacto;
- registro;
- newsletter;
- checkout;
- empleo;
- soporte.

Analizar:
- campos;
- datos personales;
- checkboxes;
- premarcados;
- consentimiento;
- marketing;
- enlaces de privacidad;
- textos informativos;
- datos especialmente sensibles;
- envío a terceros.

Cruzar formulario con:
- política;
- finalidad;
- base jurídica declarada.

**Astra recomendado:** HIGH

---

# FASE 10 — ACCESSIBILITY ENGINE

Integrar motor automático consolidado, por ejemplo axe-core.

Comprobar:
- WCAG;
- alt;
- labels;
- ARIA;
- headings;
- landmarks;
- teclado;
- focus;
- contraste automatizable;
- botones;
- enlaces;
- formularios.

Distinguir:
- automático;
- requiere revisión manual.

**Astra recomendado:** MEDIUM/HIGH

---

# FASE 11 — SECURITY POSTURE ENGINE

No presentarlo inicialmente como pentest.

Revisar:
- HTTPS;
- TLS;
- redirects;
- HSTS;
- CSP;
- X-Frame-Options / frame-ancestors;
- Referrer-Policy;
- Permissions-Policy;
- cookies Secure;
- HttpOnly;
- SameSite;
- mixed content;
- cabeceras;
- tecnologías visibles;
- información expuesta;
- dependencias cliente detectables.

Más adelante:
- subdominios;
- DNS;
- certificados;
- exposición técnica adicional.

**Astra recomendado:** HIGH

---

# FASE 12 — COMPLIANCE ENGINE Y SCORE

Crear motor agregador.

Separar puntuaciones:

```text
Privacy
Cookies
Consent
Trackers
Forms
Accessibility
Security
```

No usar una sola puntuación opaca.

Cada score debe tener:
- fórmula documentada;
- versión;
- controles aplicables;
- controles no evaluables;
- confianza.

Estados de hallazgo:
- PASS;
- FAIL;
- WARNING;
- NOT_APPLICABLE;
- NOT_TESTED;
- MANUAL_REVIEW.

**Astra recomendado:** HIGH

---

# FASE 13 — INFORME 2.0

Crear informe profesional con:

1. Portada
2. Dominio
3. Fecha
4. Versión del motor
5. Score global
6. Scores por área
7. Resumen ejecutivo
8. Hallazgos críticos
9. Evidencias
10. Privacidad
11. Cookies
12. Consentimiento
13. Trackers
14. Formularios
15. Accesibilidad
16. Seguridad
17. Recomendaciones
18. Limitaciones
19. Controles no evaluados

Cada hallazgo:
- ID;
- categoría;
- severidad;
- evidencia;
- URL;
- fecha;
- explicación;
- recomendación;
- referencia normativa cuando esté validada.

Generar PDF real desde backend/servicio, no solo print del navegador.

**Astra recomendado:** HIGH

---

# FASE 14 — DASHBOARD 2.0

Vista:
- sitios;
- score;
- última auditoría;
- cambios;
- críticos;
- evolución.

Ejemplo:

```text
empresa.es   91 ↑2
tienda.es    72 ↓11
blog.es      96 =
```

Añadir:
- filtros;
- histórico;
- estado de hallazgos;
- asignación;
- comentarios más adelante.

**Astra recomendado:** MEDIUM/HIGH

---

# FASE 15 — MONITORIZACIÓN CONTINUA

## 15.1. Programador
Auditorías automáticas:
- semanal;
- diaria;
- mensual;
- personalizada según plan.

## 15.2. Comparador
Detectar cambios entre scans:
- página nueva;
- página eliminada;
- tracker nuevo;
- cookie nueva;
- formulario nuevo;
- política modificada;
- score cambiado.

## 15.3. Estados
- nuevo;
- existente;
- resuelto;
- reaparecido.

## 15.4. Cost control
Límites por plan y presupuesto de navegación.

**Astra recomendado:** HIGH

---

# FASE 16 — ALERTAS

Canales:
- email;
- dashboard;
- webhook;
- Slack/Teams más adelante.

Alertas:
- crítico nuevo;
- tracker nuevo;
- caída importante de score;
- documento legal eliminado;
- fallo de consentimiento;
- certificado/TLS;
- cambio de normativa aplicable.

**Astra recomendado:** MEDIUM/HIGH

---

# FASE 17 — SISTEMA DE REMEDIACIÓN “FIX IT”

Primera etapa:
- instrucciones;
- snippets;
- textos;
- ejemplos.

Segunda:
- cambios descargables;
- configuración sugerida.

Tercera:
- integración GitHub/GitLab;
- branch;
- commit;
- Pull Request generado.

Siempre:
- propuesta revisable;
- nunca auto-merge por defecto;
- evidencia antes/después;
- rollback.

**Astra recomendado:** HIGH/MAX

---

# FASE 18 — SMARTAUDITS BUSINESS / BILLING

Crear planes:
- Free;
- Starter;
- Business;
- Pro;
- Enterprise.

Controlar:
- dominios;
- scans;
- retención;
- usuarios;
- reportes;
- monitorización;
- integraciones.

Integrar facturación cuando el producto ya tenga valor demostrable.

**Astra recomendado:** HIGH

---

# FASE 19 — MULTI-TENANCY / ORGANIZACIONES

Antes de Agency:
- Organization;
- Membership;
- roles internos;
- ownership;
- aislamiento de datos;
- invitaciones;
- audit trail.

Imprescindible evitar fugas cross-tenant.

**Astra recomendado:** HIGH/MAX

---

# FASE 20 — SMARTAUDITS AGENCY

Funciones:
- cientos de clientes;
- dashboard agregado;
- filtros;
- bulk scan;
- informes;
- white label;
- usuarios internos;
- permisos;
- notas;
- exportación.

Modelo comercial:
- precio por sitio;
- paquetes por volumen;
- marca blanca premium.

**Astra recomendado:** HIGH/MAX

---

# FASE 21 — WHITE LABEL

Permitir:
- logo;
- colores;
- dominio personalizado;
- pie de informe;
- nombre de producto;
- email remitente.

Sin ocultar obligaciones legales necesarias.

**Astra recomendado:** MEDIUM/HIGH

---

# FASE 22 — SMARTAUDITS VERIFIED

Crear página pública verificable:

```text
smartaudits.com/verify/{id}
```

Mostrar:
- entidad;
- dominio;
- fecha de auditoría;
- áreas auditadas;
- estado;
- validez;
- versión del estándar.

No denominar “certificación oficial” salvo que exista base jurídica/organizativa para ello.

Contemplar:
- expiración;
- revocación;
- historial;
- QR;
- firma/verificación.

**Astra recomendado:** HIGH

---

# FASE 23 — API PÚBLICA

Endpoints para:
- crear scan;
- obtener estado;
- obtener findings;
- descargar report;
- consultar score;
- webhooks.

Seguridad:
- API keys;
- OAuth más adelante;
- scopes;
- rate limits;
- quotas;
- logs.

**Astra recomendado:** HIGH

---

# FASE 24 — AI INVENTORY

Crear módulo SmartAudits AI.

Entidad AI System:
- nombre;
- proveedor;
- modelo;
- finalidad;
- usuarios;
- datos;
- decisiones;
- ámbito;
- owner;
- proveedores.

**Astra recomendado:** HIGH

---

# FASE 25 — AI ACT CLASSIFICATION

Cuestionario estructurado para evaluar:
- prohibiciones;
- alto riesgo;
- transparencia;
- GPAI cuando corresponda;
- obligaciones del proveedor/deployer/importador/etc.

Guardar:
- respuestas;
- versión del cuestionario;
- decisión;
- justificación;
- incertidumbres.

No convertirlo en asesoramiento jurídico automático definitivo.

**Astra recomendado:** HIGH/MAX

---

# FASE 26 — AI ACT WORKSPACE

Para cada sistema IA:
- clasificación;
- riesgos;
- controles;
- documentación;
- owners;
- deadlines;
- evidencias.

Módulos:
- Risk Management
- Data Governance
- Human Oversight
- Logging
- Accuracy
- Robustness
- Cybersecurity
- Transparency
- Technical Documentation
- Post-market monitoring cuando aplique

**Astra recomendado:** HIGH/MAX

---

# FASE 27 — VENDOR COMPLIANCE

Inventario de terceros:
- proveedor;
- servicio;
- datos;
- país;
- criticidad;
- contrato;
- DPA;
- subprocessors;
- certificaciones;
- fecha de revisión.

Relacionar:
- web trackers;
- SaaS;
- IA;
- infraestructura.

**Astra recomendado:** HIGH

---

# FASE 28 — EVIDENCE REPOSITORY

Repositorio central de evidencias:
- documentos;
- screenshots;
- logs;
- informes;
- contratos;
- configuraciones;
- aprobaciones.

Características:
- versionado;
- hash;
- timestamps;
- owner;
- expiración;
- auditoría.

**Astra recomendado:** HIGH

---

# FASE 29 — CONTROL LIBRARY / GRC FOUNDATION

Construir catálogo:

```text
Framework
 └── Requirement
      └── Control
           ├── Evidence
           ├── Finding
           └── Test
```

Esto permitirá reutilizar controles entre normas.

Ejemplo:
un control de gestión de proveedores puede mapear a varias normas.

**Astra recomendado:** HIGH/MAX

---

# FASE 30 — NORMATIVAS ADICIONALES

Añadir progresivamente, no simultáneamente.

Orden orientativo:
1. RGPD / privacidad
2. ePrivacy / cookies
3. European Accessibility Act
4. AI Act
5. NIS2
6. DORA
7. ENS
8. ISO 27001
9. SOC 2
10. PCI DSS u otros según demanda

Cada framework debe tener:
- versión;
- fuente;
- fecha de vigencia;
- aplicabilidad;
- controles;
- mapping;
- revisión jurídica.

**Astra recomendado:** HIGH/MAX

---

# FASE 31 — MOTOR DE CAMBIOS NORMATIVOS

Sistema para:
- versionar requisitos;
- marcar cambios;
- identificar clientes afectados;
- recalcular aplicabilidad;
- generar tareas.

No modificar automáticamente criterios jurídicos sin revisión humana.

**Astra recomendado:** HIGH/MAX

---

# FASE 32 — TASK MANAGEMENT

Convertir hallazgos en trabajo:

```text
Finding
 → Task
 → Owner
 → Due date
 → Evidence
 → Review
 → Resolved
```

Añadir:
- comentarios;
- prioridad;
- SLA;
- responsables;
- estados.

**Astra recomendado:** MEDIUM/HIGH

---

# FASE 33 — INTEGRACIONES

Prioridad:
- GitHub;
- GitLab;
- Slack;
- Teams;
- Jira;
- Azure DevOps;
- AWS;
- Azure;
- Google Cloud;
- CMS;
- CMPs.

Cada integración:
- scopes mínimos;
- OAuth;
- logs;
- revocación.

**Astra recomendado:** HIGH

---

# FASE 34 — ENTERPRISE

Funciones:
- SSO/SAML/OIDC;
- SCIM;
- RBAC avanzado;
- logs exportables;
- retención configurable;
- regiones de datos;
- claves propias más adelante;
- contratos Enterprise;
- SLA.

**Astra recomendado:** HIGH/MAX

---

# FASE 35 — SEGURIDAD DE LA PROPIA PLATAFORMA

Antes de crecer:
- threat model;
- SAST;
- DAST;
- dependency scanning;
- secret scanning;
- CSP;
- rate limiting;
- WAF/CDN;
- backup;
- restore tests;
- encryption;
- logging;
- alertas;
- pentest externo;
- gestión de incidentes.

**Astra recomendado:** HIGH/MAX

---

# FASE 36 — PRIVACIDAD DE SMARTAUDITS

Implementar en el propio SaaS:
- minimización;
- retención;
- borrado;
- exportación;
- DPA;
- subprocessors;
- registro de tratamientos;
- derechos;
- logs;
- separación tenant.

SmartAudits debe cumplir mejor de lo que audita.

**Astra recomendado:** HIGH/MAX

---

# FASE 37 — OBSERVABILIDAD

Añadir:
- logs estructurados;
- métricas;
- traces;
- dashboards;
- errores;
- tiempos de scan;
- coste por scan;
- colas;
- workers;
- disponibilidad.

**Astra recomendado:** HIGH

---

# FASE 38 — ARQUITECTURA ASÍNCRONA / WORKERS

Cuando el crawler crezca:
- job queue;
- workers;
- retries;
- dead-letter;
- prioridades;
- cancellation;
- progress;
- idempotencia.

No es necesario microservicios desde el principio.

**Astra recomendado:** HIGH/MAX

---

# FASE 39 — ESCALABILIDAD

Preparar:
- millones de páginas potenciales;
- almacenamiento de evidencias;
- límites;
- caché;
- deduplicación;
- workers horizontales;
- costes de navegador;
- políticas de reescaneo.

**Astra recomendado:** HIGH/MAX

---

# FASE 40 — INTELIGENCIA DE MERCADO

Crear datasets agregados y anonimizados cuando legalmente sea viable:

Ejemplo:
“X % de e-commerce analizados presenta Y categoría de problema”.

Usos:
- informes;
- marketing;
- benchmarking;
- producto.

Nunca revelar información privada de clientes.

---

# FASE 41 — BENCHMARKING

Permitir a clientes comparar:
- sector;
- tamaño;
- región;
- score;
- tendencia.

Solo con datos suficientes y agregados.

---

# FASE 42 — MOTOR DE LEADS

Escaneo controlado de webs públicas para identificar posibles clientes.

Requisitos:
- límites;
- respeto técnico;
- legalidad;
- no hacer afirmaciones falsas;
- no generar carga abusiva;
- opt-out cuando proceda.

Flujo comercial:
- detectar;
- crear preview;
- contactar;
- ofrecer auditoría completa.

**Astra recomendado:** HIGH

---

# FASE 43 — PRODUCT-LED GROWTH

Landing:
“Introduce tu web y descubre riesgos técnicos en minutos.”

Free scan limitado:
- score preliminar;
- algunos hallazgos;
- CTA a registro.

Evitar entregar demasiado coste de infraestructura gratis.

---

# FASE 44 — COMERCIALIZACIÓN A AGENCIAS

Objetivo:
primeros 10 partners.

Oferta:
- prueba;
- portfolio scan;
- informe de marca blanca;
- precio por volumen;
- soporte.

KPIs:
- coste adquisición;
- sitios por agencia;
- churn;
- MRR;
- expansión.

---

# FASE 45 — PRIMEROS HITOS DE NEGOCIO

## Hito A — Producto técnicamente fiable
- tests;
- seguridad;
- crawler;
- informe.

## Hito B — Primer cliente de pago
- un cliente paga por valor real.

## Hito C — 10 clientes
Validar:
- problema;
- pricing;
- onboarding;
- retención.

## Hito D — 1.000 € MRR
Comprobar uso recurrente.

## Hito E — 10.000 € MRR
Comenzar contratación selectiva.

## Hito F — 100.000 € MRR
Escalado comercial y operativo.

---

# FASE 46 — KPIs DEL PRODUCTO

Medir:
- tiempo hasta primer scan;
- % scans completados;
- coste medio por scan;
- páginas por scan;
- hallazgos por dominio;
- falsos positivos;
- falsos negativos;
- retención;
- MRR;
- ARR;
- churn;
- NRR;
- conversion free→paid;
- agencias activas;
- dominios monitorizados.

---

# FASE 47 — VALIDACIÓN JURÍDICA

Necesaria antes de vender conclusiones fuertes.

Crear proceso:
- revisión de catálogo;
- aprobación de redacción;
- versionado;
- disclaimers;
- revisión periódica;
- changelog.

Ideal:
asesoría jurídica especializada en privacidad, tecnología, accesibilidad y AI Act.

---

# FASE 48 — CALIDAD DEL MOTOR

Crear sistema de evaluación:

Dataset de referencia:
- webs;
- documentos;
- hallazgos validados;
- expected results.

Métricas:
- precision;
- recall;
- false-positive rate;
- false-negative rate.

Objetivo:
no vender “IA mágica”, sino resultados medibles.

---

# FASE 49 — IA COMO CAPA AUXILIAR

Usos posibles:
- clasificar páginas;
- resumir hallazgos;
- explicar problemas;
- proponer correcciones;
- mapear evidencia;
- analizar lenguaje.

No usar IA sola para:
- declarar cumplimiento;
- imponer severidad final;
- inventar referencias normativas;
- ejecutar cambios destructivos.

**Astra recomendado:** HIGH/MAX

---

# FASE 50 — VISIÓN FINAL: CONTINUOUS COMPLIANCE PLATFORM

SmartAudits final debería permitir:

## Web Compliance
- privacidad;
- cookies;
- consentimiento;
- formularios;
- accesibilidad;
- seguridad.

## AI Governance
- inventario;
- clasificación;
- controles;
- evidencias;
- AI Act.

## Vendor Risk
- proveedores;
- contratos;
- DPA;
- subprocesadores.

## GRC
- frameworks;
- controles;
- tareas;
- evidencias;
- reporting.

## Continuous Monitoring
- scans;
- cambios;
- alertas;
- histórico.

## Remediation
- instrucciones;
- snippets;
- PRs.

## Agency
- multi-cliente;
- white label.

## Enterprise
- SSO;
- SCIM;
- RBAC;
- API;
- integraciones.

---

# 4. Orden real de ejecución recomendado

No se desarrollarán las 50 fases linealmente sin validar mercado.

Orden práctico:

```text
0. Seguridad y estabilidad
1. Tests
2. Arquitectura del motor
3. Modelo Site/Audit/Page/Finding/Evidence
4. Crawler
5. Descubrimiento legal
6. Legal Content Engine
7. Cookies/Trackers
8. Consent
9. Accessibility
10. Informe 2.0
11. Score
12. Dashboard
13. Monitorización
14. Alertas
15. Primeros clientes
16. Agency
17. Fix It
18. Multi-tenant maduro
19. AI Inventory / AI Act
20. Verified
21. API
22. Vendor Compliance
23. GRC / frameworks
24. Enterprise
```

Después se priorizará según:
- clientes reales;
- ingresos;
- coste;
- ventaja competitiva;
- regulación;
- capacidad técnica.

---

# 5. Próximos pasos inmediatos

## Paso actual
Commit de `CURRENT_ARCHITECTURE.md`.

## Después
Crear rama:
`phase-0/security-hardening`

## Primer bloque de trabajo
1. secretos/configuración;
2. JWT y usuarios desactivados;
3. permisos;
4. XSS del informe;
5. tests de seguridad;
6. límites/errores;
7. migraciones.

Solo después:
**crawler automático**.

---

# 6. Convención de ramas

Ejemplos:

```text
phase-0/security-hardening
feature/test-foundation
feature/domain-model
feature/crawler-foundation
feature/legal-page-discovery
feature/content-extraction
feature/cookie-scanner
feature/consent-engine
feature/accessibility-engine
feature/report-v2
feature/monitoring
feature/agency
feature/ai-act
```

---

# 7. Convención de commits

Ejemplos:

```text
docs: document current architecture
security: remove fallback secrets
security: reject inactive JWT users
fix: enforce audit ownership on download
test: add authentication regression tests
feat: add site entity
feat: add crawler foundation
feat: detect legal pages
feat: add tracker detection
```

---

# 8. Definition of Done

Una tarea no está terminada hasta que:

- funciona;
- tiene tests;
- no rompe tests anteriores;
- no introduce secretos;
- está documentada;
- tiene manejo de errores;
- cumple criterios de seguridad;
- Git está limpio;
- existe commit identificable;
- se puede revertir.

---

# 9. Regla de trabajo con Astra/Codex

Antes de cada tarea:

1. indicar nivel Astra recomendado;
2. pedir análisis antes de cambios cuando la tarea sea delicada;
3. acotar archivos;
4. prohibir cambios no relacionados;
5. pedir tests;
6. pedir resumen;
7. revisar `git diff`;
8. probar;
9. commit.

Nunca:
“implementa todo SmartAudits 2.0”.

Siempre:
una unidad de trabajo controlada.

---

# 10. Objetivo empresarial

La meta no es únicamente crear un escáner.

La meta es evolucionar hacia:

**SmartAudits — Continuous Compliance Platform**

con tres motores de crecimiento:

1. **SaaS directo a empresas**
2. **SmartAudits Agency**
3. **Compliance / AI Governance / GRC empresarial**

El producto debe generar ingresos recurrentes porque el cumplimiento no se analiza una vez: cambia la web, cambian los proveedores, cambian las tecnologías y cambia la normativa.
