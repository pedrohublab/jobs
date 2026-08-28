# BRIEFING — 2026-08-28T19:25:00Z

## Mission
Deep investigation of cross-cutting concerns, infrastructure, root POM / multi-module architecture, Kafka E2E event flow, PostgreSQL schemas/migrations, docker/k8s/CI-CD, and testing strategy for Jobs NFS-e Emission Pipeline.

## 🔒 My Identity
- Archetype: Teamwork explorer
- Roles: Cross-cutting, Infrastructure, Data & E2E Integration Investigation & Synthesis
- Working directory: y:\git\jobs\.agents\teamwork_preview_explorer_survey_3
- Original parent: cc1eb7d7-aa1d-4ef0-81d9-2c17717b2188
- Milestone: Survey 3 (Cross-cutting, Infrastructure, Multi-module, Kafka & Data Models, CI/CD, Tests)

## 🔒 Key Constraints
- Read-only investigation — do NOT implement or modify codebase files
- Write all artifacts/reports inside y:\git\jobs\.agents\teamwork_preview_explorer_survey_3
- Educational explanation for every issue found (what is wrong, why it is a problem, recommended fix)

## Current Parent
- Conversation ID: cc1eb7d7-aa1d-4ef0-81d9-2c17717b2188
- Updated: 2026-08-28T19:25:00Z

## Investigation State
- **Explored paths**: Root pom.xml, jobs-api (pom, java, resources, tests), jobs-consumer (pom, java, resources, test directory absence), compose.yaml, Dockerfile, k8s/ (cassandra, deployment, kafka), .github/workflows/maven-publish.yml, scripts/ (compile.ps1, load_test.py, requests.sh, start-env.sh), README.md.
- **Key findings**:
  1. Root POM declares non-existent Spring Boot 4.1.1, references non-existent `jobs-shared` module.
  2. Severe code duplication across domain, persistence, events, configs between api and consumer.
  3. Consumer `@KafkaListener` + `@Async` loses messages on failure/crash due to premature auto-commit.
  4. Producer dual-write hazard without `@Transactional` or Transactional Outbox pattern.
  5. Persistence silently drops `errors` list from `Job` domain when persisting `Nfsejob`.
  6. No database migration tool (Flyway/Liquibase); unsafe `ddl-auto=update`.
  7. CI workflow uses JDK 11 on Java 21 codebase.
  8. Missing Kubernetes manifests for `jobs-consumer` and PostgreSQL; orphaned Cassandra manifests.
  9. Near-zero test coverage (0 tests in consumer, 0-byte JobServiceTest in api).
- **Unexplored areas**: None. All repository files and cross-cutting layers analyzed.

## Key Decisions Made
- Structure `analysis.md` into 5 major pillars with senior tech mentor educational explanations (Problem, Technical Impact/Why it matters, Recommended Direction) and clear severity ratings (CRITICAL / IMPORTANT / IMPROVEMENT).
- Create fully self-contained `handoff.md` adhering to the 5-component handoff protocol.

## Artifact Index
- DISPATCH.md — record of dispatch instruction
- BRIEFING.md — persistent working memory
- progress.md — liveness heartbeat and step tracking
- analysis.md — comprehensive technical report
- handoff.md — 5-component self-contained handoff
