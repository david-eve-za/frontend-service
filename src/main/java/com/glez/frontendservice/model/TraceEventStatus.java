package com.glez.frontendservice.model;

/**
 * Status of a single pipeline step attempt recorded in a trace event.
 */
public enum TraceEventStatus {
    STARTED,
    COMPLETED,
    FAILED,
    SKIPPED,
    /** Step interrupted by an explicit user stop: not a genuine failure. */
    CANCELLED
}
