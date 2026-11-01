package com.smartaudits.service;

import com.smartaudits.model.AuditoriaConjunta;
import com.smartaudits.model.TipoFuente;
import com.smartaudits.model.Usuario;
import com.smartaudits.model.dto.AuditoriaConjuntaRequest;
import com.smartaudits.model.dto.AuditoriaConjuntaResponse;
import com.smartaudits.model.dto.AuditoriaResponse;
import com.smartaudits.model.dto.PaginaResponse;
import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.repository.AuditoriaConjuntaRepository;
import com.smartaudits.service.html.ContenidoHtmlInvalidoException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Auditoría conjunta de varios documentos HTML aportados por el usuario. Cada documento
 * se analiza y guarda como una auditoría individual (mismo motor, incidencias, historial
 * y procedencia MANUAL); el grupo solo los enlaza. Todo ocurre en una transacción y las
 * entradas se validan antes de analizar nada: nunca queda un lote a medias.
 */
@Service
@RequiredArgsConstructor
public class AuditoriaConjuntaService {

    /** Categorías que puede cubrir una auditoría conjunta («Documento Completo» no aplica). */
    public static final List<String> CATEGORIAS = List.of(
            "Política de Privacidad", "Aviso Legal", "Política de Cookies", "Términos y Condiciones", "Condiciones de Uso");

    private final AuditoriaService auditoriaService;
    private final AuditoriaConjuntaRepository conjuntas;
    private final AuditQuotaService auditQuotaService;

    @Transactional
    public AuditoriaConjuntaResponse crear(AuditoriaConjuntaRequest request, Usuario usuario, String ipAcceso) {
        List<AuditoriaConjuntaRequest.Documento> documentos = request.getDocumentos();
        Set<String> categorias = new HashSet<>();
        long totalHtml = 0;
        for (AuditoriaConjuntaRequest.Documento d : documentos) {
            if (!categorias.add(d.getTipoDocumento())) {
                throw new ContenidoHtmlInvalidoException(
                        "Cada categoría legal solo puede aparecer una vez (repetida: " + d.getTipoDocumento() + ")");
            }
            totalHtml += d.getHtml().length();
        }
        if (totalHtml > AuditoriaConjuntaRequest.MAX_HTML_TOTAL) {
            throw new ContenidoHtmlInvalidoException(
                    "El HTML de todos los documentos no puede superar los " + AuditoriaConjuntaRequest.MAX_HTML_TOTAL + " caracteres");
        }
        // Extraer y validar todos los textos antes de analizar o guardar ninguno.
        List<String> textos = new ArrayList<>();
        for (int i = 0; i < documentos.size(); i++) {
            try {
                textos.add(auditoriaService.textoAuditableDeHtml(documentos.get(i).getHtml()));
            } catch (ContenidoHtmlInvalidoException e) {
                throw new ContenidoHtmlInvalidoException(
                        "Documento " + (i + 1) + " («" + documentos.get(i).getTitulo() + "»): " + e.getMessage());
            }
        }
        auditQuotaService.verificarPuedeCrear(usuario, documentos.size());

        List<AuditoriaResponse> creadas = new ArrayList<>();
        for (int i = 0; i < documentos.size(); i++) {
            AuditoriaConjuntaRequest.Documento d = documentos.get(i);
            creadas.add(auditoriaService.crearAuditoriaAnalizada(d.getTitulo(), d.getTipoDocumento(), textos.get(i),
                    d.getUrlOpcional(), TipoFuente.MANUAL, usuario, ipAcceso));
        }
        AuditoriaConjunta conjunta = new AuditoriaConjunta();
        conjunta.setUsuario(usuario);
        conjunta.setTitulo(request.getTitulo());
        conjunta.setFechaCreacion(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)); // DATETIME(6)
        conjunta.asignarDocumentos(creadas.stream().map(AuditoriaResponse::getId).toList());
        conjunta = conjuntas.saveAndFlush(conjunta);
        return informe(conjunta, creadas);
    }

    /** Informe conjunto del propietario o de un ADMIN; registra la consulta de cada documento. */
    @Transactional
    public AuditoriaConjuntaResponse obtener(Long id, Usuario usuario, boolean isAdmin, String ipAcceso) {
        AuditoriaConjunta conjunta = autorizada(id, usuario, isAdmin);
        List<AuditoriaResponse> documentos = new ArrayList<>();
        for (Long documentoId : conjunta.documentoIds()) {
            documentos.add(documento(conjunta, auditoriaService.obtenerAuditoriaPorId(documentoId, usuario.getId(), isAdmin,
                    usuario, ipAcceso)));
        }
        return informe(conjunta, documentos);
    }

    /**
     * Informe conjunto para generar el PDF: se construye siempre desde las auditorías
     * persistidas del propietario o de un ADMIN (nunca desde datos del navegador) y
     * registra una DESCARGA por documento. No modifica ninguna auditoría.
     */
    @Transactional
    public AuditoriaConjuntaResponse descargar(Long id, Usuario usuario, boolean isAdmin, String ipAcceso) {
        AuditoriaConjunta conjunta = autorizada(id, usuario, isAdmin);
        List<AuditoriaResponse> documentos = new ArrayList<>();
        for (Long documentoId : conjunta.documentoIds()) {
            documentos.add(documento(conjunta, auditoriaService.leerAuditoriaAutorizada(documentoId, usuario.getId(), isAdmin)));
        }
        for (Long documentoId : conjunta.documentoIds()) {
            auditoriaService.registrarDescarga(documentoId, usuario.getId(), isAdmin, usuario, ipAcceso);
        }
        return informe(conjunta, documentos);
    }

    private AuditoriaConjunta autorizada(Long id, Usuario usuario, boolean isAdmin) {
        AuditoriaConjunta conjunta = conjuntas.findById(id)
                .orElseThrow(() -> new RuntimeException("Auditoría conjunta no encontrada"));
        if (!isAdmin && !conjunta.getUsuario().getId().equals(usuario.getId())) {
            throw new AccessDeniedException("No tienes permiso para acceder a esta auditoría");
        }
        return conjunta;
    }

    private static AuditoriaResponse documento(AuditoriaConjunta conjunta, AuditoriaResponse documento) {
        if (!Objects.equals(documento.getUsuarioId(), conjunta.getUsuario().getId())) {
            throw new IllegalStateException("Documento " + documento.getId() + " no pertenece al propietario de la auditoría conjunta");
        }
        return documento;
    }

    @Transactional(readOnly = true)
    public PaginaResponse<AuditoriaConjuntaResponse.Resumen> listarMias(Long usuarioId, int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 50)),
                Sort.by(Sort.Order.desc("fechaCreacion"), Sort.Order.desc("id")));
        return PaginaResponse.from(conjuntas.findByUsuarioId(usuarioId, pageable).map(c ->
                new AuditoriaConjuntaResponse.Resumen(c.getId(), c.getTitulo(), c.getFechaCreacion(), c.documentoIds().size())));
    }

    private AuditoriaConjuntaResponse informe(AuditoriaConjunta conjunta, List<AuditoriaResponse> auditorias) {
        List<AuditoriaConjuntaResponse.Documento> documentos = new ArrayList<>();
        Map<String, List<AuditoriaResponse>> porRecomendacion = new LinkedHashMap<>();
        for (AuditoriaResponse a : auditorias) {
            ResultadoAuditoria r = a.getResultado();
            documentos.add(new AuditoriaConjuntaResponse.Documento(a.getId(), a.getTitulo(), a.getTipoDocumento(),
                    a.getUrlOpcional(), a.getPuntuacionRiesgo(), r == null ? null : r.getResumen(),
                    r == null || r.getRiesgos() == null ? List.of() : List.copyOf(r.getRiesgos()), a.getVersionMotor(),
                    a.getVersionReglas(), r == null || r.getErrores() == null ? List.of() : List.copyOf(r.getErrores())));
            if (r != null && r.getRecomendaciones() != null) {
                for (String recomendacion : r.getRecomendaciones()) {
                    List<AuditoriaResponse> lista = porRecomendacion.computeIfAbsent(recomendacion, k -> new ArrayList<>());
                    if (!lista.contains(a)) {
                        lista.add(a);
                    }
                }
            }
        }
        List<AuditoriaConjuntaResponse.RecomendacionAgrupada> recomendaciones = porRecomendacion.entrySet().stream()
                .map(e -> new AuditoriaConjuntaResponse.RecomendacionAgrupada(e.getKey(),
                        e.getValue().stream().map(AuditoriaResponse::getId).toList(),
                        e.getValue().stream().map(AuditoriaResponse::getTitulo).toList()))
                .toList();
        Set<String> aportadas = new HashSet<>();
        auditorias.forEach(a -> aportadas.add(a.getTipoDocumento()));
        List<String> noAportadas = CATEGORIAS.stream().filter(c -> !aportadas.contains(c)).toList();
        return new AuditoriaConjuntaResponse(conjunta.getId(), conjunta.getTitulo(), conjunta.getFechaCreacion(),
                conjunta.getUsuario().getId(), documentos.size(), documentos, recomendaciones, noAportadas);
    }
}
