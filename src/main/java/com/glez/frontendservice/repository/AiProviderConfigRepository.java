package com.glez.frontendservice.repository;

import com.glez.frontendservice.model.AiProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;

/** Selección de proveedor de IA por caso de uso (fila única id=1). */
public interface AiProviderConfigRepository extends JpaRepository<AiProviderConfig, Long> {
}
