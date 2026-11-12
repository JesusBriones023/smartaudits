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
y `TransporteHttpReal` con reloj UTC, plazo de adquisición de 20 s y `System::nanoTime`.
Desde PV-15 el transporte no guarda una `DestinationPolicy`: cada captura construye la suya
sobre ese resolvedor (misma política y catálogo, reevaluación en cada salto). Preparar no
consulta DNS ni abre conexiones.
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

## PV-14: admisión síncrona de capturas

`CapturaUrlConfig` registra un único `ControlConcurrenciaCaptura` por instancia de
aplicación, separado del transporte. El helper común de `IngestaUrlService` lo usa
para `capturar`, `analizar` y `auditar`, sin doble admisión. La propiedad estricta
`app.acquisition.url.execution.max-concurrent` vale `4` por defecto y acepta de `1`
a `32`; valores inválidos y opciones desconocidas del namespace impiden arrancar.
No hay valor ilimitado, cola de espera, nuevo worker ni flag de bypass.

La saturación devuelve `CAPACIDAD_AGOTADA` antes de invocar el transporte. El mismo
hilo llamante ejecuta captura y validación de respuesta y libera su permiso en
`finally` solo cuando termina esa operación, también ante fallos. Una interrupción
observada produce `CANCELADA` sin limpiar el flag ni ocultar un fallo primario.
Cancelar un Future no devuelve capacidad si su delegado sigue ejecutándose.

La capacidad se libera antes de extracción, análisis y persistencia. Se conservan
las guardas PV-13, la cuota autoritativa y el rollback obligatorio del historial
CRAWLER; ADMIN tampoco elude la admisión. No se cambia el ciclo de Scan.

El límite no cubre todo el ciclo de auditoría ni múltiples instancias desplegadas.
No añade plazo total DNS/redirects, cancelación forzada o de sockets, límite por
host, equidad por tenant, integración de workers/Scan ni activación real. D1/D2/D9
siguen OPEN y R-EGR BLOCKED, junto con los requisitos previos ya documentados.
El único transporte productivo sigue siendo `TransporteHttpNoDisponible` y activar
`real-enabled=true` sigue impidiendo el arranque.

Contrato, pruebas y validación: [PV-14](PV_14_CAPTURE_ADMISSION.md).

## PV-15: plazo total de la adquisición preparada

`TransporteHttpReal.capturar` crea al empezar un único plazo monotónico
(`PlazoCaptura`, sobre `System::nanoTime`) de `LimitesAdquisicion.total()`: 20 s por
defecto en código, máximo 60 s, sin propiedad nueva. Lo comparten las familias A y
AAAA, el fallback TCP, cada conexión, el handshake TLS, la escritura y las lecturas
de todos los saltos de redirección; nunca se reinicia ni se amplía. Cada espera
bloqueante usa el menor entre su límite propio y el tiempo restante; un tiempo de
socket nunca vale 0. La lectura del prefijo de longitud DNS sobre TCP recalcula ahora
el tiempo antes de cada byte.

Cada captura construye su propia `DestinationPolicy` sobre un `ResolvedorConPlazo`;
el paquete `destination`, la política, el catálogo y la reevaluación por salto no
cambian. Tras aprobar, tras un `DNS_FAILURE` y antes de devolver la captura se
comprueba primero la interrupción (`CANCELADA`, sin limpiar el flag) y luego el plazo
(`PLAZO_AGOTADO`, motivo añadido al final). Los rechazos de seguridad (direcciones,
`DNS_LIMIT`/`DNS_INVALID`, redirección no válida, peer o identidad TLS) no se
sustituyen por un resultado de tiempo.

El vigilante HTTP sigue siendo un único hilo daemon, ahora con eliminación inmediata
de alarmas canceladas; la conexión queda protegida por `finally` antes de armarlo y
la alarma se cancela solo tras intentar cerrar TLS y el socket crudo (el cierre crudo se
intenta aunque falle el de TLS), de modo que un `close_notify` bloqueado también termina en el
plazo de la conexión. Un `IOException` del cierre TLS (`close_notify`) se ignora, como antes.
Uno del cierre del socket crudo no se ignora: puede producirse antes de liberar nada. Cuenta el
cierre del propietario y también el del vigilante si llegó antes de la entrega del cierre crudo
del propietario. Ese es el corte del aviso; no es el final de toda la limpieza, que no es un paso
atómico. Con un fallo principal queda suprimido en él. Si el intercambio había terminado bien, la
llamada falla como `TRANSPORTE` («No se pudo cerrar la conexión con el servidor») y no devuelve
respuesta, aunque el socket ya estuviera liberado. Un fallo no comprobado al cerrar tampoco se
pierde (suprimido, o lanzado tal cual tras un intercambio correcto). Un fallo ordinario de
limpieza nunca sustituye al fallo principal. La única excepción es un `Error` de limpieza sobre
un fallo principal ordinario: se lanza el `Error`, que nunca se convierte, aunque ya forme parte
del principal (por ejemplo, como su causa), y el principal se conserva como suprimido salvo que
eso cerrase un ciclo. Los fallos se recogen antes de enlazarse, cada instancia una sola vez (por
identidad). Primero se decide qué se propaga; después cada uno de los demás fallos se adjunta por
separado, y solo se omite el que ya es alcanzable o el que cerraría un ciclo. Así, un fallo que no
puede adjuntarse no arrastra a los demás, y ningún enlace de supresión cierra un ciclo. No se
reintenta ningún cierre. En HTTPS, JSSE cierra el socket crudo dentro del
cierre TLS e ignora un `IOException` de ese cierre interno: ese caso no es observable, y no se
afirma que el sistema operativo haya liberado la conexión. Un `SSLPeerUnverifiedException` o una
causa `CertificateException` siguen siendo fallo de identidad TLS aunque el plazo haya vencido.
Cualquier otro `SSLException` se informa como fallo de comunicación o de protocolo TLS, sin
afirmar un problema de identidad. Su ciclo de vida Spring queda para la futura activación
autorizada.

No es tiempo real estricto: el redondeo a 1 ms no acota el retraso real (planificación,
granularidad del temporizador, pausas de la JVM). No detiene resolutores sin plazo (solo
comprobación posterior), la pequeña escritura TCP de la consulta DNS, CPU acotada por tamaño ni
sockets ante una interrupción. No cubre extracción, análisis, persistencia ni la petición completa, ni
implementa cancelación de Scan. `CapturaUrlConfig`, `IngestaUrlService`, PV-13 y PV-14
no cambian. D1/D2/D9 siguen OPEN y R-EGR BLOCKED; un plazo no sustituye el aislamiento
de salida.

Contrato, pruebas y validación: [PV-15](PV_15_ACQUISITION_DEADLINE.md).

**Copia E2E anterior:** `smartaudits-frontend/.e2e-runtime/pv13-source-validation/`
contiene una junction `node_modules` hacia las dependencias originales. No ejecutar
borrado recursivo sobre esa copia ni borrar el `node_modules` original. Su limpieza
queda fuera de PV-13 y requiere aprobación separada; la validación final no la
recorre, copia ni limpia.
