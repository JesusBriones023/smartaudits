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
