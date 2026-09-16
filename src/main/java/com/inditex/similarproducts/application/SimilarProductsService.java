package com.inditex.similarproducts.application;

import com.inditex.similarproducts.config.ProductApiProperties;
import com.inditex.similarproducts.domain.ProductDetail;
import com.inditex.similarproducts.infrastructure.ProductApiClient;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class SimilarProductsService {

    private final ProductApiClient productApiClient;
    private final int maxConcurrency;

    public SimilarProductsService(ProductApiClient productApiClient, ProductApiProperties properties) {
        this.productApiClient = productApiClient;
        this.maxConcurrency = properties.maxConcurrency();
    }

    public Mono<List<ProductDetail>> findSimilarProducts(String productId) {
        return productApiClient.findSimilarIds(productId)
                .map(LinkedHashSet::new)
                .flatMapMany(ids -> reactor.core.publisher.Flux.fromIterable(ids))
                .flatMapSequential(productApiClient::findProduct, maxConcurrency)
                .collectList();
    }
}
