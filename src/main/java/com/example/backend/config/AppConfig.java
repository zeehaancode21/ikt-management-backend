package com.example.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    /**
     * Shared HTTP client (used by the image generation service).
     * The old bean had no timeouts: if the provider stalled, the thread hung forever
     * and the "generation already in progress" flag never cleared, blocking every
     * later image request until a restart.
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(90_000); // image generation can legitimately take up to ~60s
        return new RestTemplate(factory);
    }
}