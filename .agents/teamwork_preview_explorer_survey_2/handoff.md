# Handoff Report: Deep Architectural Investigation of `jobs-consumer`

**Agent**: teamwork_preview_explorer_survey_2  
**Role**: Explorer / Architectural Survey  
**Date**: 2026-08-28  
**Working Directory**: `y:\git\jobs\.agents\teamwork_preview_explorer_survey_2`  
**Target Module**: `y:\git\jobs\jobs-consumer`  

---

## 1. Observation

Direct observations with verbatim code excerpts and file locations:

### 1.1 Kafka Consumer + `@Async` Fire-and-Forget Anti-Pattern
- **File**: `y:\git\jobs\jobs-consumer\src\main\java\hub\pedro\jobs\consumer\infra\kafka\consumer\JobEventoConsumer.java`
  - Lines 32-43:
    ```java
    @KafkaListener(topics = "job-created", groupId = "jobs-consumer-group")
    public void consumirEvento(String payload) {
        log.info("[Consumer] Evento recebido. payload={}", payload);
        try {
            JobCreatedEvent evento = objectMapper.readValue(payload, JobCreatedEvent.class);
            // @Async: retorna imediatamente, processamento ocorre em thread do pool
            jobProcessingService.processarNf(evento.id());
        } catch (JsonProcessingException e) {
            log.error("[Consumer] Falha ao desserializar evento Kafka: {}", e.getMessage(), e);
            throw new RuntimeException("Falha ao desserializar evento Kafka", e);
        }
    }
    ```
- **File**: `y:\git\jobs\jobs-consumer\src\main\java\hub\pedro\jobs\consumer\app\service\JobProcessingService.java`
  - Lines 24-31:
    ```java
    @Async("jobProcessingExecutor")
    public void processarNf(UUID id) {
        log.info("[Worker] Buscando Job id={} para processamento assíncrono", id);
        repository.findById(id).ifPresentOrElse(
                this::doProcessamento,
                () -> log.warn("[Worker] Job id={} não encontrado na base de dados", id)
        );
    }
    ```
- **File**: `y:\git\jobs\jobs-consumer\src\main\java\hub\pedro\jobs\consumer\config\AsyncConfig.java`
  - Lines 10-21:
    ```java
    @Bean(name = "jobProcessingExecutor")
    public ThreadPoolTaskExecutor jobProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("job-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
    ```

### 1.2 Domain Model vs JPA Entity Asymmetry & Missing `errors` Field
- **File**: `y:\git\jobs\jobs-consumer\src\main\java\hub\pedro\jobs\consumer\domain\entity\Job.java`
  - Line 22: `private List<String> errors;`
  - Lines 46-52:
    ```java
    public void markAsFailed(String errorMessage) {
        this.status = JobStatus.FAILED;
        this.attempts = (this.attempts != null ? this.attempts : 0) + 1;
        if (this.errors == null) this.errors = new ArrayList<>();
        this.errors.add(errorMessage);
        this.updatedAt = Instant.now();
    }
    ```
- **File**: `y:\git\jobs\jobs-consumer\src\main\java\hub\pedro\jobs\consumer\infra\database\postgresql\persistance\Nfsejob.java`
  - Lines 18-43: Entity contains fields `id`, `payload`, `status`, `createdAt`, `updatedAt`, `scheduledAt`, `finishedAt`, `attempts`. The field `errors` is **absent**.
- **File**: `y:\git\jobs\jobs-consumer\src\main\java\hub\pedro\jobs\consumer\infra\database\postgresql\repository\PostgresJobRepository.java`
  - Lines 22-34 (`save`) and Lines 37-49 (`findById`): `errors` is completely omitted in both mapping directions.

### 1.3 In-Memory H2 vs PostgreSQL Datasource Conflict
- **File**: `y:\git\jobs\jobs-consumer\src\main\resources\application.properties`
  - Lines 4-9:
    ```properties
    # Database Configuration (H2 in-memory em modo PostgreSQL)
    spring.datasource.url=jdbc:h2:mem:jobsdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
    spring.datasource.username=sa
    spring.datasource.password=
    spring.jpa.hibernate.ddl-auto=update
    spring.jpa.show-sql=true
    ```
- **File**: `y:\git\jobs\jobs-api\src\main\resources\application.properties`
  - Lines 5-8:
    ```properties
    spring.datasource.url=jdbc:postgresql://localhost:5432/jobsdb
    spring.datasource.username=postgres
    spring.datasource.password=postgres
    ```

### 1.4 Actuator Without Embedded Web Server (Phantom Port 8081)
- **File**: `y:\git\jobs\jobs-consumer\pom.xml`
  - Lines 29-37:
    ```xml
    <!-- Spring Boot base (sem spring-boot-starter-web) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    ```
- **File**: `y:\git\jobs\jobs-consumer\src\main\resources\application.properties`
  - Lines 2, 21-22:
    ```properties
    server.port=8081
    management.endpoints.web.exposure.include=health,info,prometheus
    management.endpoint.health.show-details=always
    ```

### 1.5 Absence of Tests and Unused Test Dependencies
- **Directory**: `y:\git\jobs\jobs-consumer\src\test` — **Does not exist**. 0 tests in module.
- **File**: `y:\git\jobs\jobs-consumer\pom.xml`
  - Lines 61-74: `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-kafka` declared in test scope but unused.

### 1.6 Hexagonal Architecture and Packaging Issues
- **File**: `y:\git\jobs\jobs-consumer\src\main\java\hub\pedro\jobs\consumer\app\port\in\JobCreatedEvent.java`
  - Record placed in `app.port.in` (DTO in input port package).
- **File**: `y:\git\jobs\jobs-consumer\src\main\java\hub\pedro\jobs\consumer\domain\interfaces\JobStatus.java`
  - Enum placed in `domain.interfaces`.
- **File**: `y:\git\jobs\jobs-consumer\src\main\java\hub\pedro\jobs\consumer\infra\database\postgresql\persistance\Nfsejob.java`
  - Package misspelled as `persistance` and class named `Nfsejob`.

### 1.7 Missing Kubernetes Manifest and CI/CD Java Version Mismatch
- **Directory**: `y:\git\jobs\k8s` — Contains `deployment.yaml` (only for `jobs-api`), `cassandra.yaml` (unused db), and `kafka.yaml`. No deployment manifest for `jobs-consumer`.
- **File**: `y:\git\jobs\.github\workflows\maven-publish.yml` — Line 23 uses `java-version: '11'` while root POM targets Java 21.

---

## 2. Logic Chain

1. **Premise 1 (Kafka Listener Lifecycle)**: Spring Kafka's listener container commits offsets when the listener method returns successfully (under default/batch commit mode) [Observation 1.1].
2. **Premise 2 (`@Async` Disconnect)**: Because `consumirEvento` calls `processarNf` which is annotated with `@Async("jobProcessingExecutor")`, the listener method returns immediately upon queueing the task in the in-memory `ThreadPoolTaskExecutor` [Observation 1.1].
3. **Inference 1 (Data Loss Risk)**: As soon as `consumirEvento` returns, Kafka marks the offset as committed. If the application JVM crashes, restarts (Kubernetes pod eviction, rolling upgrade), or if the in-memory queue overflows (capacity 100 with default AbortPolicy), tasks are dropped in memory. Because Kafka already committed the offset, those events are **permanently lost** with 0 delivery guarantees.
4. **Premise 3 (Error Recording in Domain vs JPA)**: The domain `Job` entity accumulates error messages in `private List<String> errors` when `markAsFailed()` is invoked [Observation 1.2].
5. **Premise 4 (Missing JPA Column)**: `Nfsejob` lacks an `errors` column or relation, and `PostgresJobRepository` does not map `errors` [Observation 1.2].
6. **Inference 2 (Diagnostic Blindspot)**: Any failure recorded during asynchronous execution is discarded on persist. Querying the job status reveals `FAILED` with zero diagnostic error cause.
7. **Premise 5 (Datasource Divergence)**: `jobs-consumer` uses `jdbc:h2:mem:jobsdb` while `jobs-api` uses `jdbc:postgresql://localhost:5432/jobsdb` [Observation 1.3].
8. **Inference 3 (Local Runtime Failure)**: Running the application locally outside Docker Compose causes `jobs-consumer` to look for jobs in an empty H2 in-memory database, logging `Job not found` and never processing the jobs emitted by `jobs-api`.
9. **Premise 6 (Actuator Web Server Dependency)**: Spring Boot Actuator exposes HTTP endpoints only if an embedded web server (Tomcat/Netty via `spring-boot-starter-web` or `webflux`) is present on the classpath [Observation 1.4].
10. **Inference 4 (Kubernetes Probe Crash)**: In Kubernetes, configuring liveness/readiness probes targeting `httpGet: :8081/actuator/health` will immediately fail with Connection Refused, placing consumer pods into a continuous `CrashLoopBackOff`.

---

## 3. Caveats

1. **Docker Compose Environment Overrides**: In Docker Compose (`compose.yaml`), environment variables override the datasource URL to PostgreSQL (`SPRING_DATASOURCE_URL=jdbc:postgresql://postgresql:5432/jobsdb`). Thus, the H2 issue manifests during local IDE development/testing, but not when running purely via Docker Compose.
2. **External SDK Placeholder**: The commented dependency in `pom.xml` indicates a planned integration with `nfse-nacional-sdk`. The current implementation has zero simulated delays or HTTP client calls.
3. **No Code Modified**: In accordance with strict constraints, no source code, build files, or configurations were edited or created in the workspace.

---

## 4. Conclusion

The `jobs-consumer` module contains critical reliability and architectural flaws:
- **Critical Data Loss Hazard**: The `@Async` + Kafka auto-commit pattern must be replaced with synchronous execution on Kafka partition listener threads, manual/record acknowledgments, and retry/DLT topic infrastructure.
- **Data Integrity Breakdown**: The `errors` field must be persisted to PostgreSQL to allow error tracing.
- **Configuration & Operations**: Datasources must be unified to PostgreSQL, `spring-boot-starter-web` must be added for Actuator HTTP endpoints, and Kubernetes manifests must be created.
- **Architectural Purity & Testing**: Inbound use case ports (`ProcessJobUseCase`) must be introduced, code 100% in English standardized, and a comprehensive test suite (Unit + Testcontainers integration) implemented.

---

## 5. Verification Method

To independently verify all findings:
1. **Kafka Listener & Async Inspection**:
   - Inspect `JobEventoConsumer.java:38` and `JobProcessingService.java:24`.
2. **Missing Field Inspection**:
   - Compare `Job.java:22` (`errors`) against `Nfsejob.java` and `PostgresJobRepository.java:23-49`.
3. **Datasource Mismatch**:
   - Inspect `jobs-consumer/src/main/resources/application.properties:5` vs `jobs-api/src/main/resources/application.properties:5`.
4. **Actuator Web Server Absence**:
   - Inspect `jobs-consumer/pom.xml:29-37` and run `mvn spring-boot:run` in `jobs-consumer` — observe that no HTTP server binds to port 8081.
5. **Test Directory Check**:
   - Verify directory listing `jobs-consumer/src/test` does not exist.
6. **Reference Document**:
   - Read full analysis report at `y:\git\jobs\.agents\teamwork_preview_explorer_survey_2\analysis.md`.
