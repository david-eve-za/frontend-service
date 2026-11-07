package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.AiProcessRequest;
import com.glez.frontendservice.dtos.AiProcessResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Service for AI-powered data processing.
 * Delegates to NvidiaAiService for actual model interaction.
 */
@Service
public class AiProcessingService {

    private static final Logger log = LoggerFactory.getLogger(AiProcessingService.class);

    private final NvidiaAiService nvidiaAiService;

    public AiProcessingService(NvidiaAiService nvidiaAiService) {
        this.nvidiaAiService = nvidiaAiService;
    }

    /**
     * Process text using AI model.
     *
     * @param request the processing request containing text and optional parameters
     * @return the processing response with original text, processed result, and model used
     */
    public AiProcessResponse processData(AiProcessRequest request) {
        String text = request.getText();
        Map<String, String> parameters = request.getParameters();

        // Determine model to use (default to first available NVIDIA model)
        String model = "default-model";
        if (parameters != null && parameters.containsKey("model")) {
            model = parameters.get("model");
        }

        log.info("[ai-processing] Processing text of {} characters with model: {}", text.length(), model);

        try {
            // Use NvidiaAiService to process the text
            // For now, use the translation capability as a general processing example
            // The prompt instructs the model to process/transform the input text
            String prompt = buildProcessingPrompt(text, parameters);
            String processedResult = nvidiaAiService.callModelWithRotation(prompt);

            log.info("[ai-processing] Successfully processed text, result length: {}", processedResult.length());

            return new AiProcessResponse(text, processedResult, model);

        } catch (Exception e) {
            log.error("[ai-processing] Error processing text: {}", e.getMessage(), e);
            // Return error response instead of throwing to keep API simple
            return new AiProcessResponse(
                    text,
                    "Error processing request: " + e.getMessage(),
                    model
            );
        }
    }

    /**
     * Build the prompt for AI processing based on request parameters.
     */
    private String buildProcessingPrompt(String text, Map<String, String> parameters) {
        StringBuilder prompt = new StringBuilder();

        // Base instruction
        prompt.append("Process the following text according to the instructions below.\n\n");

        // Add specific processing instructions from parameters
        if (parameters != null) {
            // Handle task parameter with switch expression
            if (parameters.containsKey("task")) {
                switch (parameters.get("task")) {
                    case "translate to Spanish" -> {
                        prompt.append("Task: Translate to Spanish\n\n");
                        prompt.append("Output language: Spanish\n\n");
                    }
                    case "translate to English" -> {
                        prompt.append("Task: Translate to English\n\n");
                        prompt.append("Output language: English\n\n");
                    }
                    case "extract key points and main ideas" -> {
                        prompt.append("Task: Extract key points and main ideas\n\n");
                    }
                    case "analyze sentiment and emotional tone" -> {
                        prompt.append("Task: Analyze sentiment and emotional tone\n\n");
                    }
                    default -> {
                        // Custom task - will be handled below
                    }
                }
            }

            // Handle other parameters
            if (parameters.containsKey("instruction")) {
                prompt.append("Instruction: ").append(parameters.get("instruction")).append("\n\n");
            }
            if (parameters.containsKey("style")) {
                prompt.append("Style: ").append(parameters.get("style")).append("\n\n");
            }
            if (parameters.containsKey("language") && !parameters.containsKey("task")) {
                prompt.append("Output language: ").append(parameters.get("language")).append("\n\n");
            }
        }

        // Default instruction if none provided
        if (parameters == null || (!parameters.containsKey("instruction") && !parameters.containsKey("task"))) {
            prompt.append("Default task: Summarize, analyze, and provide key insights from the text.\n\n");
        }

        prompt.append("Text to process:\n```\n").append(text).append("\n```\n\n");
        prompt.append("Provide only the processed result without additional commentary.");

        return prompt.toString();
    }
}