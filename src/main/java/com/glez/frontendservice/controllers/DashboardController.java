package com.glez.frontendservice.controllers;

import com.glez.frontendservice.dtos.DashboardStatsDto;
import com.glez.frontendservice.services.DashboardStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Métricas agregadas del panel de control: estado de la base de "
        + "datos, procesos en ejecución y avance del pipeline de libros")
public class DashboardController {

    private final DashboardStatsService dashboardStatsService;

    @Operation(summary = "Métricas del panel de control",
            description = "Estado de la BD, procesos activos (pipeline + sync del catálogo), "
                    + "contadores por etapa del pipeline, progreso de chunks y tamaño del catálogo.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Métricas agregadas"))
    @GetMapping("/stats")
    public ResponseEntity<DashboardStatsDto> getStats() {
        return ResponseEntity.ok(dashboardStatsService.getStats());
    }
}
