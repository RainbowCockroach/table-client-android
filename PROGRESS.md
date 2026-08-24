# Progress — table-client-android

Plan of record for implementation sessions. Working rules are in `CLAUDE.md`:
one checkpoint at a time → tests green → update this file → `git add -A` →
**stop for review. Never commit, never push.**

Prerequisite: a working `table-server` (local dev build is enough).

| # | Checkpoint | Proves | Status |
|---|---|---|---|
| C1 | Core: `api/` + `crypto/` + `transfer/` with JVM conformance-scenario tests against a local server | conformance tests green | done |
| C2 | Fault-path tests: resume-not-restart in both directions via `TABLE_TEST_FAULTS` + `X-Test-Drop-After` | fault tests green | done |
| C3 | Settings screen + main list UI; download end-to-end (temp → verify → fsync → ack → MediaStore) | manual: file from server lands in Downloads, disappears from list | done |
| C4 | Uploads with resume; WorkManager wiring (queue survives process kill); `androidx.work.testing` smoke test | manual kill-and-resume + smoke test green | done |
| C5 | Share-sheet intake, notifications, polish (expiry countdowns, download-all, Wi-Fi-only toggle) | manual release pass (DESIGN.md §7) | done |
| C6 | Release CI: every push to `main` publishes one signed APK as the sole GitHub Release | a run produces an installable APK | done |
| C7 | **Adopt `../UI.md`**: shelf under 900dp and the rail at or above it (there is no tablet layout today — the phone layout runs at every width); icon pass; reveal-in-folder on landed rows (**done 2026-08-23**, see the log); palette + authored dark mode from `../tokens.json`, seeding a static `ColorScheme` (dynamic colour deliberately not adopted); §11 shape and depth — `CircleShape` on icon buttons (M3 Button is already full-round) and `buttonElevation(default 2.dp, pressed 0.dp)`, letting M3 tonal elevation handle dark rather than hand-authoring a dark shadow | `UI.md` §12 checklist holds on a phone and on a tablet in both orientations; manual pass | staged for review |
| C8 | **Intake ladder, text rung** (`../DESIGN.md` §3 rules 16–23). `UploadIntake` gains a text entry beside `accept(uris)`: UTF-8 with no BOM (rule 21) through `staging.stage { bytes.inputStream() }` → `stagedSourceUri` → `queue.upload(uri, name, size)`, named by rule 22 (first non-empty line → 30 chars → `safeDisplayName` → `.txt`, timestamp fallback when the line is unusable), blank-or-whitespace text rejected before it becomes a row rather than after. `ShareActivity` reads `EXTRA_TEXT`, preferring `EXTRA_SUBJECT` for the name when the sender offers one, with `EXTRA_STREAM` still taking precedence when both are present. No new machinery — see the note in `DESIGN.md` §4 | manual: select a line in a note app → Share → **table** → a `.txt` reaches the table named after the line; sharing a photo *with* a caption still sends the photo; sharing a blank selection is refused with a sentence, not a queued row | staged for review |
| C9 | **Paste** (`../UI.md` §2's leading flank, §6's `paste` glyph, checklist 27–30) and the image rung. An icon-only control mirroring settings; enabled state from `getPrimaryClipDescription()` — toast-free, and recomputed on resume because API 29+ returns null without focus — and `getPrimaryClip()` read only on the tap. The image rung stages the clip's `content://` bytes unchanged (rule 20, no re-encode) | manual: copy a screenshot, then a line of text, each reaching the table with the right extension; the *"table pasted from your clipboard"* toast appears and is expected; the control is disabled on an empty clipboard rather than showing a notice | not started |

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

- **2026-07-30 — main-screen wording and controls (UI only).** "Clear all" on the Transfers
  header dismisses every settled transfer at once (`MainViewModel.dismissFinished`, which reuses
  `TransferQueue.dismiss` per record so scheduler cancellation and staged-upload cleanup still
  happen). Wording follows the table metaphor: "On the server" → "On the table", "Download" →
  "Take", "Download all" → "Take all" (DESIGN §5 updated). Text actions that have an unambiguous
  glyph became `IconButton`s — upload (+), settings (cog), retry (refresh), dismiss (×), settings
  back-arrow — which needed `material-icons-core` on the compile classpath; recent `material3`
  no longer brings it in transitively. No transfer or protocol code touched; JVM suite green,
  `assembleDebug` green. Not yet run on a device.

- **2026-07-30 — the ongoing progress notification outlived its transfer.** Reported on a small
  file that finished almost instantly, and reproducible in both directions. `TransferWorker`
  posted progress on every record change, including the terminal one, and `progress.cancel()`
  could abandon a `setForeground` mid-flight. WorkManager takes the notification down from
  `onExecuted`, straight onto the main thread, while `setForeground` delivers through
  `startService` and a binder round trip — so a post issued as the worker returned landed after
  the takedown and stayed in the shade for good. The collector now stops at the settled record
  (`takeWhile { … && !it.isFinished }`) and each `setForeground` runs under `NonCancellable`, so
  the last post is always complete before `doWork` returns and WorkManager's takedown is the last
  word. `TransferNotifications.progress` derives the notification id from the record instead of
  taking it as an argument (`progressNotificationId`, beside `settledNotificationId`). JVM suite
  green (transfer cases skipped, no dev server up), `assembleDebug` green. Needs a device check:
  notification gone on completion, both directions.

- **2026-07-31 — C6 release CI, logged after the fact** (it shipped in `17fe73e`/`54d5254` on
  2026-07-29 without a log line). **The shape differs from the row this table originally carried.**
  Instead of a signed APK attached to `v*` tags, `.github/workflows/release.yml` builds on every
  push to `main` and keeps exactly one release: it publishes `v<baseVersionName>.<run number>`
  and then deletes every other release and its tag. `versionCode` is the run number and
  `versionName` appends it to `baseVersionName` in `gradle.properties`, so neither needs a commit
  to move forward — and renaming or recreating the workflow would reset the run number and push
  `versionCode` backwards, which `README.md` says to fix by bumping `baseVersionName`. Signing
  keys come from four repository secrets; without them the run still succeeds and warns, but the
  APK is unsigned and uninstallable. A first attempt targeted GitLab CI (`.gitlab-ci.yml`,
  `17fe73e`) and was replaced by the GitHub workflow in the next commit.

- **2026-07-31 — release build minified; API key visibility toggle.** `isMinifyEnabled` and
  `isShrinkResources` are on for `release`, so `proguard-rules.pro` is now real rules rather than
  the template's comments: keep the `@Serializable` companions R8 full mode would drop,
  `-dontwarn` for OkHttp's absent TLS providers and Tink's Error Prone annotations, `-keepnames`
  on `TransferWorker` (WorkManager instantiates it by the class name stored in its own database,
  so an obfuscated name would strand transfers queued before an upgrade), and the line-number
  attributes that make `mapping.txt` useful. The workflow now ships `mapping-<version>.txt.gz`
  beside the APK, since the prune leaves no older release to recover it from. Also
  `localeFilters += "en"`, `META-INF` packaging excludes, `dependenciesInfo` off, and a show/hide
  toggle on the Settings API key field (`material-icons-extended`).
  **74 JVM tests green** (23 skipped, no dev server up), `assembleRelease` green — 3.2 MB unsigned.
  **Not yet run on a device.** R8 full mode plus reflection is exactly what the JVM suite cannot
  cover, so the minified APK still needs an install-and-transfer pass before it is trusted: a
  download, an upload, and a queue that survives a process kill.

- **2026-07-31 — the battery-optimization exemption, surfaced instead of only documented.**
  DESIGN §6 asked for this to be documented; it is now a Settings row as well, because the
  README is not where the user is standing when a queued transfer stalls. New
  `ui/BatteryOptimization.kt`: `rememberBatteryExemption` reads
  `PowerManager.isIgnoringBatteryOptimizations` and re-reads it in a `LifecycleResumeEffect`
  (the user grants the exemption on a system screen and comes back, so a value read once would
  be stale exactly when it matters), and `openBatteryOptimizationSettings` launches
  `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, falling back to the app's own details page on
  a ROM that has no such screen. `SettingsScreen` gained `BatteryOptimizationNotice`, which
  renders nothing once the app is exempt. DESIGN §5 and §6 were updated before the code went
  in, and §3 now records the Android 12+ background foreground-service refusal that until now
  lived only in C4's judgement calls. `README.md` gained a "Battery optimization" section with
  the manual path and the `dumpsys deviceidle` commands.
  **74 JVM tests green** (23 skipped, no dev server up), `assembleDebug` green.
  **Reviewer, judgement calls:** (1) `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is *not* declared —
  it would buy a one-tap dialog for a setting changed once, and Play policy restricts it to
  apps Doze actually breaks. The list screen needs no permission. (2) The notice is not a
  stored preference and so is not part of `TableSettings`: it reports OS state, and the only
  thing the app can do about it is open a screen. (3) No test — `PowerManager` is not reachable
  from the JVM suite and DESIGN §7 rules out UI automation, so this is a manual-pass item.
  (4) Whether the exemption also lets a background `setForeground` succeed is Android's
  documented behaviour but unverified here; the device check below is what would settle it.

- **2026-07-31 — "Check for update" in Settings.** The APK is side-loaded from GitHub Releases,
  so nothing on the device knows a newer build exists. New `update/UpdateChecker.kt`: an
  unauthenticated `GET /repos/RainbowCockroach/table-client-android/releases/latest`, a
  `compareVersions` that ranks the release tag against the installed `versionName`
  component-wise (leading `v` optional, a missing component counts as zero, an unrankable tag is
  a failed check rather than an update), and an `UpdateStatus` of
  `Checking | UpToDate | Available | Failed`. `AppContainer` builds it with the installed
  `versionName` from `PackageManager` and the shared OkHttp client, re-bounded with a 15 s
  `callTimeout` — the transfer client deliberately has none, and a button press must not hang
  forever. `SettingsScreen` gained a version row with the button; `Available` opens an
  `AlertDialog` whose Download button hands `RELEASES_PAGE_URL` to a browser via `ACTION_VIEW`,
  and a device with nothing to take the URL turns the prompt into a `Failed` line instead of
  dismissing silently. DESIGN §1/§4/§5/§7 and `README.md` were updated before the code went in.
  **79 JVM tests** (55 passed, 24 skipped with no dev server up), `assembleDebug` and
  `assembleRelease` green.
  **Verified on the API 36 emulator against the live GitHub API**, all three answers: installed
  `1.0.1` → "Update available / Version 1.0.5 is out", Download launching Chrome on the releases
  page; `-PbuildNumber=5` → "Up to date."; Wi-Fi and data off → "Couldn't check: Unable to
  resolve host api.github.com". The emulator has the `1.0.1` debug build installed.
  **Reviewer, judgement calls:** (1) The check is a button, never a timer and never a silent
  download — an app that side-loads its own APK would need `REQUEST_INSTALL_PACKAGES`, which is
  far more power than "tell me when there's a new build" is worth. (2) It sends the user to the
  releases *page* rather than the release's `html_url`: the workflow keeps exactly one release,
  so they are the same target, and the constant needs no response field to be right.
  (3) `versionName` comes from `PackageManager`, not `BuildConfig` — it is the version actually
  installed, and it keeps `buildConfig` off. (4) No test for the HTTP call itself: the suite has
  no MockWebServer and DESIGN §7 puts the network path in the manual pass, so only the ranking
  is unit-tested. (5) The 404 GitHub returns for a repo with no releases surfaces as
  `Failed("GitHub returned HTTP 404")` — honest, and unreachable while the release workflow runs.

- **2026-08-23 — "Show in folder" on a landed download.** A taken file said where it went and
  nothing more; this is `../UI.md` §5's reveal, the first piece of C7 to land. New
  `transfer/PublishedDownloads.kt` (does the published `content://` row still resolve?) and
  `ui/DownloadsFolder.kt`, which picks the first intent the device answers:
  `ACTION_VIEW_DOWNLOADS`, then `ACTION_VIEW` on DocumentsUI's `primary:Download` as
  `vnd.android.document/directory`. `MainViewModel` gained `goneDownloads` — recomputed when the
  landed URIs change and again on each 5 s list poll, since a file manager can delete the file
  with nothing to tell the app — and `intakeMessage` became `notice`, now that a failed reveal
  shares the banner. `TransferRow` shows a folder icon on a landed download and drops it, with
  the meta line becoming `moved or deleted`, once the copy is gone. `../UI.md` §5 and DESIGN §4
  were reconciled first (see judgement call 1). Manifest: a `VIEW_DOWNLOADS` `<queries>` entry,
  without which the resolve check is blind on API 30+.
  **79 JVM tests** (55 passed, 24 skipped with no dev server up), `assembleDebug` green.
  **Verified on the API 36 emulator**: the folder button shows on landed downloads and on no
  other row; tapping it resumes DocumentsUI's `ViewDownloadsActivity` at `Download/`; deleting
  a published row from MediaStore turns that row into `moved or deleted` with only its dismiss
  left, within one poll. Device check 5 below is the end-to-end version against a live server.
  **Reviewer, judgement calls:** (1) `../UI.md` said "`ACTION_VIEW` on the MediaStore collection",
  which nothing on the device handles — DocumentsUI answers `VIEW_DOWNLOADS`, and there is no
  select-this-file intent on Android at all. The spec cell now names the two calls and a
  sentence says the target is the folder, not the file; the other three clients are untouched.
  (2) Reveal always goes to `Download/` rather than to the file's own parent — every taken file
  is published there, so the two are the same place. (3) The button is absent, not disabled,
  when neither intent resolves: a device with no file manager has nothing to show.
  (4) The landed *upload* row's reveal (`../UI.md` §5's last line) is not in this change — the
  source is a `content://` grant, not a Downloads entry, and it wants its own decision.
  (5) No test: `Intent`, `PackageManager` and `MediaStore` are all outside the JVM suite and
  DESIGN §7 puts them in the manual pass.

- **2026-08-24 — C7: the shelf, the rail, the palette, and §11's shape and depth.** The rest of
  `../UI.md`, in one pass. **Layout**: a root `BoxWithConstraints` reads the width the app
  actually has and flips at `900dp` — shelf below, rail at or above — so freeform, split-screen
  and a phone all reach the compact layout through one code path and there is no `isPhone`
  anywhere (rule 1). The queue stopped being a section inside the table's scroll and became a
  panel with its own `LazyColumn` in both layouts (rules 2, 3): `QueueRail` is 380–480 floor to
  ceiling with `Nothing moving` when it is empty, `QueueShelf` docks to the bottom edge, peeks at
  two rows, drags to 60% of the window, collapses to its header, floats over the table rather
  than pushing it, and is absent entirely when the queue is empty (rule 6). At rail width the
  action bar and the notice lane belong to the table's column, not the window (§1). The row
  column caps at 720 inside a region ground that spans the column, so the margins absorb a
  tablet's extra width (rule 5). **Regions**: the identity bar lost its wordmark and became §2's
  action bar — the intake centred as the app's only filled button, settings trailing, no divider
  beneath (§11.4). **Rows**: one `FileRow` skeleton draws both sides (rule 7) — glyph, name, meta
  line, progress rule, trailing slot; the trailing slot is now `[state action] + [dismiss]` in
  every state, which gives an in-flight row the stop it was missing (rule 8); the row body opens
  a landed file (rule 9, verified against the system resolver); *Take* dropped its word for §6's
  glyph. **Colour**: `../tokens.json` is now the whole palette — a `TableColors` holding every
  token light and dark, seeding a static `ColorScheme` and riding beside it in
  `LocalTableColors`. Dynamic colour is gone (§10), `forceDarkAllowed` is off, and
  `UiModeManager.getContrast` drops the brand layer where the OS asks for contrast (rule 17).
  **Shape and depth** (§11): `RaisedIconButton` is a 40 circle in a 44 hit rectangle on a 2px
  wall that collapses under the press while the button travels the same 2px; `GhostIconButton`
  (dismiss) never gets a cap; *Take all* and *Clear* became real pills instead of text links, so
  the section header grew to 52 to clear a control and its wall. Region grounds are wells at 6%
  over the top 2px; region boundaries take the seam, row separators keep the plain hairline.
  **79 JVM tests, 77 passed against a live dev server** (one skipped: scenario 07 wants a short
  TTL and the manual pass wanted a long one), `assembleDebug` green.
  **Verified on the API 36 emulator against a live dev server**: phone at 411dp — action bar,
  notice lane, table rows, take, the shelf peeking at two rows, dragging to 60% and collapsing;
  tablet at 1706x1066dp — the rail floor to ceiling with the bars inside the table's column and
  the rows centred under the 720 cap; authored dark at both widths; tapping a landed `notes.txt`
  row body opened the system resolver.
  **Reviewer, judgement calls:** (1) **`../UI.md` §7's state strings are deliberately not
  adopted** — the user's call this session: they are placeholder wording, and this client keeps
  its own (`Queued`, `Verifying`, `Saved to Downloads as …`). Rule 14 is knowingly open; do not
  "fix" it without asking. (2) A landed **upload** row *opens* its source rather than revealing
  it: the source is a `content://` grant inside whichever app the picker came from, and no file
  manager can be pointed at it — the same substitution §5 makes for iOS, and it is recorded in
  DESIGN §4. (3) The table row's take button is absent, not disabled, while that file is in the
  queue, and the queue row's state joins the table row's meta line — a status word in the
  trailing slot would have broken rule 8. (4) Dismissing an in-flight download leaves its partial
  temp file in `cacheDir` until the OS sweeps the cache; a dedicated sweeper is not worth the
  code for a directory Android already reclaims. (5) The rail is not drag-resizable — §2 asks for
  that only where the platform has pointer resize — and takes 32% of the width inside 380–480.
  (6) Region 5, the drop surface, is not built; DESIGN §5 records why it is its own checkpoint.
  (7) No tests: every line here is Compose, and DESIGN §7 rules out UI automation.

- **2026-08-24 — an own upload on the table read `0 B` (bug fix, no checkpoint).** From a
  screenshot: the table row said `0 B of 7.9 MB · uploading` with a frozen bar and its `arriving`
  tag while the shelf, one region below, correctly read `3.1 MB of 7.9 MB`. The server writes
  `bytes_received` only when a `PATCH` ends and a whole file goes up in one, so the listing had
  nothing better to say — fixed there too (`../table-server`, same date). This side: `ServerFileRow`
  matches the file to a live transfer of its own (the upload session id *is* the file id) and takes
  `max(listing, ours)` for the meta line and the bar, which also carries the row across the 5s poll
  interval; `describe` takes the byte count as a parameter for it. §7's strings are untouched — the
  numbers were wrong, not the words, and `arriving` is what an uploading file says. The take button
  now goes for *any* unfinished transfer on that file, not only a download: offering to fetch back
  what this device is still sending was the same round trip twice. New `MetaLinesTest` covers the
  three meta lines; `assembleDebug` clean. **Reviewer:** unstaged tree, one new test file. Not seen
  on a device — no phone was attached, so it is in the pending list below.

- **2026-08-24 — the intake ladder specified; a live share-sheet dead end found (docs only, no
  code).** `../DESIGN.md` §3 gained an **Intake** group, rules 16–23: one ladder — files → image →
  plain text — behind all four entry points, resolved per item, falling through on a read failure
  rather than failing the intake, with one shared naming rule and a metadata-is-free-bytes-are-not
  privacy rule. `../UI.md` gained the paste control (§2's leading flank, §6's thirteenth glyph and
  its wordless exception, §8's notice, §11.5's Apple caution, checklist 27–30) and promoted the
  `260` pill cap into §3, because §2's flank arithmetic now depends on it. **Found while answering
  a question, verified from code, not yet seen on a device:** `ShareActivity.sharedUris` reads only
  `EXTRA_STREAM`, and the manifest filter is `*/*` — so sharing selected text to table is offered
  in the share sheet and then refused with *"Nothing to put on the table."* That is C8. No Android
  code was touched this session; nothing to build, nothing to install.

- **2026-08-24 — C8, the text rung of the intake ladder, staged.** New `transfer/TextUploads.kt`
  holds rules 21 and 22 as two pure functions: `textUploadBytes` (UTF-8, no BOM) and
  `textUploadName`, which takes the first non-empty line, clips it to 30 characters without
  splitting a surrogate pair, and runs it through the download path's own sanitiser — now
  `sanitizedName` in `DisplayNames.kt`, with `safeDisplayName` its `download` fallback, so one
  sanitiser still serves both. `UploadIntake` gained `accept(text, offered)` beside
  `accept(uris)`: blank text is refused before it can become a row, and the rest goes
  `staging.stage { bytes.inputStream() }` → `stagedSourceUri` → `queue.upload`, no new
  machinery as DESIGN §4 promised. `ShareActivity` now reads `EXTRA_TEXT` when `EXTRA_STREAM`
  is empty (rung 1 before rung 3: a photo shared with a caption is a photo) and prefers
  `EXTRA_SUBJECT` for the name, and — found on the emulator, see below — falls through from an
  unreadable stream to the text rather than failing the intake (rule 17). **92 JVM tests green**
  (81 + 11) against a dev server, `assembleDebug` clean, and **run on the API 36 emulator**:
  a two-line share landed as `Milk and bread.txt` byte-for-byte (no BOM); a share with a subject
  landed as `Example Domain.txt` carrying the URL; a whitespace-only share was turned away with
  *"Nothing to put on the table."* and queued nothing; a real share sheet from the Files app sent
  `picture.png` unchanged (7858 bytes, rule 19); and a `.txt` taken back down arrived in
  `Download/` with exactly its text. The one corner the emulator cannot stage — a *readable*
  stream shared together with a caption — is pending check 8 below.
  **Reviewer, judgement calls:** (0) **Rule 17 was missing and is now in.** The first device run
  shared a photo *with* a caption; the stream could not be read and the intake answered
  *"Couldn't add 1 file(s)."* — but rule 17 says a rung that is offered and fails to read falls
  through to the next. `ShareActivity.ladder` now runs the text rung when the streams queue
  nothing, which is also what makes the emulator's ungrantable media URI a *positive* test of
  rule 17: the caption became `a caption for the photo.txt`. The ladder's ordering lives in the
  activity because a share is the only entry point today offering two representations of one
  item; C9's clipboard offers several at once, and that is when it lifts into `UploadIntake`.
  (1) **The root spec gained one clause.** Rule 22 named a
  fallback for images and none for text, so `../DESIGN.md` §3 rule 22 now says text whose line
  survives neither the trim nor the sanitiser takes `Text YYYY-MM-DD HH-MM-SS` — the same shape
  with the right noun, written into the shared rule rather than decided quietly on one client.
  (2) **A pasted URL keeps its colon**: the shared sanitiser flattens separators and control
  characters only, so `https://example.com/a` is named `https:__example.com_a.txt`. Rule 22
  forbids colons in the *timestamp*; a name the user's own text produced is sanitised again by
  whichever client writes it to disk. (3) **Text is read from `ACTION_SEND` only.** A
  `SEND_MULTIPLE` may in principle carry a `CharSequence` list, but no share sheet produces one,
  and folding several results into one `IntakeResult` is machinery for a case that does not
  arise. Streams still take both actions. (4) `UploadIntake` is Android-typed on rung 1 alone,
  so the new tests construct it with a bare `ContentResolver` subclass the text rung never
  touches — that is what lets the rung be tested under JVM at all. (5) The blank-text sentence
  stays *"Nothing to put on the table."*: from the sender's side a whitespace selection and an
  empty share are the same event.

## Pending device checks

Carried from the entries above; none is blocked, all need an emulator or phone.

1. The main screen on a real device: the whole C7 layout was checked on the emulator, so what
   is left is a phone in the hand and a real tablet in both orientations (2026-08-24, replacing
   the 2026-07-30 icon-button pass, whose screen no longer exists).
2. The ongoing progress notification disappearing on completion, both directions (2026-07-30).
3. A full transfer pass on the **minified** release APK (2026-07-31).
4. The battery notice: shown while optimized, gone after the exemption is granted, and — with
   it granted — whether a retry resumed from the background now keeps its progress notification
   instead of logging the foreground-service refusal (2026-07-31).
5. Reveal end to end: take a file, tap **Show in folder**, and confirm the Files app opens at
   `Download/`; then delete the file from there and confirm the row turns into `moved or
   deleted` with only its dismiss left (2026-08-23).
6. An upload of this device's own, watched on the table: the row's bytes and bar must track the
   shelf's rather than sitting at `0 B`, and no take button until it lands (2026-08-24).
7. A photo shared *with* a caption from a real sender (Google Photos, Gmail) must still send
   the photo: the emulator cannot grant a media URI to an `adb`-injected share, so rung 1
   winning over a *readable* stream's caption is the one branch not yet seen run (2026-08-24).
