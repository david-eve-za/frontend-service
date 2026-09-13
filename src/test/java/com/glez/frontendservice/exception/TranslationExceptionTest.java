package com.glez.frontendservice.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TranslationException Tests")
class TranslationExceptionTest {

    @Nested
    @DisplayName("Constructor tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create exception with message")
        void constructor_withMessage_createsException() {
            String message = "Translation failed";
            TranslationException exception = new TranslationException(message);

            assertEquals(message, exception.getMessage());
            assertNull(exception.getCause());
        }

        @Test
        @DisplayName("Should create exception with message and cause")
        void constructor_withMessageAndCause_createsException() {
            String message = "Translation failed";
            Throwable cause = new RuntimeException("Root cause");
            TranslationException exception = new TranslationException(message, cause);

            assertEquals(message, exception.getMessage());
            assertEquals(cause, exception.getCause());
        }

        @Test
        @DisplayName("Should handle null message")
        void constructor_nullMessage_handlesNull() {
            TranslationException exception = new TranslationException((String) null);

            assertNull(exception.getMessage());
        }
    }

    @Nested
    @DisplayName("Annotation tests")
    class AnnotationTests {

        @Test
        @DisplayName("Should have ResponseStatus annotation with INTERNAL_SERVER_ERROR")
        void hasResponseStatusAnnotation() {
            ResponseStatus annotation = TranslationException.class.getAnnotation(ResponseStatus.class);
            
            assertNotNull(annotation);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, annotation.value());
        }
    }

    @Nested
    @DisplayName("Inheritance tests")
    class InheritanceTests {

        @Test
        @DisplayName("Should extend RuntimeException")
        void extendsRuntimeException() {
            TranslationException exception = new TranslationException("Test");
            
            assertTrue(exception instanceof RuntimeException);
        }

        @Test
        @DisplayName("Should be throwable")
        void isThrowable() {
            TranslationException exception = new TranslationException("Test");
            
            assertThrows(TranslationException.class, () -> { throw exception; });
        }
    }
}