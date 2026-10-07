package com.glez.frontendservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // SPA fallback - serve index.html for all non-API routes
        registry.addViewController("/book-translator/**").setViewName("forward:/index.html");
        registry.addViewController("/uikit/**").setViewName("forward:/index.html");
        registry.addViewController("/pages/**").setViewName("forward:/index.html");
        registry.addViewController("/auth/**").setViewName("forward:/index.html");
        registry.addViewController("/hierarchy/**").setViewName("forward:/index.html");
        registry.addViewController("/documentation/**").setViewName("forward:/index.html");
    }

    /**
     * Executor del pipeline sobre JAVA 21 VIRTUAL THREADS: todo el trabajo
     * es I/O (API de NVIDIA, Edge TTS, LLM), así que cada tarea aparca
     * gratis durante las esperas en lugar de inmovilizar un hilo de
     * plataforma. La concurrencia NO la limita este executor sino la
     * cuota de la API (NvidiaRateLimiter, RPM) — el patrón recomendado
     * para virtual threads: hilos ilimitados, I/O gobernado por el
     * recurso externo. El MdcTaskDecorator preserva el contexto
     * bookId/traceId/step en cada virtual thread para el Visor de Logs.
     */
    @Bean("bookProcessingExecutor")
    public Executor bookProcessingExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("book-processing-");
        executor.setVirtualThreads(true);
        executor.setTaskDecorator(new MdcTaskDecorator());
        return executor;
    }
}
