package com.glez.frontendservice.repository;

import com.glez.frontendservice.model.VideoStoryProject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Proyectos del Asistente de Historias en Video. */
public interface VideoStoryProjectRepository extends JpaRepository<VideoStoryProject, UUID> {

    /** Proyectos completos, más recientes primero. */
    List<VideoStoryProject> findAllByOrderByCreatedAtDesc();
}
