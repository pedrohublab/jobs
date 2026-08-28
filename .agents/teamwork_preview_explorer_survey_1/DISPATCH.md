## 2026-08-28T19:21:44Z
You are teamwork_preview_explorer_survey_1.
Working directory: y:\git\jobs\.agents\teamwork_preview_explorer_survey_1

MANDATORY: Read the original user request at y:\git\jobs\.agents\ORIGINAL_REQUEST.md before doing anything else.

STRICT CONSTRAINTS:
- Do NOT edit, delete, or create any source code, configuration files, build files, scripts, dockerfiles, or k8s manifests in the workspace. You are strictly READ-ONLY on codebase files.
- All your reports, progress, and metadata MUST be written inside your working directory: y:\git\jobs\.agents\teamwork_preview_explorer_survey_1

TASK OBJECTIVE:
Perform a deep, structural and architectural investigation of the `jobs-api` module in y:\git\jobs\jobs-api:
1. Package organization & Hexagonal Architecture / DDD compliance (domain, application, infrastructure, web, ports, adapters).
2. Domain model vs persistence entity mappings, DTOs, missing/lost fields, validation, error handling.
3. Configuration issues (application.yaml/properties, Actuator, database configurations like H2 vs PostgreSQL, Spring Boot versions/starters).
4. Kafka producer setup, event schemas, transactionality/reliability.
5. Naming conventions and language consistency (PT vs EN mixing in packages, classes, methods, fields).
6. Test quality, coverage, and test architecture in `jobs-api`.
7. For EVERY issue found, provide a detailed educational explanation (like a senior tech mentor/professor): what is wrong, why it is a problem (architectural/operational/reliability impact), and what the recommended architectural fix direction is.

OUTPUT REQUIREMENTS:
Write your full analysis report to `y:\git\jobs\.agents\teamwork_preview_explorer_survey_1\analysis.md` and your final handoff report to `y:\git\jobs\.agents\teamwork_preview_explorer_survey_1\handoff.md`.
Send a message to your parent when complete.
