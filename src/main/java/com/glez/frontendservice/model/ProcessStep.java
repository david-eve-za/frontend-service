package com.glez.frontendservice.model;

/**
 * Steps of the book processing pipeline, in execution order.
 */
public enum ProcessStep {
    EXTRACT,
    SPLIT,
    TRANSLATE,
    AUDIO,
    FINALIZE
}
