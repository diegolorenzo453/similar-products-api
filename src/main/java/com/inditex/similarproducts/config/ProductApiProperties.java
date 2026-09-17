package com.inditex.similarproducts.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clients.product-api")
public record ProductApiProperties(
        String baseUrl,
        Duration timeout,
        Duration operationTimeout,
        int maxConcurrency,
        int maxConcurrentCalls) {

    public ProductApiProperties {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be greater than zero");
        }
        if (operationTimeout == null || operationTimeout.isNegative() || operationTimeout.isZero()) {
            throw new IllegalArgumentException("operation-timeout must be greater than zero");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("max-concurrency must be greater than zero");
        }
        if (maxConcurrentCalls < 1) {
            throw new IllegalArgumentException("max-concurrent-calls must be greater than zero");
        }
    }
}
