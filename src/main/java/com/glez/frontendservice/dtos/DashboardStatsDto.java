package com.glez.frontendservice.dtos;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Métricas agregadas del panel de control: estado de la base de datos,
 * procesos en ejecución, pipeline de libros, chunks y catálogo.
 */
public record DashboardStatsDto(
        DatabaseStatusDto database,
        ProcessStatusDto processes,
        BookStatsDto books,
        ChunkStatsDto chunks,
        CatalogStatsDto catalog) {

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

    /** Procesos activos: pipeline de libros + sincronización del catálogo. */
    public record ProcessStatusDto(
            List<RunningProcessDto> running,
            NovelsCatalogSyncStatus catalogSync,
            List<BookProcessDto> books) {
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
}
