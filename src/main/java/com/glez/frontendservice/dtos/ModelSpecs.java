package com.glez.frontendservice.dtos;

import java.util.List;

/**
 * Especificaciones y estado de disponibilidad de un modelo de IA, tal como
 * las investiga {@code ModelInspectorService} ANTES de usarlo: quién lo
 * sirve, si está online/descargado, y sus capacidades (contexto, tamaño,
 * cuantización, habilidades) para dimensionar prompts y presupuestos de
 * salida con datos reales en lugar de supuestos.
 *
 * <p><b>source</b> distingue las especificaciones REALES (LIVE_SPECS: p. ej.
 * el contexto de un modelo local de Ollama leído de {@code /api/show}) de
 * las CONFIGURADAS (la API de NVIDIA no expone contexto/salida por modelo,
 * así que el pool remoto se dimensiona con las heurísticas documentadas de
 * {@code app.nvidia.*}).</p>
 */
public record ModelSpecs(
        String name,
        String provider,
        boolean online,
        boolean pulled,
        boolean loadedInMemory,
        boolean verifiedInCatalog,
        Long contextLength,
        Long parameterCount,
        String quantizationLevel,
        String family,
        List<String> capabilities,
        String source) {
}
