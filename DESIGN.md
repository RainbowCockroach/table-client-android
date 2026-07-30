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

- **WorkManager** is the executor: one `WorkRequest` per file, survives process death and reboot, `NETWORK_CONNECTED` constraint, exponential backoff. Long transfers run as foreground work (`dataSync` service type) with a progress notification.
- A small **Room/SQLite queue table** carries what WorkManager doesn't: upload session id, file id, temp path, bytes done — the state the worker needs to resume via `HEAD`/`Range` instead of restarting, plus what the UI needs to render the queue.
- States: `queued → running → verifying → done | failed(retryable) | failed(permanent)`.
- Concurrency cap: 2 uploads / 2 downloads.
- Shared and picked `content://` URIs are persisted with `takePersistableUriPermission` so a retry after process death can still read the source; a grant that cannot be persisted is replaced by a private copy of the file instead (§4). Copies are swept at process start once no unfinished record still names them.
- Download completion order (conformance rule): temp file in `cacheDir` fully written → verify length + SHA-256 → `FileDescriptor.sync()` → **ack** → publish to `MediaStore.Downloads` (collision-safe naming). Publish failure never loses data — the verified temp file remains and the publish is retried.

## 4. Android integrations

- **Share sheet**: intent filters for `ACTION_SEND` and `ACTION_SEND_MULTIPLE` (`*/*`). `ShareActivity` is a near-invisible trampoline: secure the sources, enqueue work, show a "queued ✓" confirmation, finish. *Securing* means `takePersistableUriPermission` where the sender offered a persistable grant (the in-app picker always does), and otherwise a private copy of the bytes taken before the activity finishes — a plain `ACTION_SEND` grant is revoked with the receiving activity, so a queued upload would have nothing left to read on its first retry.
- **In-app picker**: `ACTION_OPEN_DOCUMENT` with multi-select.
- **Notifications**: per-transfer progress (from the foreground worker) plus completion/failure; tapping a completed download opens the file via `MediaStore`.
- Settings live in **EncryptedSharedPreferences** (API key) and plain DataStore (host URL, preferences).

## 5. Screens

Same two-screen shape as every client:

1. **Main** — server file list under "On the table" (poll ~5 s while foregrounded; entries show name, size, expiry countdown, and upload progress for `uploading` files, which are downloadable immediately per the live-relay design; the per-file action is "Take") + local transfer queue with per-item progress. "Take all" action on the list, "Clear all" on the queue — it dismisses every settled transfer, the same as dismissing each by hand.
2. **Settings** — host URL, API key, "test connection" (hits `GET /files`), optional "upload on Wi-Fi only" toggle.

## 6. Android-specific edge cases

| Situation | Handling |
|---|---|
| Process death / reboot mid-transfer | WorkManager re-runs the worker; queue table has the session id and offsets; resume via `HEAD`/`Range`. |
| Doze / App Standby delaying transfers | Foreground work largely avoids it while running; queued work may wait. Document the battery-optimization exemption for users who want instant pickup. |
| `content://` permission revoked before a retry | Permanent failure with a clear "re-share the file" message. Reachable for a picked file whose grant the user revoked; a shared one was copied at intake (§4). |
| Source stream can't seek for upload resume | Re-open + `skip(offset)` — correctness identical, cost is a local re-read. |
| Storage full during download | Retryable failure surfaced in the queue; partial temp file is kept and `Range` resume continues after space is freed. |
| Network switch (Wi-Fi ↔ cellular) | Socket dies → retryable failure → backoff → resume. The Wi-Fi-only setting maps to WorkManager's `UNMETERED` constraint. |

## 7. Testing

Automation lives where the correctness risk lives — the transfer logic — and nowhere else.

- **Conformance integration tests** (the core suite): JVM tests in `src/test/` that re-run the server's conformance scenarios through `api/` + `transfer/` against a local `table-server` (`TABLE_URL`/`TABLE_API_KEY` from env or a Gradle property; tests skip with a clear message when no server is up). Roundtrip, upload resume, Range resume, hash-mismatch handling, ack semantics including 404-means-success — the same list as `table-server/conformance/scenarios/`, driven by this client's real code paths.
- **Fault-path tests**: run the dev server with `TABLE_TEST_FAULTS=1` and use `X-Test-Drop-After` to cut the connection at an exact byte in both directions — the deterministic version of "Wi-Fi died mid-transfer". Asserts the client resumes from the committed offset / partial-file size rather than restarting.
- **Unit tests** for the fiddly pure logic: rebuilding the SHA-256 digest from a partial temp file on download resume, queue state transitions, collision-safe naming, re-open + `skip(offset)` upload resume.
- **WorkManager wiring**: one smoke test with `androidx.work.testing` (`TestDriver`) proving a queued transfer runs, retries on a retryable failure, and resumes rather than restarts.
- **No UI automation.** Two screens, one user — Espresso would cost more than it catches. Share-sheet intake, notifications, and MediaStore publish are verified manually per release.

## 8. Build order

1. `api/` + `crypto/` + `transfer/` core with JVM tests against a local `table-server`.
2. Settings + main list UI; downloads first (verify → ack is the harder correctness path).
3. Uploads with resume; WorkManager wiring end-to-end.
4. Share sheet, notifications, polish.
