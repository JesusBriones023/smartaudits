-- Frozen historical catalogue: LEGAL_TEXT rules 1. No normalization of categories.
-- Two owner-confirmed historical aliases are exact-only (no prefixed variants).
-- The same local catalogue serves prevalidation and backfill; no temporary tables,
-- routines, extra grants, or permanent DDL before every historical row is validated.
DELIMITER $$
BEGIN NOT ATOMIC
    DECLARE catalogo LONGTEXT DEFAULT '[
    {
        "ruleId": "R01",
        "categoria": "Identidad del responsable del tratamiento no especificada"
    },
    {
        "ruleId": "R02",
        "categoria": "Datos de contacto del responsable ausentes o incompletos"
    },
    {
        "ruleId": "R03",
        "categoria": "Información sobre el Delegado de Protección de Datos (DPO) ausente"
    },
    {
        "ruleId": "R04",
        "categoria": "Finalidad del tratamiento de datos no especificada"
    },
    {
        "ruleId": "R05",
        "categoria": "Base legal del tratamiento no indicada"
    },
    {
        "ruleId": "R06",
        "categoria": "Derechos del interesado (ARSULIPO) no informados"
    },
    {
        "ruleId": "R07",
        "categoria": "Plazo de conservación de datos no especificado"
    },
    {
        "ruleId": "R08",
        "categoria": "Destinatarios o cesiones de datos no informadas"
    },
    {
        "ruleId": "R09",
        "categoria": "Transferencias internacionales de datos no declaradas"
    },
    {
        "ruleId": "R10",
        "categoria": "Política de cookies ausente o incompleta"
    },
    {
        "ruleId": "R11",
        "categoria": "Derecho a reclamar ante la autoridad de control (AEPD) no informado"
    },
    {
        "ruleId": "R12",
        "categoria": "Mecanismo de consentimiento no descrito adecuadamente"
    },
    {
        "ruleId": "R13",
        "categoria": "Tratamiento de datos de menores no regulado"
    },
    {
        "ruleId": "R14",
        "categoria": "Tratamiento de categorías especiales de datos no regulado"
    },
    {
        "ruleId": "R15",
        "categoria": "Medidas de seguridad técnicas no mencionadas"
    },
    {
        "ruleId": "R16",
        "categoria": "Aviso legal con información LSSI-CE ausente o incompleto"
    },
    {
        "ruleId": "R17",
        "categoria": "Información sobre decisiones automatizadas o perfilado ausente"
    },
    {
        "ruleId": "R18",
        "categoria": "Procedimiento ante brechas de seguridad no mencionado"
    },
    {
        "ruleId": "R19",
        "categoria": "Información sobre comunicaciones comerciales y derecho de exclusión publicitaria ausente"
    },
    {
        "ruleId": "G01",
        "categoria": "Cláusula abusiva de cesión de datos"
    },
    {
        "ruleId": "G02",
        "categoria": "Conservación de datos de forma indefinida"
    },
    {
        "ruleId": "G03",
        "categoria": "Consentimiento tácito o por defecto"
    },
    {
        "ruleId": "G04",
        "categoria": "Exclusión de responsabilidad en seguridad"
    },
    {
        "ruleId": "G05",
        "categoria": "Tecnologías de seguimiento invasivo sin información"
    },
    {
        "ruleId": "R05",
        "categoria": "Base legal del tratamiento ausente",
        "soloExacto": 1
    },
    {
        "ruleId": "R06",
        "categoria": "Derechos ARSULIPO no especificados",
        "soloExacto": 1
    }
]';

    IF EXISTS (
        SELECT 1 FROM (
-- V3_MAPPING_QUERY_BEGIN
    SELECT i.id, COUNT(c.rule_id) AS coincidencias, MIN(c.rule_id) AS rule_id,
           a.version_reglas
    FROM incidencias i
    LEFT JOIN auditorias a ON a.id = i.auditoria_id
    LEFT JOIN JSON_TABLE(catalogo, '$[*]' COLUMNS (
        rule_id VARCHAR(50) PATH '$.ruleId', categoria VARCHAR(200) PATH '$.categoria',
        solo_exacto INT PATH '$.soloExacto'
    )) c ON BINARY i.categoria = BINARY c.categoria
        OR (COALESCE(c.solo_exacto,0)=0 AND c.rule_id LIKE 'R%' AND BINARY i.categoria = BINARY CONCAT('Cláusula presente pero con redacción problemática: ', c.categoria))
    GROUP BY i.id, a.version_reglas
-- V3_MAPPING_QUERY_END
        ) m WHERE m.coincidencias <> 1 OR m.version_reglas IS NULL
                  OR CHAR_LENGTH(TRIM(m.version_reglas)) = 0
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V3_PRECHECK_FAILED: unmapped or ambiguous category, or missing parent rules version';
    END IF;

    ALTER TABLE incidencias
        ADD COLUMN rule_id VARCHAR(50) NULL,
        ADD COLUMN motor VARCHAR(50) NULL,
        ADD COLUMN version VARCHAR(50) NULL;

    UPDATE incidencias i
    JOIN auditorias a ON a.id = i.auditoria_id
    LEFT JOIN JSON_TABLE(catalogo, '$[*]' COLUMNS (
        rule_id VARCHAR(50) PATH '$.ruleId', categoria VARCHAR(200) PATH '$.categoria',
        solo_exacto INT PATH '$.soloExacto'
    )) c ON BINARY i.categoria = BINARY c.categoria
        OR (COALESCE(c.solo_exacto,0)=0 AND c.rule_id LIKE 'R%' AND BINARY i.categoria = BINARY CONCAT('Cláusula presente pero con redacción problemática: ', c.categoria))
    SET i.rule_id = c.rule_id,
        i.motor = 'LEGAL_TEXT',
        i.version = a.version_reglas;

    -- Defensive postcondition before NOT NULL, in addition to the pre-DDL guard.
    IF EXISTS (SELECT 1 FROM incidencias
               WHERE rule_id IS NULL OR motor IS NULL OR version IS NULL
                  OR CHAR_LENGTH(TRIM(version)) = 0) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V3_BACKFILL_FAILED: incomplete incident provenance';
    END IF;

    ALTER TABLE incidencias
        MODIFY COLUMN rule_id VARCHAR(50) NOT NULL,
        MODIFY COLUMN motor VARCHAR(50) NOT NULL,
        MODIFY COLUMN version VARCHAR(50) NOT NULL;
END$$
DELIMITER ;
