# Progress — Survey 3

Last visited: 2026-08-28T19:26:00Z

- [x] Initialized BRIEFING.md and DISPATCH.md
- [x] Explore root POM and multi-module layout (Spring Boot version 4.1.1 bug, missing jobs-shared, duplicated code)
- [x] Trace end-to-end Kafka event flow & serialization/deserialization between jobs-api and jobs-consumer (@Async commit hazard, Claim Check vs Event-Carried mismatch)
- [x] Inspect database configuration, JPA models, DDL/Flyway/Liquibase state, dual vs single DB architecture (dropped errors, ddl-auto update, H2 mismatch)
- [x] Inspect Dockerfiles, docker-compose / compose.yaml, Kubernetes manifests, GitHub Actions workflows (JDK 11 in CI, missing consumer k8s, orphaned cassandra)
- [x] Inspect test suite, test coverage, testing methodologies and gaps across both modules (0 tests in consumer, empty JobServiceTest, broken test annotations)
- [x] Synthesize findings into analysis.md with educational explanations
- [x] Create self-contained handoff.md and notify parent
