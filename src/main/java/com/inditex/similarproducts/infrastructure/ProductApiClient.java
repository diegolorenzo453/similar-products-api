package com.inditex.similarproducts.infrastructure;

import com.inditex.similarproducts.config.ProductApiProperties;
import com.inditex.similarproducts.generated.model.ProductDetail;
import com.inditex.similarproducts.observability.SimilarProductsMetrics;
import com.inditex.similarproducts.web.ProductNotFoundException;
import com.inditex.similarproducts.web.UpstreamServiceException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class ProductApiClient {

    private static final Logger log = LoggerFactory.getLogger(ProductApiClient.class);
    private static final ParameterizedTypeReference<List<String>> STRING_LIST = new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final ProductApiProperties properties;
    private final Bulkhead productDetailsBulkhead;
    private final SimilarProductsMetrics metrics;

    public ProductApiClient(WebClient productWebClient, ProductApiProperties properties,
            Bulkhead productDetailsBulkhead, SimilarProductsMetrics metrics) {
        this.webClient = productWebClient;
        this.properties = properties;
        this.productDetailsBulkhead = productDetailsBulkhead;
        this.metrics = metrics;
    }

    public Mono<List<String>> findSimilarIds(String productId) {
        return webClient.get()
                .uri("/product/{productId}/similarids", productId)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(STRING_LIST);
                    }
                    if (response.statusCode().value() == HttpStatus.NOT_FOUND.value()) {
                        return response.releaseBody().then(Mono.error(new ProductNotFoundException(productId)));
                    }
                    return response.releaseBody().then(Mono.error(new UpstreamServiceException(
                            "Product service returned " + response.statusCode().value())));
                })
                .timeout(properties.timeout())
                .doOnError(error -> metrics.recordDownstreamError("similar-ids", reason(error)))
                .onErrorMap(TimeoutException.class,
                        error -> new UpstreamServiceException("Product service timed out", error));
    }

    public Mono<ProductDetail> findProduct(String productId) {
        return webClient.get()
                .uri("/product/{productId}", productId)
                .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToMono(ProductDetail.class)
                        : response.releaseBody().then(Mono.empty()))
                .timeout(properties.timeout())
                .transformDeferred(BulkheadOperator.of(productDetailsBulkhead))
                .onErrorResume(error -> {
                    String reason = reason(error);
                    metrics.recordDownstreamError("product-detail", reason);
                    log.warn("Skipping similar product; productId={}, reason={}, message={}",
                            productId, reason, error.getMessage());
                    return Mono.empty();
                });
    }

    private static String reason(Throwable error) {
        if (error instanceof TimeoutException) {
            return "timeout";
        }
        if (error instanceof BulkheadFullException) {
            return "bulkhead-full";
        }
        if (error instanceof ProductNotFoundException) {
            return "not-found";
        }
        return "upstream-error";
    }
}
