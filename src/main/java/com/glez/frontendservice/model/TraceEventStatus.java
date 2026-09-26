package com.glez.frontendservice.model;

/**
 * Status of a single pipeline step attempt recorded in a trace event.
 */
public enum TraceEventStatus {
    STARTED,
    COMPLETED,
    FAILED,
    SKIPPED
}
