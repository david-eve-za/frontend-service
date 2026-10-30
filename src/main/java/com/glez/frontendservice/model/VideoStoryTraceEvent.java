package com.glez.frontendservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Un evento de traza persistido de un intento de paso del Asistente de
 * Historias en Video. La lista completa de eventos de un proyecto forma su
 * cronología — el equivalente wizard del {@link ProcessTraceEvent} del
 * pipeline de libros, en tabla PROPIA (FK a {@link VideoStoryProject}) para
 * no tocar en absoluto la maquinaria de trazas del pipeline crítico.
 *
 * <p>Reutiliza {@link TraceEventStatus} (STARTED/COMPLETED/FAILED/SKIPPED/
 * CANCELLED), que es genérico por diseño; el paso es {@link VideoStoryStep}.
 * Tabla NUEVA: hbm2ddl la crea al nacer con sus enums completos (sin el
 * problema del ENUM legacy del pipeline) — cero migraciones.</p>
 */
@Entity
// Índice de cobertura para el conteo de fallos por (proyecto, paso) y el
// orden de la cronología sin cargar los eventos.
@Table(indexes = @Index(name = "idx_wizard_trace_project_step_started", columnList = "project_id, step, started_at"))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VideoStoryTraceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * LAZY: la cronología de un proyecto acumula un evento por intento de
     * paso; arrastrar el proyecto (con sus CLOBs de historia/diseños/
     * assets/escenas) con cada fila solo cuesta. Solo el FK id se lee.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private VideoStoryProject project;

    /** Correlación con el visor de logs: el MDC traceId del asistente ES el id del proyecto. */
    @Column(name = "trace_id", nullable = false)
    private String traceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VideoStoryStep step;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TraceEventStatus status;

    /** Nº de intento del paso: 1 + fallos previos genuinos de (proyecto, paso). */
    @Column(nullable = false)
    private Integer attempt;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(length = 2000)
    private String details;
}
