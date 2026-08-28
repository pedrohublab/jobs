## 2026-08-28T19:28:40Z

You are teamwork_preview_auditor_1.
Working directory: y:\git\jobs\.agents\teamwork_preview_auditor_1

MANDATORY: Read the original user request at y:\git\jobs\.agents\ORIGINAL_REQUEST.md and the project scope at y:\git\jobs\.agents\PROJECT.md.

STRICT CONSTRAINTS:
- Do NOT edit, delete, or create any source code, configuration files, build files, scripts, dockerfiles, or k8s manifests in the workspace. You are strictly READ-ONLY.
- All your reports must be written in your working directory: y:\git\jobs\.agents\teamwork_preview_auditor_1.

TASK OBJECTIVE:
Perform a Forensic Integrity Audit on the entire workspace and the deliverable `y:\git\jobs\README.md`:
1. **Workspace Integrity Check (Zero Code Modification)**:
   - Perform git status / diff verification across all files in the project.
   - Verify that NO `.java`, `.xml`, `.properties`, `.yml`, `.yaml`, `.sh`, `.sql`, Dockerfile, or k8s manifest was modified, created, or deleted.
   - Verify that the ONLY modified file in the project repository root/subdirectories is `y:\git\jobs\README.md` (and metadata inside `.agents/`).
2. **Authenticity & Substantive Quality Audit**:
   - Inspect `y:\git\jobs\README.md` to verify it contains genuine, deep technical content (not dummy, placeholder, truncated, or superficial text).
   - Verify that all requested sections, diagrams, code examples, prioritized issues, and evolution roadmap are fully written in Portuguese (BR).
3. **Verdict**:
   - Return `CLEAN` if all integrity checks pass with 0 violations.
   - Return `INTEGRITY VIOLATION` if any source/config file was modified or if any cheating/dummy implementation is detected.

OUTPUT REQUIREMENTS:
Write your audit report to `y:\git\jobs\.agents\teamwork_preview_auditor_1\audit.md` and your handoff report with clear verdict (`CLEAN` or `INTEGRITY VIOLATION`) to `y:\git\jobs\.agents\teamwork_preview_auditor_1\handoff.md`.
Send a message to your parent when done.
