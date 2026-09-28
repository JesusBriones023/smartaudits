-- Fixed historical identifiers: engine 1 / rules 1 produced all pre-versioning audits.
-- These are an immutable migration snapshot, not the runtime source of versions.
ALTER TABLE auditorias
    ADD COLUMN version_motor VARCHAR(50) NULL,
    ADD COLUMN version_reglas VARCHAR(50) NULL,
    ADD COLUMN fecha_analisis DATETIME(6) NULL,
    ADD COLUMN tipo_fuente VARCHAR(16) NULL;

-- Analysis was synchronous during creation. Preserve that recorded timestamp,
-- including microseconds; optional URLs were references, never crawled content.
UPDATE auditorias
SET version_motor = '1',
    version_reglas = '1',
    fecha_analisis = fecha_creacion,
    tipo_fuente = 'MANUAL';

ALTER TABLE auditorias
    MODIFY COLUMN version_motor VARCHAR(50) NOT NULL,
    MODIFY COLUMN version_reglas VARCHAR(50) NOT NULL,
    MODIFY COLUMN fecha_analisis DATETIME(6) NOT NULL,
    MODIFY COLUMN tipo_fuente VARCHAR(16) NOT NULL,
    ADD CONSTRAINT ck_auditorias_tipo_fuente
        CHECK (BINARY tipo_fuente IN ('MANUAL','CRAWLER','FILE','API'));
