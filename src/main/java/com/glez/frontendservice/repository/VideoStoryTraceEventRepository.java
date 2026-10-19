package com.glez.frontendservice.repository;

import com.glez.frontendservice.model.TraceEventStatus;
import com.glez.frontendservice.model.VideoStoryProject;
import com.glez.frontendservice.model.VideoStoryStep;
import com.glez.frontendservice.model.VideoStoryTraceEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Eventos de traza del Asistente de Historias (cronología por proyecto). */
public interface VideoStoryTraceEventRepository extends JpaRepository<VideoStoryTraceEvent, UUID> {

    /** Cronología completa de un proyecto, la más antigua primero. */
    List<VideoStoryTraceEvent> findByProjectOrderByStartedAtAscIdAsc(VideoStoryProject project);

    /**
     * Fallos genuinos previos de un paso (para numerar el intento actual):
     * igual que el pipeline de libros, solo cuentan las corridas que
     * terminaron FAILED.
     */
    long countByProjectIdAndStepAndStatus(UUID projectId, VideoStoryStep step, TraceEventStatus status);

    /** Eventos de un conjunto de proyectos (el panel reduce el último por proyecto). */
    List<VideoStoryTraceEvent> findByProjectIdInOrderByStartedAtAscIdAsc(Collection<UUID> projectIds);
}
