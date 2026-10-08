package com.glez.frontendservice.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Activa la abstracción de caché respaldada por Caffeine (véase spring.cache
 * en application.yml): patrones de limpieza de texto y conteos del catálogo
 * del panel de control.
 *
 * <p>Vive en su propia {@code @Configuration} (y no en {@code App}) para que
 * los slices de test ({@code @DataJpaTest}) no arranquen la infraestructura
 * de caché: el slice no registra un {@code CacheManager} y la caché, de
 * todos modos, no participa en las pruebas de repositorio.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig {
}
