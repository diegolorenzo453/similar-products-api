package com.inditex.similarproducts.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class SimilarProductsMetrics {

    private final MeterRegistry registry;

    public SimilarProductsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordOperation(Duration duration, String outcome) {
        Timer.builder("similar_products.operation.duration")
                .description("End-to-end similar-products operation latency")
                .tag("outcome", outcome)
                .register(registry)
                .record(duration);
    }

    public void recordResult(boolean partial) {
        Counter.builder("similar_products.responses")
                .description("Similar-products responses by completeness")
                .tag("result", partial ? "partial" : "complete")
                .register(registry)
                .increment();
    }

    public void recordDownstreamError(String operation, String reason) {
        Counter.builder("similar_products.downstream.errors")
                .description("Downstream product API errors")
                .tag("operation", operation)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
