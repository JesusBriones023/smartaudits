package com.smartaudits.model;

/**
 * Tipos de acciones administrativas que se registran en el historial.
 * Cada vez que un ADMIN ejecuta una de estas acciones sobre otro usuario,
 * se persiste una fila en la tabla historial_acciones_admin para garantizar
 * la trazabilidad completa.
 */
public enum TipoAccionAdmin {

    /** Baja lógica de un usuario (activo: true → false) */
    DESACTIVAR("Desactivar usuario"),

    /** Reactivación de una cuenta previamente desactivada (activo: false → true) */
    REACTIVAR("Reactivar usuario"),

    /** Promoción de rol (CLIENTE → ADMIN) */
    PROMOVER("Promover a Administrador"),

    /** Degradación de rol (ADMIN → CLIENTE) */
    DEGRADAR("Degradar a Cliente");

    private final String etiqueta;

    TipoAccionAdmin(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String getEtiqueta() {
        return etiqueta;
    }
}
