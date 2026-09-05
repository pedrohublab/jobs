# =============================================================================
#  Multi-stage build — jobs-api + jobs-consumer
#  Uso:
#    docker build --target jobs-api-runtime  -t jobs-api:latest .
#    docker build --target jobs-consumer-runtime -t jobs-consumer:latest .
#  Ou via docker compose:
#    docker compose build
# =============================================================================

# ── Stage 1: Build dos módulos com Maven ──────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

COPY pom.xml .
COPY jobs-api/pom.xml jobs-api/pom.xml
COPY jobs-consumer/pom.xml jobs-consumer/pom.xml

COPY jobs-api/src jobs-api/src
COPY jobs-consumer/src jobs-consumer/src

RUN mvn clean package -DskipTests --no-transfer-progress

# ── Stage 2: Runtime jobs-api ─────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS jobs-api-runtime
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=builder /build/jobs-api/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

# ── Stage 3: Runtime jobs-consumer ───────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS jobs-consumer-runtime
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=builder /build/jobs-consumer/target/*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
