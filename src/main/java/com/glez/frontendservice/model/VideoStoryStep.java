package com.glez.frontendservice.model;

/**
 * Pasos del Asistente de Historias en Video, en orden de la cadena: cada
 * paso alimenta al siguiente y la presencia de su output marca el
 * progreso del proyecto.
 */
public enum VideoStoryStep {
    STORY,
    DESIGNS,
    ASSETS,
    SCENES,
    VIDEO
}
