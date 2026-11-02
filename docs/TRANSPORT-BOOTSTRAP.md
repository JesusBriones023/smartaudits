# Transporte preparado, activación bloqueada

`CapturaUrlConfig` registra únicamente `TransporteHttpNoDisponible` como transporte.
`app.acquisition.url.real-enabled=true` sigue impidiendo el arranque, incluso con DNS válido.
No hay nuevos endpoints ni ruta de activación desde una petición.

La configuración de operador se valida al arrancar, también con el transporte deshabilitado:

| Propiedad | Valor y restricciones |
| --- | --- |
| `app.acquisition.url.dns.server-ip` | Sin valor por defecto. IPv4 decimal de cuatro octetos sin ceros iniciales, o IPv6 hexadecimal completa/comprimida. |
| `app.acquisition.url.dns.port` | `53` por defecto; rango 1–65535. |
| `app.acquisition.url.dns.timeout` | `2s` por defecto; rango 1ms–10s por intercambio DNS. |

Se rechazan nombres DNS, URLs, espacios, CIDR, puertos dentro de la dirección,
corchetes, zonas IPv6, colas IPv4 decimales o direcciones IPv4 mapeadas, direcciones no especificadas,
multicast y broadcast limitado. Las propiedades DNS desconocidas también fallan.
Para IPv6, una lista ASCII de caracteres y la presencia de `:` restringen el parser
del JDK a su ruta numérica, sin resolver nombres. IPv4 se convierte directamente a bytes.

El servidor puede ser una IP privada o loopback de infraestructura elegida por el operador;
esto no cambia la política SSRF de los destinos web. No se selecciona automáticamente
un DNS público, el del sistema ni un fallback. Sin `server-ip` la aplicación deshabilitada
arranca, pero preparar la composición falla. Un valor presente pero vacío es inválido.

`PreparacionTransporte` es una factoría interna de paquete, sin anotaciones Spring,
sin bean y sin llamantes de producción. Compone `ResolvedorDnsDirecto`,
`ConectorDirecto`, `ClienteHttpFijado` con TLS estándar y `LimitesHttp.porDefecto()`,
y `TransporteHttpReal` con reloj UTC. El constructor existente del transporte crea
la única `DestinationPolicy`; no se añade otra. Preparar no consulta DNS ni abre conexiones.
Una activación futura exige un cambio de ensamblaje expresamente autorizado.

Antes de autorizar ese cambio siguen pendientes:

- **D1/D2:** comprobar en el entorno autorizado el DNS explícito (UDP y fallback TCP),
  ausencia de resoluciones alternativas y conexión exclusiva a la IP aprobada tras cada redirección.
- **D9:** comprobar Host, SNI, identidad/cadena TLS y almacén de confianza efectivo en ese entorno.
- **R-EGR, bloqueante:** verificar rutas, traducciones/NAT64, túneles, proxies/intercepción
  y servicios internos del proveedor para impedir alcanzar destinos internos mediante IP pública.

Estas comprobaciones físicas no se ejecutan ni se dan por superadas en este gate.

## PV-13: frontera de adquisición y persistencia

`IngestaUrlService.capturar`, `analizar` y `auditar` rechazan al entrar cualquier
transacción activa (también read-only), sincronización transaccional o recurso
JPA/JDBC vinculado al hilo. No suspenden transacciones. `auditar` ya no es
`@Transactional`: valida la URL, hace una precomprobación de cuota sin bloqueo,
comprueba otra vez la ausencia de recursos y adquiere la captura. Solo después de
validar respuesta, procedencia real y texto llama al proxy de
`AuditoriaService.crearAuditoriaAnalizada`.

La transacción de persistencia conserva la comprobación de cuota autoritativa con
bloqueo del usuario. La precomprobación no reserva cuota; una carrera puede gastar
una captura, pero no saltarse el límite final. ADMIN conserva su exención. En un
contexto sin transacción, sincronización ni recursos vinculados, la consulta
declarada `countByUsuarioIdAndFechaCreacionGreaterThanEqual` no inicia una
transacción Spring: no tiene metadata `@Transactional` en la configuración
Spring Data JPA 3.2.1 inspeccionada. No debe confundirse con `count()` heredado,
que sí tiene configuración read-only. Una sonda sobre el proxy real confirmó
atributo transaccional nulo y ausencia de transacción durante la consulta
declarada. El EntityManager temporal se cierra al terminar. Eso no demuestra por
sí solo liberación física de JDBC: los tests H2/Hikari comprueban además cero
conexiones prestadas antes de adquirir, sin certificar el despliegue productivo.
Un contexto externo/OSIV se rechaza; no se reutiliza para la precomprobación.
Para CRAWLER, un fallo al guardar CREACION se propaga y deshace
auditoría, incidencias e historial. Los caminos manual/HTML/conjunto conservan su
comportamiento anterior.

OSIV sigue sin configuración global explícita y Spring Boot lo habilita por
defecto en el contexto web. PV-13 rechaza ese contexto incluso si su EntityManager
todavía no tiene conexión; no lo desvincula, limpia ni cierra por cuenta del
llamante. Antes de una futura integración web/worker debe diseñarse una frontera
que no conserve recursos durante la adquisición. La simulación OSIV y la
liberación del pool en tests no validan el despliegue real ni recursos no
vinculados que pudiera retener un llamante arbitrario.

**Bloqueos adicionales antes de activar:** el constructor público del record
`ProcedenciaCaptura` permite fabricar internamente `HTTP_REAL`; la factoría de
paquete no es una frontera de seguridad suficiente. PV-13 no cambia esos
constructores. Tampoco añade plazo absoluto para toda la adquisición (DNS y
redirecciones), concurrencia global/por host ni cancelación ligada a Scan.
La integración con OSIV, esa procedencia y D1/D2/D9/R-EGR siguen pendientes;
R-EGR continúa bloqueante. No se añaden endpoints ni migraciones y Fase 4 no se
declara completa.

Diseño y validación: [PV-13](PV_13_ACQUISITION_TRANSACTION_BOUNDARY.md).

**Copia E2E anterior:** `smartaudits-frontend/.e2e-runtime/pv13-source-validation/`
contiene una junction `node_modules` hacia las dependencias originales. No ejecutar
borrado recursivo sobre esa copia ni borrar el `node_modules` original. Su limpieza
queda fuera de PV-13 y requiere aprobación separada; la validación final no la
recorre, copia ni limpia.
