package com.smartaudits.service.motor;

/** Identificadores técnicos independientes de la versión de la aplicación. */
public record VersionAnalizador(String versionMotor, String versionReglas) {
    public VersionAnalizador {
        validar(versionMotor);
        validar(versionReglas);
    }

    private static void validar(String version) {
        if (version == null || version.isBlank() || version.length() > 50) {
            throw new IllegalArgumentException("La versión debe tener entre 1 y 50 caracteres");
        }
    }
}
