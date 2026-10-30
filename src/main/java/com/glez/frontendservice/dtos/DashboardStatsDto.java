package com.glez.frontendservice.dtos;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Métricas agregadas del panel de control: estado de la base de datos,
 * procesos en ejecución, pipeline de libros, chunks, catálogo y asistente
 * de historias.
 */
public record DashboardStatsDto(
        DatabaseStatusDto database,
        ProcessStatusDto processes,
        BookStatsDto books,
        ChunkStatsDto chunks,
        CatalogStatsDto catalog,
        WizardStatsDto wizard) {

    /** Estado de la conexión a la base de datos H2 y del archivo en disco. */
    public record DatabaseStatusDto(
            String status,
            String product,
            String version,
            String url,
            Long fileSizeBytes,
            String error) {
    }

    /** Libro que está siendo procesado por esta instancia del pipeline. */
    public record RunningProcessDto(
            UUID bookId,
            String name,
            String status,
            String currentStep) {
    }

    /**
     * Libro con ciclo de vida activo (todo estado salvo COMPLETED) para el
     * panel de procesos: estado persistido + flag running (corrida real en
     * memoria de esta instancia; un PROCESSING sin corrida es un huérfano
     * de crash pendiente de curación).
     */
    public record BookProcessDto(
            UUID bookId,
            String name,
            String status,
            String currentStep,
            boolean running) {
    }

    /** Procesos activos: pipeline de libros + sincronización + asistente de historias. */
    public record ProcessStatusDto(
            List<RunningProcessDto> running,
            NovelsCatalogSyncStatus catalogSync,
            List<BookProcessDto> books,
            List<WizardProcessDto> wizardProcesses) {
    }

    /**
     * Proyecto del Asistente de Historias con la cadena incompleta, para
     * el panel de procesos: estado derivado (RUNNING si hay un paso en
     * curso en memoria de esta instancia; FAILED si su último intento de
     * paso terminó fallido; IN_PROGRESS si ya tiene outputs; NEW si solo
     * existe), paso siguiente pendiente y progreso de la cadena (n/5).
     */
    public record WizardProcessDto(
            UUID projectId,
            String title,
            String state,
            String currentStep,
            int stepsDone,
            int stepsTotal,
            boolean running,
            java.time.Instant updatedAt) {
    }

    /**
     * Contadores del pipeline. Las etapas son inclusivas (un libro
     * traducido también fue spliteado) y las semánticas coinciden con
     * NovelsCatalogQueryService: split = tiene chunks, traducido = todos
     * los chunks COMPLETED, listo para audio = traducido sin audio aún.
     */
    public record BookStatsDto(
            long total,
            long split,
            long translated,
            long audioCreated,
            long readyForAudio,
            Map<String, Long> byStatus) {
    }

    /** Progreso global de traducción a nivel de chunks. */
    public record ChunkStatsDto(
            long total,
            long completed) {
    }

    /** Tamaño del catálogo de novelas sincronizado + actividad reciente. */
    public record CatalogStatsDto(
            long novels,
            long volumes,
            List<CatalogActivityPointDto> activity) {
    }

    /**
     * Descubrimientos del catálogo de elscione en un día: obras y
     * volúmenes creados (vistos por primera vez por el sync) en esa
     * fecha. Alimenta el gráfico de monitoreo del panel.
     */
    public record CatalogActivityPointDto(
            String day,
            long novels,
            long volumes) {
    }

    /** Métricas del Asistente de Historias para el panel de estado. */
    public record WizardStatsDto(
            long totalProjects,
            long completedChains,
            long stepsCompleted,
            long stepsFailed,
            List<WizardActivityPointDto> activity) {
    }

    /**
     * Actividad del asistente en un día: proyectos creados y pasos
     * completados/fallidos (por el resultado del intento). Los días sin
     * actividad llegan ya rellenados a cero: la serie de 14 días es
     * continua, igual que la del catálogo.
     */
    public record WizardActivityPointDto(
            String day,
            long createdProjects,
            long completedSteps,
            long failedSteps) {
    }
}
