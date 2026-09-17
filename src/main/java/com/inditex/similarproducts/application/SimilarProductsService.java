package com.inditex.similarproducts.application;

import com.inditex.similarproducts.config.ProductApiProperties;
import com.inditex.similarproducts.generated.model.ProductDetail;
import com.inditex.similarproducts.infrastructure.ProductApiClient;
import com.inditex.similarproducts.observability.SimilarProductsMetrics;
import com.inditex.similarproducts.web.UpstreamServiceException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class SimilarProductsService {

    private static final Logger log = LoggerFactory.getLogger(SimilarProductsService.class);

    private final ProductApiClient productApiClient;
    private final int maxConcurrency;
    private final Duration operationTimeout;
    private final SimilarProductsMetrics metrics;

    public SimilarProductsService(ProductApiClient productApiClient, ProductApiProperties properties,
            SimilarProductsMetrics metrics) {
        this.productApiClient = productApiClient;
        this.maxConcurrency = properties.maxConcurrency();
        this.operationTimeout = properties.operationTimeout();
        this.metrics = metrics;
    }

    public Mono<List<ProductDetail>> findSimilarProducts(String productId) {
        return Mono.defer(() -> {
            long startedAt = System.nanoTime();
            return productApiClient.findSimilarIds(productId)
                    .timeout(operationTimeout)
                    .map(LinkedHashSet::new)
                    .flatMap(ids -> collectWithinBudget(ids, startedAt)
                            .doOnNext(products -> recordResult(productId, ids.size(), products.size())))
                    .onErrorMap(TimeoutException.class,
                            error -> new UpstreamServiceException("Similar-products operation timed out", error))
                    .doOnError(error -> log.warn("Similar-products request failed; productId={}, reason={}",
                            productId, error.getClass().getSimpleName()))
                    .doOnSuccess(ignored -> metrics.recordOperation(elapsed(startedAt), "success"))
                    .doOnError(ignored -> metrics.recordOperation(elapsed(startedAt), "error"));
        });
    }

    private Mono<List<ProductDetail>> collectWithinBudget(LinkedHashSet<String> ids, long startedAt) {
        Duration remaining = operationTimeout.minus(elapsed(startedAt));
        if (remaining.isNegative() || remaining.isZero()) {
            return Mono.just(List.of());
        }
        return Flux.fromIterable(ids)
                .flatMapSequential(productApiClient::findProduct, maxConcurrency)
                .take(remaining)
                .collectList();
    }

    private void recordResult(String productId, int requested, int returned) {
        boolean partial = returned < requested;
        metrics.recordResult(partial);
        log.info("Similar-products response; productId={}, requested={}, returned={}, partial={}",
                productId, requested, returned, partial);
    }

    private static Duration elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }
}
