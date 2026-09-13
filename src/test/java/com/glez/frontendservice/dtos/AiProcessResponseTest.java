package com.glez.frontendservice.dtos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiProcessResponse DTO Tests")
class AiProcessResponseTest {

    @Nested
    @DisplayName("Constructor tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create with no-args constructor")
        void noArgsConstructor_createsResponse() {
            AiProcessResponse response = new AiProcessResponse();
            assertNotNull(response);
            assertNull(response.getOriginalText());
            assertNull(response.getProcessedResult());
            assertNull(response.getModelUsed());
        }

        @Test
        @DisplayName("Should create with all-args constructor")
        void allArgsConstructor_createsResponseWithFields() {
            String originalText = "Original text";
            String processedResult = "Processed result";
            String modelUsed = "test-model";

            AiProcessResponse response = new AiProcessResponse(originalText, processedResult, modelUsed);

            assertEquals(originalText, response.getOriginalText());
            assertEquals(processedResult, response.getProcessedResult());
            assertEquals(modelUsed, response.getModelUsed());
        }
    }

    @Nested
    @DisplayName("Getter and setter tests")
    class GetterSetterTests {

        @Test
        @DisplayName("Should set and get originalText")
        void setOriginalText_getOriginalText_worksCorrectly() {
            AiProcessResponse response = new AiProcessResponse();
            String text = "New original text";

            response.setOriginalText(text);

            assertEquals(text, response.getOriginalText());
        }

        @Test
        @DisplayName("Should set and get processedResult")
        void setProcessedResult_getProcessedResult_worksCorrectly() {
            AiProcessResponse response = new AiProcessResponse();
            String result = "New processed result";

            response.setProcessedResult(result);

            assertEquals(result, response.getProcessedResult());
        }

        @Test
        @DisplayName("Should set and get modelUsed")
        void setModelUsed_getModelUsed_worksCorrectly() {
            AiProcessResponse response = new AiProcessResponse();
            String model = "new-model";

            response.setModelUsed(model);

            assertEquals(model, response.getModelUsed());
        }

        @Test
        @DisplayName("Should handle null values")
        void setters_null_handlesNull() {
            AiProcessResponse response = new AiProcessResponse();
            response.setOriginalText("Initial");
            response.setProcessedResult("Initial");
            response.setModelUsed("Initial");

            response.setOriginalText(null);
            response.setProcessedResult(null);
            response.setModelUsed(null);

            assertNull(response.getOriginalText());
            assertNull(response.getProcessedResult());
            assertNull(response.getModelUsed());
        }
    }

    @Nested
    @DisplayName("Field access tests")
    class FieldAccessTests {

        @Test
        @DisplayName("Should access all fields via getters")
        void getters_returnCorrectValues() {
            AiProcessResponse response = new AiProcessResponse("original", "processed", "test-model");
            assertEquals("original", response.getOriginalText());
            assertEquals("processed", response.getProcessedResult());
            assertEquals("test-model", response.getModelUsed());
        }
    }
}