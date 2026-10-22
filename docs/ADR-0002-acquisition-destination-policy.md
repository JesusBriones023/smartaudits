# ADR-0002 — Acquisition Destination Policy

**Status:** Proposed — corregido tras la primera revisión de Astra (1 HIGH, 3 MEDIUM y
1 LOW documentales); pendiente de revalidación. Especificación sin implementación.

**Date:** 2026-10-20

**Relación:** complementa ADR-0001 sin modificarlo. Base: `public-main`, commit `0436701`.

## 1. Contexto

**Estado actual.** Fase 3 está cerrada y publicada: V4–V8 crean `organizations`,
`organization_memberships`, `sites`, `audits` y `scans`, y `AuditScanService` es un
servicio interno sin endpoints públicos 2.0. Ninguna parte del sistema visita URL:
`sites.base_url` conserva el texto recibido sin canonización, DNS ni peticiones
(ADR-0001 §7), y el campo legacy `urlOpcional` de las auditorías solo tiene límite
de longitud. Hoy la ausencia de adquisición es la única protección frente a SSRF.

**Necesidad.** La Fase 4 del roadmap (Smart Crawler Foundation) empieza por la
entrada por dominio (4.1), con seguridad SSRF obligatoria. ADR-0001 asigna a
F4.1/F4.5 las decisiones sobre DNS, redirects, SSRF, canonicalización e identidad
por URL, y a F4 la tabla `scan_failures`, Page y Capture (§5, §7, §8 y §9). Antes de
diseñar cualquier descarga, el producto necesita una frontera que decida de forma
determinista qué destino de adquisición es admisible: qué texto de URL se acepta,
a qué host y puerto apunta, qué direcciones devuelve el DNS para ese host y si
alguna pertenece a rangos que el crawler nunca debe alcanzar.

**Decisión de planificación.** La Fase 4.1-A fue aprobada "con ajustes
arquitectónicos": URL canónica, validación de destinos, política anti-SSRF pura,
clasificación IPv4/IPv6, `HostResolver` como interfaz, resultados inmutables y
pruebas sin red. No se aprobaron todavía fetcher HTTP, DNS real, endpoints,
crawler, persistencia, V9, workers ni frontend. Este ADR fija la arquitectura de
4.1-A dentro de ese perímetro.

## 2. Estado y alcance

**Este ADR es una especificación, no una implementación.** No crea código, tests,
migraciones, dependencias ni configuración, y no describe una política existente.
Implementar 4.1-A requerirá una autorización posterior explícita y la revisión de
Astra sobre este documento.

**Encuadre obligatorio.**

- 4.1-A es una política interna **sin red**: decide sobre texto, sobre una
  respuesta DNS entregada por una interfaz y sobre un catálogo estático.
- **No es una protección SSRF completa.** SSRF se materializa en el transporte
  (conexión, TLS, redirects, reintentos, proxies, reutilización de conexiones),
  que pertenece a 4.1-B, y en el entorno de salida del despliegue (NAT64,
  traducciones, túneles). Una decisión aprobada solo protege si el fetcher futuro
  conecta exclusivamente a las direcciones aprobadas y cumple la sección 17,
  incluido el control bloqueante del entorno de salida.
- Validar una URL y una respuesta DNS **no equivale a HTTP seguro**.
- 4.1-A **no es un crawler funcional**: no descarga, no descubre enlaces, no
  persiste y ningún flujo de usuario la invoca.

**Decisión en síntesis.**

- Solo `http` y `https`, con sus puertos por defecto; host DNS ASCII de al menos
  dos etiquetas; sin userinfo, literales IP ni IDN.
- Canonización mínima, conservadora e idempotente. El destino de adquisición no
  fija la identidad de Site ni la de Page.
- Precedencia de rechazo determinista con dieciocho códigos estables.
- DNS a través de `HostResolver`: respuesta A + AAAA completa, como máximo 32
  registros y 8 saltos CNAME, sin fallback implícito.
- Toda dirección se clasifica con un catálogo versionado de dos capas; una sola
  dirección inadmisible rechaza el conjunto.
- La aprobación es un objeto inmutable, sin construcción pública, que documenta
  una evaluación: liga URL, host, esquema, puerto, direcciones y revisión del
  catálogo, pero no autoriza por sí sola ninguna conexión. 4.1-B obtendrá una
  evaluación nueva en cada intento.

**Entregables futuros de 4.1-A** (tras autorización):

- tipos Java puros e inmutables en `com.smartaudits.v2.acquisition.destination`;
- un catálogo de direcciones y nombres congelado, versionado y con hash;
- resolvers falsos solo en código de test;
- tests JUnit parametrizados y validación por mutación.

**Exclusiones de 4.1-A:** fetcher HTTP, cliente DNS real, sockets, proxies,
endpoints o controllers, beans o configuración Spring, crawler, workers,
persistencia, migraciones (V9 o cualquier otra), `scan_failures`, cambios en
Site/Audit/Scan, frontend y dependencias nuevas.

**Secuencia de subfases.**

| Subfase | Contenido | Estado |
| --- | --- | --- |
| 4.1-A | URL de destino y política de destino pura, sin red | Este ADR; implementación pendiente de autorización |
| 4.1-B | Fetcher seguro: DNS real, conexión fijada a IP, TLS, redirects y límites | Futura, con decisión propia |
| 4.1-C | Identidad de Site por URL | Futura |
| 4.2-A | `scan_failures` y ejecución segura de Scan | Futura |
| 4.2-B | Page y Capture | Futura |
| 4.3–4.6 | Descubrimiento, presupuestos de crawl, normalización y evidencia | Futuras |

No se asignan números de migración Flyway (V9, V10, V11…) a ninguna subfase ni se
inicia ninguna. Document, Finding y Evidence siguen en F6 (ADR-0001 §5).

## 3. Amenazas y fronteras de confianza

**Actores no confiables.**

- Usuario autenticado de una organización que introduce una URL, malicioso o
  equivocado.
- Titular de un dominio externo, que controla su DNS autoritativo: puede devolver
  cualquier A/AAAA, cambiarlo entre consultas, usar TTL 0 y construir cadenas
  CNAME arbitrarias. En 4.1-B controlará además las respuestas HTTP y los
  redirects.
- Contenido descubierto (enlaces, redirects, sitemaps) en fases posteriores, que
  recibe exactamente el mismo tratamiento que la entrada del usuario.

**Fronteras.**

| Frontera | Origen | Confianza | Tratamiento en 4.1-A |
| --- | --- | --- | --- |
| Texto de URL | Usuario, legacy, contenido descubierto | Ninguna | Perfil estricto, sin trim, reparación ni decodificación |
| Respuesta DNS | `HostResolver` | Ninguna | Validación estructural y de límites; clasificación de todas las direcciones |
| Catálogo | Repositorio, revisado | Confiable si está congelado y revisado | Constante versionada; lo que queda fuera del perímetro positivo se prohíbe, y no certifica destinos (sección 11) |
| Llamante (servicio o worker futuro) | Código propio | Responsable de la tenancy | La política no autoriza tenancy ni recibe entidades |
| Red y transporte | Entorno de despliegue | Fuera de 4.1-A | Obligaciones de 4.1-B (sección 17) |

**Objetivos del atacante.** Alcanzar loopback y servicios del propio host
(backend, MariaDB, Adminer, Docker), redes privadas o de contenedores, y servicios
de metadatos cloud con sus credenciales; eludir comprobaciones mediante formas
alternativas de host, redirects o DNS rebinding; explorar puertos internos;
obtener información a través de mensajes de error; agotar recursos.

**Principios.**

- Denegar por defecto: lo no reconocido se rechaza.
- Fallo cerrado: un error, una respuesta incompleta o una ambigüedad producen
  rechazo.
- Toda dirección se clasifica; ninguna se descarta en silencio.
- Determinismo: igual entrada, igual catálogo e igual respuesta DNS producen igual
  resultado.
- Sin efectos secundarios: ni red, ni logs, ni estado mutable compartido.
- Los errores esperados son resultados tipados, no excepciones.

## 4. Perfil de URL

Una URL de destino admisible es una URL absoluta y jerárquica de la forma
`esquema://host[:puerto][ruta][?query][#fragmento]`, compuesta solo por ASCII
imprimible, que cumple todas estas reglas:

| Elemento | Regla | Código si se incumple |
| --- | --- | --- |
| Entrada | Cadena Java no nula, evaluada tal cual: sin trim, reparación ni decodificación. `null` es un error de contrato del llamante, no una decisión | — |
| Longitud | Como máximo 2048 caracteres en la entrada y en la forma canónica | URL_TOO_LONG |
| Caracteres | Nunca controles C0 (U+0000–U+001F), DEL (U+007F), espacio ni barra invertida; nunca caracteres no ASCII | URL_CHARACTERS o IDN_UNSUPPORTED |
| Forma | Absoluta, jerárquica y con autoridad explícita (`//`); se rechazan referencias relativas, de esquema relativo (`//host`), opacas (`https:host`) y ambiguas | URL_FORMAT |
| Esquema | Solo `http` o `https` | SCHEME |
| Userinfo | Prohibida, incluso vacía: ningún `@` en la autoridad | USERINFO |
| Host | Nombre DNS ASCII de letras, dígitos y guiones, con al menos dos etiquetas | HOST_FORMAT o HOST_FORBIDDEN |
| Literales IP | Prohibidos en cualquier forma, también los públicos | IP_LITERAL |
| IDN | Sin U-labels, A-labels `xn--`, etiquetas reservadas ni puntos Unicode | IDN_UNSUPPORTED |
| Puerto | Ausente o igual al puerto por defecto del esquema | PORT |
| Percent-encoding | Prohibido en la autoridad; en ruta, query y fragmento solo `%HH` válido | PERCENT_ENCODING |
| Escapes peligrosos | Sin `%00`–`%1F`, `%7F` ni `%5C` | URL_CHARACTERS |
| Segmentos punto | Sin segmentos `.` ni `..` en la ruta, tampoco en forma `%2e` | DOT_SEGMENT |
| Fragmento | Se valida sintácticamente y se elimina; nunca forma parte del destino | — |

**Límites exactos.**

- URL: como máximo 2048 caracteres. Como solo se acepta ASCII, en una URL aceptada
  coinciden caracteres, code points y bytes. El límite coincide con
  `sites.base_url` VARCHAR(2048), sin establecer ninguna relación de identidad.
- Host: como máximo 253 caracteres y sin punto final.
- Etiqueta: entre 1 y 63 caracteres de `a–z`, `0–9` y `-`, sin guion inicial ni
  final.
- Se rechazan el guion bajo, los comodines (`*`) y cualquier otro carácter en el
  host.
- Un host de una sola etiqueta es un nombre local y se rechaza.

**Caracteres por componente.** Es el repertorio de RFC 3986.

- Ruta: no reservados (`A–Z a–z 0–9 - . _ ~`), sub-delimitadores
  (`! $ & ' ( ) * + , ; =`), `:`, `@`, `/` y `%HH`.
- Query y fragmento: lo mismo, más `?`.
- Se rechazan literalmente `"`, `<`, `>`, `[`, `]`, `^`, `{`, `}`, la barra
  vertical (U+007C), el acento grave (U+0060) y un segundo `#` dentro del
  fragmento.

Quien necesite esos caracteres debe enviarlos escapados.

**Mayúsculas.** Esquema y host se comparan y canonizan con un mapeo exclusivamente
ASCII (`A–Z` → `a–z`), aplicado solo después de comprobar que el texto es ASCII.
La ruta, la query y los escapes conservan su caja.

## 5. Precedencia de validación

La evaluación es una secuencia fija.

- La primera regla incumplida determina el código y las posteriores no se evalúan.
- Si una regla recorre varios componentes, el orden es ruta, query y fragmento. El
  código depende de la regla, no de la posición.
- El resolver solo se invoca cuando se cumplen las reglas 1–25.

| Orden | Etapa | Condición de rechazo | Código |
| --- | --- | --- | --- |
| 1 | Entrada | Más de 2048 unidades UTF-16 | URL_TOO_LONG |
| 2 | Entrada | Algún U+0000–U+0020, U+007F o barra invertida en cualquier posición | URL_CHARACTERS |
| 3 | Estructura | Entrada vacía, sin `:`, con `/`, `?` o `#` antes del primer `:`, o esquema que no cumple `ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )` | URL_FORMAT |
| 4 | Esquema | Esquema distinto de `http` o `https` (comparación ASCII sin distinguir mayúsculas) | SCHEME |
| 5 | Estructura | Tras `esquema:` no sigue exactamente `//` | URL_FORMAT |
| 6 | Autoridad | Contiene `@` | USERINFO |
| 7 | Autoridad | Empieza por `[` | IP_LITERAL |
| 8 | Autoridad | Contiene algún carácter no ASCII | IDN_UNSUPPORTED |
| 9 | Autoridad | Contiene `%` | PERCENT_ENCODING |
| 10 | Autoridad | Autoridad vacía, host vacío o más de un `:` | HOST_FORMAT |
| 11 | Host | Termina en número (ver abajo) | IP_LITERAL |
| 12 | Host | Carácter fuera de `a–z`, `0–9`, `-` y `.`, tras pasar a minúsculas ASCII | HOST_FORMAT |
| 13 | Host | Punto final | HOST_FORMAT |
| 14 | Host | Más de 253 caracteres | HOST_FORMAT |
| 15 | Host | Etiqueta vacía, de más de 63 caracteres o con guion inicial o final | HOST_FORMAT |
| 16 | Host | Etiqueta que empieza por `xn--` o con `--` en las posiciones 3 y 4 | IDN_UNSUPPORTED |
| 17 | Host | Una sola etiqueta | HOST_FORBIDDEN |
| 18 | Host | Igual a un nombre prohibido del catálogo o subdominio suyo | HOST_FORBIDDEN |
| 19 | Puerto | Puerto presente que no es el puerto por defecto escrito de forma canónica (sección 9) | PORT |
| 20 | Ruta, query, fragmento | Carácter no ASCII, o ASCII no permitido en ese componente | URL_CHARACTERS |
| 21 | Ruta, query, fragmento | `%` no seguido de dos dígitos hexadecimales | PERCENT_ENCODING |
| 22 | Ruta, query, fragmento | Escape `%00`–`%1F`, `%7F` o `%5C`, con cualquier caja | URL_CHARACTERS |
| 23 | Ruta | Segmento punto (ver abajo) | DOT_SEGMENT |
| 24 | Contraste | `java.net.URI` rechaza la entrada o discrepa de la partición mínima | URL_FORMAT |
| 25 | Forma canónica | La forma canónica supera 2048 caracteres | URL_TOO_LONG |
| 26 | DNS | El resolver lanza una excepción, es interrumpido o informa FAILURE en alguna familia | DNS_FAILURE |
| 27 | DNS | Más de 32 registros de dirección o más de 8 saltos CNAME | DNS_LIMIT |
| 28 | DNS | Respuesta estructuralmente inválida o contradictoria | DNS_INVALID |
| 29 | DNS | Ninguna dirección en ninguna familia | DNS_EMPTY |
| 30 | Direcciones | Ninguna dirección admisible | ADDRESS_FORBIDDEN |
| 31 | Direcciones | Direcciones admisibles e inadmisibles a la vez | ADDRESS_MIXED |

**Partición mínima.** Las reglas 3–23 operan sobre una partición mínima de la
entrada por delimitadores, con una precedencia fija. Toma del Apéndice B de
RFC 3986 solo la regla de corte: **no es una implementación general de RFC 3986**.
No resuelve referencias, no normaliza y no valida gramáticas de componentes más
allá de las reglas explícitas de esta sección. Los cortes son:

- el esquema es el texto anterior al primer `:`;
- la autoridad empieza tras `//` y termina en el primer `/`, `?` o `#`, o al final;
- la ruta termina en el primer `?` o `#`, y la query en el primer `#`;
- el resto es el fragmento.

La regla 2 ya rechazó la barra invertida. Así desaparece la ambigüedad de los
analizadores WHATWG, que la tratan como `/` en esquemas especiales. Host y puerto
se separan por el único `:` admitido en la autoridad.

**Regla "termina en número" (11).** Se toma la última etiqueta del host, ignorando
una única etiqueta vacía final. Si está formada solo por dígitos, o por `0x`
seguido de cero o más dígitos hexadecimales, el host es un literal IP.

- Equivale al "ends in a number checker" del URL Standard de WHATWG.
- Cubre IPv4 canónica, abreviada (`127.1`), entera (`2130706433`), octal
  (`0177.0.0.1`), hexadecimal (`0x7f000001`) y mixta.
- Ningún TLD es numérico, por lo que no rechaza nombres DNS legítimos. Una
  etiqueta numérica que no es la última (`123.example.com`) sigue siendo válida.

**Segmento punto (23).** La ruta se divide por `/`. Solo para comparar, sin
alterar la ruta, cada `%2e` o `%2E` se interpreta como `.`. Un segmento es un
segmento punto en dos casos:

- si el resultado es `.` o `..`;
- si lo es su parte anterior al primer `;`. Es una regla de producto que cubre
  `..;` en servidores con parámetros de ruta.

La regla no se aplica a la query ni al fragmento.

### Parser y contraste con `java.net.URI` (regla 24)

**Uso como segundo analizador.** `java.net.URI` de Java 17 es un segundo
analizador sintáctico, nunca la autoridad única. Una entrada que superó las reglas
1–23 debe cumplir tres condiciones:

- el constructor de un argumento la acepta sin excepción;
- es absoluta y no opaca;
- sus componentes crudos (`getScheme`, `getRawAuthority`, `getRawPath`,
  `getRawQuery`, `getRawFragment`) coinciden exactamente con la partición mínima,
  incluida la diferencia entre componente ausente y vacío.

Cualquier discrepancia es URL_FORMAT.

**Métodos de `URI` excluidos de la decisión.**

- `getHost()` y `getPort()`. `getHost()` devuelve `null` cuando `URI` trata la
  autoridad como basada en registro; por ejemplo, con `exa_mple.com`. Además, su
  gramática de host (RFC 2396) no es la de este perfil.
- `normalize()`, `resolve()`, `toURL()` y `toASCIIString()`, y los constructores
  de varios argumentos, que recodifican componentes.

**APIs prohibidas en el paquete.**

- `java.net.URL`: su `equals`/`hashCode` resuelve DNS, y sus constructores están
  obsoletos desde Java 20.
- `URLDecoder`/`URLEncoder`: codificación de formularios y semántica de `+`.
- `java.net.IDN`.
- `InetAddress.getByName`/`getAllByName`, que resuelven nombres y aceptan formas
  heterodoxas, y `getHostName`/`getCanonicalHostName`, que hacen resolución
  inversa.
- `String.equalsIgnoreCase`, y `toLowerCase`/`toUpperCase` sobre texto no
  verificado o con locale implícito. En Java 17, `equalsIgnoreCase` considera
  iguales `https` y `http` seguido de U+017F, y el signo Kelvin (U+212A) igual a
  `k`; con locale turco, `"TITLE".toLowerCase()` no produce `title`.

**Otras reglas del analizador.**

- Sin trim, normalización Unicode ni reparación de entradas.
- Cada regla es lineal en la longitud de la entrada, acotada por la regla 1.
- El corpus normativo prevalece. Una sonda desechable, ejecutada fuera del
  repositorio al redactar este ADR, observó que `URI` de Java 17 distingue query o
  fragmento vacíos (`""`) de ausentes (`null`). No es un test del proyecto: los
  tests de la implementación deberán fijarlo. Si `URI` no reprodujera el corpus de
  las secciones 5 y 6, la implementación se detendría y volvería a revisión, sin
  adaptar el corpus en silencio.

### Corpus de rechazo (normativo)

| Entrada | Código | Regla |
| --- | --- | --- |
| URL de 2049 caracteres | URL_TOO_LONG | 1 |
| `https://example.com/a b` | URL_CHARACTERS | 2 |
| `https://example.com` precedida de un espacio | URL_CHARACTERS | 2 |
| U+0009 o U+000A dentro del host | URL_CHARACTERS | 2 |
| `https://example.com\@evil.example/` | URL_CHARACTERS | 2 |
| Cadena vacía | URL_FORMAT | 3 |
| `example.com` | URL_FORMAT | 3 |
| `//example.com/` | URL_FORMAT | 3 |
| `/ruta` | URL_FORMAT | 3 |
| `1http://example.com/` | URL_FORMAT | 3 |
| `ftp://example.com/` | SCHEME | 4 |
| `file:///etc/passwd` | SCHEME | 4 |
| `javascript:alert(1)` | SCHEME | 4 |
| `wss://example.com/` | SCHEME | 4 |
| `localhost:8080` (RFC 3986 lee `localhost` como esquema) | SCHEME | 4 |
| `https:example.com` | URL_FORMAT | 5 |
| `https:/example.com` | URL_FORMAT | 5 |
| `https://user:pass@example.com/` | USERINFO | 6 |
| `https://@example.com/` | USERINFO | 6 |
| `https://example.com@evil.example/` | USERINFO | 6 |
| `https://[::1]/` | IP_LITERAL | 7 |
| `https://[::ffff:127.0.0.1]/` | IP_LITERAL | 7 |
| `https://[fe80::1%25eth0]/` | IP_LITERAL | 7 |
| `https://bücher.example/` | IDN_UNSUPPORTED | 8 |
| `https://example。com/` (U+3002) | IDN_UNSUPPORTED | 8 |
| Host con U+200B o U+00AD entre letras | IDN_UNSUPPORTED | 8 |
| `https://ex%61mple.com/` | PERCENT_ENCODING | 9 |
| `https://` | HOST_FORMAT | 10 |
| `https:///ruta` | HOST_FORMAT | 10 |
| `https://:443/` | HOST_FORMAT | 10 |
| `https://::1/` | HOST_FORMAT | 10 |
| `https://127.0.0.1/` | IP_LITERAL | 11 |
| `https://127.1/` | IP_LITERAL | 11 |
| `https://2130706433/` | IP_LITERAL | 11 |
| `https://0177.0.0.1/` | IP_LITERAL | 11 |
| `https://0x7f000001/` | IP_LITERAL | 11 |
| `https://1.2.3.4./` | IP_LITERAL | 11 |
| `https://example.0x1f/` | IP_LITERAL | 11 |
| `https://exa_mple.com/` | HOST_FORMAT | 12 |
| `https://*.example.com/` | HOST_FORMAT | 12 |
| `https://example.com./` | HOST_FORMAT | 13 |
| Host de 254 caracteres | HOST_FORMAT | 14 |
| `https://a..example.com/` | HOST_FORMAT | 15 |
| `https://-a.example.com/` | HOST_FORMAT | 15 |
| Etiqueta de 64 caracteres | HOST_FORMAT | 15 |
| `https://xn--bcher-kva.example/` | IDN_UNSUPPORTED | 16 |
| `https://ab--cd.example/` | IDN_UNSUPPORTED | 16 |
| `https://localhost/` | HOST_FORBIDDEN | 17 |
| `https://intranet/` | HOST_FORBIDDEN | 17 |
| `https://example/` (sin excepción por ser nombre de documentación) | HOST_FORBIDDEN | 17 |
| `https://app.localhost/` | HOST_FORBIDDEN | 18 |
| `https://printer.local/` | HOST_FORBIDDEN | 18 |
| `https://metadata.google.internal/` | HOST_FORBIDDEN | 18 |
| `https://router.home.arpa/` | HOST_FORBIDDEN | 18 |
| `https://example.com:8080/` | PORT | 19 |
| `http://example.com:443/` | PORT | 19 |
| `https://example.com:80/` | PORT | 19 |
| `https://example.com:/` | PORT | 19 |
| `https://example.com:0443/` | PORT | 19 |
| `https://example.com:+443/` | PORT | 19 |
| `https://example.com:99999/` | PORT | 19 |
| `https://example.com/café` | URL_CHARACTERS | 20 |
| `https://example.com/?a[0]=1` | URL_CHARACTERS | 20 |
| `https://example.com/#a#b` | URL_CHARACTERS | 20 |
| `https://example.com/%zz` | PERCENT_ENCODING | 21 |
| `https://example.com/a%2` | PERCENT_ENCODING | 21 |
| `https://example.com/#%G0` | PERCENT_ENCODING | 21 |
| `https://example.com/%0d%0aX:y` | URL_CHARACTERS | 22 |
| `https://example.com/?q=%00` | URL_CHARACTERS | 22 |
| `https://example.com/%5c..%5cx` | URL_CHARACTERS | 22 |
| `https://example.com/./a` | DOT_SEGMENT | 23 |
| `https://example.com/a/../b` | DOT_SEGMENT | 23 |
| `https://example.com/a/%2E%2e/b` | DOT_SEGMENT | 23 |
| `https://example.com/a/..` | DOT_SEGMENT | 23 |
| `https://example.com/..;/admin` | DOT_SEGMENT | 23 |
| URL de 2048 caracteres con ruta vacía y query | URL_TOO_LONG | 25 |

### Violaciones múltiples

| Entrada | Reglas incumplidas | Código | Motivo |
| --- | --- | --- | --- |
| `ftp://user@127.0.0.1:21/../` | Esquema, userinfo, literal IP, puerto, segmento punto | SCHEME | La regla 4 precede a las demás |
| `https://user@[::1]:8080/../` | Userinfo, literal IP, puerto, segmento punto | USERINFO | La regla 6 precede a la 7 |
| `https://EXAMPLE.com:8080/a b` | Espacio, puerto | URL_CHARACTERS | La regla 2 es global y temprana |
| `https://127.0.0.1:8080/` | Literal IP, puerto | IP_LITERAL | El host (11) precede al puerto (19) |
| `https://localhost:8080/` | Nombre local, puerto | HOST_FORBIDDEN | La regla 17 precede a la 19 |
| `https://bücher.example:8080/` | IDN, puerto | IDN_UNSUPPORTED | La regla 8 precede a la 19 |
| `https://example.com:8080/../` | Puerto, segmento punto | PORT | La regla 19 precede a la 23 |
| `https://example.com/../%5c` | Segmento punto, `%5C` | URL_CHARACTERS | La regla 22 precede a la 23 |
| `javascript:` seguido de 2038 caracteres | Longitud, esquema | URL_TOO_LONG | La regla 1 es la primera |

## 6. Canonización

La forma canónica de un `DestinationUrl` concatena componentes crudos ya
validados, en este orden:

1. el esquema en minúsculas, seguido de `://`;
2. el host en minúsculas;
3. la ruta, o `/` si está vacía;
4. `?` seguido de la query, solo si la entrada contenía `?`.

Nunca incluye el puerto: solo se admiten puertos por defecto, que se omiten, y el
puerto efectivo (80 u 443) se deriva del esquema. Nunca incluye el fragmento. No se
construye con constructores de `URI`, `normalize()` ni decodificación o
recodificación.

**Se conserva exactamente:**

- la caja de la ruta, las barras repetidas y la barra final;
- el orden de los parámetros de la query, los parámetros duplicados y los valores
  vacíos;
- la diferencia entre query ausente y query vacía (`?`);
- el carácter `+`;
- cada escape `%HH` con su caja original, incluidos los de bytes no ASCII y los de
  `/` o `?`.

**No se hace:**

- decodificar o recodificar escapes, ni normalizar la caja hexadecimal;
- eliminar parámetros de tracking u ordenar la query;
- fusionar `http` con `https` o `www` con el dominio raíz;
- colapsar barras o resolver segmentos punto (se rechazan; no se resuelven);
- añadir o quitar una barra final, salvo la ruta vacía.

**Propiedades.** La canonización es conservadora, determinista e idempotente.

- Solo cambia cuatro cosas: la caja del esquema y del host, el puerto por defecto
  (que omite), la ruta vacía (que sustituye por `/`) y el fragmento (que elimina).
- La forma canónica de una forma canónica es ella misma.
- Volver a analizar la forma canónica produce un `DestinationUrl` igual.

### Corpus de canonización (normativo)

| Entrada | Forma canónica | Aspecto verificado |
| --- | --- | --- |
| `https://example.com` | `https://example.com/` | Ruta vacía |
| `HTTPS://EXAMPLE.COM/Path` | `https://example.com/Path` | Minúsculas solo en esquema y host |
| `https://example.com:443/` | `https://example.com/` | Puerto por defecto explícito |
| `http://example.com:80/a` | `http://example.com/a` | Puerto por defecto en http |
| `https://example.com:443?x=1` | `https://example.com/?x=1` | Ruta vacía con query |
| `https://example.com/A/b//c/` | Igual | Caja de ruta, barras repetidas, barra final |
| `https://example.com/p?b=2&a=1&a=1&c=&d` | Igual | Orden, duplicados, valores vacíos |
| `https://example.com/p?` | Igual | Query vacía presente |
| `https://example.com/p` | Igual | Query ausente, distinta de la anterior |
| `https://example.com/a+b?q=a+b%20c` | Igual | `+` y escapes |
| `https://example.com/%7euser/%7E/%2F?x=%41` | Igual | Escapes sin decodificar ni cambiar de caja |
| `https://example.com/caf%C3%A9` | Igual | Bytes no ASCII escapados |
| `https://example.com/%E2%80%AE` | Igual | Escape opaco; no se decodifica |
| `https://example.com/p#sec` | `https://example.com/p` | Fragmento eliminado |
| `https://example.com/p?a=1#x?y` | `https://example.com/p?a=1` | Fragmento con `?` |
| `https://example.com#` | `https://example.com/` | Fragmento vacío |
| `https://www.example.com/` | Igual | `www` no se fusiona |
| `http://example.com/` | Igual, distinta de la variante https | http y https distintos |
| `https://example.com/?utm_source=x&gclid=y` | Igual | Tracking conservado |
| `https://example.com/a;b=c/..d/e..` | Igual | Parámetros de ruta; `..d` y `e..` no son segmentos punto |
| `https://example.com/p?next=../x` | Igual | `..` en la query no es segmento punto |
| `https://123.example.com/` | Igual | Etiqueta numérica no final |
| `https://sub-1.a.example.com/x/` | Igual | Host LDH de varias etiquetas |

Para cada fila, analizar la forma canónica devuelve la misma forma canónica.

Los corpus usan `example.com` solo como nombre sintácticamente válido. Que una
forma canónica se acepte en el análisis no implica que el nombre resuelva ni que
el destino se apruebe (sección 11).

## 7. Identidades excluidas

| Identidad | Qué responde | Dónde se decide | Relación con `DestinationUrl` |
| --- | --- | --- | --- |
| Destino de adquisición | Qué petición exacta se haría, hacia qué host y puerto | Este ADR | Es `DestinationUrl` |
| Identidad de origen | Si dos URL comparten esquema, host y puerto (RFC 6454) | 4.1-B (redirects) y 4.3 | Derivable, sin definirse aquí |
| Identidad de Site | Qué URL y dominios pertenecen al mismo Site registrado (http/https, `www`, subdominios, ruta base) | 4.1-C, conforme a ADR-0001 §7 | `DestinationUrl` no la fija |
| Identidad de Page | Si dos URL adquiridas son la misma página (tracking, orden de query, fragmentos, enlace canónico) | 4.2-B y 4.5 | `DestinationUrl` no deduplica Pages |

Consecuencias:

- La igualdad de dos `DestinationUrl` solo significa "mismo destino de
  adquisición".
- Dos `DestinationUrl` distintos pueden ser la misma Page.
- Un mismo `DestinationUrl` puede pertenecer a Sites distintos, incluso de
  organizaciones distintas.
- `sites.base_url` sigue siendo literal, y `DestinationUrl` no lo sustituye ni lo
  valida retroactivamente.
- En 4.1-A ningún `DestinationUrl` se persiste ni se usa como clave. Cuando se
  persista una forma canónica (4.1-C o 4.2-B), será ASCII y se comparará con
  semántica binaria (ADR-0001 §12).

## 8. Literales IP e IDN

### Literales IP

Se rechaza **todo** host que sea un literal IP, también si la dirección es pública:

- IPv4 canónica;
- IPv6 entre corchetes, incluidas IPv4-mapped (`[::ffff:127.0.0.1]`) e
  identificadores de zona (`[fe80::1%25eth0]`);
- IPv4 abreviada (`127.1`), entera decimal (`2130706433`), octal (`0177.0.0.1`),
  hexadecimal (`0x7f000001`) y mixta;
- hosts con terminación numérica ambigua (`example.0x1f`).

La detección es puramente sintáctica (reglas 7 y 11). No consulta DNS ni usa
`InetAddress`. Una IPv6 sin corchetes no es sintaxis URI válida y se rechaza como
HOST_FORMAT por contener más de un `:`.

Motivos para rechazar también los literales públicos:

- eliminan la superficie de diferenciales entre analizadores (`inet_aton`, octal,
  hexadecimal, enteros);
- evitan certificados TLS emitidos para IP;
- mantienen un único camino de decisión: nombre, después DNS y después
  clasificación.

Admitir literales públicos es una ampliación futura que requiere decisión
explícita. Solo admitiría IPv4 decimal con puntos e IPv6 en forma RFC 5952 sin
zona, sometidas a la misma `AddressPolicy`.

### IDN

**Se difiere IDN.** Solo se aceptan etiquetas DNS ASCII.

- Se rechazan U-labels, A-labels `xn--`, etiquetas R-LDH (`--` en las posiciones 3
  y 4), puntos Unicode (U+3002, U+FF0E, U+FF61) y formas invisibles o de
  compatibilidad: anchura cero, guion débil, letras de anchura completa.
- No se aplica NFKC ni ninguna normalización Unicode, ni se convierte nada en
  silencio con `java.net.IDN`.

**Por qué `java.net.IDN` no sirve.** En Java 17 implementa IDNA2003 (RFC 3490),
con nameprep basado en Unicode 3.2. No equivale a IDNA2008 (RFC 5890–5894) ni a
UTS #46: difiere, entre otros, en `ß`, `ς` y los caracteres de unión ZWJ/ZWNJ.

**Opción futura.** Una implementación UTS #46 no transicional (por ejemplo, la de
ICU4J), con CheckBidi, CheckJoiners y reglas STD3. Solo aceptaría `xn--` cuando
la conversión de ida y vuelta sea válida, y exigiría una política de presentación
frente a homógrafos. 4.1-A no añade esa dependencia.

**Limitación funcional asumida.** Se rechazan dominios IDN legítimos, como los que
contienen `ñ` o vocales acentuadas.

## 9. Protocolos y puertos

| Esquema | Puerto efectivo | Puerto explícito admitido | Canonización |
| --- | --- | --- | --- |
| `http` | 80 | `80` | Se omite |
| `https` | 443 | `443` | Se omite |

Producen PORT:

- un puerto vacío (`host:`);
- un puerto con signo o con caracteres no decimales;
- un puerto con ceros a la izquierda (`080`, `0443`, `00`);
- un puerto de más de cinco dígitos o mayor que 65535, comprobado antes de
  convertir a entero;
- cualquier valor distinto del puerto por defecto del esquema, como
  `http://host:443` o `https://host:80`.

**Esquemas.** Se rechaza cualquier esquema distinto de `http` y `https`, en
particular `file`, `ftp`, `gopher`, `data`, `javascript`, `jar`, `ws` y `wss`. Se
aceptan ambos esquemas. La política de cambio de esquema en redirects
(`https` → `http`) corresponde a 4.1-B.

**Motivo.** Los puertos no estándar concentran servicios internos: MariaDB,
paneles de administración, Docker o cachés. Restringir puertos reduce superficie,
pero no sustituye a la política de direcciones: un servicio interno en 80 o 443
sigue dependiendo del catálogo. Ampliar puertos (por ejemplo, 8080/8443 para
entornos de staging de clientes) exige una decisión explícita y una enmienda de
este ADR.

## 10. Contrato DNS

**Interfaz.** `HostResolver` es una interfaz sin implementación productiva en
4.1-A.

- Recibe únicamente un host ya validado, de un tipo que solo puede obtenerse de un
  `DestinationUrl` aceptado. Nunca recibe texto libre, literales IP, nombres de
  una etiqueta ni nombres prohibidos.
- Una sola llamada por evaluación devuelve la respuesta completa para A y AAAA.
- Los tests usan resolvers falsos. 4.1-A no realiza DNS.

**Respuesta.** Para cada familia (A y AAAA), la respuesta contiene:

- un estado:
  - ADDRESSES;
  - NODATA: el nombre existe, pero no tiene registros de esa familia;
  - NXDOMAIN: el nombre no existe;
  - FAILURE: no se obtuvo respuesta (tiempo agotado, SERVFAIL, REFUSED, error de
    red o equivalente);
- la cadena CNAME seguida, en orden, sin el nombre consultado; cada nombre se
  entrega sin el punto raíz final (ver "CNAME");
- los registros de dirección, como bytes crudos.

La respuesta es un dato no confiable, y su construcción no la valida: las
respuestas malformadas previstas deben llegar a la política y rechazarse con un
código.

- Se consideran malformadas previstas las familias, listas o elementos ausentes
  (`null`) y los bytes de longitud incorrecta.
- La copia es profunda: cada `byte[]` se clona y las listas se copian a listas
  inmutables que conservan los nulos. Nunca lanza por contenido malformado
  previsto.
- No se usa `List.copyOf` sobre datos de respuesta: lanza `NullPointerException`
  ante un elemento nulo y convertiría un DNS_INVALID esperado en una excepción
  accidental.
- Un FAILURE no transporta mensajes ni causas; como mucho, una categoría
  enumerada.

**Validación** (reglas 26–29, en este orden):

1. DNS_FAILURE:
   - el resolver lanza cualquier excepción, es interrumpido o informa FAILURE en
     cualquiera de las dos familias;
   - no hay fallback implícito: si AAAA falla, no se continúa solo con A aunque A
     tenga direcciones;
   - ante una interrupción se restaura el estado de interrupción del hilo.
2. DNS_LIMIT:
   - más de 32 registros de dirección, sumando ambas familias antes de deduplicar.
     El recuento incluye todas las entradas presentes, también las nulas o
     malformadas; una lista ausente cuenta como cero y se rechaza en el paso 3;
   - o más de 8 nombres en la cadena CNAME de alguna familia, aunque la cadena
     contenga además un ciclo o un nombre inválido.
3. DNS_INVALID:
   - respuesta o familia ausente (`null`), lista ausente o elemento nulo;
   - registro A que no tiene exactamente 4 bytes, o AAAA que no tiene exactamente
     16;
   - ADDRESSES sin registros, o NODATA o NXDOMAIN con registros;
   - NXDOMAIN en una familia y otro estado en la otra;
   - cadenas CNAME distintas entre familias, comparadas en forma canónica;
   - ciclo CNAME (ver "CNAME");
   - nombre de la cadena que incumple las reglas de host 11–16, incluido el punto
     raíz final (regla 13), o que es un nombre prohibido (reglas 17–18).
4. DNS_EMPTY: ambas familias en NODATA, o ambas en NXDOMAIN, sin ninguna
   dirección.
5. Solo una respuesta que supera los pasos 1–4 se clasifica (reglas 30–31).

**Deduplicación y orden.** Tras validar, las direcciones se deduplican por familia
y bytes exactos. Una IPv4 y su forma IPv4-mapped son direcciones distintas, y la
segunda está prohibida. El orden resultante es el de primera aparición: primero
las IPv4 y después las IPv6.

**Clasificación.** Toda dirección deduplicada se clasifica (sección 11).

- Si ninguna es admisible: ADDRESS_FORBIDDEN.
- Si hay admisibles e inadmisibles: ADDRESS_MIXED. Una dirección inadmisible
  nunca se descarta para seguir con el resto.
- Solo si todas son admisibles se aprueba el destino, con la lista completa.

**CNAME.**

- Se admiten hasta 8 saltos: la cadena de cada familia tiene como máximo 8
  nombres.
- **Forma canónica de un nombre:** mapeo ASCII a minúsculas (`A–Z` → `a–z`), sin
  punto raíz. El contrato entrega los nombres sin punto raíz. Un nombre que lo
  conserve, o que tenga etiquetas vacías, incumple las reglas de host y produce
  DNS_INVALID. El host inicial ya está en forma canónica (sección 5).
- **Ciclo:** el mismo nombre canónico aparece dos veces en la secuencia formada
  por el host inicial seguido de la cadena. El host inicial cuenta:
  `a.example.com` → `b.example.com` → `a.example.com` es un ciclo.
- La comparación de cadenas entre familias y la detección de ciclos usan siempre
  la forma canónica.
- Las direcciones terminales se clasifican siempre. Ningún CDN o proveedor es de
  confianza por su nombre.
- El nombre original del `DestinationUrl` sigue siendo el de la petición: un
  destino CNAME nunca se usa como Host, SNI ni nombre de verificación del
  certificado.

**Corpus DNS (normativo).** Todas las respuestas proceden de resolvers falsos;
no hay consultas reales. P4a y P4b son IPv4 admisibles, y P6 una IPv6 admisible,
según el snapshot congelado.

En las filas de ciclos CNAME, el host es `a.example.com`, A = [P4a], AAAA =
NODATA, y ambas familias llevan la misma cadena. Todos los nombres son
sintácticamente válidos y no prohibidos, de modo que un fallo de la detección de
ciclos no puede quedar oculto por las reglas de host. Cada ciclo y su control
acíclico difieren solo en el nombre repetido.

| Respuesta simulada (A / AAAA) | Resultado |
| --- | --- |
| [P4a] / NODATA | Aprobado [P4a] |
| NODATA / [P6] | Aprobado [P6] |
| [P4a, P4a] / [P6] | Aprobado [P4a, P6] |
| [P4a, P4b] / NODATA, con 8 saltos CNAME iguales en ambas familias | Aprobado [P4a, P4b] |
| 32 registros iguales a P4a | Aprobado [P4a] |
| [10.0.0.1] / NODATA | ADDRESS_FORBIDDEN |
| [169.254.169.254] / [`fd00:ec2::254`] | ADDRESS_FORBIDDEN |
| [P4a, 10.0.0.1] / NODATA, y también en orden inverso | ADDRESS_MIXED |
| [P4a] / [`::ffff:7f00:1`] | ADDRESS_MIXED |
| [P4a] / FAILURE | DNS_FAILURE |
| FAILURE / NODATA | DNS_FAILURE |
| El resolver lanza una excepción | DNS_FAILURE |
| 33 registros en total | DNS_LIMIT |
| 33 registros iguales a P4a | DNS_LIMIT (el límite se aplica antes de deduplicar) |
| 9 saltos CNAME | DNS_LIMIT |
| Registro A de 16 bytes, o AAAA de 4 | DNS_INVALID |
| ADDRESSES sin registros | DNS_INVALID |
| NXDOMAIN / [P6] | DNS_INVALID |
| NXDOMAIN / NODATA | DNS_INVALID |
| Cadena `b.example.com`, `a.example.com` (vuelve al host inicial) | DNS_INVALID (ciclo) |
| Control: cadena `b.example.com`, `c.example.com` | Aprobado [P4a] |
| Cadena `b.example.com`, `c.example.com`, `b.example.com` (ciclo interno) | DNS_INVALID (ciclo) |
| Control: cadena `b.example.com`, `c.example.com`, `d.example.com` | Aprobado [P4a] |
| Cadena `b.example.com`, `A.EXAMPLE.COM` | DNS_INVALID (ciclo en forma canónica) |
| Control: cadena `b.example.com`, `C.EXAMPLE.COM` (nombre válido en mayúsculas, sin repetición) | Aprobado [P4a] |
| Cadena con `b.example.com.` (punto raíz conservado) | DNS_INVALID (punto raíz final; reglas de host 13 y 15) |
| CNAME hacia `db.internal` | DNS_INVALID |
| Cadenas CNAME distintas en A y AAAA | DNS_INVALID |
| El resolver devuelve `null` | DNS_INVALID |
| NODATA / NODATA | DNS_EMPTY |
| NXDOMAIN / NXDOMAIN | DNS_EMPTY |

**Errores simultáneos.** Rige la precedencia DNS_FAILURE, DNS_LIMIT, DNS_INVALID,
DNS_EMPTY y, por último, ADDRESS_FORBIDDEN o ADDRESS_MIXED.

| Situación | Resultado | Motivo |
| --- | --- | --- |
| AAAA en FAILURE y 33 registros en A | DNS_FAILURE | La regla 26 precede a la 27 |
| 33 registros, uno de ellos A de 16 bytes | DNS_LIMIT | La regla 27 precede a la 28 |
| Cadena de 9 nombres que contiene un ciclo | DNS_LIMIT | La regla 27 precede a la 28 |
| Un registro malformado junto a P4a y a `10.0.0.1` | DNS_INVALID | Solo se clasifica una respuesta válida (28 precede a 30–31) |
| Elemento nulo en la lista A junto a P4a | DNS_INVALID | La copia conserva el nulo; no hay excepción accidental |

**Obligaciones del resolver real (4.1-B).**

- Consultar el nombre como FQDN absoluto, sin dominios de búsqueda, sin fichero
  hosts y sin mDNS, LLMNR ni NetBIOS.
- Distinguir NODATA, NXDOMAIN y fallo, y exponer la cadena CNAME seguida.
  `InetAddress.getAllByName` no cumple este contrato: no distingue esos casos, no
  expone CNAME y aplica la configuración del sistema operativo. La elección del
  cliente DNS es una decisión de 4.1-B.
- Entregar los nombres de la cadena sin el punto raíz y sin ninguna otra
  transformación.
- Aplicar límites mientras analiza la respuesta (tamaño, registros y saltos) y
  tiempos máximos, y ser cancelable.
- Una caché, si 4.1-B la adopta, no puede superar el TTL ni compartirse entre
  organizaciones, y su contenido pasa por la política completa en cada
  evaluación.

## 11. Catálogo IPv4/IPv6

### Modelo de dos capas

`AddressPolicy` clasifica bytes mediante prefijos binarios explícitos. No depende
de `InetAddress.isSiteLocalAddress`, `isLoopbackAddress` ni métodos análogos, que
son incompletos: por ejemplo, en IPv6 `isSiteLocalAddress` solo reconoce el
obsoleto `fec0::/10` y no las ULA.

1. **Capa de asignación (lista permitida).** La dirección debe pertenecer a un
   prefijo asignado para unicast global según los registros congelados de IANA:
   - IPv4: bloques /8 con estado ALLOCATED o LEGACY del IPv4 Address Space
     Registry;
   - IPv6: prefijos asignados a RIR en IPv6 Global Unicast Address Assignments.

   Fuera de esta capa, la dirección está prohibida. No se admite todo
   `2000::/3`.
2. **Capa de uso especial (lista prohibida).** Comprende:
   - todos los prefijos de los registros IANA de direcciones de uso especial
     (IPv4 e IPv6), con independencia de sus atributos, incluido "Globally
     Reachable";
   - las reglas de producto de este ADR.
3. **Decisión.** Una dirección es admisible solo si pertenece a la capa 1 y a
   ningún prefijo de la capa 2. Una superposición con la capa 2 prohíbe siempre:
   no hay excepciones permitidas dentro de un prefijo prohibido.

**Alcance de la garantía.** El catálogo es una lista de exclusión dentro de un
perímetro positivo; no certifica destinos.

- Se rechazan las direcciones fuera de los prefijos positivos congelados.
- Dentro de un prefijo positivo ya admitido (un /8 IPv4 o un prefijo IPv6
  asignado a un RIR), el catálogo no detecta automáticamente nuevas asignaciones,
  reasignaciones ni cambios. La capa 2 sigue bloqueando los rangos especiales
  conocidos.
- "Admisible" solo significa "no excluida por el catálogo congelado". El catálogo
  no demuestra asignación a un cliente final, anuncio BGP, alcanzabilidad real ni
  ausencia de rutas internas, ni que el destino sea seguro.

**Fuentes.**

- IANA IPv4 Special-Purpose Address Registry.
- IANA IPv6 Special-Purpose Address Registry.
- IANA IPv4 Address Space Registry.
- IANA Internet Protocol Version 6 Address Space.
- IANA IPv6 Global Unicast Address Assignments.
- IANA Special-Use Domain Names.
- Reglas de producto de este ADR.

### Mínimo obligatorio IPv4

Son mínimos de producto: deben figurar en la capa 2 aunque un registro cambie. Las
referencias son informativas. La congelación registrará la referencia exacta de
cada entrada.

| Prefijo | Uso | Referencia informativa |
| --- | --- | --- |
| `0.0.0.0/8` | "Esta red" (incluye 0.0.0.0) | RFC 1122 |
| `10.0.0.0/8` | Privada | RFC 1918 |
| `100.64.0.0/10` | Espacio compartido (CGNAT); incluye 100.100.100.200, metadatos de Alibaba Cloud | RFC 6598 |
| `127.0.0.0/8` | Loopback | RFC 1122 |
| `169.254.0.0/16` | Enlace local; incluye 169.254.169.254, metadatos de AWS, Azure, GCP y otros | RFC 3927 |
| `172.16.0.0/12` | Privada | RFC 1918 |
| `192.0.0.0/24` | Asignaciones de protocolo IETF | RFC 6890 |
| `192.0.2.0/24` | Documentación (TEST-NET-1) | RFC 5737 |
| `192.168.0.0/16` | Privada | RFC 1918 |
| `198.18.0.0/15` | Pruebas de rendimiento | RFC 2544 |
| `198.51.100.0/24` | Documentación (TEST-NET-2) | RFC 5737 |
| `203.0.113.0/24` | Documentación (TEST-NET-3) | RFC 5737 |
| `224.0.0.0/4` | Multicast | RFC 5771 |
| `240.0.0.0/4` | Reservado | RFC 1112 |
| `255.255.255.255/32` | Broadcast limitado (contenido en `240.0.0.0/4`; se lista aparte) | RFC 919 |
| `168.63.129.16/32` | Regla de producto: IP de plataforma de Azure, pública pero prohibida para el crawler | Documentación de Azure |

### Mínimo obligatorio IPv6

| Prefijo | Uso | Referencia informativa |
| --- | --- | --- |
| `::/128` | No especificada | RFC 4291 |
| `::1/128` | Loopback | RFC 4291 |
| `::/96` | IPv4-compatible, obsoleta (regla de producto) | RFC 4291 |
| `::ffff:0:0/96` | IPv4-mapped | RFC 4291 |
| `64:ff9b::/96` | Prefijo NAT64 bien conocido | RFC 6052 |
| `64:ff9b:1::/48` | Traducción IPv4/IPv6 de uso local | RFC 8215 |
| `100::/64` | Descarte | RFC 6666 |
| `2001::/23` | Asignaciones de protocolo IETF; incluye Teredo (`2001::/32`) | RFC 2928, RFC 4380 |
| `2001:db8::/32` | Documentación | RFC 3849 |
| `2002::/16` | 6to4 | RFC 3056 |
| `3fff::/20` | Documentación | RFC 9637 |
| `fc00::/7` | ULA; incluye `fd00:ec2::254`, metadatos de AWS | RFC 4193 |
| `fe80::/10` | Enlace local | RFC 4291 |
| `fec0::/10` | Site-local, obsoleta | RFC 3879 |
| `ff00::/8` | Multicast | RFC 4291 |

### Entradas de uso especial confirmadas documentalmente

Astra ha confirmado documentalmente estas siete entradas en los registros oficiales
de IANA, y su revisión señala 2025-10-09 como fecha de actualización consultada.
Forman parte de la capa 2.

- IPv4: https://www.iana.org/assignments/iana-ipv4-special-registry
- IPv6: https://www.iana.org/assignments/iana-ipv6-special-registry

| Prefijo | Entrada | Registro |
| --- | --- | --- |
| `192.31.196.0/24` | AS112-v4 | IPv4 Special-Purpose |
| `192.52.193.0/24` | AMT | IPv4 Special-Purpose |
| `192.88.99.0/24` | 6to4 Relay Anycast (entrada deprecada) | IPv4 Special-Purpose |
| `192.175.48.0/24` | Direct Delegation AS112 | IPv4 Special-Purpose |
| `2620:4f:8000::/48` | Direct Delegation AS112 | IPv6 Special-Purpose |
| `5f00::/16` | SRv6 SIDs | IPv6 Special-Purpose |
| `100:0:0:1::/64` | Dummy IPv6 Prefix | IPv6 Special-Purpose |

Una entrada deprecada no se elimina: `192.88.99.0/24` sigue prohibida. Si un
registro posterior retirase alguna de las siete, quitarla del catálogo exigiría
una decisión explícita.

Hay tres niveles distintos, y hoy solo existe el primero:

1. **Confirmación documental por Astra**: existe para estas siete entradas.
2. **Snapshot reproducible** de los registros: todavía no existe.
3. **Congelación criptográfica** con SHA-256 calculados: todavía no existe. Astra
   no la ha realizado, y este ADR no contiene ni inventa ningún hash.

La capa 2 contendrá exactamente lo que diga el snapshot congelado (sección 12),
más los mínimos de producto y estas siete entradas.

### Notas de diseño

- Una dirección no es inválida por terminar en `.0` o `.255`: el broadcast depende
  de la subred. Solo `255.255.255.255/32` se trata como broadcast limitado.
- Las formas IPv6 que incrustan una IPv4 (IPv4-mapped, IPv4-compatible, NAT64,
  6to4, Teredo) se prohíben completas. No se extrae la IPv4 incrustada para
  admitirla.
- **NAT64 y entorno de salida.** Un operador puede traducir IPv6 → IPv4 con
  prefijos propios (RFC 6052) dentro de espacio asignado, y el entorno de salida
  del despliegue puede contener otras traducciones, túneles, rutas o servicios del
  proveedor. Por esas vías, una dirección que parece pública y admisible podría
  acabar en un destino interno. Un catálogo estático no puede identificarlo: **el
  catálogo CIDR no es una protección completa contra NAT64 ni contra las
  traducciones del despliegue**, y este ADR no promete que lo sea. Cerrar ese
  riesgo es un criterio bloqueante para aprobar 4.1-B (sección 17).
- ISATAP y otros mecanismos que incrustan IPv4 en el identificador de interfaz no
  se reconocen por prefijo. Es un riesgo residual del entorno de salida, sujeto al
  mismo criterio bloqueante (sección 17).
- **Dirección pública no es destino seguro.** Ejemplos:
  - las direcciones de salida o del propio despliegue de SmartAudits;
  - redes de clientes con direccionamiento público tras cortafuegos que confían en
    la IP del crawler;
  - IP de plataforma de proveedores, como la de Azure;
  - destinos internos accesibles a través de una IP que parece pública.

  El catálogo solo expresa conocimiento general más las reglas de producto. Una
  lista prohibida por despliegue es una ampliación futura, y solo podría añadir
  prohibiciones. No sustituye al control del entorno de salida (sección 17).

### Nombres prohibidos

Un host está prohibido si es igual a una entrada o es subdominio suyo. La
comparación es por etiquetas completas, no por sufijo de texto: `mylocalhost.com`
no coincide con `localhost`.

| Nombre (y subdominios) | Motivo | Origen |
| --- | --- | --- |
| `localhost` | Loopback | RFC 6761 |
| `local` | mDNS; se resuelve fuera del DNS público | RFC 6762 |
| `test` | Pruebas; sin resolución pública | RFC 6761 |
| `invalid` | Nunca válido | RFC 6761 |
| `onion` | Tor; fuera del DNS | RFC 7686 |
| `alt` | Espacios de nombres no DNS | RFC 9476 |
| `arpa` (todo el TLD, incluido `home.arpa`) | Infraestructura y redes domésticas (RFC 8375) | Regla de producto |
| `internal` | Uso privado; incluye `metadata.google.internal` | Regla de producto (confirmar en la congelación) |
| `home`, `corp`, `mail`, `lan`, `intranet`, `private`, `localdomain` | Convenciones de redes privadas y nombres en colisión | Regla de producto |

**Nombres de documentación.** `example.com`, `example.net` y `example.org` no se
excluyen automáticamente por estar reservados para documentación: RFC 6761 pide
que las aplicaciones no los traten de forma especial. Hay tres conceptos
distintos, y ninguno implica los otros:

- no figurar entre los nombres prohibidos del catálogo;
- cumplir el perfil de URL;
- tener un DNS que resuelva.

Estos nombres siguen sujetos a todas las demás reglas: al menos dos etiquetas,
longitudes, sintaxis ASCII, respuesta DNS completa y clasificación de todas las
direcciones.

- No se garantiza que resuelvan ni que el destino se apruebe.
- No reciben ninguna excepción de seguridad.
- `https://example/` sigue rechazado por tener una sola etiqueta (regla 17).
- No existe ningún bypass para tests: los tests usan resolvers falsos, no
  excepciones de la política.

En la congelación, cada entrada del registro Special-Use Domain Names se
clasificará como prohibida o no prohibida, con justificación. Una entrada sin
clasificar queda prohibida.

## 12. Versionado y mantenimiento

**Identidad del catálogo.** Un único catálogo inmutable contiene los prefijos IPv4,
los prefijos IPv6 y los nombres prohibidos, con estos datos:

- una revisión: un identificador legible, con fecha de congelación y secuencia;
- por cada fuente: nombre del registro, URL oficial, fecha de consulta, fecha de
  última actualización publicada y SHA-256 del fichero descargado;
- por cada entrada: prefijo o nombre, categoría, fuente o regla de producto y
  justificación;
- el hash SHA-256 de una serialización canónica (entradas ordenadas y formato
  textual fijo), comprobado por los tests.

Toda decisión, aprobada o rechazada, registra la revisión del catálogo.

**Representación.** El catálogo serán constantes Java en una clase final del
paquete, y solo después de un snapshot aprobado: sin E/S, sin análisis en el
arranque y sin un recurso que pueda alterarse en runtime. Los tests recalculan el
hash de la serialización canónica y lo comparan con el valor congelado.

**Procedimiento de congelación.** Es obligatorio antes de implementar las
constantes Java. Se ejecutará durante la implementación de 4.1-A, previa
autorización y fuera de los tests. Al redactar este ADR no se ha ejecutado ningún
paso ni se ha descargado nada.

1. Seleccionar las fuentes y los registros oficiales (sección 11).
2. Obtener las versiones concretas de cada registro, en su formato publicado.
3. Registrar la fecha de obtención y la revisión o fecha de última actualización
   de cada fuente.
4. Definir una serialización determinista del snapshot y del catálogo: campos,
   separadores y representación canónica de prefijos y nombres.
5. Fijar la codificación de caracteres: UTF-8 sin BOM.
6. Fijar los finales de línea (LF) y el orden de las filas. Se propone ordenar por
   familia, red binaria y longitud de prefijo, y los nombres en orden ASCII.
7. Calcular el SHA-256 real de cada fuente obtenida y del snapshot serializado.
8. Revisar de forma independiente la transcripción frente a las fuentes
   obtenidas. La revisión incluye:
   - la clasificación de cada nombre de uso especial como prohibido o no
     prohibido, con justificación (sin clasificar, prohibido);
   - la presencia de los mínimos, de las siete entradas confirmadas y de las
     reglas de producto.
9. Convertir el catálogo revisado en constantes Java.
10. Crear los tests de integridad (hash, redes canónicas, sin duplicados) y de
    límites de prefijos.

Las fuentes obtenidas se conservan como evidencia fuera del build y no se leen en
runtime. El hash del catálogo se registra en el código y en una nota de este ADR.

**Mantenimiento.**

- **Revisión mensual.** Los registros se comparan con la revisión vigente, y
  cualquier cambio produce una revisión nueva por el flujo normal de revisión.
- **Revisión obligatoria antes de cada release**, aunque no haya cambios.
- **Sin actualización automática.** El catálogo es una constante del código:
  - no se descarga ni se recarga en runtime;
  - no cambia durante una evaluación ni durante la vida del proceso.
- Los tests nunca consultan DNS ni IANA.
- Lo que queda fuera de los prefijos positivos congelados falla de forma
  conservadora (capa 1). Dentro de ellos rige el alcance limitado descrito en la
  sección 11.

**Riesgo de envejecimiento.** Un catálogo estático necesita mantenimiento. Entre
revisiones:

- una dirección fuera de los prefijos positivos congelados se rechaza (fallo
  cerrado);
- un cambio dentro de un prefijo positivo ya admitido no se detecta;
- un nuevo uso especial podría no estar prohibido todavía.

La revisión mensual y la revisión antes de cada release acotan ese riesgo, pero no
lo eliminan.

**Datos no inventados.** Este ADR no contiene un snapshot de los registros ni
ningún SHA-256. Las tablas de la sección 11 son mínimos de producto con referencias
informativas, más las siete entradas confirmadas documentalmente. El snapshot
completo y su congelación criptográfica solo existirán tras el procedimiento
anterior.

## 13. Modelo interno

Paquete: `com.smartaudits.v2.acquisition.destination`. Java 17 puro: sin Spring,
JPA, Jackson ni dependencias nuevas, y sin funcionalidades preview. Se usan
interfaces selladas y `instanceof` con patrones (estables en Java 17), no `switch`
con patrones.

| Tipo | Naturaleza | Responsabilidad e invariantes |
| --- | --- | --- |
| `DestinationUrl` | Clase final inmutable | Destino canónico: esquema, host ASCII en minúsculas, puerto efectivo, ruta no vacía, query ausente o presente, sin fragmento. Solo la crea su analizador. `equals`/`hashCode` por forma canónica; `toString` redactado |
| `UrlParseResult` (o equivalente) | Interfaz sellada: analizada o rechazada | Resultado del analizador, sin excepciones para entradas inválidas |
| `ValidatedHost` (o equivalente) | Clase final | Host que recibe el resolver; sin constructor público, solo se obtiene de un `DestinationUrl` |
| `HostResolver` | Interfaz | Consulta A + AAAA; sin implementación productiva en 4.1-A |
| `DnsAnswer` (o equivalente) | Inmutable, dato no confiable | Estados, cadenas CNAME y bytes por familia, con copias defensivas profundas que conservan los nulos; representa las respuestas malformadas previstas sin lanzar (sección 10) |
| `IpAddress` | Clase final inmutable | Familia y bytes (exactamente 4 o 16); copia al construir y al devolver; sin `InetAddress` ni resolución inversa; `toString` sin bytes |
| `AddressCatalog` (o equivalente) | Constante final | Prefijos, nombres, revisión y hash; entradas canónicas |
| `AddressPolicy` | Clase final sin estado mutable | Clasificación por prefijos binarios: admisible o prohibida, con categoría |
| `DestinationPolicy` | Clase final | Orquesta análisis, una sola consulta DNS por evaluación y clasificación; sin cachés |
| `DestinationDecision` | Interfaz sellada: aprobada o rechazada | Resultado final; no existe `boolean allowed` |
| `RejectionCode` | Enum | Los dieciocho códigos de la sección 14 |

**Construcción de la aprobación.**

- Ni el tipo aprobado ni ninguna otra clase ofrecen un constructor público o una
  factoría pública que devuelva una aprobación. Solo `DestinationPolicy`, en el
  mismo paquete, puede crearla.
- La aprobación no es un `record`: un `record` público siempre tiene un
  constructor canónico al menos tan accesible como la clase.
- No es `Serializable` y no lleva anotaciones de persistencia ni de JSON.
- La visibilidad de paquete es una salvaguarda arquitectónica, no un sandbox
  (reflexión, paquetes partidos). Los tests verifican que no existe ninguna vía
  pública de construcción.

**Lo que liga la aprobación.** Liga, de forma coherente:

- el `DestinationUrl`;
- el host ASCII, el esquema y el puerto efectivo;
- la lista completa de direcciones aprobadas: todas las de la respuesta,
  deduplicadas y en el orden de la sección 10;
- la revisión del catálogo.

**Alcance de la aprobación.**

- La aprobación documenta el resultado de una evaluación. Por sí sola no autoriza
  ninguna conexión, y no tiene una caducidad temporal implementada: no lleva marca
  de tiempo ni TTL.
- En 4.1-B, cada intento de adquisición obtendrá una evaluación nueva dentro del
  propio intento, y solo la usará el transporte interno controlado.
- No se serializa, persiste, cachea ni comparte entre intentos, Scans u
  organizaciones. El transporte no acepta aprobaciones externas ni reutilizadas.
- Toda conexión nueva, reintento, fallback o redirect exige una evaluación nueva.

**Inmutabilidad.**

- No se exponen arrays mutables: los `byte[]` se clonan al entrar y al salir.
- Los datos ya validados, como la lista aprobada, se copian con `List.copyOf`.
  `DnsAnswer` no usa `List.copyOf` sobre datos de respuesta: copia en profundidad
  a listas inmutables que conservan los nulos (sección 10).
- Todos los campos son finales, sin setters ni estado estático mutable, y los
  tipos son seguros entre hilos.

**API de la política.** `DestinationPolicy` evalúa tanto texto como un
`DestinationUrl` ya analizado; esto último se usará para reevaluar redirects en
4.1-B. Un rechazo sintáctico nunca invoca el resolver.

## 14. Resultados y códigos

| Código | Etapa | Significado |
| --- | --- | --- |
| URL_FORMAT | Sintaxis | No es absoluta ni jerárquica, no tiene autoridad, es relativa o discrepa de `java.net.URI` |
| URL_TOO_LONG | Sintaxis | Más de 2048 caracteres en la entrada o en la forma canónica |
| URL_CHARACTERS | Sintaxis | Controles, espacio, barra invertida, no ASCII fuera de la autoridad, ASCII no permitido en un componente, escapes de control o `%5C` |
| SCHEME | Sintaxis | Esquema distinto de `http` o `https` |
| USERINFO | Sintaxis | `@` en la autoridad |
| HOST_FORMAT | Sintaxis | Host vacío o malformado |
| HOST_FORBIDDEN | Sintaxis | Host de una sola etiqueta o nombre prohibido |
| PORT | Sintaxis | Puerto no admitido |
| IP_LITERAL | Sintaxis | Literal IP en cualquier forma |
| IDN_UNSUPPORTED | Sintaxis | No ASCII en la autoridad, `xn--` o etiqueta R-LDH |
| PERCENT_ENCODING | Sintaxis | `%` en la autoridad o escape mal formado |
| DOT_SEGMENT | Sintaxis | Segmento `.` o `..`, incluidas las formas `%2e` |
| DNS_FAILURE | DNS | No hay respuesta completa: fallo, excepción o interrupción del resolver en alguna familia |
| DNS_EMPTY | DNS | Respuesta completa sin direcciones |
| DNS_INVALID | DNS | Respuesta estructuralmente malformada o contradictoria |
| DNS_LIMIT | DNS | Más de 32 registros o más de 8 saltos CNAME |
| ADDRESS_FORBIDDEN | Direcciones | Ninguna dirección admisible |
| ADDRESS_MIXED | Direcciones | Mezcla de direcciones admisibles e inadmisibles |

**Reglas.**

- Precedencia única: la de la sección 5.
- Una dirección inadmisible nunca se elimina en silencio.
- Los errores esperados son resultados tipados. Las únicas excepciones son las de
  contrato, como un argumento `null`.
- Los códigos son identificadores estables. Renombrarlos o reinterpretarlos exige
  modificar este ADR.

**Relación con `scan_failures`.** La correspondencia entre estos códigos y los de
`scan_failures` se decidirá en 4.2-A. ADR-0001 §8 cita como ejemplos DNS, TIMEOUT,
TLS, SSRF e INTERRUPTED, que son categorías más gruesas, y 4.1-A no crea esa tabla
ni fija la correspondencia. En el diseño previsto, los rechazos sintácticos
ocurrirían al validar la entrada, antes de crear una Audit. Los de DNS y
direcciones ocurrirían al ejecutar un Scan. Ambas cosas son decisiones de 4.1-C y
4.2-A.

## 15. Protección de diagnósticos

- **Rechazos.** Un rechazo expone solo el código y la revisión del catálogo. Nunca
  la entrada, el host, la ruta, la query, la userinfo, las direcciones ni mensajes
  del resolver.
- **`toString` redactado en todos los tipos:**
  - `DestinationUrl`: esquema y puerto efectivo;
  - `IpAddress`: familia;
  - aprobación: esquema, puerto, número de direcciones y revisión;
  - rechazo: código y revisión.

  Los valores reales solo se obtienen con accesores explícitos, que usará 4.1-B.
- **Errores de contrato.** Las excepciones de contrato (`null`) llevan mensajes
  constantes, sin datos.
- **Excepciones del resolver.** No se propagan ni su mensaje ni su causa, ni se
  encadenan. El resultado es DNS_FAILURE.
- **Logs.** 4.1-A no emite logs. Las capas futuras que registren decisiones
  anotarán solo el código, la revisión y sus propios identificadores (por ejemplo,
  el id de Scan). La URL completa, la query, las credenciales y las direcciones
  privadas quedan fuera de los logs normales. Cualquier detalle adicional exige una
  decisión propia, como el detalle saneado de `scan_failures` en 4.2-A.
- **Tests.** Se introducen secretos falsos en la query, la userinfo, el fragmento,
  la ruta y los mensajes de excepción del resolver, y se comprueba que no aparecen
  en ningún `toString` ni en ningún mensaje de excepción.

## 16. Matriz de amenazas

Ninguna amenaza que dependa del transporte se considera resuelta por 4.1-A.

| Amenaza | Control 4.1-A | Test futuro | Riesgo residual | Subfase |
| --- | --- | --- | --- | --- |
| SSRF hacia servicios internos | Perfil de URL, DNS completo y catálogo; rechazo sin conexión | Corpus sintáctico y DNS | Sin pinning real no hay protección efectiva | 4.1-A diseño; 4.1-B aplicación |
| IPv4/IPv6 privadas | Prefijos binarios, capas de asignación y de uso especial | Límites CIDR y vecinos | Catálogo desactualizado; cambios dentro de prefijos positivos no detectados | 4.1-A |
| Metadatos cloud | Enlace local, ULA, CGNAT, `168.63.129.16/32` y nombres `internal` | Casos explícitos por proveedor | Endpoints públicos no catalogados, propios del despliegue | 4.1-A; lista de despliegue futura |
| DNS mixto | ADDRESS_MIXED; nunca se filtra | Mezclas en ambos órdenes y familias | — | 4.1-A |
| DNS incompleto | A + AAAA obligatorios; DNS_FAILURE sin fallback | Una familia en FAILURE | Rechazo de hosts con un AAAA roto (disponibilidad) | 4.1-A |
| DNS rebinding | La aprobación liga las direcciones y no se reutiliza entre intentos | Respuestas cambiantes entre evaluaciones | 4.1-A no conecta; abierto hasta el pinning | 4.1-B |
| Redirects maliciosos | Ninguno en 4.1-A; cada salto será un `DestinationUrl` nuevo | — | Abierto | 4.1-B |
| NAT64 y traducciones de salida | `64:ff9b::/96` y `64:ff9b:1::/48` prohibidos; el catálogo no es protección completa | Límites de ambos prefijos | Prefijos propios, traducciones y túneles del despliegue | 4.1-B: criterio bloqueante del entorno de salida |
| IPv4-mapped | `::ffff:0:0/96` y `::/96` prohibidos completos; literales rechazados | AAAA con dirección mapped | — | 4.1-A |
| Host malformado | HOST_FORMAT con precedencia determinista | Corpus de host | — | 4.1-A |
| Punycode | `xn--` y R-LDH rechazados | Corpus IDN | Rechazo de dominios IDN legítimos | 4.1-A; IDN futuro |
| Unicode | No ASCII rechazado; sin NFKC ni `java.net.IDN` | Homógrafos, puntos Unicode, invisibles | — | 4.1-A |
| Puertos peligrosos | Solo 80 y 443 por defecto | Corpus de puertos | Servicios internos en 80/443 dependen del catálogo | 4.1-A |
| URLs ambiguas | Partición mínima, contraste con `URI`, sin barra invertida ni controles, `%` en la autoridad prohibido, segmentos punto rechazados | Corpus de diferenciales | Diferencias con el cliente HTTP futuro | 4.1-A y 4.1-B |
| Mutabilidad del resultado | Tipos inmutables; aprobación sin construcción pública | Reflexión y copias defensivas | Reflexión deliberada o paquete partido | 4.1-A |
| Fuga de información | Códigos sin datos, `toString` redactado, sin logs | Secretos falsos | Logs de capas futuras | 4.1-A y 4.1-B |
| Agotamiento de recursos | Límites 2048/253/63/32/8 y validación lineal | Límites exactos | Tiempos y tamaños de red | 4.1-B y 4.4 |

## 17. Obligaciones de 4.1-B

Ninguna se implementa en 4.1-A. Son condiciones para aprobar 4.1-B, que necesitará
una decisión propia.

**Entorno de salida (criterio bloqueante).** El catálogo CIDR de 4.1-A no protege
frente a lo que el despliegue haga con una dirección aparentemente pública. Antes
de aprobar 4.1-B:

- Se identificarán y documentarán las condiciones reales de salida de red del
  despliegue:
  - NAT64 con prefijos propios;
  - traducciones de red;
  - túneles;
  - rutas de salida;
  - servicios especiales del proveedor cloud;
  - destinos internos accesibles a través de una IP que parece pública.
- El fetcher deberá impedir las traducciones o túneles no controlados en su vía de
  salida, o bien aplicar restricciones de salida verificadas que impidan alcanzar
  destinos internos.
- Si no puede garantizarse una vía de salida segura, esa vía de adquisición no se
  habilita; por ejemplo, la salida IPv6 cuando exista un NAT64 no controlado.
- Sin esa evidencia, 4.1-B no se aprueba. El criterio es bloqueante e
  independiente del catálogo y de la prueba de pinning.

**Conexión.**

- Conectar exclusivamente a una dirección de la lista aprobada de la evaluación
  vigente (IP fijada). Ni el cliente HTTP, ni la librería TLS ni el sistema pueden
  hacer resoluciones adicionales ocultas.
- El destino efectivo del cliente debe coincidir con el aprobado:
  - la dirección remota de la conexión está en la lista aprobada;
  - el puerto es el puerto efectivo y el esquema es el aprobado;
  - el hostname de Host, SNI y certificado es el del `DestinationUrl`.

  Cualquier discrepancia aborta la adquisición.
- La petición conserva la ruta y la query crudas de la forma canónica. El cliente
  no las decodifica, recodifica ni normaliza.
- El transporte obtiene la evaluación dentro de cada intento. No acepta
  aprobaciones externas, recibidas de un llamante o reutilizadas.
- Conservar el hostname original para la cabecera Host, el SNI y la verificación
  del certificado.
- Validar TLS por completo contra el hostname: almacén de confianza estándar y
  verificación de nombre activada, sin trust-all ni excepciones por destino.
- No heredar proxies sin control: se ignoran las propiedades del sistema, las
  variables de entorno y el `ProxySelector` por defecto. Un proxy de salida exige
  una decisión explícita, y en ese caso la política debe aplicarse donde se
  resuelve el nombre.

**Redirects, reintentos y conexiones.**

- El cliente no sigue redirects automáticamente. Cada `Location` se resuelve
  contra la URL actual, se convierte en `DestinationUrl` y pasa por la política
  completa (DNS y direcciones) antes de abrir una conexión nueva.
- 4.1-B fija el número máximo de saltos y la política de cambio de esquema.
- Los reintentos, las conexiones nuevas y cualquier fallback (a otra dirección o a
  otra familia) reevalúan la política, y solo conectan a una dirección de esa
  evaluación nueva.
- Una conexión solo se reutiliza dentro de la misma evaluación. Ningún pool
  comparte conexiones entre evaluaciones, Scans u organizaciones de forma que
  eluda la política.

**Límites y cancelación.**

- Tiempos máximos de DNS, conexión, handshake TLS, lectura y total por
  adquisición.
- Límites de número y tamaño de cabeceras, de bytes recibidos y de bytes
  descomprimidos (bombas de compresión).
- Concurrencia global y por host, y cancelación cooperativa vinculada al Scan.

**Transacciones.** Ninguna operación de red se ejecuta dentro de una transacción ni
mientras se mantiene el bloqueo de una Audit (frontera de V8, ADR-0001 §8).

**Cliente HTTP.** Todavía no se elige. Debe permitir conectar a una IP fijada con
Host, SNI y certificado del hostname, sin resolución propia, sin proxy heredado,
con límites aplicables y cancelable.

En Java 17, `java.net.http.HttpClient` no ofrece un mecanismo por petición para
fijar la dirección y trata `Host` como cabecera restringida. La SPI de resolución
(JEP 418) llega en Java 18 y es global. Habrá que demostrar la idoneidad de ese
cliente o de cualquier alternativa.

**Prueba real de pinning, obligatoria antes de aprobar 4.1-B.** Debe demostrar
que:

- la conexión va a la IP aprobada;
- Host y SNI llevan el hostname;
- no hay una segunda resolución (resolver espía);
- un cambio de la respuesta DNS entre la evaluación y la conexión no altera el
  destino.

Como la política prohíbe loopback, la prueba necesitará un punto de inyección
exclusivo de test, imposible de activar en producción. Su diseño forma parte de
4.1-B.

**Fallos.** Los rechazos de la política y los fallos de transporte se traducirán a
`scan_failures` en 4.2-A, con detalle saneado.

## 18. Compatibilidad Fase 3

**Lo que no se modifica.** Organization, OrganizationMembership, Site, Audit, Scan,
`AuditScanService`, `AnalizadorLegal`, `EntradaAnalisis`, los endpoints legacy, el
frontend y las migraciones V1–V8.

**Lo que no ocurre.**

- La URL legacy (`urlOpcional`) nunca se visita automáticamente, y `sites.base_url`
  sigue siendo literal (ADR-0001 §7).
- No hay validación retroactiva: `DestinationUrl` no se aplica a Sites, Audits ni
  auditorías legacy existentes.
- No se generan Audits ni Scans.
- V8 no se toca. Las transiciones del worker (start, succeed, fail) y
  `scan_failures` siguen pendientes para fases posteriores de F4 (ADR-0001 §8).

El cambio es puramente aditivo: un paquete nuevo y sus tests, cuando se autorice.

**Coherencia con ADR-0001.**

- §5: ningún concepto diferido se adelanta.
- §6: la política no conoce la tenancy. El llamante autoriza (Membership OWNER
  ACTIVE) antes de evaluar, y la política no recibe ni devuelve entidades.
- §7: cubre la parte de DNS y SSRF de F4.1 sin fijar la identidad de Site. Exponer
  Site sigue bloqueado hasta 4.1-C. El guard de una sola `CREATE TABLE` por
  migración condicionará cómo persistir esa identidad; aquí no se decide.
- §12: las futuras columnas de URL tendrán semántica binaria.
- §17: no hay endpoints públicos 2.0.
- §19: no se adopta ninguna alternativa rechazada allí.

## 19. Plan de tests

**Principios.**

- JUnit 5 con tests parametrizados.
- Cada caso de rechazo afirma el código exacto. Ninguna excepción cuenta como PASS.
- Las expectativas se escriben literalmente en los tests. Nunca se derivan del
  catálogo productivo ni del código bajo prueba.

**Corpus sintáctico.**

- Todas las filas de las secciones 5 y 6, literalmente.
- Idempotencia de cada forma aceptada.
- Conservación byte a byte de la ruta y la query.
- Precedencia en las violaciones múltiples.
- Un resolver espía comprueba que ningún rechazo sintáctico lo invoca.
- Sin excepción para nombres de documentación: `https://example/` se rechaza, y
  `example.com` solo se aprueba cuando un resolver falso devuelve direcciones
  admisibles.

**Límites.**

- URL de 2048 aceptada y de 2049 rechazada; además, una entrada de 2048 cuya forma
  canónica tiene 2049 por la ruta vacía.
- Host de 253 aceptado y de 254 rechazado; etiqueta de 63 aceptada y de 64
  rechazada.
- 32 registros aceptados y 33 rechazados, también si los 33 son duplicados.
- 8 saltos CNAME aceptados y 9 rechazados.
- Puertos 65535, 65536 y de seis o más dígitos.

**Catálogo y CIDR.**

- Para cada prefijo obligatorio: primera y última dirección prohibidas, y la
  dirección inmediatamente anterior y la siguiente, con expectativa explícita
  según el snapshot congelado.
- Se tienen en cuenta superposiciones y adyacencias:
  - `2001::/32` dentro de `2001::/23`;
  - `255.255.255.255` dentro de `240.0.0.0/4`;
  - `169.254.169.254` dentro de `169.254.0.0/16`;
  - `fe80::/10` seguido de `fec0::/10`.
- Vecinos ilustrativos:
  - IPv4: `9.255.255.255` y `11.0.0.0`; `100.63.255.255` y `100.128.0.0`;
    `172.15.255.255` y `172.32.0.0`; `198.17.255.255` y `198.20.0.0`;
    `168.63.129.15` y `168.63.129.17`; `223.255.255.255` y `224.0.0.0`;
  - IPv6: el final de `2001::/23` y `2001:200::`.
- Validez del catálogo: redes canónicas (bits de host a cero), longitudes de
  prefijo en rango, sin duplicados, orden estable y hash congelado.
- Presencia de los mínimos, de las siete entradas confirmadas (incluida la
  deprecada `192.88.99.0/24`) y de las reglas de producto, con la primera y la
  última dirección de cada una.
- Una IPv4-mapped no se desenvuelve.
- Los tests de integridad (hash y forma del catálogo) y los de clasificación son
  conjuntos distintos, y sus resultados se informan por separado (ver
  "Mutación").

**DNS.**

- Las filas del corpus DNS y de los errores simultáneos.
- Respuestas pública, privada, mixta, vacía, malformada, incompleta, contradictoria
  y excesiva, con duplicados, CNAME demasiado largo y CNAME hacia nombre inválido o
  prohibido.
- Ciclos CNAME con nombres completos válidos (sección 10): cada ciclo, tanto el que
  vuelve al host inicial como el interno, se prueba junto a su control acíclico.
  El par solo difiere en el nombre repetido, y el ciclo debe dar DNS_INVALID por
  la detección de ciclos, no por una regla de host. Se incluye el ciclo que solo
  aparece tras la forma canónica (mayúsculas), junto a su control acíclico en
  mayúsculas, que se aprueba.
- Respuestas cambiantes: un resolver con estado devuelve respuestas distintas en
  llamadas sucesivas. Cada decisión refleja solo su única consulta, y el resolver
  se invoca exactamente una vez por evaluación.
- Un resolver que lanza una excepción, que devuelve `null` o que es interrumpido;
  en este último caso, el estado de interrupción queda restaurado.

**Inmutabilidad.**

- Modificar el `byte[]` de entrada o el devuelto no altera `IpAddress`.
- Las listas son inmutables, y `DnsAnswer` copia sus entradas en profundidad:
  modificar las listas o los `byte[]` de entrada después de construirla no la
  altera.
- Una `DnsAnswer` con una familia, una lista o un elemento nulo se construye sin
  excepción, y la política devuelve DNS_INVALID.
- Por reflexión: la aprobación no tiene constructores públicos, no hay factorías
  públicas que la devuelvan, no es `record` ni `Serializable`, y todos los campos
  son finales.

**Diagnósticos.** Los secretos falsos de la sección 15 no aparecen en ningún
`toString` ni en ningún mensaje de excepción.

**Ausencia de red.**

- Una prueba estática recorre las fuentes del paquete y rechaza las APIs
  prohibidas de la sección 5, además de `Socket`, `HttpClient`, `URLConnection` y
  las búsquedas de nombres de `InetAddress`.
- Todas las direcciones se construyen desde bytes, y todos los resolvers son
  falsos.
- No se usa `SecurityManager`, obsoleto para eliminación desde Java 17 (JEP 411).

### Mutación

**Objetivo de mutación.** Cada instancia de la tabla es un objetivo: una regresión
prevista que la suite debe detectar. Un mutante es un intento concreto de
representar ese objetivo en el código.

**Clasificación de cada intento.**

- **KILLED:** el mutante compila, se ejecuta y al menos una aserción de test
  detecta la regresión.
- **SURVIVED:** el mutante compila, se ejecuta y ningún test detecta la regresión.
- **EQUIVALENT:** el mutante no produce ningún cambio de comportamiento observable
  relevante. Exige una justificación semántica independiente; por ejemplo, que la
  precedencia o la capa 1 hagan inalcanzable la rama mutada.
- **NOT COMPILED:** el intento no compila y, por tanto, nunca llega a ejecutarse.
  - Es un intento inválido como evidencia funcional; se registra su causa.
  - No cuenta como KILLED ni como EQUIVALENT.
  - No satisface por sí solo el objetivo seleccionado.

**Tratamiento de NOT COMPILED.**

- Cuando una mutación necesaria no compila, se reformula hasta obtener una
  variante compilable y ejecutable que represente la misma regresión prevista.
- El objetivo no se da por superado porque el primer intento no compilara.
- Si no se consigue ninguna variante compilable, el objetivo queda PENDIENTE: el
  gate de mutación no se declara PASS y el bloqueo se informa para revisión
  técnica.
- La equivalencia se demuestra por ausencia de cambio observable. Nunca se infiere
  de un fallo de compilación.

**Criterio de aceptación.**

- Cada objetivo seleccionado y no equivalente tiene un mutante compilable,
  ejecutado y KILLED.
- Cero SURVIVED.
- Cero objetivos obligatorios sin evidencia ejecutada: ninguno queda PENDIENTE ni
  cubierto solo por intentos NOT COMPILED.
- Los EQUIVALENT justificados se registran y quedan fuera del denominador de
  detección funcional. No se exige matarlos ni sustituirlos por otro mutante
  sobre la misma regla, que podría no existir.
- Los intentos NOT COMPILED se registran por separado, con su causa, y no entran
  en ningún cómputo de éxito.
- Se registra qué test mata cada mutante, en una copia limpia compilada con
  javac 17.

**Regla 24.** Si se demuestra que el contraste con `java.net.URI` es redundante por
las reglas 1–23, su mutante se acepta como EQUIVALENT con esa demostración. No se
inventa un caso de prueba para matarlo.

**Detección funcional frente a integridad.** Alterar el catálogo también cambia su
hash, y el test de integridad lo detectaría. Esa detección prueba que el catálogo
cambió, no que la regresión sea observable. Se informan por separado:

- **detección funcional:** falla un test de clasificación, de corpus o de límites;
- **detección por integridad:** solo falla el test de hash o de forma del catálogo.

Los mutantes de catálogo exigen detección funcional. La de integridad se informa
aparte y no cuenta como KILLED funcional.

**Mutantes de catálogo equivalentes por diseño.** Eliminar o reducir un prefijo de
la capa 2 que la capa 1 ya excluye es EQUIVALENT por diseño (denegación
redundante). Es el caso de `fc00::/7`, que queda fuera de las asignaciones a RIR.
Por eso la fila 9 altera un prefijo de la capa 2 contenido en un prefijo positivo
de la capa 1, elegido tras la congelación. Por ejemplo, `172.16.0.0/12` → `/16`,
si el /8 que lo contiene es positivo en el snapshot.

**Instancias.** La tabla tiene 18 filas, pero la fila 6 requiere cinco mutaciones
distintas (2048, 253, 63, 32 y 8). Si cada una de las demás filas genera una sola
mutación, el plan equivale como mínimo a 22 instancias individuales. Es el número
de objetivos, no un número de KILLED a alcanzar: el número de KILLED exigido es el
de objetivos menos los EQUIVALENT justificados.

| # | Mutante | Instancias | Detección exigida |
| --- | --- | --- | --- |
| 1 | Aceptar una IP privada: la lógica de decisión trata como admisible una dirección de un prefijo privado de la capa 2 | 1 | Funcional: clasificación y corpus DNS |
| 2 | Validar solo la primera IP | 1 | Funcional: DNS mixto |
| 3 | Ignorar AAAA | 1 | Funcional: mezcla con AAAA prohibida |
| 4 | Descartar una IP prohibida en lugar de rechazar el conjunto | 1 | Funcional: ADDRESS_MIXED |
| 5 | Aceptar una lista DNS vacía | 1 | Funcional: ADDRESSES sin registros y DNS_EMPTY |
| 6 | Eliminar un límite: 2048, 253, 63, 32 u 8 | 5 | Funcional: límites exactos, uno por límite |
| 7 | Aceptar userinfo | 1 | Funcional: corpus USERINFO |
| 8 | Aceptar un literal IP | 1 | Funcional: corpus IP_LITERAL |
| 9 | Alterar un CIDR de la capa 2 contenido en un prefijo positivo | 1 | Funcional: vecinos y límites; el hash se informa aparte |
| 10 | Exponer arrays mutables | 1 | Funcional: inmutabilidad |
| 11 | Permitir un host de una etiqueta | 1 | Funcional: corpus HOST_FORBIDDEN |
| 12 | Fallback a A cuando AAAA falla | 1 | Funcional: DNS_FAILURE |
| 13 | Contar el límite de 32 después de deduplicar | 1 | Funcional: 33 registros duplicados |
| 14 | Decodificar escapes al canonizar | 1 | Funcional: corpus de canonización |
| 15 | Omitir el contraste con `java.net.URI` (regla 24) | 1 | Funcional si existe una entrada alcanzable; si no, EQUIVALENT demostrado |
| 16 | Aceptar el puerto por defecto del otro esquema (`http:443`) | 1 | Funcional: corpus PORT |
| 17 | Invocar el resolver ante un rechazo sintáctico | 1 | Funcional: resolver espía |
| 18 | Aceptar un punto final en el host (quitar la regla 13) | 1 | Funcional: corpus HOST_FORMAT. Si la regla 15 (etiqueta vacía) rechaza el mismo caso con el mismo código, EQUIVALENT justificado |

Nada de esto se ha ejecutado: es el plan para la implementación futura.

**Validación futura (no se ejecuta ahora).**

- Copia limpia compilada con javac 17: la compilación incremental del IDE escribe
  en `target`, y la evidencia debe venir de una copia limpia.
- Suite Maven completa, sin dependencias nuevas.
- Job backend de GitHub CI en verde.
- Revisión de Astra sobre la implementación.

## 20. Criterios de aceptación

La implementación futura de 4.1-A solo se acepta si cumple todos estos criterios:

1. Solo hay archivos nuevos en el paquete y en sus tests: ningún cambio en código,
   migraciones, `pom.xml`, CI ni frontend de Fase 3, y ninguna dependencia nueva.
2. Los corpus normativos (URL, canonización y DNS) están implementados
   literalmente, con códigos exactos y precedencia verificada.
3. El catálogo está congelado con el procedimiento de la sección 12: fuentes,
   versiones y fechas registradas, serialización determinista y SHA-256 reales.
   Contiene los mínimos, las siete entradas confirmadas y las reglas de producto, y
   su transcripción tiene revisión independiente.
4. El contrato DNS completo está probado con resolvers falsos, y ningún rechazo
   sintáctico invoca el resolver.
5. La inmutabilidad y la ausencia de construcción pública de la aprobación están
   verificadas.
6. Ningún diagnóstico contiene datos sensibles.
7. No hay DNS ni sockets, y existe la prueba estática de APIs prohibidas.
8. La mutación cumple la sección 19, en una copia limpia javac 17:
   - cada objetivo seleccionado y no equivalente tiene un mutante compilable,
     ejecutado y KILLED;
   - cero SURVIVED y cero objetivos obligatorios PENDIENTES o sin evidencia
     ejecutada;
   - los EQUIVALENT, con justificación semántica independiente, quedan fuera del
     denominador funcional;
   - los intentos NOT COMPILED se registran por separado con su causa; nunca
     satisfacen un objetivo ni demuestran equivalencia;
   - la detección funcional se informa aparte de la detección por integridad del
     catálogo.
9. La suite Maven completa y el job backend de GitHub CI están en verde.
10. Astra aprueba la implementación.
11. No hay endpoint, bean, persistencia, migración ni acceso a red.

## 21. Alternativas rechazadas

| Alternativa rechazada | Motivo |
| --- | --- |
| Validar solo con `java.net.URI` o `java.net.URL` | Diferencias entre analizadores; `getHost()` nulo en autoridades de registro; `URL.equals` resuelve DNS |
| Clasificar con `InetAddress.isSiteLocalAddress` y similares | Cobertura incompleta (CGNAT, documentación, NAT64, 6to4, ULA) |
| Analizar literales con `InetAddress.getByName` | Puede resolver DNS y acepta formas heterodoxas |
| Admitir ya los literales IP públicos | Superficie de diferenciales y certificados por IP; queda como ampliación futura |
| IDN con `java.net.IDN` | Implementa IDNA2003, no IDNA2008 ni UTS #46, y convierte en silencio |
| Normalizar con `URLDecoder`/`URLEncoder` o `URI.normalize()` | Cambian la semántica de `+`, de los escapes y de los segmentos |
| Eliminar tracking, fusionar `www` o unir http y https | Son decisiones de identidad de Site o Page (4.1-C, 4.5), no de destino |
| Lista prohibida IPv6 con todo `2000::/3` admitido | Admitiría espacio no asignado o futuros usos especiales; se adopta la capa de asignación |
| Filtrar las direcciones prohibidas y seguir con el resto | Oculta ataques de DNS mixto y hace el resultado dependiente del orden |
| Validar solo la primera dirección o solo A | Rebinding y destinos IPv6 internos |
| Fallback a una familia ante un fallo | Trata una respuesta incompleta como completa |
| Resultado `boolean allowed` | Pierde el motivo, no liga destino, direcciones y revisión, y facilita la reutilización |
| Resolver al crear el Site y reutilizar la respuesta | TOCTOU y rebinding; la decisión no es permanente |
| Admitir cualquier puerto, o 8080/8443 ya | Superficie de servicios internos; requiere decisión explícita |
| Persistir decisiones o crear `scan_failures` ya | Fuera de 4.1-A; `scan_failures` llega en 4.2-A |
| Dependencias (ICU4J, Guava, commons-validator, librerías anti-SSRF) | Innecesarias en 4.1-A y nueva superficie de cadena de suministro |
| DNS o registros IANA reales en tests | Tests no deterministas y con red |
| Snapshot "exhaustivo" escrito a mano en este ADR | Serían datos no verificados atribuidos a IANA; se adopta una congelación verificable |
| Delegar la protección en el cliente HTTP | No controla pinning, redirects ni proxies; la política es independiente del cliente |

## 22. Limitaciones y riesgos

**No es protección completa.** DNS rebinding, redirects, proxies y reutilización de
conexiones siguen abiertos hasta 4.1-B. Mientras 4.1-B no exista nada conecta, de
modo que el riesgo no se materializa. Ninguna decisión de 4.1-A debe autorizar una
conexión hasta entonces.

**Riesgos técnicos.**

- El catálogo estático envejece y no detecta cambios dentro de los prefijos
  positivos (secciones 11 y 12).
- El catálogo no demuestra asignación a un cliente final, anuncio BGP,
  alcanzabilidad ni ausencia de rutas internas.
- El catálogo no identifica los prefijos NAT64 propios, las traducciones, los
  túneles, ISATAP ni las direcciones públicas internas o del propio despliegue.
  Cerrar ese riesgo es un criterio bloqueante de 4.1-B (sección 17).
- La visibilidad de paquete no resiste la reflexión deliberada.
- La política depende del comportamiento de `java.net.URI` de Java 17. Una
  actualización de JDK exige volver a ejecutar los corpus.

**Restricciones funcionales asumidas.** Se rechazan:

- los dominios IDN;
- los puertos no estándar y los sitios servidos por IP literal;
- los hosts con guion bajo;
- `[`, `]` y otros ASCII sin escapar en la query;
- los hosts con más de 32 direcciones;
- los hosts cuya consulta AAAA o A falla aunque la otra funcione.

**Otros riesgos.**

- Si se delegara un TLD de la lista de producto, como `mail`, sus dominios se
  rechazarían hasta la siguiente revisión.
- Los escapes de bytes no ASCII, incluidos los controles Unicode escapados, se
  conservan opacos. Las capas de presentación no deben decodificarlos sin
  sanearlos.
- Los códigos todavía no son un contrato de API pública.

**Estado de las decisiones propuestas.**

Aprobadas, o mantenidas por indicación expresa, en las revisiones de Astra. No se
reabren sin una contradicción confirmada. El cambio de estado no modifica ninguna
regla: cada decisión conserva el significado descrito en su sección.

- El perímetro general: 4.1-A sin red, perfil ASCII restrictivo, IDN diferido,
  rechazo de literales IP, solo HTTP 80 y HTTPS 443, catálogo positivo y negativo
  con denegación dominante.
- Host en mayúsculas canonizado con mapeo ASCII.
- Partición mínima de URL y contraste defensivo con `URI` (regla 24).
- Prohibición de `arpa` como regla de producto, y comparación por etiquetas de los
  nombres especiales.
- Destinos CNAME sometidos al perfil de host.
- IPv4 antes que IPv6 en la lista aprobada.
- Catálogo como constantes Java, solo tras un snapshot aprobado.
- Diagnósticos redactados.
- Precedencia DNS: DNS_FAILURE, DNS_LIMIT, DNS_INVALID, DNS_EMPTY y, después,
  ADDRESS_FORBIDDEN o ADDRESS_MIXED.
- Regla de producto `..;` en segmentos punto (sección 5, regla 23).
- Rechazo de etiquetas R-LDH (sección 5, regla 16).
- Rechazo de los ASCII no admitidos por RFC 3986 cuando no van escapados, incluidos
  `[` y `]` (secciones 4 y 5, regla 20).
- Precedencia de NXDOMAIN: en ambas familias es DNS_EMPTY, y junto a otro estado es
  DNS_INVALID (sección 10).
- `toString` redactado sin hostname (sección 15).
- Nombres reservados de producto: `home`, `corp`, `mail`, `lan`, `intranet`,
  `private`, `localdomain` e `internal`, además de `arpa` (sección 11).
- Nombres de documentación: `example.com`, `example.net` y `example.org` no se
  excluyen automáticamente, pero siguen sujetos a todas las reglas, sin excepción
  de seguridad ni garantía de resolución, y `https://example/` se rechaza por
  tener una sola etiqueta (sección 11).
- Tratamiento de los mutantes equivalentes, incluidos la regla 24 y las filas 1, 9
  y 18, que se aceptan como EQUIVALENT si se demuestra su redundancia (sección 19).

Corregido tras la segunda revisión y pendiente de aprobación:

- Tratamiento de NOT COMPILED: no satisface ningún objetivo ni demuestra
  equivalencia, y un objetivo sin variante compilable queda PENDIENTE y bloquea el
  gate de mutación (sección 19).

No queda abierta ninguna otra decisión propuesta en este ADR.

## 23. Ampliaciones futuras

Cada una exige una decisión explícita y una enmienda de este ADR o un ADR nuevo:

- literales IP públicos en forma canónica, sometidos a la misma `AddressPolicy`;
- IDN con UTS #46 no transicional y política de homógrafos;
- puertos adicionales;
- lista prohibida por despliegue, que solo añade prohibiciones (direcciones de
  salida, prefijos NAT64 propios y redes del proveedor). Complementa el control
  bloqueante del entorno de salida de 4.1-B (sección 17); nunca lo sustituye;
- descubrimiento del prefijo NAT64 (RFC 7050), como ayuda de diagnóstico si el
  despliegue usa solo IPv6. No habilita por sí mismo ninguna vía de salida;
- escapado de caracteres por 4.3/4.5 antes de construir un `DestinationUrl` a
  partir de enlaces descubiertos;
- política de redirects y de cambio de esquema (4.1-B);
- identidad de Site (4.1-C) y de Page (4.2-B y 4.5);
- correspondencia de códigos con `scan_failures` (4.2-A).

La implementación deberá respetar esta decisión: política pura sin red, perfil de
URL estricto, precedencia determinista, catálogo versionado de dos capas,
aprobación inmutable sin construcción pública y ninguna capacidad de adquisición
antes de 4.1-B. Ninguna vía de salida se habilitará sin el control bloqueante del
entorno de salida. Fase 3 permanece intacta.
