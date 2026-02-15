package com.glez.frontendservice.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class AiProcessingControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void processData_shouldReturnBadRequest_whenNoDataProvided() throws Exception {
        mockMvc.perform(post("/api/process-data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")) // Empty content for failing test
                .andExpect(status().isBadRequest());
    }

    @Test
    void processData_shouldReturnOk_whenValidDataProvided() throws Exception {
        String requestBody = "{\"text\": \"hello world\", \"parameters\": {\"model\": \"test-model\"}}";
        mockMvc.perform(post("/api/process-data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());
    }
}
