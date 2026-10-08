package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.DashboardStatsDto;
import com.glez.frontendservice.repository.NovelRepository;
import com.glez.frontendservice.repository.NovelVolumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conteo de obras/volúmenes del catálogo para el panel de control, cacheado
 * en "catalogCounts" (TTL corto en la spec de Caffeine como red de
 * seguridad). El bean separado existe porque la anotación no funciona por
 * auto-invocación: {@code DashboardStatsService} la llama a través del
 * proxy. {@link NovelsCatalogSyncService} limpia la caché vía su
 * {@code CacheManager} cuando termina cada corrida de sincronización.
 */
@Service
@RequiredArgsConstructor
public class CatalogStatsService {

    private final NovelRepository novelRepository;
    private final NovelVolumeRepository novelVolumeRepository;

    @Cacheable("catalogCounts")
    @Transactional(readOnly = true)
    public DashboardStatsDto.CatalogStatsDto catalogStats() {
        return new DashboardStatsDto.CatalogStatsDto(novelRepository.count(),
                novelVolumeRepository.count());
    }
}
