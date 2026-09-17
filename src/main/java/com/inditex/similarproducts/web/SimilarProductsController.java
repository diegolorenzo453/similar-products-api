package com.inditex.similarproducts.web;

import com.inditex.similarproducts.application.SimilarProductsService;
import com.inditex.similarproducts.generated.api.ProductsApi;
import com.inditex.similarproducts.generated.model.ProductDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public class SimilarProductsController implements ProductsApi {

    private final SimilarProductsService service;

    public SimilarProductsController(SimilarProductsService service) {
        this.service = service;
    }

    @Override
    public Mono<ResponseEntity<Flux<ProductDetail>>> getProductSimilar(
            String productId, ServerWebExchange exchange) {
        return service.findSimilarProducts(productId)
                .map(products -> ResponseEntity.ok(Flux.fromIterable(products)));
    }
}
