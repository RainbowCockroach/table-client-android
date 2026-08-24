# table-client-android — design

Native Android client: **Kotlin + Jetpack Compose**, single-module app.

The wire protocol, lifecycle rules, and integrity guarantees live in the root `DESIGN.md` of the `table` project; this document covers only what is specific to the Android client. Every transfer path here must satisfy the **client conformance checklist** in the root doc.

---

## 1. Project structure

```
table-client-android/
  app/
    src/main/kotlin/<pkg>/table/
      api/          # TableClient (OkHttp + kotlinx.serialization): typed wrapper over the HTTP API
      transfer/     # WorkManager workers, queue repository, resume logic
      crypto/       # streaming SHA-256
      settings/     # EncryptedSharedPreferences-backed settings store
      update/       # GitHub release check behind the Settings "Check for update" button
      share/        # ShareActivity: share-sheet intake
      ui/           # Compose screens: Main, Settings
    src/test/       # JVM tests for api/ + transfer/ against a local table-server
```

Everything outside `ui/` and `share/` is plain Kotlin with no Android UI dependencies, so the protocol logic runs under fast JVM tests.

## 2. Networking

**OkHttp** throughout; the `Authorization` header is attached by a single interceptor in `TableClient`, never at call sites.

- **Upload**: a custom `RequestBody` that streams from a `ContentResolver` `InputStream`. Resume after failure: `HEAD` for the server's committed offset, re-open the source stream, `skip()` to that offset, `PATCH` the remainder. (Content providers don't expose seekable streams; re-open + skip is the equivalent and only costs a local read.)
- **Download**: stream the body to a temp file in ~1 MiB buffers while feeding a `MessageDigest`. Resume: `Range` from the partial temp file's size, appending; on resume the digest is rebuilt by re-feeding the existing partial bytes, then continues incrementally.
- **Timeouts**: no overall call timeout on transfer requests (a 4 GB file must not race a stopwatch); an inactivity watchdog (~60 s without progress → fail retryable) instead. Same policy as the other clients.

## 3. Transfer queue

- **WorkManager** is the executor: one `WorkRequest` per file, survives process death and reboot, `NETWORK_CONNECTED` constraint, exponential backoff. Long transfers run as foreground work (`dataSync` service type) with a progress notification. Promotion to foreground is best-effort: Android 12+ refuses a foreground service started from the background, which is exactly where a retry or a reboot-resume begins, and the transfer then continues as ordinary background work with only the notification lost. Battery-optimization exemption (§6) is one of the documented conditions that lift that refusal.
- A small **Room/SQLite queue table** carries what WorkManager doesn't: upload session id, file id, temp path, bytes done — the state the worker needs to resume via `HEAD`/`Range` instead of restarting, plus what the UI needs to render the queue.
- States: `queued → running → verifying → done | failed(retryable) | failed(permanent)`.
- Concurrency cap: 2 uploads / 2 downloads.
- Shared and picked `content://` URIs are persisted with `takePersistableUriPermission` so a retry after process death can still read the source; a grant that cannot be persisted is replaced by a private copy of the file instead (§4). Copies are swept at process start once no unfinished record still names them.
- Download completion order (conformance rule): temp file in `cacheDir` fully written → verify length + SHA-256 → `FileDescriptor.sync()` → **ack** → publish to `MediaStore.Downloads` (collision-safe naming). Publish failure never loses data — the verified temp file remains and the publish is retried.

## 4. Android integrations

- **Share sheet**: intent filters for `ACTION_SEND` and `ACTION_SEND_MULTIPLE` (`*/*`). `ShareActivity` is a near-invisible trampoline: secure the sources, enqueue work, show a "queued ✓" confirmation, finish. *Securing* means `takePersistableUriPermission` where the sender offered a persistable grant (the in-app picker always does), and otherwise a private copy of the bytes taken before the activity finishes — a plain `ACTION_SEND` grant is revoked with the receiving activity, so a queued upload would have nothing left to read on its first retry.

  **Text share** (`../DESIGN.md` §3 rules 16–22): a share whose `EXTRA_STREAM` is empty falls to `EXTRA_TEXT`, which `UploadIntake.accept(text, offered)` writes as UTF-8 with no BOM into a staged `.txt`. A stream still wins when a sender supplies both, because rung 1 precedes rung 3 and a photo shared with a caption is a photo. The name comes from `EXTRA_SUBJECT` where the sender offers one — a browser sends the page title there and the URL as the text — and otherwise from the text's own first line. Blank text is refused with a sentence rather than queued as an empty file, and a stream that cannot be read falls through to the caption rather than failing the intake (rule 17) — `ShareActivity.ladder` holds that ordering until C9's clipboard, which offers several representations of one item at once, lifts it into `UploadIntake`. Only `ACTION_SEND` is read for text: no share sheet produces a multi-text send, and rule 18's per-item resolve is what a single item gets.

- **In-app picker**: `ACTION_OPEN_DOCUMENT` with multi-select.
- **Notifications**: per-transfer progress (from the foreground worker) plus completion/failure; tapping a completed download opens the file via `MediaStore`.
- **Reveal in the file manager** (`../UI.md` §5): a landed download's row carries a button that opens the phone's file manager at `Download/` — `ACTION_VIEW_DOWNLOADS` where the device has that screen, otherwise `ACTION_VIEW` on DocumentsUI's `Download/` folder. Android has no select-this-file intent, and every taken file is published to `Download/`, so the folder is the whole target. The button is absent on a device that resolves neither. The published copy's `MediaStore` row is re-queried while the list polls: once it is gone the meta line becomes `moved or deleted` and the row keeps only its dismiss.
- **Opening a landed file** (`../UI.md` §4, §5): the row body and — on an upload — the row's first slot fire `ACTION_VIEW` on the file's own URI, `content://` from `MediaStore` for a download and the persisted source grant for an upload. An upload's source lives in whichever app the picker or share sheet came from, and no file manager can be pointed at it, so that row *opens* where a download *reveals*; §5's iOS row is the same substitution for the same reason. Both are absent when nothing on the device resolves the intent.
- **High contrast** (`../UI.md` §10): `UiModeManager.getContrast` (API 34+) is the only forced-contrast signal an app gets; at or above 0.5 the brand layer is dropped and every family collapses onto the neutrals. Below API 34 there is nothing to read and the palette stays as authored.
- Settings live in **EncryptedSharedPreferences** (API key) and plain DataStore (host URL, preferences).
- **Update check**: the app is distributed as an APK from GitHub Releases (`.github/workflows/release.yml`), so no store tells it a newer build exists. Settings' "Check for update" reads `GET /repos/RainbowCockroach/table-client-android/releases/latest` — unauthenticated, no API key involved, nothing to do with the table server — and compares the release tag with the installed `versionName`; both are `<baseVersionName>.<CI run number>`, so the comparison is component-wise numeric and a tag that is not a dotted number is reported as a failed check rather than as an update. A newer tag prompts, and only the user's yes opens the releases page in a browser. Never on a timer, never a silent download, never a self-install: the check is a button, and the download is the browser's job.

## 5. Screens

Same two-screen shape as every client:

**Layout, rows, icons, state strings, colour, and the shape and elevation of every control are not this repo's to decide** — they are specified for all four clients in `../UI.md`, with a conformance checklist in its §12. This section covers only what is specific to this platform.

The one exception on record: `../UI.md` §7's state strings are treated as placeholder wording, and this client keeps its own (`Queued`, `Verifying`, `Saved to Downloads as …`). Checklist rule 14 is knowingly open — see `../UI.md` §13 and the 2026-08-24 `PROGRESS.md` entry.


1. **Main** — the regions of `../UI.md` §2 in one composable tree: the action bar, the notice lane, the table (poll ~5 s while foregrounded; entries show name, size, expiry countdown, and upload progress for `uploading` files, which are downloadable immediately per the live-relay design) and the queue as either the shelf or the rail. The flip is read from a root `BoxWithConstraints` — the width the app actually has — so a freeform, split-screen or folded window reaches the shelf through the same code path a phone does, and there is no tablet layout to keep in step. *Clear* dismisses every settled transfer, the same as dismissing each by hand. Region 5, the drop surface, is not built: dropping onto an app is a multi-window and tablet affordance on Android, and it carries its own permission dance (`requestDragAndDropPermissions`) — a checkpoint of its own rather than a line in this one.
2. **Settings** — host URL, API key, "test connection" (hits `GET /files`), optional "upload on Wi-Fi only" toggle, a version row with "Check for update" that offers the releases page when GitHub has a newer build (§4), and — only while the app is still battery-optimized — a notice that queued transfers may wait, with a button to the system screen that grants the exemption (§6).

## 6. Android-specific edge cases

| Situation | Handling |
|---|---|
| Process death / reboot mid-transfer | WorkManager re-runs the worker; queue table has the session id and offsets; resume via `HEAD`/`Range`. |
| Doze / App Standby delaying transfers | Foreground work largely avoids it while running; queued work may wait. Settings carries the battery-optimization notice while the app is optimized and opens the system list; the one-tap request is not worth `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, so granting it stays the user's own action. The exemption should also lift the background foreground-service refusal in §3. |
| `content://` permission revoked before a retry | Permanent failure with a clear "re-share the file" message. Reachable for a picked file whose grant the user revoked; a shared one was copied at intake (§4). |
| Source stream can't seek for upload resume | Re-open + `skip(offset)` — correctness identical, cost is a local re-read. |
| Storage full during download | Retryable failure surfaced in the queue; partial temp file is kept and `Range` resume continues after space is freed. |
| Network switch (Wi-Fi ↔ cellular) | Socket dies → retryable failure → backoff → resume. The Wi-Fi-only setting maps to WorkManager's `UNMETERED` constraint. |

## 7. Testing

Automation lives where the correctness risk lives — the transfer logic — and nowhere else.

- **Conformance integration tests** (the core suite): JVM tests in `src/test/` that re-run the server's conformance scenarios through `api/` + `transfer/` against a local `table-server` (`TABLE_URL`/`TABLE_API_KEY` from env or a Gradle property; tests skip with a clear message when no server is up). Roundtrip, upload resume, Range resume, hash-mismatch handling, ack semantics including 404-means-success — the same list as `table-server/conformance/scenarios/`, driven by this client's real code paths.
- **Fault-path tests**: run the dev server with `TABLE_TEST_FAULTS=1` and use `X-Test-Drop-After` to cut the connection at an exact byte in both directions — the deterministic version of "Wi-Fi died mid-transfer". Asserts the client resumes from the committed offset / partial-file size rather than restarting.
- **Unit tests** for the fiddly pure logic: rebuilding the SHA-256 digest from a partial temp file on download resume, queue state transitions, collision-safe naming, the text rung's naming and its refusal of blank text, re-open + `skip(offset)` upload resume, ranking a release tag against the installed version. The update check's own HTTP call and browser hand-off are part of the manual release pass.
- **WorkManager wiring**: one smoke test with `androidx.work.testing` (`TestDriver`) proving a queued transfer runs, retries on a retryable failure, and resumes rather than restarts.
- **No UI automation.** Two screens, one user — Espresso would cost more than it catches. Share-sheet intake, notifications, and MediaStore publish are verified manually per release.

## 8. Build order

1. `api/` + `crypto/` + `transfer/` core with JVM tests against a local `table-server`.
2. Settings + main list UI; downloads first (verify → ack is the harder correctness path).
3. Uploads with resume; WorkManager wiring end-to-end.
4. Share sheet, notifications, polish.
5. The intake ladder behind all four entry points (`../DESIGN.md` §3 rules 16–23): the share-text rung is in (§4); then paste and the image rung.
