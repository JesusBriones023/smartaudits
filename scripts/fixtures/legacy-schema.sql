-- CI legacy schema fixture: DDL only, no historical rows or dump metadata.
-- Frozen independently of V1; derived from the approved Phase 0.8 schema.
SET FOREIGN_KEY_CHECKS=0;
CREATE TABLE `auditorias` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `fecha_creacion` datetime(6) NOT NULL,
  `puntuacion_riesgo` int(11) DEFAULT NULL,
  `resultado_json` text DEFAULT NULL,
  `texto_original` text DEFAULT NULL,
  `tipo_documento` varchar(50) DEFAULT NULL,
  `titulo` varchar(200) NOT NULL,
  `url_opcional` varchar(500) DEFAULT NULL,
  `usuario_id` bigint(20) NOT NULL,
  `estado` varchar(20) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKd7b67ypdg6v2ve12iwe9mfmhx` (`usuario_id`),
  CONSTRAINT `FKd7b67ypdg6v2ve12iwe9mfmhx` FOREIGN KEY (`usuario_id`) REFERENCES `usuarios` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `historial_acciones_admin` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `admin_email` varchar(150) NOT NULL,
  `admin_nombre` varchar(100) NOT NULL,
  `detalles` varchar(500) DEFAULT NULL,
  `fecha` datetime(6) NOT NULL,
  `objetivo_email` varchar(150) NOT NULL,
  `objetivo_nombre` varchar(100) NOT NULL,
  `tipo_accion` enum('DESACTIVAR','REACTIVAR','PROMOVER','DEGRADAR') NOT NULL,
  `admin_id` bigint(20) NOT NULL,
  `usuario_objetivo_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_hist_admin_id` (`admin_id`),
  KEY `idx_hist_objetivo_id` (`usuario_objetivo_id`),
  KEY `idx_hist_fecha` (`fecha`),
  CONSTRAINT `FK5yti773bbeu984qtwscg7y688` FOREIGN KEY (`admin_id`) REFERENCES `usuarios` (`id`),
  CONSTRAINT `FKljywmqw0k9qesqd0kepkckkl0` FOREIGN KEY (`usuario_objetivo_id`) REFERENCES `usuarios` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `historial_auditorias` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `accion` varchar(20) NOT NULL,
  `fecha_acceso` datetime(6) NOT NULL,
  `ip_acceso` varchar(50) DEFAULT NULL,
  `auditoria_id` bigint(20) NOT NULL,
  `usuario_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK6qltwcyqn6a7p1lmtan4ve6pn` (`auditoria_id`),
  KEY `FKqur2cgujbkw25qk5rl5t9aoax` (`usuario_id`),
  CONSTRAINT `FK6qltwcyqn6a7p1lmtan4ve6pn` FOREIGN KEY (`auditoria_id`) REFERENCES `auditorias` (`id`),
  CONSTRAINT `FKqur2cgujbkw25qk5rl5t9aoax` FOREIGN KEY (`usuario_id`) REFERENCES `usuarios` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `incidencias` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `categoria` varchar(200) DEFAULT NULL,
  `descripcion` text DEFAULT NULL,
  `evidencia` text DEFAULT NULL,
  `impacto` text DEFAULT NULL,
  `recomendacion` text DEFAULT NULL,
  `severidad` varchar(10) NOT NULL,
  `auditoria_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK9jm8d5ttats0optsf4t7m2c2c` (`auditoria_id`),
  CONSTRAINT `FK9jm8d5ttats0optsf4t7m2c2c` FOREIGN KEY (`auditoria_id`) REFERENCES `auditorias` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `resultados` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `fecha_resultado` datetime(6) NOT NULL,
  `puntuacion_cumplimiento` int(11) DEFAULT NULL,
  `recomendaciones_generales` text DEFAULT NULL,
  `resumen_general` text DEFAULT NULL,
  `auditoria_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_21ijlu42acphbiah6egbr3kuk` (`auditoria_id`),
  CONSTRAINT `FK6hjf41ajsk7h3fj0td98wio8i` FOREIGN KEY (`auditoria_id`) REFERENCES `auditorias` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `roles` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `descripcion` varchar(200) DEFAULT NULL,
  `nombre` varchar(50) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_ldv0v52e0udsh2h1rs0r0gw1n` (`nombre`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `usuarios` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `email` varchar(150) NOT NULL,
  `fecha_registro` datetime(6) NOT NULL,
  `nombre` varchar(100) NOT NULL,
  `password` varchar(255) NOT NULL,
  `role` enum('CLIENTE','ADMIN') NOT NULL,
  `activo` bit(1) NOT NULL,
  `protegido` bit(1) NOT NULL,
  `token_version` bigint(20) NOT NULL DEFAULT 0,
  `row_version` bigint(20) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_kfsp0s1tflm1cwlj8idhqsad0` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `usuarios_roles` (
  `usuario_id` bigint(20) NOT NULL,
  `rol_id` bigint(20) NOT NULL,
  PRIMARY KEY (`usuario_id`,`rol_id`),
  KEY `FK5338ehgluufgc8bpj08nrq970` (`rol_id`),
  CONSTRAINT `FK5338ehgluufgc8bpj08nrq970` FOREIGN KEY (`rol_id`) REFERENCES `roles` (`id`),
  CONSTRAINT `FKqcxu02bqipxpr7cjyj9dmhwec` FOREIGN KEY (`usuario_id`) REFERENCES `usuarios` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
SET FOREIGN_KEY_CHECKS=1;
