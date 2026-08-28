# Handoff Report: `jobs-api` Architectural and Structural Survey

**Agent**: `teamwork_preview_explorer_survey_1`  
**Working Directory**: `y:\git\jobs\.agents\teamwork_preview_explorer_survey_1`  
**Date**: 2026-08-28T19:24:00Z  
**Type**: Hard Handoff (Task Complete)

---

## 1. Observation

Direct observations from source inspection of `y:\git\jobs\jobs-api` and root `pom.xml`:

1. **Root POM (`y:\git\jobs\pom.xml`)**:
   - Line 24: `<spring-boot.version>4.1.1</spring-boot.version>` — Non-existent Spring Boot version.
   - Line 12 & 14-17: `<description>` mentions `jobs-shared`, `jobs-producer`, `jobs-consumer`, but `<modules>` contains only `<module>jobs-api</module>` and `<module>jobs-consumer</module>`.
   - Line 51-55: Dependency on internal module `jobs-shared` is in `dependencyManagement`, but module directory does not exist on disk.

2. **Module POM (`y:\git\jobs\jobs-api\pom.xml`)**:
   - Line 46: `<artifactId>spring-boot-starter-kafka</artifactId>` instead of `spring-kafka`.
   - Line 67: `<artifactId>spring-boot-starter-webmvc-test</artifactId>` instead of `spring-boot-starter-test`.
   - Line 70-84: Testcontainers dependencies present, but 0 integration tests exist in `src/test`.
   - Missing `h2` database dependency despite H2 configuration in properties.

3. **Application Properties (`y:\git\jobs\jobs-api\src\main\resources\application.properties`)**:
   - Line 5: `spring.datasource.url=jdbc:h2:mem:jobsdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL` — H2 driver not on classpath (`org.postgresql:postgresql` is runtime dependency).
   - Line 8: `spring.jpa.hibernate.ddl-auto=update` — Unsafe for production databases.
   - Line 12: `spring.kafka.bootstrap-servers=localhost:9094` — Hardcoded host without environment variable overrides.
   - Missing producer reliability configs (`acks=all`, `idempotence`, retries).

4. **Domain Layer (`y:\git\jobs\jobs-api\src\main\java\hub\pedro\jobs\api\domain\`)**:
   - `domain/entity/Job.java`: Line 21 defines `private List<String> errors;`. Class has getters and builder, but lacks business methods for status transition invariants (anemic model).
   - `domain/interfaces/JobRepository.java`: Repository interface placed in `domain.interfaces` package while empty folder `domain/repository` exists.
   - `domain/shared/JobStatus.java`: Enum placed in `domain.shared`.

5. **Application Layer (`y:\git\jobs\jobs-api\src\main\java\hub\pedro\jobs\api\app\`)**:
   - `app/service/JobService.java`: Line 27-39:
     - No `@Transactional` annotation.
     - Dual-write pattern: `repository.save(job)` followed by `publisher.publish(event)` without Transactional Outbox.
     - Does not implement an Inbound Port interface (`app.port.in.ScheduleJobUseCase` is missing).
     - Portuguese log messages at lines 30 and 37.
   - `app/port/out/JobCreatedEvent.java`: Line 5: `public record JobCreatedEvent(UUID id, JobStatus status, byte[] payload, String createdAt)` — `createdAt` converted to `String`, payload raw `byte[]`, lacks event ID, correlation ID, and schema version.

6. **Persistence & Infrastructure (`y:\git\jobs\jobs-api\src\main\java\hub\pedro\jobs\api\infra\`)**:
   - Package name: `hub.pedro.jobs.api.infra.database.postgresql.persistance` (contains typo: `persistance` with `a`).
   - `Nfsejob.java`:
     - Class name violates PascalCase (`Nfsejob` vs `NfseJobJpaEntity`).
     - **Field `errors` is missing completely** (no column, no `@ElementCollection`).
     - Lacks `@Version` for optimistic locking.
   - `PostgresJobRepository.java`: Lines 22-34 (`save`) discards `job.getErrors()`. Lines 37-49 (`findById`) never populates `errors`. Manual procedural mapping used instead of MapStruct.
   - `KafkaJobEventPublisher.java`:
     - Line 15: `private static final String TOPIC = "job-created";` hardcoded topic name.
     - Lines 27-42: Asynchronous `kafkaTemplate.send(...).whenComplete(...)` logs error on failure, but `publish` method completes normally. Catch block swallows exceptions. Silent failure to HTTP caller.

7. **Web Layer (`y:\git\jobs\jobs-api\src\main\java\hub\pedro\jobs\api\web\`)**:
   - Package name: `hub.pedro.jobs.api.web.api.in` (redundant naming).
   - `JobController.java`:
     - Line 26: `@PostMapping(value = "/nfs", consumes = MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<Map<String, Object>> processNfs(@RequestBody byte[] rawPayLoad)` — Accepts raw `byte[]`, no typed DTO, no Jakarta Bean Validation.
     - Lines 27-35: Generic `try-catch (Exception e)` in controller method returning `ResponseEntity.internalServerError().body(Map.of("error", ... + e.getMessage()))` (CWE-209 information disclosure).
     - No `GET /api/jobs/{id}` endpoint to query job status.
   - `PayloadSizeFilter.java`: Line 16: `request.getContentLengthLong()` can be bypassed with `Transfer-Encoding: chunked` or omitted `Content-Length`. Plain text error written directly to response.

8. **Configuration Layer (`y:\git\jobs\jobs-api\src\main\java\hub\pedro\jobs\api\config\`)**:
   - `JacksonConfig.java`: Line 13-17 creates `new ObjectMapper()` bean, destroying Spring Boot auto-configuration.

9. **Test Suite (`y:\git\jobs\jobs-api\src\test\`)**:
   - `app/service/JobServiceTest.java`: Exactly 0 bytes (empty file, zero coverage on service orchestration).
   - `web/api/in/JobControllerTest.java`: Only 1 happy path test with `@MockitoBean`. No tests for filter rejection, invalid input, service failures, or integration tests with Testcontainers.

---

## 2. Logic Chain

1. **From Observation 1 & 2 to Conclusion on Build & Dependency Integrity**:
   - Root POM declares `spring-boot.version=4.1.1` (non-existent) and invalid artifact names (`spring-boot-starter-kafka`, `spring-boot-starter-webmvc-test`).
   - Therefore, the project build configuration contains hallucinated/corrupted starter definitions and invalid parent versions that prevent clean Maven lifecycle resolution in standard repositories.

2. **From Observation 3 to Conclusion on Database Runtime Failure**:
   - `application.properties` configures an H2 URL (`jdbc:h2:mem:...`), but `pom.xml` only includes `postgresql` runtime driver and lacks `com.h2database:h2`.
   - Therefore, running `jobs-api` against default properties causes a fatal `ClassNotFoundException: org.h2.Driver`.

3. **From Observation 4, 6 to Conclusion on Data Loss Bug**:
   - `Job.java` domain model holds `private List<String> errors`.
   - `Nfsejob.java` JPA entity has no `errors` column or relation.
   - `PostgresJobRepository.java` drops errors during persistence and reconstruction.
   - Therefore, any error recorded on a Job is silently and permanently lost upon persisting to PostgreSQL.

4. **From Observation 5, 6, 7 to Conclusion on Distributed Dual-Write & Silent Failure**:
   - `JobService.java` executes `repository.save(job)` and `publisher.publish(event)` in sequence without distributed transaction or Outbox pattern.
   - `KafkaJobEventPublisher.java` performs async send and swallows exceptions without throwing.
   - Controller returns `202 Accepted` regardless of whether Kafka delivery succeeded or failed.
   - Therefore, if Kafka is unreachable, the job is saved as `PENDING` in DB but never processed, and the HTTP client is falsely informed of success (silent message drop).

5. **From Observation 7 to Conclusion on API Quality & Security Vulnerability**:
   - `JobController.java` consumes raw `byte[]` without Bean Validation, returns untyped `Map<String, Object>`, and catches generic `Exception` exposing raw `e.getMessage()`.
   - Therefore, the API violates REST contract conventions (RFC 7807) and introduces CWE-209 (Information Exposure).

6. **From Observation 9 to Conclusion on Test Deficit**:
   - `JobServiceTest.java` is empty (0 bytes) and Testcontainers dependencies are unused.
   - Therefore, the application core and integration paths have 0% effective test coverage.

---

## 3. Caveats

- **Consumer Module Scope**: Detailed inspection of `jobs-consumer` was referenced solely to check cross-module shared code and event contract duplication (`JobCreatedEvent`, `Nfsejob`, `JobRepository`). Deep consumer analysis is handled by peer survey agents.
- **Read-Only Mode**: No source files or build files were modified in accordance with strict read-only constraints.
- **Runtime Execution**: Commands were not executed against live Docker containers or Kafka brokers in this survey step; all findings are derived from static source code, POM, and configuration analysis.

---

## 4. Conclusion

The `jobs-api` module requires a prioritized architectural overhaul in the following 5 dimensions:
1. **Critical Reliability & Data Integrity (P1, P2, P3, P4)**: Implement Transactional Outbox Pattern for Kafka publishing, map `errors` in JPA entity, add `@Version` optimistic locking, fix H2/PostgreSQL driver and URL configuration.
2. **Build & Dependency Corrections (P10)**: Correct Spring Boot version to official 3.4.x / 3.3.x, fix starter artifact IDs (`spring-kafka`, `spring-boot-starter-test`).
3. **Hexagonal & Domain Model Enrichment (P8, P13, P14)**: Introduce `ScheduleJobUseCase` inbound port, enrich `Job` aggregate root with business methods/invariants, fix package typos (`persistance` -> `persistence`).
4. **API Design & Security (P6, P7, P9, P11)**: Introduce typed Request/Response DTOs with Jakarta Bean Validation, RFC 7807 `ProblemDetail` via `@RestControllerAdvice`, and implement `GET /api/jobs/{id}` query endpoint.
5. **Testing Architecture (P5)**: Implement unit tests for `JobService` and `Job` domain aggregate, and integration tests using Testcontainers (PostgreSQL + Kafka).

---

## 5. Verification Method

To verify all findings independently:

1. **Verify Corrupted Starters & Missing Drivers**:
   - Inspect `y:\git\jobs\pom.xml` line 24 (`spring-boot.version`)
   - Inspect `y:\git\jobs\jobs-api\pom.xml` line 46 (`spring-boot-starter-kafka`) and line 67 (`spring-boot-starter-webmvc-test`)
   - Inspect `y:\git\jobs\jobs-api\src\main\resources\application.properties` line 5 (`jdbc:h2:...` with missing H2 POM dependency)

2. **Verify Data Loss of `errors` Field**:
   - Inspect `y:\git\jobs\jobs-api\src\main\java\hub\pedro\jobs\api\domain\entity\Job.java` line 21
   - Inspect `y:\git\jobs\jobs-api\src\main\java\hub\pedro\jobs\api\infra\database\postgresql\persistance\Nfsejob.java` (observe total absence of `errors`)
   - Inspect `y:\git\jobs\jobs-api\src\main\java\hub\pedro\jobs\api\infra\database\postgresql\repository\PostgresJobRepository.java` lines 22-49

3. **Verify Dual-Write and Silent Failure**:
   - Inspect `y:\git\jobs\jobs-api\src\main\java\hub\pedro\jobs\api\app\service\JobService.java` lines 27-39
   - Inspect `y:\git\jobs\jobs-api\src\main\java\hub\pedro\jobs\api\infra\kafka\publisher\KafkaJobEventPublisher.java` lines 26-43

4. **Verify 0-byte Test File**:
   - Inspect file size of `y:\git\jobs\jobs-api\src\test\java\hub\pedro\jobs\api\app\service\JobServiceTest.java` (0 bytes).

---
*End of Handoff Report.*
