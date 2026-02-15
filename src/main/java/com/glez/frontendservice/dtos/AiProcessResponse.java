package com.glez.frontendservice.dtos;

public class AiProcessResponse {
    private String originalText;
    private String processedResult;
    private String modelUsed;

    public AiProcessResponse() {
    }

    public AiProcessResponse(String originalText, String processedResult, String modelUsed) {
        this.originalText = originalText;
        this.processedResult = processedResult;
        this.modelUsed = modelUsed;
    }

    public String getOriginalText() {
        return originalText;
    }

    public void setOriginalText(String originalText) {
        this.originalText = originalText;
    }

    public String getProcessedResult() {
        return processedResult;
    }

    public void setProcessedResult(String processedResult) {
        this.processedResult = processedResult;
    }

    public String getModelUsed() {
        return modelUsed;
    }

    public void setModelUsed(String modelUsed) {
        this.modelUsed = modelUsed;
    }
}
