package com.glez.frontendservice.tts;

/**
 * Base exception for all errors raised by the Edge TTS client.
 */
public class EdgeTtsException extends RuntimeException {

    public EdgeTtsException(String message) {
        super(message);
    }

    public EdgeTtsException(String message, Throwable cause) {
        super(message, cause);
    }

    static final class NoAudioReceived extends EdgeTtsException {
        NoAudioReceived(String message) {
            super(message);
        }
    }

    static final class UnexpectedResponse extends EdgeTtsException {
        UnexpectedResponse(String message) {
            super(message);
        }
    }

    static final class UnknownResponse extends EdgeTtsException {
        UnknownResponse(String message) {
            super(message);
        }
    }

    static final class WebSocketError extends EdgeTtsException {
        WebSocketError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class SkewAdjustmentError extends EdgeTtsException {
        SkewAdjustmentError(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
