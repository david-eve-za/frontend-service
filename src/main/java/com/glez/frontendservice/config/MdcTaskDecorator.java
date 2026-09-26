package com.glez.frontendservice.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Propagates the MDC logging context from the submitting thread to the
 * executor thread (and restores it afterwards), so asynchronous pipeline
 * steps keep the correlation keys (bookId, traceId, step) in every log line.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                } else {
                    MDC.clear();
                }
                runnable.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
