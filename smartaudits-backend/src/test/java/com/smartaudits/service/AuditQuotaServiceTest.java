package com.smartaudits.service;

import com.smartaudits.model.Role;
import com.smartaudits.model.Usuario;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuditQuotaServiceTest {

    private UsuarioRepository usuarios;
    private AuditoriaRepository auditorias;

    private Usuario cliente;
    private Usuario admin;

    @BeforeEach
    void setup() {

        usuarios = mock(UsuarioRepository.class);
        auditorias = mock(AuditoriaRepository.class);

        cliente = new Usuario();
        cliente.setId(10L);
        cliente.setRole(Role.CLIENTE);

        admin = new Usuario();
        admin.setId(20L);
        admin.setRole(Role.ADMIN);

        when(usuarios.bloquearPorId(10L))
                .thenReturn(Optional.of(cliente));
    }

    @Test
    void clientePuedeCrearMientrasEstePorDebajoDeLaCuota() {

        AuditQuotaService service =
                new AuditQuotaService(
                        usuarios,
                        auditorias,
                        100
                );

        when(
                auditorias
                        .countByUsuarioIdAndFechaCreacionGreaterThanEqual(
                                eq(10L),
                                any()
                        )
        ).thenReturn(99L);

        assertThatCode(
                () ->
                        service.verificarPuedeCrear(
                                cliente
                        )
        ).doesNotThrowAnyException();

        verify(usuarios)
                .bloquearPorId(10L);
    }

    @Test
    void clienteEsBloqueadoAlAlcanzarLaCuota() {

        AuditQuotaService service =
                new AuditQuotaService(
                        usuarios,
                        auditorias,
                        100
                );

        when(
                auditorias
                        .countByUsuarioIdAndFechaCreacionGreaterThanEqual(
                                eq(10L),
                                any()
                        )
        ).thenReturn(100L);

        assertThatThrownBy(
                () ->
                        service.verificarPuedeCrear(
                                cliente
                        )
        )
                .isInstanceOf(
                        AuditQuotaExceededException.class
                )
                .hasMessageContaining(
                        "límite diario de 100 auditorías"
                );
    }

    @Test
    void adminNoConsumeLaCuotaTecnica() {

        AuditQuotaService service =
                new AuditQuotaService(
                        usuarios,
                        auditorias,
                        100
                );

        assertThatCode(
                () ->
                        service.verificarPuedeCrear(
                                admin
                        )
        ).doesNotThrowAnyException();

        verifyNoInteractions(
                usuarios,
                auditorias
        );
    }
}