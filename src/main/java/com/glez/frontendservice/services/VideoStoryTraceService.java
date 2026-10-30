package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.VideoStoryTraceDto;
import com.glez.frontendservice.model.TraceEventStatus;
import com.glez.frontendservice.model.VideoStoryProject;
import com.glez.frontendservice.model.VideoStoryStep;
import com.glez.frontendservice.model.VideoStoryTraceEvent;
import com.glez.frontendservice.repository.VideoStoryProjectRepository;
import com.glez.frontendservice.repository.VideoStoryTraceEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persiste un evento de traza por intento de paso del Asistente de
 * Historias — el equivalente wizard de {@link ProcessingTraceService},
 * sobre tabla propia ({@code VIDEO_STORY_TRACE_EVENT}) para no tocar la
 * maquinaria de trazas del pipeline de libros.
 *
 * <p>Todas las mutaciones corren en transacciones independientes
 * (REQUIRES_NEW) para que la traza sobreviva a cualquier rollback o crash
 * del negocio; el MDC del wizard (traceId=projectId, step=WIZARD_*) lo
 * sigue gestionando {@code VideoStoryWizardService} — este servicio solo
 * persiste.</p>
 */
@Service
@RequiredArgsConstructor
public class VideoStoryTraceService {

    private static final Logger log = LoggerFactory.getLogger(VideoStoryTraceService.class);

    private final VideoStoryTraceEventRepository traceEventRepository;
    private final VideoStoryProjectRepository projectRepository;

    /**
     * Registra el inicio de un intento de paso. El intento es 1 + los
     * fallos genuinos previos de (proyecto, paso), igual que el pipeline
     * de libros.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VideoStoryTraceEvent startStep(UUID projectId, VideoStoryStep step, String details) {
        VideoStoryProject project = projectRepository.getReferenceById(projectId);

        VideoStoryTraceEvent event = new VideoStoryTraceEvent();
        event.setProject(project);
        // El MDC traceId del asistente ES el id del proyecto: la cronología
        // y el visor de logs comparten correlación.
        event.setTraceId(projectId.toString());
        event.setStep(step);
        event.setStatus(TraceEventStatus.STARTED);
        event.setAttempt((int) traceEventRepository
                .countByProjectIdAndStepAndStatus(projectId, step, TraceEventStatus.FAILED) + 1);
        event.setStartedAt(Instant.now());
        event.setDetails(details);
        event = traceEventRepository.save(event);

        log.info("[wizard-trace] Paso {} iniciado (intento {})", step, event.getAttempt());
        return event;
    }

    /** Marca el intento como completado con su duración ya registrada. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeStep(VideoStoryTraceEvent event, String details) {
        event.setStatus(TraceEventStatus.COMPLETED);
        event.setFinishedAt(Instant.now());
        if (details != null) {
            event.setDetails(details);
        }
        traceEventRepository.save(event);
        log.info("[wizard-trace] Paso {} completado en {} ms", event.getStep(),
                event.getFinishedAt().toEpochMilli() - event.getStartedAt().toEpochMilli());
    }

    /** Marca el intento como fallido, persistiendo la causa. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failStep(VideoStoryTraceEvent event, Throwable error) {
        event.setStatus(TraceEventStatus.FAILED);
        event.setFinishedAt(Instant.now());
        event.setErrorMessage(truncate(error));
        traceEventRepository.save(event);
        log.error("[wizard-trace] Paso {} fallido: {}", event.getStep(), error.getMessage());
    }

    /**
     * Cronología completa de un proyecto (la más antigua primero) con la
     * duración de cada intento ya calculada.
     */
    @Transactional(readOnly = true)
    public List<VideoStoryTraceDto> timeline(UUID projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new IllegalArgumentException("Proyecto " + projectId + " no encontrado.");
        }
        return traceEventRepository
                .findByProjectOrderByStartedAtAscIdAsc(projectRepository.getReferenceById(projectId))
                .stream()
                .map(e -> new VideoStoryTraceDto(
                        e.getId(),
                        e.getStep().name(),
                        e.getStatus().name(),
                        e.getAttempt(),
                        e.getStartedAt(),
                        e.getFinishedAt(),
                        e.getFinishedAt() != null
                                ? e.getFinishedAt().toEpochMilli() - e.getStartedAt().toEpochMilli()
                                : null,
                        e.getErrorMessage(),
                        e.getDetails()))
                .toList();
    }

    /**
     * Último estado de traza de cada proyecto (para el panel de procesos):
     * reduce en Java el último evento por proyecto — la tabla es pequeña
     * (un evento por intento de paso) y así se evita SQL de ventana.
     */
    @Transactional(readOnly = true)
    public Map<UUID, TraceEventStatus> lastStatuses(Collection<UUID> projectIds) {
        Map<UUID, TraceEventStatus> last = new HashMap<>();
        if (projectIds == null || projectIds.isEmpty()) {
            return last;
        }
        for (VideoStoryTraceEvent event
                : traceEventRepository.findByProjectIdInOrderByStartedAtAscIdAsc(projectIds)) {
            last.put(event.getProject().getId(), event.getStatus());
        }
        return last;
    }

    private static String truncate(Throwable error) {
        String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}
