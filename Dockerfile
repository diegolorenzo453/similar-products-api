FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml similarProducts.yaml ./
COPY src src
RUN chmod +x mvnw && ./mvnw --batch-mode -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 spring
COPY --from=build /workspace/target/similar-products-1.0.0.jar app.jar
USER spring
EXPOSE 5000
ENTRYPOINT ["java", "-jar", "app.jar"]
