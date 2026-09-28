# Migraciones de base de datos — Fase 0.8

Fecha: 11 de septiembre de 2026.

## 1. Objetivo

La fase 0.8 sustituye la evolución automática del esquema mediante
`spring.jpa.hibernate.ddl-auto=update` por migraciones versionadas con Flyway.

Objetivos:

- disponer de un esquema reproducible;
- impedir que Hibernate modifique tablas automáticamente;
- permitir instalaciones nuevas desde cero;
- adoptar instalaciones históricas sin recrearlas;
- conservar datos existentes;
- validar el esquema mediante Hibernate;
- documentar recuperación y rollback operativo;
- preparar las futuras migraciones V2, V3, etc.

La base de datos habitual no debe recrearse ni vaciarse como parte de esta adopción.

---

## 2. Stack validado

Configuración probada:

- Java 17;
- Spring Boot 3.2.1;
- Hibernate ORM 6.4.1;
- MariaDB 11.2;
- Flyway 11.8.0;
- `flyway-core`;
- `flyway-mysql`.

Spring Boot 3.2.1 gestiona originalmente una versión anterior de Flyway.
SmartAudits fija explícitamente Flyway 11.8.0 porque la versión gestionada
originalmente no reconocía MariaDB 11.2 durante la validación real.

La combinación anterior ha sido validada mediante arranques reales contra
MariaDB 11.2 aislada.

No configurar `spring.flyway.license-key` con esta combinación sin realizar
antes una nueva prueba de compatibilidad.

---

## 3. Autoridad del esquema

A partir de la adopción de Flyway:

1. Los archivos `V*.sql` de `src/main/resources/db/migration` son la autoridad
   para evolucionar el esquema.
2. Hibernate se utiliza con `ddl-auto=validate`.
3. Hibernate no debe utilizarse con `update`, `create` o `create-drop` sobre
   bases de datos de aplicación.
4. Los cambios posteriores al baseline deben escribirse como V2, V3, etc.
5. `V1__initial_schema.sql` queda congelado una vez adoptado.

Los tests H2 que requieran `create-drop` mantienen Flyway desactivado y no
representan una estrategia de evolución de una base real.

---

## 4. V1 — esquema inicial

Archivo:

`smartaudits-backend/src/main/resources/db/migration/V1__initial_schema.sql`

V1 define las ocho tablas de dominio actuales:

- `usuarios`;
- `roles`;
- `usuarios_roles`;
- `auditorias`;
- `resultados`;
- `incidencias`;
- `historial_auditorias`;
- `historial_acciones_admin`.

No contiene:

- `DROP`;
- `TRUNCATE`;
- `ALTER`;
- `UPDATE`;
- `DELETE`;
- inserciones de datos de negocio;
- `IF NOT EXISTS`.

Una instalación nueva ejecuta V1.

Una instalación histórica compatible NO ejecuta V1: se incorpora mediante
baseline en versión 1.

---

## 5. Equivalencia con el esquema histórico

Antes de autorizar la adopción se obtuvo un dump estructural de la instancia
histórica:

`%LOCALAPPDATA%\SmartAuditsBackups\phase08-schema-before.sql`

El dump se obtuvo sin datos.

Se comparó exhaustivamente contra `V1__initial_schema.sql`.

Resultado:

- 8 tablas coincidentes;
- 55 columnas coincidentes;
- tipos y longitudes coincidentes;
- nullability coincidente;
- defaults coincidentes;
- `AUTO_INCREMENT` de columnas coincidente;
- ENUM y orden de valores coincidentes;
- motor InnoDB coincidente;
- charset `utf8mb4` coincidente;
- collation `utf8mb4_general_ci` coincidente;
- 8 PK coincidentes;
- 3 UNIQUE coincidentes;
- 8 índices secundarios coincidentes;
- 9 FK coincidentes;
- nombres de constraints coincidentes;
- acciones referenciales coincidentes;
- ningún trigger, vista, procedimiento, función o columna adicional detectado.

Las diferencias observadas fueron exclusivamente propias de un dump:

- contadores actuales `AUTO_INCREMENT=...`;
- formato `bigint(20)` frente a `BIGINT`;
- orden de creación;
- comentarios;
- sentencias auxiliares de exportación;
- `DROP TABLE IF EXISTS` utilizados por el dump para restauración.

Estas diferencias no forman parte del esquema lógico que debe reproducir V1.

Conclusión:

**V1 reproduce el esquema histórico y debe mantenerse congelado.**

---

## 6. Configuración segura por defecto

Configuración base:

```properties
spring.datasource.url=${DB_URL}

spring.jpa.hibernate.ddl-auto=${SPRING_JPA_HIBERNATE_DDL_AUTO:validate}

spring.flyway.enabled=${FLYWAY_ENABLED:false}
spring.flyway.locations=classpath:db/migration
spring.flyway.baseline-on-migrate=${FLYWAY_BASELINE_ON_MIGRATE:false}
spring.flyway.baseline-version=1
spring.flyway.baseline-description=Legacy SmartAudits schema
spring.flyway.validate-on-migrate=true
spring.flyway.clean-disabled=true
```

Principios:

- el destino de base de datos debe proporcionarse explícitamente;
- Hibernate valida, pero no evoluciona el esquema;
- Flyway permanece desactivado si no se habilita explícitamente;
- `baseline-on-migrate` permanece desactivado por defecto;
- `clean` está desactivado;
- nunca almacenar `baseline-on-migrate=true` como configuración habitual.

---

## 7. DataInitializer

El inicializador dispone de dos controles diferentes:

`APP_DATA_INITIALIZER_ENABLED`

Controla si `DataInitializer` puede ejecutar cualquier inicialización.

`APP_ADMIN_BOOTSTRAP_ENABLED`

Controla específicamente el bootstrap del administrador.

Durante una adopción histórica:

```text
APP_DATA_INITIALIZER_ENABLED=false
APP_ADMIN_BOOTSTRAP_ENABLED=false
```

De esta forma, la única escritura prevista antes del arranque normal es la
creación de `flyway_schema_history` y su entrada BASELINE.

---

## 8. Instalación NUEVA

Una base vacía no puede arrancar únicamente con Hibernate `validate`.

Procedimiento conceptual:

```text
BD vacía
    ↓
FLYWAY_ENABLED=true
FLYWAY_BASELINE_ON_MIGRATE=false
ddl-auto=validate
    ↓
Flyway crea flyway_schema_history
    ↓
ejecuta V1
    ↓
Hibernate validate
    ↓
aplicación
```

Una instalación nueva NO debe usar baseline.

Después de V1, las futuras versiones se aplican mediante V2, V3, etc.

---

## 9. Instalación HISTÓRICA

Una instalación que ya contenga el esquema equivalente a V1 debe ser adoptada,
no recreada.

Requisitos previos:

- identificar inequívocamente la base de datos;
- disponer de backup actual;
- comprobar que el backup es restaurable en aislamiento;
- comprobar la equivalencia del esquema con V1;
- utilizar el artefacto exacto que contiene V1;
- detener escrituras de aplicación durante la operación;
- mantener Hibernate en `validate`;
- desactivar `DataInitializer`;
- desactivar bootstrap administrativo;
- no ejecutar V1 directamente;
- no utilizar `ddl-auto=update`.

El script local dispone del modo explícito:

```powershell
powershell -NoProfile -File .\scripts\Start-LocalBackend.ps1 -AdoptLegacySchema
```

Ese modo fuerza temporalmente:

```text
FLYWAY_ENABLED=true
FLYWAY_BASELINE_ON_MIGRATE=true
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
APP_DATA_INITIALIZER_ENABLED=false
APP_ADMIN_BOOTSTRAP_ENABLED=false
```

Resultado esperado:

```text
Schema history table ... does not exist
Creating Schema History table ... with baseline
Successfully baselined schema with version: 1
Current version: 1
Schema is up to date. No migration necessary.
```

NO debe aparecer:

```text
Migrating schema ... to version "1 - initial schema"
```

sobre una instalación histórica.

Tras una adopción correcta, `baseline-on-migrate` vuelve a `false`.

---

## 10. Operación normal después de la adopción

El baseline es una acción única.

Una vez exista `flyway_schema_history`, los arranques normales deberán utilizar:

```text
FLYWAY_ENABLED=true
FLYWAY_BASELINE_ON_MIGRATE=false
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
```

De esta forma:

- una BD en V1 no vuelve a ejecutar V1;
- una futura V2 se aplicará una sola vez;
- Flyway comprobará checksums e historial;
- Hibernate comprobará posteriormente que el esquema resultante es compatible.

`baseline-on-migrate=true` no debe utilizarse como modo normal de ejecución.

---

## 11. Validaciones realizadas en aislamiento

### 11.1. Instalación nueva

MariaDB temporal:

- contenedor: `sa08_flyway_empty`;
- puerto temporal: `13368`;
- base temporal: `sa08_validation`.

Resultado:

- MariaDB 11.2 reconocida por Flyway;
- V1 validada;
- `flyway_schema_history` creada;
- V1 ejecutada correctamente;
- 8 tablas de dominio creadas;
- 9 tablas totales incluyendo historial Flyway;
- Hibernate `validate` correcto;
- backend arrancado en puerto temporal `18088`;
- endpoint protegido sin JWT devolvió HTTP 401.

### 11.2. Instalación histórica

MariaDB temporal:

- contenedor: `sa08_flyway_legacy`;
- puerto temporal: `13369`;
- base temporal: `sa08_legacy_validation`.

Se importó el dump estructural histórico y se añadieron datos centinela.

Antes de Flyway:

- 8 tablas;
- 1 usuario centinela;
- 2 roles centinela;
- 1 relación usuario/rol;
- `token_version=7`;
- `row_version=3`.

Resultado de adopción:

- Flyway creó `flyway_schema_history`;
- registró versión 1;
- tipo `BASELINE`;
- `success=1`;
- NO ejecutó V1;
- quedaron 9 tablas;
- usuario centinela intacto;
- `token_version=7`;
- `row_version=3`;
- recuentos de datos intactos;
- Hibernate `validate` correcto;
- backend arrancado en puerto temporal `18089`;
- endpoint protegido sin JWT devolvió HTTP 401.

Estas pruebas utilizaron exclusivamente recursos temporales.

---

## 12. Pruebas automatizadas existentes

Tras introducir Flyway:

```text
Tests run: 65
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Los tests H2 que utilizan esquemas creados por Hibernate tienen Flyway
explícitamente desactivado.

Estas pruebas no sustituyen las validaciones MariaDB de migraciones.

La validación reproducible específica de Flyway está implementada en:

`scripts/Validate-FlywayPhase08.ps1`

Se ejecuta desde la raíz del repositorio:

```powershell
powershell -NoProfile -File .\scripts\Validate-FlywayPhase08.ps1
```

El validador utiliza exclusivamente recursos MariaDB temporales y aislados y
comprueba:

- instalación nueva mediante ejecución de V1;
- inventario exacto de tablas;
- historial Flyway esperado;
- HTTP 401 sobre un endpoint protegido sin autenticación;
- rechazo de un esquema histórico no vacío si se intenta arrancar sin baseline;
- código de salida no cero y diagnóstico específico de Flyway en ese rechazo;
- adopción histórica mediante BASELINE versión 1;
- conservación exacta y sensible a mayúsculas/minúsculas del usuario centinela;
- conservación exacta de su `fecha_registro`, `token_version` y `row_version`;
- conservación exacta y sensible a mayúsculas/minúsculas de los roles centinela;
- conservación de la relación usuario/rol centinela;
- conservación de los recuentos esperados de usuarios, roles y relaciones;
- segundo arranque histórico con baseline desactivado;
- identidad SHA-256 del snapshot histórico aprobado;
- limpieza de todos los procesos Java temporales;
- limpieza de todos los contenedores creados por la propia ejecución;
- limpieza de todos los volúmenes MariaDB temporales creados por la propia ejecución;
- verificación de ownership antes de eliminar contenedores o volúmenes;
- verificación posterior de que los contenedores y volúmenes eliminados ya no existen.

Una ejecución normal satisfactoria solo muestra:

`PHASE 0.8 FLYWAY VALIDATION PASSED`

después de completar y verificar correctamente tanto la validación funcional como
la limpieza de todos los recursos temporales.

Si se utiliza explícitamente `-KeepContainers`, la validación conserva los
contenedores y volúmenes propios de esa ejecución y utiliza un resultado distinto:

`PHASE 0.8 FLYWAY VALIDATION COMPLETED - CLEANUP SKIPPED BY REQUEST`

Por tanto, una ejecución con `-KeepContainers` no certifica limpieza completa y
no utiliza el marcador global `PASSED`.

### 12.1. Garantías operativas de la Fase 0.8

El arranque local endurecido y la adopción histórica aplican las siguientes
restricciones:

- Hibernate opera obligatoriamente con `ddl-auto=validate`.
- Flyway y Hibernate utilizan explícitamente el mismo datasource.
- Flyway permanece desactivado por defecto.
- `baseline-on-migrate` permanece desactivado por defecto.
- La adopción histórica solo se permite mediante
  `Start-LocalBackend.ps1 -AdoptLegacySchema`.
- La adopción histórica está restringida a un artefacto que contenga
  exclusivamente `V1__initial_schema.sql`.
- El modo de adopción utiliza `spring.flyway.target=1`, por lo que no puede
  ejecutar una futura V2+ durante la operación extraordinaria de baseline.
- `V1__initial_schema.sql` queda congelada. Los futuros cambios se implementan
  mediante V2, V3, etc.
- El lanzador rechaza variables `SPRING_*` heredadas que puedan alterar la
  configuración controlada.
- También rechaza `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS`.
- La configuración Spring del lanzamiento controlado queda limitada al
  `application.properties` empaquetado en el artefacto.
- `DB_ROOT_PASSWORD` nunca se propaga al backend.
- Durante adopción y validación se desactivan `DataInitializer` y el bootstrap
  administrativo.
- Los backends temporales escuchan exclusivamente en `127.0.0.1`.
- Los recursos de validación utilizan nombres e identificadores únicos por
  ejecución.
- Cada contenedor se registra para limpieza por su nombre único antes de intentar
  crearlo.
- La creación y el arranque de los contenedores se realizan como operaciones
  separadas mediante `docker create` y `docker start`, de forma que un fallo
  posterior a la creación siga dejando el recurso identificado para limpieza.
- Cada MariaDB temporal utiliza un volumen nombrado propio, identificado y
  etiquetado para esa ejecución; no se depende de volúmenes anónimos.
- Los volúmenes se registran antes de su creación para que puedan localizarse y
  limpiarse incluso si una operación posterior falla.
- El validador solo elimina contenedores o volúmenes cuya pertenencia a su propia
  ejecución puede verificar mediante labels de ownership.
- La limpieza se realiza en orden: procesos Java, contenedores y posteriormente
  volúmenes.
- Tras eliminar un contenedor o volumen se verifica explícitamente que ya no
  exista.
- Si un intento de limpieza falla, se continúan intentando limpiar los demás
  recursos y la validación completa se considera fallida.
- El mensaje global `PHASE 0.8 FLYWAY VALIDATION PASSED` no puede emitirse antes
  de completar y verificar la limpieza.
- `-KeepContainers` conserva explícitamente los contenedores y volúmenes propios
  de la ejecución y utiliza un mensaje final distinto que no contiene `PASSED`.
- El snapshot histórico utilizado en las pruebas se verifica mediante SHA-256
  antes de iniciar cualquier escenario.
- Los datos centinela permiten detectar modificaciones de contenido, fecha o
  recuentos durante la adopción histórica.
- Las comparaciones textuales críticas del usuario y de los roles centinela son
  sensibles a mayúsculas/minúsculas.

Las pruebas adversariales específicas de estas garantías han comprobado:

- fallo provocado después de `docker create` y antes de `docker start`, sin dejar
  contenedor ni volumen residual;
- detección de un cambio exclusivamente de capitalización en el usuario
  centinela;
- detección de un cambio exclusivamente de capitalización en la descripción de
  un rol centinela;
- funcionamiento explícito de `-KeepContainers`, conservando dos contenedores y
  dos volúmenes sin emitir el marcador global `PASSED`;
- eliminación posterior controlada de esos recursos por su label de ejecución;
- ejecución normal completa posterior sin contenedores ni volúmenes residuales.

---

## 13. Backup obligatorio antes de adoptar una instalación real

El dump:

`phase08-schema-before.sql`

es únicamente estructural.

NO constituye un backup completo de recuperación porque no contiene los datos
de negocio.

Antes de adoptar una base histórica real debe generarse un backup actual que
incluya como mínimo:

- esquema;
- usuarios;
- auditorías;
- resultados;
- incidencias;
- historiales;
- roles y relaciones;
- metadatos necesarios para reconstrucción.

El backup debe almacenarse fuera del repositorio y fuera del volumen que se
pretende proteger.

No incluir secretos en Git.

---

## 14. Restauración obligatoria

No basta con crear un backup: debe comprobarse que puede restaurarse.

La restauración se ensaya siempre sobre:

- otro contenedor;
- otro volumen;
- otro puerto;
- otra base;
- sin tráfico de aplicación.

Nunca se valida una restauración sobrescribiendo la base habitual.

La prueba debe comprobar:

- todas las tablas esperadas;
- recuentos;
- relaciones;
- columnas de versionado;
- datos representativos;
- arranque con Hibernate `validate`.

Hasta realizar esa prueba, una copia se considera disponible pero no
completamente validada como mecanismo de recuperación.

---

## 15. Recuperación por escenarios

### Caso A — falla antes de crear `flyway_schema_history`

No se ha adoptado la base.

Acciones:

1. detener el proceso;
2. no repetir automáticamente;
3. diagnosticar configuración y destino;
4. comprobar que la base no cambió;
5. corregir únicamente después de identificar la causa.

### Caso B — baseline versión 1 se registra correctamente pero falla después el arranque

NO eliminar ni repetir el baseline.

El baseline ya representa una operación completada.

Acciones:

1. detener la aplicación;
2. conservar `flyway_schema_history`;
3. investigar el fallo posterior;
4. mantener `ddl-auto=validate`;
5. corregir aplicación/configuración;
6. volver a arrancar sin `baseline-on-migrate`.

### Caso C — una futura migración V2+ falla antes de aplicar DDL

Acciones:

1. detener despliegue;
2. analizar el error;
3. no utilizar `repair` automáticamente;
4. verificar esquema e historial;
5. corregir mediante estrategia revisada.

### Caso D — una futura migración ejecuta DDL parcial

MariaDB puede realizar commits implícitos para DDL.

Por tanto, no se debe asumir rollback transaccional de una migración con varias
operaciones DDL.

Acciones:

1. detener tráfico;
2. no ejecutar nuevamente la migración a ciegas;
3. registrar qué sentencias llegaron a aplicarse;
4. comparar esquema con backup;
5. determinar recuperación mediante restauración o migración correctiva;
6. ensayar primero la recuperación en aislamiento;
7. actuar sobre la base real únicamente después de revisión.

### Caso E — una migración completó correctamente pero la nueva aplicación falla

No revertir la base automáticamente.

Acciones:

1. determinar si el código anterior es compatible con el nuevo esquema;
2. no arrancar una versión antigua que utilice `ddl-auto=update`;
3. mantener `validate`;
4. si existe compatibilidad hacia atrás, puede revertirse únicamente la
   aplicación;
5. si no existe compatibilidad, utilizar el procedimiento de recuperación
   previamente ensayado.

### Caso F — destino de BD incorrecto

Detener inmediatamente.

Nunca continuar con baseline únicamente porque Flyway lo permite.

Verificar:

- host;
- puerto;
- nombre de base;
- identidad del entorno;
- tablas;
- recuentos;
- backup.

---

## 16. Qué NO es rollback

No son mecanismos de rollback de esquema:

```text
FLYWAY_ENABLED=false
```

```text
volver al JAR anterior
```

```text
flyway repair
```

Desactivar Flyway no deshace DDL.

Cambiar de aplicación no deshace DDL.

`repair` modifica metadatos del historial Flyway; no restaura objetos ni datos.

La recuperación real depende de:

- diseño compatible de migraciones;
- backup;
- restauración validada;
- migraciones correctivas cuando proceda.

---

## 17. Estrategia DEV

Desarrollo:

- Flyway explícitamente habilitado cuando se trabaja contra una BD gestionada
  mediante migraciones;
- baseline desactivado;
- Hibernate `validate`;
- cambios de esquema nuevos mediante V2+;
- bases desechables pueden recrearse para validar el camino completo V1 → Vn;
- no editar una migración ya adoptada.

Configuración esperada:

```text
FLYWAY_ENABLED=true
FLYWAY_BASELINE_ON_MIGRATE=false
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
```

---

## 18. Estrategia PROD

Producción:

- backup actualizado antes de cambios de esquema;
- restauración previamente ensayada;
- versión exacta del artefacto conocida;
- migraciones revisadas;
- Flyway habilitado explícitamente;
- baseline desactivado durante operación normal;
- Hibernate exclusivamente `validate`;
- `clean` desactivado;
- migraciones aplicadas de forma controlada antes de abrir tráfico cuando el
  cambio lo requiera.

Una instalación histórica todavía no adoptada requiere un procedimiento
extraordinario de baseline una sola vez.

No mantener:

```text
FLYWAY_BASELINE_ON_MIGRATE=true
```

en configuración productiva habitual.

---

## 19. V2 y siguientes

Después de adoptar V1:

- V1 queda congelado;
- el siguiente cambio será `V2__descripcion.sql`;
- nunca alterar V1 para reflejar cambios posteriores;
- una migración debe asumir que parte del esquema producido por todas las
  versiones anteriores;
- una V2 debe funcionar tanto sobre:
  - una instalación nueva que ejecutó V1;
  - una histórica que fue marcada mediante BASELINE 1.

Antes de integrar cada nueva migración se validarán ambos caminos cuando sea
relevante.

---

## 20. Evidencias necesarias antes de adoptar la BD habitual

La adopción de la MariaDB habitual permanece pendiente.

Antes de autorizarla deben existir:

- [x] V1 definida.
- [x] V1 validada sobre MariaDB 11.2 vacía.
- [x] Histórico temporal adoptado mediante BASELINE 1.
- [x] Datos centinela preservados.
- [x] Hibernate `validate` en ambos caminos.
- [x] Comparación estructural completa histórico ↔ V1.
- [x] Configuración endurecida.
- [x] Procedimiento de recuperación documentado.
- [x] Backup completo actual de la BD habitual.
- [x] Restauración del backup completo ensayada en aislamiento.
- [x] Pruebas Flyway reproducibles integradas.
- [x] Segunda validación completa tras todos los cambios de fase 0.8.
- [x] Revisión adversarial final.
- [x] Commit de fase 0.8.

Hasta cerrar todos los elementos pendientes:

**NO activar Flyway sobre la MariaDB habitual.**

---

## 21. Estado

Fase 0.8 en validación final.

Se han completado y validado en aislamiento:

- V1 sobre instalación nueva;
- baseline de instalación histórica;
- equivalencia entre histórico y V1;
- backup completo de la MariaDB habitual;
- restauración aislada del backup;
- conservación de datos y recuentos;
- validación Hibernate del esquema restaurado;
- validador Flyway reproducible;
- endurecimiento adversarial de los scripts de migración.

La adopción de Flyway sobre la MariaDB habitual permanece deliberadamente
pendiente hasta superar la validación completa final, la última revisión
adversarial y el commit de la fase 0.8.