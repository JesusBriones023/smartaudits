package com.smartaudits.service;

import com.smartaudits.model.Role;
import com.smartaudits.model.Usuario;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class AuditQuotaService {

    private final UsuarioRepository usuarioRepository;
    private final AuditoriaRepository auditoriaRepository;
    private final int dailyLimit;

    public AuditQuotaService(
            UsuarioRepository usuarioRepository,
            AuditoriaRepository auditoriaRepository,
            @Value("${app.audit.daily-limit:100}")
            int dailyLimit) {

        this.usuarioRepository = usuarioRepository;
        this.auditoriaRepository = auditoriaRepository;
        this.dailyLimit = Math.max(1, dailyLimit);
    }

    /**
     * La cuota actual es técnica, no comercial.
     *
     * Los planes y entitlements llegarán en la fase de Billing.
     */
    public void verificarPuedeCrear(Usuario usuario) {

        if (usuario.getRole() == Role.ADMIN) {
            return;
        }

        /*
         * Bloqueamos la fila del usuario dentro de la transacción
         * de creación para impedir que dos peticiones concurrentes
         * consuman simultáneamente el último hueco disponible.
         */
        usuarioRepository
                .bloquearPorId(usuario.getId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Usuario no encontrado"
                        ));

        LocalDateTime ahora =
                LocalDateTime.now();

        LocalDateTime inicioDia =
                ahora.toLocalDate()
                        .atStartOfDay();

        long usadas =
                auditoriaRepository
                        .countByUsuarioIdAndFechaCreacionGreaterThanEqual(
                                usuario.getId(),
                                inicioDia
                        );

        if (usadas < dailyLimit) {
            return;
        }

        LocalDateTime proximoDia =
                ahora.toLocalDate()
                        .plusDays(1)
                        .atStartOfDay();

        long retryAfter =
                Math.max(
                        1,
                        Duration.between(
                                ahora,
                                proximoDia
                        ).getSeconds()
                );

        throw new AuditQuotaExceededException(
                "Has alcanzado el límite diario de "
                        + dailyLimit
                        + " auditorías. Podrás crear nuevas auditorías cuando se reinicie la cuota diaria.",
                retryAfter
        );
    }
}