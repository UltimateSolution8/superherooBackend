# Agent Rules for Codex and Antigravity

## Work Efficiently
- Read only files needed for the current task.
- Prefer `rg` and targeted file reads over broad scans.
- Do not inspect, summarize, or include unrelated documents.

## Skip by Default
Avoid these unless explicitly required:
- `node_modules/`, `.git/`, `dist/`, `build/`, `target/`, `.gradle/`, `Pods/`, `coverage/`, `.cache/`
- archives: `*.zip`, `*.tar`, `*.tar.gz`, `*.rar`
- binaries/media: `*.apk`, `*.aab`, `*.png`, `*.jpg`, `*.jpeg`, `*.webp`, `*.mp4`
- documents/spreadsheets/PDFs: `*.pdf`, `*.docx`, `*.xlsx`, `*.pptx`
- generated reports and large testing artifacts unless the task is specifically about them

## Secrets and Safety
- Never print full secrets, API keys, tokens, service-account JSON, or private credentials.
- Use environment variables for new keys.
- Do not rewrite unrelated files.
- Do not run destructive git commands unless the user explicitly asks.

## Implementation Style
- Reuse existing backend APIs, app patterns, validation, auth, and realtime contracts.
- Keep changes scoped and production-oriented.
- Add tests for changed behavior.
- Run build/tests before final handoff.
