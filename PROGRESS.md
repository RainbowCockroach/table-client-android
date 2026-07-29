# Progress — table-client-android

Plan of record for implementation sessions. Working rules are in `CLAUDE.md`:
one checkpoint at a time → tests green → update this file → `git add -A` →
**stop for review. Never commit, never push.**

Prerequisite: a working `table-server` (local dev build is enough).

| # | Checkpoint | Proves | Status |
|---|---|---|---|
| C1 | Core: `api/` + `crypto/` + `transfer/` with JVM conformance-scenario tests against a local server | conformance tests green | staged for review |
| C2 | Fault-path tests: resume-not-restart in both directions via `TABLE_TEST_FAULTS` + `X-Test-Drop-After` | fault tests green | staged for review |
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
- **2026-07-28 — C2 fault paths staged.** New `FaultInjector` interceptor in `testsupport`
  arms `X-Test-Drop-After` (root DESIGN §2) on the next request of a given method, one-shot so
  the resume it is testing reaches the server intact; `TestServer` gained `faultsEnabled` from
  `TABLE_TEST_FAULTS`, and `clientOrNull` now takes `vararg Interceptor` instead of one
  `RequestLog`. **26 JVM tests green** (23 + 3): scenario 10's two directions — a download
  dropped at byte 300000 resuming via `Range: bytes=300000-`, and a `PATCH` dropped at the same
  offset resuming from the `HEAD` offset — plus a download dropped *twice*, which is the only
  way to land a drop on an already-partial file and so the only real exercise of the
  rebuild-the-digest-from-disk path. Both drop tests also assert the thrown `IOException` is
  `isRetryable`, since C4's backoff depends on that classification. Without `TABLE_TEST_FAULTS=1`
  the three skip with a message; it is now forwarded by the Gradle test task and documented in
  `README.md`'s dev loop.
  **Reviewer, judgement calls:** (1) Scenario 10 lives in `ConformanceTest` rather than a new
  file — the class is the scenario→test map, and the fault tests reuse `uploadFully`,
  `assertResumesFrom` and `downloadVerified` unchanged. The injector is installed on every
  test's client but is inert until armed. (2) The drop offset (300000) and file size (1 MiB)
  are kept identical to `10_fault_injection.sh` so the two suites fail the same way.
  (3) **Pre-existing server race found, not fixed** — see below.

## Open question for the server (found during C2, not caused by it)

`scenario 08` (live relay) fails roughly 1 run in 8, on both this checkpoint's code and the
committed C1 baseline: `expected:<DELETED> but was:<ALREADY_GONE>`.

It is a real race in `table-server`, not a test artifact. `tailReader.Read` returns `io.EOF`
as soon as `pos >= size` (`internal/store/relay.go`), and the frontier reaches `size` inside
`commitBytes` — *before* `finalize` hashes the file, renames it, and flips the row to
`available` (`internal/store/upload.go`; `relays.finish` is its last statement). `handleAck`
404s any id whose state is not `available` (`internal/api/files.go`). So a client that
tail-follows to the last byte can verify and ack inside the finalize window and be told the
file is already gone.

No data loss — the client keeps its verified copy, and rule 9 says treat `404` as success —
but the ack silently fails to delete the server's copy, so a live-relay download leaves the
file to sit until its TTL. Fixing it belongs in `table-server` (the narrow fix is to hold EOF
until `finalized`), so this checkpoint leaves the assertion strict rather than papering over it.
