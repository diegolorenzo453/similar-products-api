package com.inditex.similarproducts.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clients.product-api")
public record ProductApiProperties(String baseUrl, Duration timeout, int maxConcurrency) {

    public ProductApiProperties {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("max-concurrency must be greater than zero");
        }
    }
}
