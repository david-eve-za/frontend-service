package com.glez.frontendservice;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel; // Import GoogleGenAiChatModel
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.mockito.Mockito;

@Configuration
public class TestConfig {

    @Bean
    @Primary
    public GoogleGenAiChatModel mockGoogleGenAiChatModel() { // Change return type and method name
        return Mockito.mock(GoogleGenAiChatModel.class);
    }
}
