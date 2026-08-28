# Project: Jobs — NFS-e Emission Pipeline (Architectural Analysis & Documentation)

## Architecture Overview
The "Jobs — NFS-e Emission Pipeline" is an asynchronous, event-driven, multi-module distributed system designed in Java 21 and Spring Boot. It follows Hexagonal Architecture (Ports and Adapters) and Domain-Driven Design (DDD) principles to ingest NFS-e (Nota Fiscal de Serviços Eletrônica) emission requests via a REST API (`jobs-api`), persist them in PostgreSQL, dispatch events to Apache Kafka (`job-created` topic), and process them asynchronously in background workers (`jobs-consumer`).

```
                                  +-------------------------------------------------------+
                                  |                     jobs-api                          |
                                  |  [Web Controller] -> [Inbound Port] -> [JobService]  |
                                  |                            |                |         |
                                  |                            v                v         |
                                  |                     [PostgreSQL DB]   [Kafka Topic]   |
                                  |                        (jobsdb)       (job-created)   |
                                  +---------------------------------------------|---------+
                                                                                |
                                                                                v
                                  +-------------------------------------------------------+
                                  |                    jobs-consumer                      |
                                  |  [Kafka Listener] -> [Inbound Port] -> [ProcessService]
                                  |                            |                |         |
                                  |                            v                v         |
                                  |                     [PostgreSQL DB]   [External SEFAZ |
                                  |                        (jobsdb)          Simulator]   |
                                  +-------------------------------------------------------+
```

## Feature Inventory (Survey Synthesis)
| # | Feature / Issue Area | Description | Milestone | Source | Status |
|---|----------------------|-------------|-----------|--------|--------|
| F01 | Data Loss in Consumer (`@Async` + Auto-commit) | Premature Kafka offset commit with unmanaged thread pool losing jobs on restart/failure | M1 | Survey 2 & 3 | DONE |
| F02 | Dual-Write & Silent Drop in Producer | `jobs-api` lacks Transactional Outbox pattern; async send error swallowed returning HTTP 202 | M1 | Survey 1 & 3 | DONE |
| F03 | JPA Entity Data Loss (`errors` field) | Domain `Job.errors` discarded by `Nfsejob` JPA entity and `PostgresJobRepository` in both modules | M1 | Survey 1, 2, 3 | DONE |
| F04 | Database Datasource Runtime Mismatch | Default properties use `jdbc:h2:mem` without H2 dependency on classpath vs PostgreSQL in Docker | M1 | Survey 1, 2, 3 | DONE |
| F05 | Hallucinated Spring Boot Version & Starters | Root POM has `4.1.1`; corrupted starter names (`spring-boot-starter-kafka`, `spring-boot-starter-webmvc-test`) | M1 | Survey 1, 3 | DONE |
| F06 | Missing `jobs-shared` & Code Duplication | Declared in parent POM but missing on disk; duplicate entities, events, repositories, configs | M1 | Survey 1, 2, 3 | DONE |
| F07 | CI/CD Pipeline Java Version Mismatch | GitHub Actions workflow configured with Java 11 for Java 21 codebase, failing build | M1 | Survey 3 | DONE |
| F08 | Actuator Phantom Server in Consumer | Consumer has Actuator + `server.port=8081` but lacks embedded web server dependency | M1 | Survey 2 & 3 | DONE |
| F09 | Test Coverage Vacuum & Empty Test Files | Consumer has 0 tests; API has 0-byte `JobServiceTest.java`; unused Testcontainers | M1 | Survey 1, 2, 3 | DONE |
| F10 | Missing Inbound Ports & Anemic Domain | Services bypass use case ports; domain `Job` lacks state transition encapsulation & validation | M1 | Survey 1, 2, 3 | DONE |
| F11 | REST API Design, Validation & CWE-209 | Raw `byte[]` payload, missing DTOs/Bean Validation, raw `e.getMessage()` leak, missing GET endpoint | M1 | Survey 1 | DONE |
| F12 | Missing Database Migrations (Flyway) | Relies on `ddl-auto=update` without versioned schema migration scripts | M1 | Survey 1, 2, 3 | DONE |
| F13 | Concurrency & Missing Optimistic Locking | `Nfsejob` entity lacks `@Version` field, leaving updates vulnerable to race conditions | M1 | Survey 1, 2 | DONE |
| F14 | Language & Naming Inconsistencies | Mixing Portuguese methods/logs with English classes; package typo `persistance`; non-PascalCase `Nfsejob` | M1 | Survey 1, 2, 3 | DONE |
| F15 | Outdated Kubernetes & Script Manifests | K8s missing consumer/db manifests; orphan `cassandra.yaml` and broken `start-env.sh` cqlsh script | M1 | Survey 3 | DONE |
| F16 | Destructive Jackson `ObjectMapper` Bean | Custom `JacksonConfig` overrides Spring Boot auto-configuration destroying JavaTime modules | M1 | Survey 1, 2 | DONE |
| F17 | Fragile Request Size Filter | `PayloadSizeFilter` depends on `Content-Length` header bypassable via `Transfer-Encoding: chunked` | M1 | Survey 1 | DONE |
| F18 | Comprehensive Mentorship README Rewrite | Complete rewrite of `y:\git\jobs\README.md` in PT-BR as a professional technical mentorship guide | M2 | User Request R2 | DONE |
| F19 | Verification & Code Preservation Audit | Verification of README.md quality, completeness, and strict codebase preservation | M3 | User Request R1/R3 | DONE |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Architectural & Structural Analysis Synthesis | Consolidate and structure all findings, technical root causes, educational explanations, and recommended target patterns | none | DONE |
| M2 | README.md Technical Mentorship Rewrite | Rewrite `y:\git\jobs\README.md` in Portuguese (BR) with architecture diagrams, layer breakdowns, data models, execution guide, 17 prioritized issues with educational explanations, and evolution roadmap | M1 | DONE |
| M3 | Comprehensive Review, Challenge & Forensic Verification | Independent review (2 Reviewers), empirical challenge (2 Challengers), and forensic integrity audit (1 Auditor) to verify strict code preservation and README quality | M2 | DONE |

## Interface Contracts & Deliverable Boundaries
- **Target Deliverable**: Exactly one file in repository root: `y:\git\jobs\README.md`.
- **Language**: Portuguese (Brazil) — standard professional technical tone.
- **Code Preservation**: All files in `jobs-api/`, `jobs-consumer/`, `k8s/`, `scripts/`, `.github/`, `pom.xml`, `compose.yaml`, `Dockerfile` remain strictly untouched (0 modifications, 0 creations, 0 deletions outside `.agents/` and `README.md`).
