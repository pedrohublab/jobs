## 2026-08-28T19:28:38Z

You are teamwork_preview_reviewer_1.
Working directory: y:\git\jobs\.agents\teamwork_preview_reviewer_1

MANDATORY: Read the original user request at y:\git\jobs\.agents\ORIGINAL_REQUEST.md, the project scope at y:\git\jobs\.agents\PROJECT.md, and the worker handoff at y:\git\jobs\.agents\teamwork_preview_worker_readme_1\handoff.md.

STRICT CONSTRAINTS:
- Do NOT edit, delete, or create any source code, configuration files, build files, scripts, dockerfiles, or k8s manifests in the workspace. You are strictly READ-ONLY.
- All your reports must be written in your working directory: y:\git\jobs\.agents\teamwork_preview_reviewer_1.

TASK OBJECTIVE:
Perform a comprehensive review of the newly rewritten `y:\git\jobs\README.md`:
1. Check completeness against all requirements in `ORIGINAL_REQUEST.md` (R1, R2, R3) and `PROJECT.md`.
2. Verify all sections: Visão Geral, Diagrama de Arquitetura ASCII e ciclo de vida, Estrutura de Módulos e Camadas Hexagonais, Modelo de Dados e Contratos Kafka, Decisões Técnicas e Trade-offs, Guia de Execução Local (Docker Compose + Maven + curl), Diagnóstico Completo dos Problemas Conhecidos (Críticos, Importantes, Melhorias com explicação educacional e direção de correção), Roadmap de Evolução Técnica.
3. Verify tone and language: 100% Portuguese (BR), senior tech mentor pedagogical depth.
4. Verify code preservation: Confirm that no files in `jobs-api/`, `jobs-consumer/`, `k8s/`, `scripts/`, `.github/`, `pom.xml`, `compose.yaml` were touched.

OUTPUT REQUIREMENTS:
Write your review report to `y:\git\jobs\.agents\teamwork_preview_reviewer_1\review.md` and your final handoff report with clear verdict (`APPROVE` or `REQUEST_CHANGES`) to `y:\git\jobs\.agents\teamwork_preview_reviewer_1\handoff.md`.
Send a message to your parent when done.
