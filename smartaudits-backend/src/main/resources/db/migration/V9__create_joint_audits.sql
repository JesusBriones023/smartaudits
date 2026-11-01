CREATE TABLE auditorias_conjuntas (
    id BIGINT NOT NULL AUTO_INCREMENT,
    usuario_id BIGINT NOT NULL,
    titulo VARCHAR(200) NOT NULL,
    fecha_creacion DATETIME(6) NOT NULL,
    documento_1_id BIGINT NOT NULL,
    documento_2_id BIGINT NULL,
    documento_3_id BIGINT NULL,
    documento_4_id BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_auditorias_conjuntas_documento_1 (documento_1_id),
    UNIQUE KEY uq_auditorias_conjuntas_documento_2 (documento_2_id),
    UNIQUE KEY uq_auditorias_conjuntas_documento_3 (documento_3_id),
    UNIQUE KEY uq_auditorias_conjuntas_documento_4 (documento_4_id),
    KEY ix_auditorias_conjuntas_usuario_fecha (usuario_id, fecha_creacion),
    CONSTRAINT fk_auditorias_conjuntas_usuario
        FOREIGN KEY (usuario_id) REFERENCES usuarios (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB
  DEFAULT CHARACTER SET=utf8mb4
  COLLATE=utf8mb4_unicode_520_ci;
