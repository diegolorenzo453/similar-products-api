# Similar Products API

Spring Boot application that exposes the product details of the products most similar to a given product. [`similarProducts.yaml`](./similarProducts.yaml) is the source of truth: the Maven build generates the WebFlux controller interface and response model, and the handwritten controller implements that interface. The application consumes the mock APIs described in [`existingApis.yaml`](./existingApis.yaml).

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

- Docker Compose (recommended), or Java 17+ for local development

Maven does not need to be installed: the repository includes Maven Wrapper.

## Run the complete stack with Docker Compose

Build and start the application and supplied mock in one command:

```bash
docker compose up --build app simulado
```

The API is available at <http://localhost:5000/product/1/similar>. The image is built in two stages, runs on Java 17 as an unprivileged user, and does not require a prebuilt JAR.

## Run locally

Start the supplied product API mock from the project root:

```bash
docker compose up -d simulado
```

Then start the application (it listens on port **5000**):

```bash
./mvnw spring-boot:run
```

On Windows PowerShell use `./mvnw.cmd spring-boot:run`.

The upstream URL and resilience settings can be overridden without rebuilding:

| Environment variable | Default | Purpose |
|---|---:|---|
| `PRODUCT_API_BASE_URL` | `http://localhost:3001` | Existing product API URL |
| `PRODUCT_API_TIMEOUT` | `2s` | Timeout for each upstream request |
| `PRODUCT_API_MAX_CONCURRENCY` | `10` | Maximum concurrent detail requests per call |
| `PRODUCT_API_MAX_CONCURRENT_CALLS` | `100` | Global maximum detail calls across all requests |
| `PRODUCT_API_OPERATION_TIMEOUT` | `3s` | End-to-end budget for the complete operation |

## Automated tests

The tests are self-contained and use a mocked HTTP transport; Docker and open network ports are not required:

```bash
./mvnw clean verify
```

On Windows use `./mvnw.cmd clean verify`. JaCoCo creates an HTML report at `target/site/jacoco/index.html` and fails the build if line coverage falls below **70%**. GitHub Actions runs this same command on every push and pull request.

The automated suite covers:

- Successful aggregation and response contract.
- Concurrent responses completing out of order.
- Preservation of similarity order.
- Removal of duplicate IDs.
- Missing source products.
- Missing individual product details.
- Upstream server errors and timeouts.
- Partial responses when one recommendation is unavailable.
- End-to-end operation budget expiration.
- Rejection when the global bulkhead is full.

## Supplied performance test

Start the full stack and execute the supplied k6 test inside its Compose network:

```bash
docker compose up -d --build app simulado influxdb grafana
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

The generated response model uses `BigDecimal` for prices so decimal values are represented without binary floating-point artifacts.

## Design decisions

- **Non-blocking I/O:** Spring WebFlux avoids tying up one platform thread per upstream request under the supplied concurrent load.
- **Parallel fan-out with stable ordering:** product details are fetched concurrently, while `flatMapSequential` preserves the similarity order from the IDs endpoint. Concurrency is bounded to protect the dependency.
- **Contract-first API:** OpenAPI Generator creates `ProductsApi` and `ProductDetail` during `generate-sources`. A contract change therefore produces a compile-time change instead of silently drifting from the implementation. The contract documents 200, 404 and 502 responses.
- **Partial-result resilience:** an unavailable, failed, or timed-out similar-product detail is omitted instead of failing the whole response. If the initial IDs lookup returns 404, the API returns 404; other failures of that essential lookup return 502.
- **Bounded latency:** every upstream call has a timeout and the complete aggregation has a separate deadline. When the detail phase reaches that deadline, already completed products are returned and outstanding work is cancelled.
- **Global load protection:** the per-request fan-out limit is complemented by a singleton Resilience4j semaphore bulkhead. Consequently, simultaneous incoming requests share one fixed downstream concurrency budget rather than each receiving the full allowance.
- **Separation of responsibilities:** generated API contract, web adapter, application orchestration, observability and external API adapter live in separate packages.

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
- The semaphore bulkhead rejects excess detail calls immediately and they follow the documented partial-response policy. This prevents queue growth and protects both this service and its dependency.
- A circuit breaker was considered but not enabled blindly: the mock does not provide failure-rate or recovery objectives from which to choose thresholds. In production, one would be added around the shared client once those SLOs exist, with failure-rate/slow-call thresholds, a short open interval and half-open probes. The bulkhead remains necessary because a circuit breaker controls failure propagation, not concurrent resource consumption.
- Automatic retries are deliberately absent. Retrying a degraded dependency under load can amplify an incident; any future retry policy should be limited to safe transient failures and use backoff and jitter.

## Observability

Spring Boot Actuator exposes:

- `GET /actuator/health`, `/actuator/health/liveness` and `/actuator/health/readiness`.
- `GET /actuator/prometheus` for Prometheus-compatible metrics.
- `GET /actuator/metrics` to inspect available meter names.

Custom metrics include `similar_products_operation_duration_seconds`, `similar_products_responses_total{result="partial|complete"}` and `similar_products_downstream_errors_total`. The partial-response percentage is `partial / (partial + complete)`. Logs include the source product ID, requested/returned counts, partial status, failed detail ID and normalized failure reason; they avoid logging response bodies.

## Assumptions

- The similar-IDs endpoint is the source of truth for whether the requested product exists.
- The list returned by that endpoint is already ordered by similarity.
- Returning the available recommendations is preferable to failing the complete request when only one detail is unavailable.
- The upstream APIs use the JSON schemas supplied with the exercise.

## Package as a container

The recommended container command is the complete Compose workflow above. To build an individual image instead:

```bash
docker build -t similar-products .
```

With Docker Desktop, where `host.docker.internal` resolves the host automatically:

```bash
docker run --rm -p 5000:5000 \
  -e PRODUCT_API_BASE_URL=http://host.docker.internal:3001 \
  similar-products
```

For Docker Engine inside Linux/WSL, `docker compose up --build app simulado` works unchanged because services communicate by Compose DNS. The default non-containerized development configuration expects the mock at `http://localhost:3001`.
