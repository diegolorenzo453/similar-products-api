package com.inditex.similarproducts;

import com.inditex.similarproducts.application.SimilarProductsService;
import com.inditex.similarproducts.config.ProductApiProperties;
import com.inditex.similarproducts.infrastructure.ProductApiClient;
import com.inditex.similarproducts.observability.SimilarProductsMetrics;
import com.inditex.similarproducts.web.ApiExceptionHandler;
import com.inditex.similarproducts.web.SimilarProductsController;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class SimilarProductsApiTest {

    private final Map<String, StubResponse> responses = new java.util.concurrent.ConcurrentHashMap<>();
    private WebTestClient api;

    @BeforeEach
    void setUp() {
        configureApi(new ProductApiProperties(
                "http://product-api", Duration.ofSeconds(1), Duration.ofSeconds(2), 10, 10));
    }

    private void configureApi(ProductApiProperties properties) {
        ExchangeFunction exchange = request -> {
            StubResponse stub = responses.get(request.url().getPath());
            if (stub == null) {
                return Mono.just(response(HttpStatus.NOT_FOUND, ""));
            }
            return Mono.delay(stub.delay()).thenReturn(response(stub.status(), stub.body()));
        };
        WebClient webClient = WebClient.builder().exchangeFunction(exchange).build();
        SimilarProductsMetrics metrics = new SimilarProductsMetrics(new SimpleMeterRegistry());
        Bulkhead bulkhead = Bulkhead.of("test-product-details", BulkheadConfig.custom()
                .maxConcurrentCalls(properties.maxConcurrentCalls())
                .maxWaitDuration(Duration.ZERO)
                .build());
        ProductApiClient client = new ProductApiClient(webClient, properties, bulkhead, metrics);
        SimilarProductsService service = new SimilarProductsService(client, properties, metrics);

        api = WebTestClient.bindToController(new SimilarProductsController(service))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsProductDetailsInSimilarityOrderEvenWhenTheyCompleteOutOfOrder() {
        stub("/product/1/similarids", HttpStatus.OK, "[\"2\",\"3\",\"4\"]");
        stub("/product/2", HttpStatus.OK,
                "{\"id\":\"2\",\"name\":\"Dress\",\"price\":19.99,\"availability\":true}", 50);
        stub("/product/3", HttpStatus.OK,
                "{\"id\":\"3\",\"name\":\"Blazer\",\"price\":29.99,\"availability\":false}");
        stub("/product/4", HttpStatus.OK,
                "{\"id\":\"4\",\"name\":\"Boots\",\"price\":39.99,\"availability\":true}");

        api.get().uri("/product/1/similar").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo("2")
                .jsonPath("$[1].id").isEqualTo("3")
                .jsonPath("$[2].id").isEqualTo("4");
    }

    @Test
    void skipsAProductWhoseDetailIsNotFound() {
        stub("/product/4/similarids", HttpStatus.OK, "[\"2\",\"5\"]");
        stub("/product/2", HttpStatus.OK,
                "{\"id\":\"2\",\"name\":\"Dress\",\"price\":19.99,\"availability\":true}");
        stub("/product/5", HttpStatus.NOT_FOUND, "");

        api.get().uri("/product/4/similar").exchange()
                .expectStatus().isOk()
                .expectBody()
                .json("[{\"id\":\"2\",\"name\":\"Dress\",\"price\":19.99,\"availability\":true}]");
    }

    @Test
    void returnsNotFoundWhenTheSourceProductDoesNotExist() {
        stub("/product/missing/similarids", HttpStatus.NOT_FOUND, "");

        api.get().uri("/product/missing/similar").exchange()
                .expectStatus().isNotFound()
                .expectBody().isEmpty();
    }

    @Test
    void isolatesTimeoutsInIndividualProductDetails() {
        stub("/product/2/similarids", HttpStatus.OK, "[\"2\",\"slow\"]");
        stub("/product/2", HttpStatus.OK,
                "{\"id\":\"2\",\"name\":\"Dress\",\"price\":19.99,\"availability\":true}");
        stub("/product/slow", HttpStatus.OK,
                "{\"id\":\"slow\",\"name\":\"Slow\",\"price\":1,\"availability\":true}", 1500);

        api.get().uri("/product/2/similar").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo("2");
    }

    @Test
    void returnsBadGatewayWhenSimilarIdsApiFails() {
        stub("/product/1/similarids", HttpStatus.INTERNAL_SERVER_ERROR, "");

        api.get().uri("/product/1/similar").exchange()
                .expectStatus().isEqualTo(502)
                .expectBody().isEmpty();
    }

    @Test
    void returnsBadGatewayWhenSimilarIdsApiTimesOut() {
        stub("/product/slow/similarids", HttpStatus.OK, "[\"2\"]", 1500);

        api.get().uri("/product/slow/similar").exchange()
                .expectStatus().isEqualTo(502)
                .expectBody().isEmpty();
    }

    @Test
    void removesDuplicateIdsWithoutChangingOrder() {
        stub("/product/1/similarids", HttpStatus.OK, "[\"2\",\"2\",\"3\"]");
        stub("/product/2", HttpStatus.OK,
                "{\"id\":\"2\",\"name\":\"Dress\",\"price\":19.99,\"availability\":true}");
        stub("/product/3", HttpStatus.OK,
                "{\"id\":\"3\",\"name\":\"Blazer\",\"price\":29.99,\"availability\":false}");

        api.get().uri("/product/1/similar").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].id").isEqualTo("2")
                .jsonPath("$[1].id").isEqualTo("3");
    }

    @Test
    void returnsAvailableResultsWhenTheWholeOperationBudgetExpires() {
        configureApi(new ProductApiProperties(
                "http://product-api", Duration.ofSeconds(2), Duration.ofMillis(250), 2, 10));
        stub("/product/1/similarids", HttpStatus.OK, "[\"2\",\"slow\"]");
        stub("/product/2", HttpStatus.OK,
                "{\"id\":\"2\",\"name\":\"Dress\",\"price\":19.99,\"availability\":true}");
        stub("/product/slow", HttpStatus.OK,
                "{\"id\":\"slow\",\"name\":\"Slow\",\"price\":1,\"availability\":true}", 600);

        api.get().uri("/product/1/similar").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo("2");
    }

    @Test
    void globalBulkheadLimitsConcurrentDetailCalls() {
        configureApi(new ProductApiProperties(
                "http://product-api", Duration.ofSeconds(1), Duration.ofSeconds(2), 2, 1));
        stub("/product/1/similarids", HttpStatus.OK, "[\"2\",\"3\"]");
        stub("/product/2", HttpStatus.OK,
                "{\"id\":\"2\",\"name\":\"Dress\",\"price\":19.99,\"availability\":true}", 100);
        stub("/product/3", HttpStatus.OK,
                "{\"id\":\"3\",\"name\":\"Blazer\",\"price\":29.99,\"availability\":true}", 100);

        api.get().uri("/product/1/similar").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1);
    }

    private void stub(String path, HttpStatus status, String body) {
        stub(path, status, body, 0);
    }

    private void stub(String path, HttpStatus status, String body, long delayMillis) {
        responses.put(path, new StubResponse(status, body, Duration.ofMillis(delayMillis)));
    }

    private static ClientResponse response(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private record StubResponse(HttpStatus status, String body, Duration delay) {
    }
}
