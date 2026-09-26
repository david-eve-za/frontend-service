package com.glez.frontendservice.exception;

public class NovelsApiException extends RuntimeException {

    public NovelsApiException(String message) {
        super(message);
    }

    public NovelsApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
