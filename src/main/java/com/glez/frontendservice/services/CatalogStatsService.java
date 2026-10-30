package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.DashboardStatsDto;
import com.glez.frontendservice.repository.NovelRepository;
import com.glez.frontendservice.repository.NovelVolumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Conteo de obras/volúmenes del catálogo y serie de actividad (últimos
 * {@link #ACTIVITY_WINDOW_DAYS} días) para el panel de control, cacheados
 * en "catalogCounts" (TTL corto en la spec de Caffeine como red de
 * seguridad). El bean separado existe porque la anotación no funciona por
 * auto-invocación: {@code DashboardStatsService} la llama a través del
 * proxy. {@link NovelsCatalogSyncService} limpia la caché vía su
 * {@code CacheManager} cuando termina cada corrida de sincronización.
 *
 * <p>La serie de actividad viaja DENTRO del mismo método cacheado (no como
 * un método hermano): dos métodos {@code @Cacheable} sin argumentos en la
 * misma caché colisionarían (ambos usan {@code SimpleKey.EMPTY}) y, además,
 * comparten ciclo de vida — conteos y descubrimientos solo cambian cuando
 * el sync toca el catálogo.</p>
 */
@Service
@RequiredArgsConstructor
public class CatalogStatsService {

    /** Días de historia de descubrimientos que alimenta el gráfico de elscione. */
    public static final int ACTIVITY_WINDOW_DAYS = 14;

    private final NovelRepository novelRepository;
    private final NovelVolumeRepository novelVolumeRepository;
    private final JdbcTemplate jdbcTemplate;

    @Cacheable("catalogCounts")
    @Transactional(readOnly = true)
    public DashboardStatsDto.CatalogStatsDto catalogStats() {
        return new DashboardStatsDto.CatalogStatsDto(
                novelRepository.count(),
                novelVolumeRepository.count(),
                activitySeries());
    }

    /**
     * Descubrimientos por día (obras y volúmenes) de la ventana reciente,
     * con los días sin actividad rellenados a cero para que el gráfico
     * muestre SIEMPRE la misma serie de fechas.
     *
     * <p>GROUP BY nativo con FORMATDATETIME (H2): agrupar por día en JPQL
     * no es portable y el panel ya usa SQL nativo contra H2 en otros
     * agregados. El corte ({@code created_at >= ?}) viaja como parámetro
     * calculado con el mismo ZoneId que etiqueta los días.</p>
     */
    private List<DashboardStatsDto.CatalogActivityPointDto> activitySeries() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate firstDay = today.minusDays(ACTIVITY_WINDOW_DAYS - 1L);
        Timestamp cutoff = Timestamp.from(firstDay.atStartOfDay(zone).toInstant());

        // day ("yyyy-MM-dd") -> [novelas, volúmenes]
        Map<String, long[]> byDay = new HashMap<>();
        countByDay("novel", cutoff, byDay, 0);
        countByDay("novel_volume", cutoff, byDay, 1);

        List<DashboardStatsDto.CatalogActivityPointDto> series = new ArrayList<>(ACTIVITY_WINDOW_DAYS);
        for (int i = 0; i < ACTIVITY_WINDOW_DAYS; i++) {
            long[] counts = byDay.get(firstDay.plusDays(i).toString());
            series.add(new DashboardStatsDto.CatalogActivityPointDto(
                    firstDay.plusDays(i).toString(),
                    counts != null ? counts[0] : 0,
                    counts != null ? counts[1] : 0));
        }
        return series;
    }

    /** Volca en byDay[index] el COUNT(*) por día LOCAL de una tabla del catálogo. */
    private void countByDay(String table, Timestamp cutoff, Map<String, long[]> byDay, int index) {
        // Alias day_key: "day" es palabra reservada en H2 2.x. El CAST es
        // OBLIGATORIO: created_at es TIMESTAMP WITH TIME ZONE y
        // FORMATDATETIME lo renderiza en UTC; con el cast al TIMESTAMP local
        // el día de la clave coincide con el LocalDate de la serie (sin él,
        // los conteos de las 18:00-24:00 locales caían en el día UTC
        // siguiente y desaparecían del gráfico).
        jdbcTemplate.query(
                "SELECT FORMATDATETIME(CAST(created_at AS TIMESTAMP), 'yyyy-MM-dd') AS day_key, COUNT(*) AS total FROM " + table
                        + " WHERE created_at >= ? GROUP BY FORMATDATETIME(CAST(created_at AS TIMESTAMP), 'yyyy-MM-dd')",
                rs -> {
                    byDay.computeIfAbsent(rs.getString("day_key"), key -> new long[2])[index]
                            += rs.getLong("total");
                },
                cutoff);
    }
}
