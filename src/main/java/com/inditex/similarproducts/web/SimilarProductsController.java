package com.inditex.similarproducts.web;

import com.inditex.similarproducts.application.SimilarProductsService;
import com.inditex.similarproducts.domain.ProductDetail;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Validated
@RestController
@RequestMapping("/product")
public class SimilarProductsController {

    private final SimilarProductsService service;

    public SimilarProductsController(SimilarProductsService service) {
        this.service = service;
    }

    @GetMapping("/{productId}/similar")
    public Mono<ResponseEntity<List<ProductDetail>>> getSimilarProducts(
            @PathVariable @NotBlank String productId) {
        return service.findSimilarProducts(productId).map(ResponseEntity::ok);
    }
}
