-- SmartAudits schema baseline
-- Version 1
--
-- Este script se ejecuta únicamente sobre esquemas nuevos.
-- Una instalación histórica se incorporará mediante Flyway baseline,
-- por lo que V1 NO debe ejecutarse sobre una BD ya poblada.

CREATE TABLE `usuarios` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `email` VARCHAR(150) NOT NULL,
    `fecha_registro` DATETIME(6) NOT NULL,
    `nombre` VARCHAR(100) NOT NULL,
    `password` VARCHAR(255) NOT NULL,
    `role` ENUM('CLIENTE','ADMIN') NOT NULL,
    `activo` BIT(1) NOT NULL,
    `protegido` BIT(1) NOT NULL,
    `token_version` BIGINT NOT NULL DEFAULT 0,
    `row_version` BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_kfsp0s1tflm1cwlj8idhqsad0` (`email`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci;


CREATE TABLE `roles` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `descripcion` VARCHAR(200) DEFAULT NULL,
    `nombre` VARCHAR(50) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_ldv0v52e0udsh2h1rs0r0gw1n` (`nombre`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci;


CREATE TABLE `usuarios_roles` (
    `usuario_id` BIGINT NOT NULL,
    `rol_id` BIGINT NOT NULL,
    PRIMARY KEY (`usuario_id`, `rol_id`),
    KEY `FK5338ehgluufgc8bpj08nrq970` (`rol_id`),
    CONSTRAINT `FK5338ehgluufgc8bpj08nrq970`
        FOREIGN KEY (`rol_id`)
        REFERENCES `roles` (`id`),
    CONSTRAINT `FKqcxu02bqipxpr7cjyj9dmhwec`
        FOREIGN KEY (`usuario_id`)
        REFERENCES `usuarios` (`id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci;


CREATE TABLE `auditorias` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `fecha_creacion` DATETIME(6) NOT NULL,
    `puntuacion_riesgo` INT DEFAULT NULL,
    `resultado_json` TEXT DEFAULT NULL,
    `texto_original` TEXT DEFAULT NULL,
    `tipo_documento` VARCHAR(50) DEFAULT NULL,
    `titulo` VARCHAR(200) NOT NULL,
    `url_opcional` VARCHAR(500) DEFAULT NULL,
    `usuario_id` BIGINT NOT NULL,
    `estado` VARCHAR(20) NOT NULL,
    PRIMARY KEY (`id`),
    KEY `FKd7b67ypdg6v2ve12iwe9mfmhx` (`usuario_id`),
    CONSTRAINT `FKd7b67ypdg6v2ve12iwe9mfmhx`
        FOREIGN KEY (`usuario_id`)
        REFERENCES `usuarios` (`id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci;


CREATE TABLE `resultados` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `fecha_resultado` DATETIME(6) NOT NULL,
    `puntuacion_cumplimiento` INT DEFAULT NULL,
    `recomendaciones_generales` TEXT DEFAULT NULL,
    `resumen_general` TEXT DEFAULT NULL,
    `auditoria_id` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_21ijlu42acphbiah6egbr3kuk` (`auditoria_id`),
    CONSTRAINT `FK6hjf41ajsk7h3fj0td98wio8i`
        FOREIGN KEY (`auditoria_id`)
        REFERENCES `auditorias` (`id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci;


CREATE TABLE `incidencias` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `categoria` VARCHAR(200) DEFAULT NULL,
    `descripcion` TEXT DEFAULT NULL,
    `evidencia` TEXT DEFAULT NULL,
    `impacto` TEXT DEFAULT NULL,
    `recomendacion` TEXT DEFAULT NULL,
    `severidad` VARCHAR(10) NOT NULL,
    `auditoria_id` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    KEY `FK9jm8d5ttats0optsf4t7m2c2c` (`auditoria_id`),
    CONSTRAINT `FK9jm8d5ttats0optsf4t7m2c2c`
        FOREIGN KEY (`auditoria_id`)
        REFERENCES `auditorias` (`id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci;


CREATE TABLE `historial_auditorias` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `accion` VARCHAR(20) NOT NULL,
    `fecha_acceso` DATETIME(6) NOT NULL,
    `ip_acceso` VARCHAR(50) DEFAULT NULL,
    `auditoria_id` BIGINT NOT NULL,
    `usuario_id` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    KEY `FK6qltwcyqn6a7p1lmtan4ve6pn` (`auditoria_id`),
    KEY `FKqur2cgujbkw25qk5rl5t9aoax` (`usuario_id`),
    CONSTRAINT `FK6qltwcyqn6a7p1lmtan4ve6pn`
        FOREIGN KEY (`auditoria_id`)
        REFERENCES `auditorias` (`id`),
    CONSTRAINT `FKqur2cgujbkw25qk5rl5t9aoax`
        FOREIGN KEY (`usuario_id`)
        REFERENCES `usuarios` (`id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci;


CREATE TABLE `historial_acciones_admin` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `admin_email` VARCHAR(150) NOT NULL,
    `admin_nombre` VARCHAR(100) NOT NULL,
    `detalles` VARCHAR(500) DEFAULT NULL,
    `fecha` DATETIME(6) NOT NULL,
    `objetivo_email` VARCHAR(150) NOT NULL,
    `objetivo_nombre` VARCHAR(100) NOT NULL,
    `tipo_accion`
        ENUM(
            'DESACTIVAR',
            'REACTIVAR',
            'PROMOVER',
            'DEGRADAR'
        )
        NOT NULL,
    `admin_id` BIGINT NOT NULL,
    `usuario_objetivo_id` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_hist_admin_id` (`admin_id`),
    KEY `idx_hist_objetivo_id` (`usuario_objetivo_id`),
    KEY `idx_hist_fecha` (`fecha`),
    CONSTRAINT `FK5yti773bbeu984qtwscg7y688`
        FOREIGN KEY (`admin_id`)
        REFERENCES `usuarios` (`id`),
    CONSTRAINT `FKljywmqw0k9qesqd0kepkckkl0`
        FOREIGN KEY (`usuario_objetivo_id`)
        REFERENCES `usuarios` (`id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci;