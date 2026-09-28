package com.smartaudits.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuración de CORS (Cross-Origin Resource Sharing).
 *
 * Permite al frontend (Vite, puerto 5173) y al frontend de desarrollo
 * alternativo (puerto 3000) hacer peticiones al backend (puerto 8080).
 *
 * Métodos HTTP permitidos:
 *  - GET, POST, PUT, DELETE: operaciones CRUD estándar
 *  - PATCH: actualizaciones parciales (cambio de rol, desactivación,
 *    reactivación de usuarios, etc.)
 *  - OPTIONS: necesario para las peticiones preflight de CORS
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("http://localhost:5173", "http://localhost:3000")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
