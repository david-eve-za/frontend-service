package com.glez.frontendservice.controllers;

import com.glez.frontendservice.dtos.AiProcessRequest;
import com.glez.frontendservice.dtos.AiProcessResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiProcessingController Tests")
class AiProcessingControllerTest {

    private AiProcessingController controller;

    @BeforeEach
    void setUp() {
        controller = new AiProcessingController();
    }

    @Nested
    @DisplayName("processData method tests")
    class ProcessDataTests {

        @Test
        @DisplayName("Should return 400 for null request")
        void processData_withNullRequest_returnsBadRequest() {
            ResponseEntity<AiProcessResponse> response = controller.processData(null);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNull(response.getBody());
        }

        @Test
        @DisplayName("Should return 400 for request with null text")
        void processData_withNullText_returnsBadRequest() {
            AiProcessRequest request = new AiProcessRequest();
            request.setText(null);

            ResponseEntity<AiProcessResponse> response = controller.processData(request);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNull(response.getBody());
        }

        @Test
        @DisplayName("Should return 400 for request with empty text")
        void processData_withEmptyText_returnsBadRequest() {
            AiProcessRequest request = new AiProcessRequest();
            request.setText("");

            ResponseEntity<AiProcessResponse> response = controller.processData(request);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNull(response.getBody());
        }

        @Test
        @DisplayName("Should return 200 with processed response for valid request")
        void processData_withValidRequest_returnsOkResponse() {
            AiProcessRequest request = new AiProcessRequest();
            request.setText("Test input text");
            request.setParameters(java.util.Map.of("model", "test-model"));

            ResponseEntity<AiProcessResponse> response = controller.processData(request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("Test input text", response.getBody().getOriginalText());
            assertEquals("Processed: Test input text", response.getBody().getProcessedResult());
            assertEquals("test-model", response.getBody().getModelUsed());
        }

        @Test
        @DisplayName("Should use default model when parameters is null")
        void processData_withNullParameters_usesDefaultModel() {
            AiProcessRequest request = new AiProcessRequest();
            request.setText("Test input text");
            request.setParameters(null);

            ResponseEntity<AiProcessResponse> response = controller.processData(request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("default-model", response.getBody().getModelUsed());
        }

        @Test
        @DisplayName("Should return null model when parameters doesn't contain model key")
        void processData_withParametersWithoutModel_returnsNullModel() {
            AiProcessRequest request = new AiProcessRequest();
            request.setText("Test input text");
            request.setParameters(java.util.Map.of("other", "value"));

            ResponseEntity<AiProcessResponse> response = controller.processData(request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertNull(response.getBody().getModelUsed());
        }

        @Test
        @DisplayName("Should handle whitespace-only text as valid")
        void processData_withWhitespaceText_processesText() {
            AiProcessRequest request = new AiProcessRequest();
            request.setText("   ");

            ResponseEntity<AiProcessResponse> response = controller.processData(request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("   ", response.getBody().getOriginalText());
        }
    }
}