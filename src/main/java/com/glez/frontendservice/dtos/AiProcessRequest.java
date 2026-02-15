package com.glez.frontendservice.dtos;

import java.util.Map;

public class AiProcessRequest {
    private String text;
    private Map<String, String> parameters;

    public AiProcessRequest() {
    }

    public AiProcessRequest(String text, Map<String, String> parameters) {
        this.text = text;
        this.parameters = parameters;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
    }
}
