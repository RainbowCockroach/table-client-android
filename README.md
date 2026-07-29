# table-client-android

Native Android client for [table](../DESIGN.md) — an ephemeral personal file drop.
Kotlin + Jetpack Compose, single module. Design: `DESIGN.md`; plan of record: `PROGRESS.md`.

## Layout

```
app/src/main/kotlin/com/rainbowcockroach/table/tableandroidclient/
  api/        TableClient — typed wrapper over the HTTP API (OkHttp + kotlinx.serialization)
  crypto/     streaming SHA-256
  transfer/   Downloader, Uploader, DownloadTask, DownloadQueue — the conformance-checklist
              transfer logic, plus the MediaStore publish step
  settings/   host URL and preferences in DataStore, API key in EncryptedSharedPreferences
  ui/         Compose screens: Main, Settings
app/src/test/kotlin/…  JVM tests, including the conformance suite
```

Everything outside `ui/` and `share/` is plain Kotlin with no Android UI dependencies, so
the protocol logic runs under fast JVM tests.

## Dev loop

The conformance tests run against a real `table-server`. Start one (see
`table-server/CLAUDE.md`), then point the tests at it:

```sh
# dev server: short TTL so the expiry test runs, fault injection for the drop scenarios
TABLE_API_KEY=devkey TABLE_DATA_DIR=$(mktemp -d) TABLE_TTL=5s TABLE_TEST_FAULTS=1 \
  TABLE_ADDR=127.0.0.1:8080 go run .

# JVM tests (unit + conformance)
TABLE_URL=http://127.0.0.1:8080 TABLE_API_KEY=devkey TABLE_TTL_SECONDS=5 TABLE_TEST_FAULTS=1 \
  ./gradlew :app:testDebugUnitTest
```

`TABLE_URL` and `TABLE_API_KEY` may also be passed as Gradle properties (`-PTABLE_URL=…`).
Without them the conformance tests skip with a message and the unit tests still run;
`TABLE_TTL_SECONDS` gates the expiry test alone, and `TABLE_TEST_FAULTS=1` — which the dev
server needs too — gates the scenario 10 drop tests.

## Running the app against a local server

The MediaStore publish and the two screens are verified by hand (DESIGN §7). On an emulator:

```sh
./gradlew :app:installDebug
adb reverse tcp:8080 tcp:8080        # so 127.0.0.1:8080 in the app reaches the dev server
```

Then in Settings enter `http://127.0.0.1:8080`, the API key, and turn on **Allow plain
http://** — conformance rule 13 refuses the host without it. A dev server started with the
5 s `TABLE_TTL` above expires files faster than you can tap; use `TABLE_TTL=30m` for a
manual pass.

## Releases (`.gitlab-ci.yml`)

Every push to the default branch builds a release APK, publishes it to the generic package
registry, and creates a GitLab Release for it. The version is derived from the build number
rather than committed: `versionName` = `baseVersionName` in `gradle.properties` + the pipeline
IID (`1.0.42`), `versionCode` = the pipeline IID. A final job deletes every other release, its
tag and its package, so exactly one release exists at a time.

Reproduce a CI version locally with `./gradlew assembleRelease -PbuildNumber=42`.

Set these CI/CD variables before the first run:

| Variable | Notes |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -i release.keystore \| tr -d '\n'`, type Variable (not File). Without it the APK builds unsigned and cannot be installed. |
| `ANDROID_KEYSTORE_PASSWORD` | masked |
| `ANDROID_KEY_ALIAS` | |
| `ANDROID_KEY_PASSWORD` | masked |
| `GITLAB_API_TOKEN` | Project access token, Maintainer role + `api` scope. `CI_JOB_TOKEN` cannot delete releases, tags or packages, so the cleanup job needs its own. |
