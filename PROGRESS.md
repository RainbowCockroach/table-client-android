# Progress — table-client-android

Plan of record for implementation sessions. Working rules are in `CLAUDE.md`:
one checkpoint at a time → tests green → update this file → `git add -A` →
**stop for review. Never commit, never push.**

Prerequisite: a working `table-server` (local dev build is enough).

| # | Checkpoint | Proves | Status |
|---|---|---|---|
| C1 | Core: `api/` + `crypto/` + `transfer/` with JVM conformance-scenario tests against a local server | conformance tests green | not started |
| C2 | Fault-path tests: resume-not-restart in both directions via `TABLE_TEST_FAULTS` + `X-Test-Drop-After` | fault tests green | not started |
| C3 | Settings screen + main list UI; download end-to-end (temp → verify → fsync → ack → MediaStore) | manual: file from server lands in Downloads, disappears from list | not started |
| C4 | Uploads with resume; WorkManager wiring (queue survives process kill); `androidx.work.testing` smoke test | manual kill-and-resume + smoke test green | not started |
| C5 | Share-sheet intake, notifications, polish (expiry countdowns, download-all, Wi-Fi-only toggle) | manual release pass (DESIGN.md §7) | not started |
| C6 | Release CI (deferred): signed APK attached to `v*` tag releases | — | not started |

Status values: `not started` → `in progress` → `staged for review` → `done` (user committed).

## Log

*(append one dated line per session)*
