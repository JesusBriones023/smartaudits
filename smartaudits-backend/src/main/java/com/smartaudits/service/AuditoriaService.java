package com.smartaudits.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.Auditoria;
import com.smartaudits.model.Incidencia;
import com.smartaudits.model.Usuario;
import com.smartaudits.model.TipoFuente;
import com.smartaudits.model.dto.AuditoriaHtmlRequest;
import com.smartaudits.model.dto.AuditoriaRequest;
import com.smartaudits.model.dto.AuditoriaResponse;
import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.service.html.ContenidoHtmlInvalidoException;
import com.smartaudits.service.html.ExtractorTextoHtml;
import com.smartaudits.service.motor.AnalizadorLegal;
import com.smartaudits.service.motor.EntradaAnalisis;
import com.smartaudits.model.dto.PaginaResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class AuditoriaService {

    private final AuditoriaRepository auditoriaRepository;
    private final AnalizadorLegal analizadorLegal;
    private final HistorialService historialService;
    private final ObjectMapper objectMapper;
    private final AuditQuotaService auditQuotaService;
    private final ExtractorTextoHtml extractorHtml = new ExtractorTextoHtml();

    @Transactional
    public AuditoriaResponse crearAuditoria(AuditoriaRequest request, Usuario usuario, String ipAcceso) {
        return crearAuditoriaAnalizada(request.getTitulo(), request.getTipoDocumento(), request.getTextoOriginal(),
                request.getUrlOpcional(), TipoFuente.MANUAL, usuario, ipAcceso);
    }

    /**
     * Audita el texto legible de un HTML pegado por el usuario. El HTML no se ejecuta,
     * no se guarda y la URL opcional nunca se descarga: se persiste el texto extraído,
     * que es exactamente lo que analiza el motor. Es contenido aportado a mano por el
     * usuario, igual que el texto pegado, así que su fuente sigue siendo MANUAL.
     */
    @Transactional
    public AuditoriaResponse crearAuditoriaDesdeHtml(AuditoriaHtmlRequest request, Usuario usuario, String ipAcceso) {
        return crearAuditoriaAnalizada(request.getTitulo(), request.getTipoDocumento(),
                textoAuditableDeHtml(request.getHtml()), request.getUrlOpcional(), TipoFuente.MANUAL, usuario, ipAcceso);
    }

    /** Texto legible del HTML, o ContenidoHtmlInvalidoException si queda vacío o demasiado largo. */
    public String textoAuditableDeHtml(String html) {
        String texto = extractorHtml.extraer(html);
        if (texto.isBlank()) {
            throw new ContenidoHtmlInvalidoException("El HTML no contiene texto legible para auditar");
        }
        if (texto.length() > AuditoriaRequest.MAX_TEXTO_LENGTH) {
            throw new ContenidoHtmlInvalidoException(
                    "El texto extraído del HTML supera los " + AuditoriaRequest.MAX_TEXTO_LENGTH + " caracteres");
        }
        return texto;
    }

    /**
     * Analiza un texto y guarda su auditoría con incidencias e historial. Se une a la
     * transacción del llamante: en una auditoría conjunta, un fallo deshace todo el lote.
     */
    @Transactional
    public AuditoriaResponse crearAuditoriaAnalizada(String titulo, String tipoDocumento, String texto, String urlOpcional,
                                                     TipoFuente fuente, Usuario usuario, String ipAcceso) {

        auditQuotaService.verificarPuedeCrear(usuario);
        // 1. Analizar texto con motor propio
        var version = analizadorLegal.version();
        LocalDateTime fechaAnalisis = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ResultadoAuditoria resultadoDto = analizadorLegal.analyze(new EntradaAnalisis(texto, tipoDocumento));

        // 2. Crear entidad Auditoria
        Auditoria auditoria = new Auditoria();
        auditoria.registrarProcedencia(version.versionMotor(), version.versionReglas(),
                fechaAnalisis, fuente);
        auditoria.setTitulo(titulo);
        auditoria.setTipoDocumento(tipoDocumento);
        auditoria.setTextoOriginal(texto);
        auditoria.setUrlOpcional(urlOpcional);
        auditoria.setPuntuacionRiesgo(resultadoDto.getPuntuacionRiesgo());
        auditoria.setEstado("COMPLETADA");
        auditoria.setFechaCreacion(LocalDateTime.now());
        auditoria.setUsuario(usuario);

        // 3. Serializar JSON para el frontend
        try {
            auditoria.setResultadoJson(objectMapper.writeValueAsString(resultadoDto));
        } catch (Exception e) {
            throw new RuntimeException("Error al serializar resultado", e);
        }

        // 4. Guardar auditoría (necesaria antes de las FK de las tablas hijas)
        auditoria = auditoriaRepository.save(auditoria);

        // 5. Crear Incidencias (una fila por cada error detectado)
        if (resultadoDto.getErrores() != null) {
            for (ResultadoAuditoria.ErrorAuditoria error : resultadoDto.getErrores()) {
                Incidencia inc = new Incidencia();
                inc.registrarProcedencia(error.getRuleId(), error.getMotor(), error.getVersion());
                inc.setAuditoria(auditoria);
                inc.setCategoria(error.getTitulo());
                inc.setSeveridad(error.getSeveridad());
                inc.setDescripcion(error.getDescripcion());
                inc.setRecomendacion(error.getAccion());
                inc.setEvidencia(error.getEvidencia());
                inc.setImpacto(error.getImpacto());
                auditoria.getIncidencias().add(inc);
            }
        }

        // 6. Guardar las incidencias mediante cascade
        auditoria = auditoriaRepository.save(auditoria);

        // 7. Registrar CREACION en historial
        historialService.registrarCreacion(auditoria, usuario, ipAcceso);

        return toResponseComplete(auditoria, resultadoDto);
    }

    @Transactional(readOnly = true)
public PaginaResponse<AuditoriaResponse> obtenerMisAuditorias(
        Long usuarioId,
        int page,
        int size) {

    Pageable pageable = crearPageable(page, size);

    Page<AuditoriaResponse> resultado =
            auditoriaRepository
                    .findByUsuarioId(usuarioId, pageable)
                    .map(this::toResponseSimple);

    return PaginaResponse.from(resultado);
}

@Transactional(readOnly = true)
public PaginaResponse<AuditoriaResponse> obtenerTodasLasAuditorias(
        int page,
        int size,
        String filtroUsuario) {

    Pageable pageable = crearPageable(page, size);

    String filtro = filtroUsuario == null
            ? ""
            : filtroUsuario.trim();

    // El nombre de Usuario admite 100 caracteres.
    // No enviamos patrones arbitrariamente grandes a la BD.
    if (filtro.length() > 100) {
        filtro = filtro.substring(0, 100);
    }

    Page<Auditoria> pagina;

    if (filtro.isBlank()) {
        pagina = auditoriaRepository.findAll(pageable);
    } else {
        pagina = auditoriaRepository
                .findByUsuarioNombreContainingIgnoreCase(
                        filtro,
                        pageable
                );
    }

    return PaginaResponse.from(
            pagina.map(this::toResponseSimple)
    );
}

/**
 * Construye una paginación defensiva:
 * - página mínima: 0
 * - tamaño mínimo: 1
 * - máximo absoluto: 50
 * - orden estable: fecha DESC + ID DESC
 */
private Pageable crearPageable(int page, int size) {

    int paginaSegura = Math.max(page, 0);
    int tamanoSeguro = Math.max(
            1,
            Math.min(size, 50)
    );

    Sort orden = Sort.by(
            Sort.Order.desc("fechaCreacion"),
            Sort.Order.desc("id")
    );

    return PageRequest.of(
            paginaSegura,
            tamanoSeguro,
            orden
    );
}

    public AuditoriaResponse obtenerAuditoriaPorId(Long id, Long usuarioId, boolean isAdmin,
                                                Usuario usuario, String ipAcceso) {

    Auditoria auditoria = obtenerAuditoriaAutorizada(id, usuarioId, isAdmin);

    // Registrar CONSULTA en historial
    historialService.registrarConsulta(auditoria, usuario, ipAcceso);

    return respuestaCompleta(auditoria);
}

    /**
     * Auditoría completa del propietario o de un ADMIN sin registrar consulta: la usa la
     * descarga de un informe conjunto, que registra DESCARGA en su lugar. Solo lee.
     */
    public AuditoriaResponse leerAuditoriaAutorizada(Long id, Long usuarioId, boolean isAdmin) {
        return respuestaCompleta(obtenerAuditoriaAutorizada(id, usuarioId, isAdmin));
    }

    private AuditoriaResponse respuestaCompleta(Auditoria auditoria) {
        try {
            ResultadoAuditoria resultado = objectMapper.readValue(
                    auditoria.getResultadoJson(), ResultadoAuditoria.class);
            return toResponseComplete(auditoria, resultado);
        } catch (Exception e) {
            throw new RuntimeException("Error al deserializar resultado", e);
        }
    }

    /**
     * Registra una descarga únicamente si el usuario es propietario
     * de la auditoría o tiene rol ADMIN.
     */
    public void registrarDescarga(Long id, Long usuarioId, boolean isAdmin,
                              Usuario usuario, String ipAcceso) {

        Auditoria auditoria = obtenerAuditoriaAutorizada(id, usuarioId, isAdmin);

        historialService.registrarDescarga(auditoria, usuario, ipAcceso);
    }

    /**
     * Fuente única de autorización para operaciones sobre una auditoría concreta.
     */
    private Auditoria obtenerAuditoriaAutorizada(Long id, Long usuarioId, boolean isAdmin) {

        Auditoria auditoria = auditoriaRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Auditoría no encontrada"));

        if (!isAdmin && !auditoria.getUsuario().getId().equals(usuarioId)) {
            throw new AccessDeniedException("No tienes permiso para acceder a esta auditoría");
        }
        return auditoria;
    }

    private AuditoriaResponse toResponseSimple(Auditoria auditoria) {
        AuditoriaResponse response = new AuditoriaResponse();
        response.setId(auditoria.getId());
        response.setTitulo(auditoria.getTitulo());
        response.setTipoDocumento(auditoria.getTipoDocumento());
        response.setFechaCreacion(auditoria.getFechaCreacion());
        response.setVersionMotor(auditoria.getVersionMotor());
        response.setVersionReglas(auditoria.getVersionReglas());
        response.setFechaAnalisis(auditoria.getFechaAnalisis());
        response.setTipoFuente(auditoria.getTipoFuente());
        response.setPuntuacionRiesgo(auditoria.getPuntuacionRiesgo());
        response.setUrlOpcional(auditoria.getUrlOpcional());
        response.setUsuarioId(auditoria.getUsuario().getId());
        response.setUsuarioNombre(auditoria.getUsuario().getNombre());
        response.setUsuarioEmail(auditoria.getUsuario().getEmail());
        return response;
    }

    private AuditoriaResponse toResponseComplete(Auditoria auditoria, ResultadoAuditoria resultado) {
        AuditoriaResponse response = toResponseSimple(auditoria);
        response.setTextoOriginal(auditoria.getTextoOriginal());
        response.setResultado(resultado);
        return response;
    }
}
