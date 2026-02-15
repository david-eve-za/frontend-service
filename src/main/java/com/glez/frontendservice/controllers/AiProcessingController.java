package com.glez.frontendservice.controllers;

import com.glez.frontendservice.dtos.AiProcessRequest;
import com.glez.frontendservice.dtos.AiProcessResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AiProcessingController {

    @PostMapping("/process-data")
    public ResponseEntity<AiProcessResponse> processData(@RequestBody AiProcessRequest request) {
        if (request == null || request.getText() == null || request.getText().isEmpty()) {
            // For the bad request test to pass.
            return ResponseEntity.badRequest().build();
        }

        // Dummy response to make the test pass initially
        AiProcessResponse response = new AiProcessResponse(
                request.getText(),
                "Processed: " + request.getText(),
                request.getParameters() != null ? request.getParameters().get("model") : "default-model"
        );
        return ResponseEntity.ok(response);
    }
}
