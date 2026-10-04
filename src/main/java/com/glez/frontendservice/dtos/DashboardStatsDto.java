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

    /** Procesos activos: pipeline de libros + sincronización del catálogo. */
    public record ProcessStatusDto(
            List<RunningProcessDto> running,
            NovelsCatalogSyncStatus catalogSync) {
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

    /** Tamaño del catálogo de novelas sincronizado. */
    public record CatalogStatsDto(
            long novels,
            long volumes) {
    }
}
