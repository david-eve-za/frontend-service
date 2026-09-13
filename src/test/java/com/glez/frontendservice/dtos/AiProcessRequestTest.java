package com.glez.frontendservice.dtos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiProcessRequest DTO Tests")
class AiProcessRequestTest {

    @Nested
    @DisplayName("Constructor tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create with no-args constructor")
        void noArgsConstructor_createsRequest() {
            AiProcessRequest request = new AiProcessRequest();
            assertNotNull(request);
            assertNull(request.getText());
            assertNull(request.getParameters());
        }

        @Test
        @DisplayName("Should create with all-args constructor")
        void allArgsConstructor_createsRequestWithFields() {
            String text = "Test text";
            Map<String, String> parameters = new HashMap<>();
            parameters.put("model", "test-model");
            parameters.put("temperature", "0.5");

            AiProcessRequest request = new AiProcessRequest(text, parameters);

            assertEquals(text, request.getText());
            assertEquals(parameters, request.getParameters());
        }
    }

    @Nested
    @DisplayName("Getter and setter tests")
    class GetterSetterTests {

        @Test
        @DisplayName("Should set and get text")
        void setText_getText_worksCorrectly() {
            AiProcessRequest request = new AiProcessRequest();
            String text = "New text content";

            request.setText(text);

            assertEquals(text, request.getText());
        }

        @Test
        @DisplayName("Should set and get parameters")
        void setParameters_getParameters_worksCorrectly() {
            AiProcessRequest request = new AiProcessRequest();
            Map<String, String> parameters = new HashMap<>();
            parameters.put("key1", "value1");
            parameters.put("key2", "value2");

            request.setParameters(parameters);

            assertEquals(parameters, request.getParameters());
        }

        @Test
        @DisplayName("Should handle null text")
        void setText_null_handlesNull() {
            AiProcessRequest request = new AiProcessRequest();
            request.setText("Initial");
            request.setText(null);

            assertNull(request.getText());
        }

        @Test
        @DisplayName("Should handle null parameters")
        void setParameters_null_handlesNull() {
            AiProcessRequest request = new AiProcessRequest();
            request.setParameters(new HashMap<>());
            request.setParameters(null);

            assertNull(request.getParameters());
        }
    }

    @Nested
    @DisplayName("Field access tests")
    class FieldAccessTests {

        @Test
        @DisplayName("Should access text field via getter")
        void getText_returnsCorrectValue() {
            AiProcessRequest request = new AiProcessRequest("test text", Map.of("model", "test"));
            assertEquals("test text", request.getText());
        }

        @Test
        @DisplayName("Should access parameters field via getter")
        void getParameters_returnsCorrectValue() {
            Map<String, String> params = Map.of("model", "test-model", "temp", "0.7");
            AiProcessRequest request = new AiProcessRequest("text", params);
            assertEquals(params, request.getParameters());
            assertEquals("test-model", request.getParameters().get("model"));
        }
    }
}