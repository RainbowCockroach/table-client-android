# Progress — table-client-android

Plan of record for implementation sessions. Working rules are in `CLAUDE.md`:
one checkpoint at a time → tests green → update this file → `git add -A` →
**stop for review. Never commit, never push.**

Prerequisite: a working `table-server` (local dev build is enough).

| # | Checkpoint | Proves | Status |
|---|---|---|---|
| C1 | Core: `api/` + `crypto/` + `transfer/` with JVM conformance-scenario tests against a local server | conformance tests green | staged for review |
| C2 | Fault-path tests: resume-not-restart in both directions via `TABLE_TEST_FAULTS` + `X-Test-Drop-After` | fault tests green | not started |
| C3 | Settings screen + main list UI; download end-to-end (temp → verify → fsync → ack → MediaStore) | manual: file from server lands in Downloads, disappears from list | not started |
| C4 | Uploads with resume; WorkManager wiring (queue survives process kill); `androidx.work.testing` smoke test | manual kill-and-resume + smoke test green | not started |
| C5 | Share-sheet intake, notifications, polish (expiry countdowns, download-all, Wi-Fi-only toggle) | manual release pass (DESIGN.md §7) | not started |
| C6 | Release CI (deferred): signed APK attached to `v*` tag releases | — | not started |

Status values: `not started` → `in progress` → `staged for review` → `done` (user committed).

## Log

*(append one dated line per session)*

- **2026-07-28 — C1 core staged.** New `crypto/Sha256.kt` (incremental hasher, hex, the 1 MiB
  transfer buffer), `api/` (`TableClient` covering all eight contract operations, `Models`,
  `Errors` with an `isRetryable` policy, `DownloadStream`, one-shot `StreamingRequestBody`),
  and `transfer/` (`Downloader`, `Uploader`, `UploadSource` + `skipFully`). Build: OkHttp 4.12
  and kotlinx.serialization 1.7.3 added; sources live in `src/main/kotlin` per DESIGN §1 while
  the template's Compose files stay in `src/main/java` (the Kotlin plugin registers both, so
  nothing had to move). Template `ExampleUnitTest.kt` deleted. **23 JVM tests green** against a
  dev server (`TABLE_TTL=5s`): conformance scenarios 01–09 driven through the real client code
  paths, plus unit tests for digest-rebuild-from-partial, `skipFully`, and the rule 13 host
  check. Without `TABLE_URL`/`TABLE_API_KEY` the conformance tests skip with a message and the
  unit tests still run. `README.md` gained the dev loop.
  **Reviewer, judgement calls:** (1) `Downloader.download`/`Uploader.upload` are *one attempt*
  each — retry and backoff belong to the caller (WorkManager, C4), and every attempt resumes
  from the temp file's size / the server's `HEAD` offset. (2) Scenario 03 mirrors the shell
  scenario's two clean `PATCH`es rather than a dropped connection: a client-side abort RSTs the
  socket, so the server commits *nothing* and there is no resume to prove — the exact-offset
  drop is scenario 10 (C2) via `X-Test-Drop-After`. A separate test drives a source stream that
  dies mid-upload and asserts the retry resumes from whatever `HEAD` reports. (3) Rule 6 is
  checked against the response's own `Content-Length`/`X-Checksum-SHA256`, and `checkDeclaration`
  additionally fails loudly if the server's declaration for an id ever disagrees with the queued
  one. (4) The ack-`409` → discard path (rule 10) can't be provoked honestly against a correct
  server, so the corrupt-copy test exercises the local verification gate instead. (5) `abortUpload`
  has no caller yet; `api/` is specified as the typed wrapper over the contract, so it covers all
  eight operations. (6) The test task is `outputs.upToDateWhen { false }` — the server is an input
  Gradle cannot fingerprint, so a green run must never let the next one be skipped.
