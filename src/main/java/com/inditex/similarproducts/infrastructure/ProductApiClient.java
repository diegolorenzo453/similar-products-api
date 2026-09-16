package com.inditex.similarproducts.infrastructure;

import com.inditex.similarproducts.config.ProductApiProperties;
import com.inditex.similarproducts.domain.ProductDetail;
import com.inditex.similarproducts.web.ProductNotFoundException;
import com.inditex.similarproducts.web.UpstreamServiceException;
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

    public ProductApiClient(WebClient productWebClient, ProductApiProperties properties) {
        this.webClient = productWebClient;
        this.properties = properties;
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
                .onErrorResume(error -> {
                    log.warn("Skipping unavailable similar product {}: {}", productId, error.getMessage());
                    return Mono.empty();
                });
    }
}
