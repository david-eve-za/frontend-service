package com.glez.frontendservice.crawler;

/**
 * Exception thrown by {@link SourceCrawler} implementations.
 * Wraps underlying errors with source context.
 */
public class CrawlerException extends RuntimeException {

    private final String sourceId;
    private final String operation;
    private final boolean retryable;

    public CrawlerException(String sourceId, String operation, String message, Throwable cause) {
        super(message, cause);
        this.sourceId = sourceId;
        this.operation = operation;
        this.retryable = isRetryableCause(cause);
    }

    public CrawlerException(String sourceId, String operation, String message) {
        this(sourceId, operation, message, null);
    }

    public CrawlerException(String sourceId, String operation, Throwable cause) {
        this(sourceId, operation, cause.getMessage(), cause);
    }

    private static boolean isRetryableCause(Throwable cause) {
        if (cause == null) return false;
        String msg = cause.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("timeout") ||
               lower.contains("connection reset") ||
               lower.contains("connection refused") ||
               lower.contains("503") ||
               lower.contains("502") ||
               lower.contains("504") ||
               lower.contains("rate limit") ||
               lower.contains("too many requests");
    }

    public String getSourceId() { return sourceId; }
    public String getOperation() { return operation; }
    public boolean isRetryable() { return retryable; }

    @Override
    public String getMessage() {
        return String.format("[%s] %s: %s", sourceId, operation, super.getMessage());
    }
}