# Progress — table-client-android

Plan of record for implementation sessions. Working rules are in `CLAUDE.md`:
one checkpoint at a time → tests green → update this file → `git add -A` →
**stop for review. Never commit, never push.**

Prerequisite: a working `table-server` (local dev build is enough).

| # | Checkpoint | Proves | Status |
|---|---|---|---|
| C1 | Core: `api/` + `crypto/` + `transfer/` with JVM conformance-scenario tests against a local server | conformance tests green | staged for review |
| C2 | Fault-path tests: resume-not-restart in both directions via `TABLE_TEST_FAULTS` + `X-Test-Drop-After` | fault tests green | staged for review |
| C3 | Settings screen + main list UI; download end-to-end (temp → verify → fsync → ack → MediaStore) | manual: file from server lands in Downloads, disappears from list | staged for review |
| C4 | Uploads with resume; WorkManager wiring (queue survives process kill); `androidx.work.testing` smoke test | manual kill-and-resume + smoke test green | staged for review |
| C5 | Share-sheet intake, notifications, polish (expiry countdowns, download-all, Wi-Fi-only toggle) | manual release pass (DESIGN.md §7) | staged for review |
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

- **2026-07-29 — C3 settings, main list and download-to-Downloads staged.** New `settings/`
  (`ApiKeyStore` on EncryptedSharedPreferences, `SettingsStore` combining it with a DataStore
  for host URL and the rule 13 override, `TableSettings`), `transfer/` gained the publish half
  of rule 11 (`DownloadPublisher`, `MediaStoreDownloadPublisher`, `DisplayNames`, `DownloadTask`,
  `DownloadQueue`, `Transfers`), and `ui/` the two screens of DESIGN §5 with their view models.
  `TableApp`/`AppContainer` is the object graph; the template `MainActivity`, its instrumented
  test and the espresso deps are gone, and `ui/theme/` moved to `src/main/kotlin` so nothing
  is left in `src/main/java`. **43 JVM tests green** (26 + 17): collision-safe naming, the queue
  state machine and concurrency cap under `kotlinx-coroutines-test`, and four server-backed
  `DownloadTask` tests covering rule 11's ordering — published only after the ack, a failed
  publish keeps the verified copy and the retry publishes it (rule 9 makes the second ack a
  no-op), and a deleted file fails permanently.
  **Manual pass on an API 36 emulator against a dev server, all verified:** rule 13 refusal
  shown in Settings and lifted by the override; a 300 KB file downloaded to `Download/` with a
  matching SHA-256, gone from the server list, temp file removed; a second file of the same
  name landing as `hello-table (1).bin` with the first untouched; and a live-relay download
  started mid-upload (rule 15) verifying and acking byte-identically.
  **Reviewer, judgement calls:** (1) **`minSdk` 26 → 29.** DESIGN §3 publishes to
  `MediaStore.Downloads`, which is API 29+; the pre-29 path needs `WRITE_EXTERNAL_STORAGE`
  and direct file access, which would double the manual-test surface for devices this app
  will not see. (2) The queue is in memory and app-scoped — it survives rotation, not process
  death. Rule 14's persistence is C4's Room table, so a process kill after the ack but before
  the publish currently orphans a verified copy in `cacheDir` rather than resuming it.
  (3) Retry is a button, not backoff: `DownloadTask` is one attempt, and scheduling is
  WorkManager's from C4 on. (4) `EncryptedSharedPreferences` is deprecated in security-crypto
  1.1.0 with no AndroidX replacement; DESIGN §4 and the CLAUDE.md non-negotiable both name it,
  so it stays behind a `@Suppress` — **the spec should pick a successor eventually.**
  (5) `usesCleartextTraffic="true"` in the main manifest, not a debug-only one, so the rule 13
  override works in a release build for a self-hosted LAN server; the app-level refusal is the
  actual gate. (6) Expiry countdowns, download-all and the Wi-Fi-only toggle are C5 per the
  table, so the list shows name, size and upload progress only. (7) Publishing resolves a free
  display name *and* reads back the name MediaStore actually stored — scoped storage hides
  other apps' files from the collision query, and MediaStore uniquifies the rest silently.

- **2026-07-29 — C4 uploads, the persistent queue and WorkManager staged.** The in-memory
  `DownloadQueue` is gone; in its place `transfer/` has the queue rule 14 asks for — a Room
  table (`TransferRecord`/`TransferStore`/`RoomTransferStore`), a `TransferQueue` for what the
  user does to it, a `TransferRunner` that takes one record to a verdict, and `TransferWorker`
  + `WorkTransferScheduler` (one unique `WorkRequest` per record, `NETWORK_CONNECTED`,
  exponential backoff, `dataSync` foreground work with a progress notification). The upload
  half is `UploadTask` over the existing `Uploader`, with `ContentUploadSources`/
  `UriUploadSource` re-opening a `content://` URI and `UploadIntake` persisting the read grant;
  the UI gained an Upload action (`ACTION_OPEN_DOCUMENT`, multi-select) and transfer rows for
  both directions. Build: Room 2.7.2 + KSP, WorkManager 2.10.5, `work-testing` for the
  instrumented suite. **58 JVM tests green** (43 + 15): `UploadTaskTest` drives the runner
  against a real server for rules 1, 2 (a `PATCH` dropped at an exact byte resumes from `HEAD`
  with no new session) and 3 (a rejected finalize clears the session), plus a permanently
  unreadable source; `TransferQueueTest` and `TransferRunnerTest` cover dedupe, retry, dismiss,
  resume-on-boot, the state machine, the attempt cap and the concurrency cap. **2 instrumented
  tests green** (`./gradlew :app:connectedDebugAndroidTest`, DESIGN §7's smoke test): a queued
  upload runs to a finalized file through WorkManager, and an unreachable server yields
  `Result.retry` — `ENQUEUED`, `runAttemptCount` 1 — after which the next attempt resumes the
  same session rather than opening a new one.
  **Manual pass on an API 36 emulator against a dev server (300 MB file), all verified:** an
  upload picked from the share-sheet-less picker finalizing with a matching SHA-256; the app
  killed mid-upload, WorkManager restarting the process and continuing the *same* session
  (the server's `tmp/<session-id>` kept growing) to a matching finalize; a download killed at
  263 MB resuming from its partial temp file to 300 MB, verifying, acking (the server's copy
  disappeared) and publishing as `big (2).bin` with a byte-exact hash; an `uploading` file
  offering Download (rule 15); and the queue surviving a kill with its rows intact, including
  the session-expired retry message after an aborted session.
  **Reviewer, judgement calls:** (1) The store is an interface with a Room implementation so
  the queue, the runner and their tests stay plain Kotlin under JVM tests (CLAUDE.md's last
  non-negotiable); enums are stored by name rather than through a `TypeConverter`.
  (2) A retryable failure is recorded as `FAILED` with `retryable = true`, which is DESIGN §3's
  `failed(retryable)`; WorkManager owns the retry, the UI says "Retrying soon", and `MAX_ATTEMPTS`
  (8) converts it to a permanent failure so a hopeless transfer cannot back off forever.
  (3) `setForeground` is best-effort: Android 12+ refuses a foreground service started from the
  background, which is exactly where a queue resumed after process death starts (seen in
  logcat during the manual pass), so the transfer continues as ordinary background work and
  only the notification is lost. `POST_NOTIFICATIONS` is requested at launch — without it that
  notification is silently dropped on API 33+; the *completion* notifications are still C5.
  (4) The 2-per-direction cap is a semaphore in the runner, not a WorkManager feature, so
  waiting records stay `QUEUED` in the UI. (5) Progress is written to the store at most twice a
  second, and never after the terminal state, so the flow the UI collects is cheap.
  (6) An upload source whose provider reports no size is refused ("share or pick it again"):
  rule 1 needs the size before the session exists, and every picker path supplies it.
  (7) The instrumented test waits on `WorkInfo` rather than trusting `SynchronousExecutor`: a
  `CoroutineWorker` runs off WorkManager's executor, so the test executor returns before the
  transfer is over. Its second attempt is released with `TestDriver.setAllConstraintsMet`,
  which is also how a real retry is unblocked. (8) The Wi-Fi-only toggle (a `UNMETERED`
  constraint) is C5 per the table, so the only constraint today is `NETWORK_CONNECTED`.

- **2026-07-30 — C5 share sheet, notifications and the polish items staged.** New `share/`
  (`ShareActivity`, the DESIGN §4 trampoline for `ACTION_SEND`/`ACTION_SEND_MULTIPLE`),
  `transfer/UploadStaging` (the private copy a non-persistable share grant needs),
  completion/failure notifications in `TransferNotifications` with tap-to-open via the
  published MediaStore uri (`DownloadPublisher` now returns `PublishedDownload`; Room
  migration 1→2 adds `publishedUri`), expiry countdowns and "Download all" on the main
  screen, and the "Upload on Wi-Fi only" setting wired through `TransferScheduler` as
  WorkManager's `UNMETERED` constraint. `SettingsStore.setHost`/`setApiKey` collapsed into
  one `save(TableSettings)`. **74 JVM tests green** (58 + 16): `FormatTest` for the countdown
  and intake wording, `UploadStagingTest` for the copy, its all-or-nothing failure and the
  sweep, and three more `TransferQueueTest` cases for constraint routing, `applyUploadPolicy`
  and staged-copy cleanup on dismiss. **2 instrumented tests still green.**
  **Manual pass on an API 36 emulator against a dev server, all verified:** rule 13 refusal
  and its override; two files downloaded via "Download all", both byte-exact in `Download/`,
  gone from the server, no temp files left; expiry countdowns ticking from `expires_at`;
  completion notifications for both directions, with a downloaded PNG opening in Photos from
  its notification and an `application/octet-stream` one falling back to the app; the share
  sheet showing "Put on the table", queueing with a "Queued 1 for the table ✓" toast and
  finalizing byte-exact; the staged copies swept at the next app start; and — with Wi-Fi off
  so the emulator is on metered LTE — an upload held with `Unsatisfied constraints:
  CONNECTIVITY` against a `NOT_METERED` request, released both by re-enabling Wi-Fi and by
  turning the setting off.
  **Reviewer, judgement calls:** (1) **A share grant is not persistable, so the bytes are
  copied.** `takePersistableUriPermission` fails for a plain `ACTION_SEND` and the grant dies
  with `ShareActivity`, so an upload queued from the share sheet would fail on its very first
  retry — verified on the emulator, where every share produced a staged copy. `UploadIntake`
  therefore takes the copy while it can still read, into `filesDir/staged-uploads`, and the
  record carries a `file:` URI. DESIGN §3, §4 and §6 were updated to say so before the code
  went in. The cost is a second copy of a shared file until it settles; picked files
  (`ACTION_OPEN_DOCUMENT`) still use the persisted grant and are never copied. (2) Copies are
  swept at process start, keeping only what an unfinished record names, and dropped
  immediately on dismiss — so a `DONE` row's copy lives until the next launch. (3) The
  trampoline waits for the intake rather than finishing first, which is the only order in
  which the grant is still valid; a large shared file therefore shows a blank translucent
  window for as long as the copy takes. (4) Toggling Wi-Fi-only re-enqueues unfinished uploads
  (`applyUploadPolicy`) instead of only affecting future ones — otherwise turning it on while
  on cellular would not stop the upload the user turned it on for. Those rows go back to
  `QUEUED` first, because work waiting on a constraint is not running. (5) Notifications are
  posted by `TransferWorker` after the runner returns, not by the runner: the runner is the
  plain-Kotlin half. A retryable failure gets none — the queue says "Retrying soon". (6) The
  settled notification uses its own id and channel; WorkManager cancels the foreground
  progress notification when the work ends and would take the completion one with it.
  (7) `<queries>` for `ACTION_VIEW` is in the manifest so the notification can tell whether
  anything can open the file; when nothing can, tapping opens the app instead. (8) The Room
  schema is migrated rather than rebuilt, so an upgrade cannot drop transfers in flight.

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

## Second open question for the server (found during C3's manual pass)

`bytes_received` does not move during a single `PATCH`, so an `uploading` file sits at 0 in
`GET /files` until the whole upload finalizes.

`Store.Append` calls `commitOffset` once, *after* `commitBytes` has copied the entire request
body (`internal/store/upload.go`), so the row backing `bytes_received` is only written at the
end of each `PATCH`. Root DESIGN §2 says uploading entries "show live progress via
`bytes_received`", and conformance rule 15 says clients show that progress — but §2 also tells
clients to send the file in **one** `PATCH` when the connection holds, and in that shape the
progress reported is always 0 until the file is finished.

Confirmed by polling `GET /files` directly during a throttled upload, so it is not a client
artifact: the Android list renders exactly what the API returns. The live relay itself is
unaffected — the tail-follow reader reads the file on disk, not the row — so a download started
mid-upload still works, which C3's manual pass verified. Only the number shown is stale.
Fixing it belongs in `table-server` (commit the offset periodically inside `commitBytes`), so
no client-side smoothing was added to hide it.

**C4 update — the same bug costs data, not just a number.** During C4's kill-and-resume pass,
`HEAD /uploads/{id}` right after the app was killed 167 MB into a 300 MB `PATCH` answered
`Upload-Offset: 0`, even though `tmp/<session-id>` on the server held all 167 MB. Conformance
rule 2 says the client resumes from exactly what the server reports, so it dutifully re-sent
from zero: a mid-`PATCH` interruption currently throws away every uncommitted byte. The client
side of rule 2 is proven by the fault-injection tests, which pass — the `X-Test-Drop-After`
middleware commits its `n` bytes *deliberately*, so those resume from the right offset; a real
dropped connection does not. The periodic `commitOffset` above is what makes resume worth
having on a large upload, which raises it from cosmetic to the main reason to fix it.

- **2026-07-30 — API prefix dropped (server contract change, tracked here).** The server now
  serves `/files` and `/uploads` at the host root, so `api/TableClient.kt` loses `API_PREFIX`:
  `parseHostUrl` validates the scheme and trims a trailing slash, and the host URL it returns is
  the base for every request — it no longer appends the prefix or strips a pasted copy of it. The
  host test that checked the prefix was not doubled now checks trailing-slash tolerance. No
  transfer, queue or UI code changed; a saved host URL that still ends in `/api/v1` will 404 and
  has to be re-entered in Settings. Full JVM suite green against a root-path dev server (14
  `ConformanceTest` cases, 0 skipped).
