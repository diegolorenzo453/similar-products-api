# Similar Products API

Spring Boot application that exposes the product details of the products most similar to a given product. It implements the contract in [`similarProducts.yaml`](./similarProducts.yaml) and consumes the mock APIs described in [`existingApis.yaml`](./existingApis.yaml).

## API

```http
GET /product/{productId}/similar
```

Example:

```bash
curl http://localhost:5000/product/1/similar
```

```json
[
  {"id":"2","name":"Dress","price":19.99,"availability":true},
  {"id":"3","name":"Blazer","price":29.99,"availability":false},
  {"id":"4","name":"Boots","price":39.99,"availability":true}
]
```

The response retains the order supplied by the similar-IDs API and contains no duplicate products.

## Requirements

- Java 17+
- Maven 3.9+
- Docker only for running the supplied mocks and performance test

## Run locally

Start the supplied product API mock from the project root:

```bash
docker compose up -d simulado
```

Then start the application (it listens on port **5000**):

```bash
mvn spring-boot:run
```

The upstream URL and resilience settings can be overridden without rebuilding:

| Environment variable | Default | Purpose |
|---|---:|---|
| `PRODUCT_API_BASE_URL` | `http://localhost:3001` | Existing product API URL |
| `PRODUCT_API_TIMEOUT` | `2s` | Timeout for each upstream request |
| `PRODUCT_API_MAX_CONCURRENCY` | `10` | Maximum concurrent detail requests per call |

## Automated tests

The tests are self-contained and use a mocked HTTP transport; Docker and open network ports are not required:

```bash
mvn clean verify
```

The automated suite covers:

- Successful aggregation and response contract.
- Concurrent responses completing out of order.
- Preservation of similarity order.
- Removal of duplicate IDs.
- Missing source products.
- Missing individual product details.
- Upstream server errors and timeouts.
- Partial responses when one recommendation is unavailable.

## Supplied performance test

With this application running on port 5000:

```bash
docker compose up -d simulado influxdb grafana
docker compose run --rm k6 run scripts/test.js
```

When Docker Engine runs directly inside Linux or WSL (instead of Docker Desktop), run k6 on the host network:

```bash
docker run --rm --network host \
  -v "$PWD/shared/k6:/scripts" \
  -e BASE_URL=http://localhost:5000 \
  -e K6_OUT=influxdb=http://localhost:8086/k6 \
  loadimpact/k6:0.28.0 run scripts/test.js
```

Results are available in Grafana at <http://localhost:3000/d/Le2Ku9NMk/k6-performance-test>.

## Architecture and request flow

The implementation uses a small layered structure:

```text
HTTP request
    -> SimilarProductsController       web/API adapter
    -> SimilarProductsService          orchestration and ordering
    -> ProductApiClient                external API adapter
    -> supplied product APIs
```

For each request, the service first obtains the ordered similar-product IDs. It removes duplicates while preserving their first occurrence, then fetches the corresponding details concurrently. `flatMapSequential` allows those HTTP calls to run in parallel but emits their results in the original similarity order.

The domain response uses `BigDecimal` for prices so decimal values are represented without binary floating-point artifacts.

## Design decisions

- **Non-blocking I/O:** Spring WebFlux avoids tying up one platform thread per upstream request under the supplied concurrent load.
- **Parallel fan-out with stable ordering:** product details are fetched concurrently, while `flatMapSequential` preserves the similarity order from the IDs endpoint. Concurrency is bounded to protect the dependency.
- **Partial-result resilience:** an unavailable, failed, or timed-out similar-product detail is omitted instead of failing the whole response. If the initial IDs lookup returns 404, the API returns 404; other failures of that essential lookup return 502.
- **Bounded latency:** every upstream operation has a configurable timeout. Duplicate IDs are removed while retaining their first occurrence, satisfying the response contract's uniqueness constraint.
- **Separation of responsibilities:** the web adapter, application orchestration, domain model, and external API adapter live in separate packages.

### Error semantics

| Situation | API behaviour | Rationale |
|---|---|---|
| Similar-IDs API returns 404 | `404 Not Found` | The requested source product does not exist. |
| Similar-IDs API fails or times out | `502 Bad Gateway` | This dependency is essential to build the response. |
| One product detail returns 404/5xx | Product is omitted | One unavailable recommendation should not discard all valid recommendations. |
| One product detail times out | Product is omitted | Keeps total latency bounded and provides a useful partial result. |

Returning partial results is an explicit product-level assumption because the supplied OpenAPI contract does not define this case. In a production system this behaviour would be agreed with API consumers and documented in the public contract.

### Performance and resilience trade-offs

- Detail calls are independent, so executing them concurrently reduces response time from the sum of all latencies to approximately the slowest accepted call.
- Concurrency is bounded instead of unlimited, preventing one request with many IDs from overwhelming the upstream service.
- Requests are not retried automatically. Retrying a degraded dependency under load could amplify the incident; retries would require an agreed policy, backoff and jitter.
- A circuit breaker and metrics could be added for production operation, but were intentionally not introduced into this small exercise without concrete availability objectives.

## Assumptions

- The similar-IDs endpoint is the source of truth for whether the requested product exists.
- The list returned by that endpoint is already ordered by similarity.
- Returning the available recommendations is preferable to failing the complete request when only one detail is unavailable.
- The upstream APIs use the JSON schemas supplied with the exercise.

## Package as a container

Build the executable JAR and the image:

```bash
mvn clean package
docker build -t similar-products .
```

With Docker Desktop, where `host.docker.internal` resolves the host automatically:

```bash
docker run --rm -p 5000:5000 \
  -e PRODUCT_API_BASE_URL=http://host.docker.internal:3001 \
  similar-products
```

With Docker Engine installed directly in Linux/WSL, attach the application to the Compose network and address the mock by its service name. Replace `backenddevtest_default` if `docker network ls` shows a different Compose network name:

```bash
docker compose up -d simulado
docker run --rm --network backenddevtest_default -p 5000:5000 \
  -e PRODUCT_API_BASE_URL=http://simulado:80 \
  similar-products
```

The image uses a Java 17 JRE and exposes port 5000. The default non-containerized development configuration expects the mock at `http://localhost:3001`.
