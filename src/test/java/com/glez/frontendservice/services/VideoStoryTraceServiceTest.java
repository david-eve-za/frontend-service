package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.VideoStoryTraceDto;
import com.glez.frontendservice.model.VideoStoryProject;
import com.glez.frontendservice.model.VideoStoryStep;
import com.glez.frontendservice.model.VideoStoryTraceEvent;
import com.glez.frontendservice.repository.VideoStoryProjectRepository;
import com.glez.frontendservice.repository.VideoStoryTraceEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests del servicio de trazas del Asistente de Historias: el ciclo
 * STARTED → COMPLETED/FAILED por intento de paso, la cronología con
 * duración calculada y el último estado por proyecto para el panel.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Video story trace service tests")
class VideoStoryTraceServiceTest {

    @Mock
    private VideoStoryTraceEventRepository traceEventRepository;

    @Mock
    private VideoStoryProjectRepository projectRepository;

    private VideoStoryTraceService traceService;

    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        traceService = new VideoStoryTraceService(traceEventRepository, projectRepository);
    }

    /** Evento guardado por startStep, con la forma que devuelve el mock. */
    private VideoStoryTraceEvent startedEvent(int attempt) {
        VideoStoryTraceEvent event = new VideoStoryTraceEvent();
        event.setId(UUID.randomUUID());
        event.setTraceId(projectId.toString());
        event.setStep(VideoStoryStep.DESIGNS);
        event.setAttempt(attempt);
        event.setStartedAt(Instant.now().minusSeconds(60));
        return event;
    }

    @Test
    @DisplayName("startStep: intento = 1 + fallos previos del (proyecto, paso), con traceId=projectId")
    void startStep_numbersAttemptFromPreviousGenuineFailures() {
        when(projectRepository.getReferenceById(projectId)).thenReturn(new VideoStoryProject());
        when(traceEventRepository.countByProjectIdAndStepAndStatus(eq(projectId), eq(VideoStoryStep.DESIGNS), any()))
                .thenReturn(2L);
        when(traceEventRepository.save(any(VideoStoryTraceEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        VideoStoryTraceEvent event = traceService.startStep(projectId, VideoStoryStep.DESIGNS, "estilo «pixar»");

        assertEquals(3, event.getAttempt(), "2 fallos previos → intento 3");
        assertEquals(projectId.toString(), event.getTraceId(),
                "El traceId del asistente ES el id del proyecto (correlación con el visor de logs)");
        assertEquals(VideoStoryStep.DESIGNS, event.getStep());
        assertEquals("estilo «pixar»", event.getDetails());
        verify(traceEventRepository).save(event);
    }

    @Test
    @DisplayName("completeStep: cierra el intento con finishedAt y sobreescritura de detalles")
    void completeStep_closesTheAttempt() {
        VideoStoryTraceEvent event = startedEvent(1);
        when(traceEventRepository.save(any(VideoStoryTraceEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        traceService.completeStep(event, "4500 caracteres de diseños");

        assertEquals("COMPLETED", event.getStatus().name());
        assertEquals("4500 caracteres de diseños", event.getDetails());
        assertTrue(event.getFinishedAt() != null, "El cierre deja finishedAt");
        verify(traceEventRepository).save(event);
    }

    @Test
    @DisplayName("failStep: cierra el intento como FAILED persistiendo la causa")
    void failStep_closesTheAttemptWithTheCause() {
        VideoStoryTraceEvent event = startedEvent(1);
        when(traceEventRepository.save(any(VideoStoryTraceEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        traceService.failStep(event, new IllegalStateException("La generación con la IA falló"));

        assertEquals("FAILED", event.getStatus().name());
        assertEquals("La generación con la IA falló", event.getErrorMessage());
        verify(traceEventRepository).save(event);
    }

    @Test
    @DisplayName("timeline: proyecta los eventos con la duración ya calculada")
    void timeline_mapsEventsWithDuration() {
        when(projectRepository.existsById(projectId)).thenReturn(true);
        when(projectRepository.getReferenceById(projectId)).thenReturn(new VideoStoryProject());

        VideoStoryTraceEvent event = startedEvent(1);
        event.setStatus(com.glez.frontendservice.model.TraceEventStatus.COMPLETED);
        event.setFinishedAt(event.getStartedAt().plusSeconds(90));
        when(traceEventRepository.findByProjectOrderByStartedAtAscIdAsc(any(VideoStoryProject.class)))
                .thenReturn(List.of(event));

        List<VideoStoryTraceDto> timeline = traceService.timeline(projectId);

        assertEquals(1, timeline.size());
        assertEquals("DESIGNS", timeline.get(0).step());
        assertEquals("COMPLETED", timeline.get(0).status());
        assertEquals(90000L, timeline.get(0).durationMs(), "Duración = finishedAt - startedAt");
    }

    @Test
    @DisplayName("timeline: proyecto inexistente falla con mensaje claro")
    void timeline_unknownProject_fails() {
        when(projectRepository.existsById(projectId)).thenReturn(false);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> traceService.timeline(projectId));
        assertTrue(e.getMessage().contains("no encontrado"), e.getMessage());
    }

    @Test
    @DisplayName("lastStatuses: reduce el último evento por proyecto (el panel marca Fallido)")
    void lastStatuses_reducesLastEventPerProject() {
        UUID other = UUID.randomUUID();
        VideoStoryProject project = new VideoStoryProject();
        project.setId(other);
        VideoStoryTraceEvent first = startedEvent(1);
        first.setProject(project);
        VideoStoryTraceEvent last = startedEvent(2);
        last.setProject(project);
        last.setStatus(com.glez.frontendservice.model.TraceEventStatus.FAILED);
        when(traceEventRepository.findByProjectIdInOrderByStartedAtAscIdAsc(any()))
                .thenReturn(List.of(first, last));

        Map<UUID, com.glez.frontendservice.model.TraceEventStatus> lastStatuses =
                traceService.lastStatuses(List.of(other));

        // Llegan ordenados por fecha: el último del proyecto manda.
        assertEquals(1, lastStatuses.size());
        assertEquals("FAILED", lastStatuses.values().iterator().next().name());
    }
}
