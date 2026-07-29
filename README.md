# table-client-android

Native Android client for [table](../DESIGN.md) — an ephemeral personal file drop.
Kotlin + Jetpack Compose, single module. Design: `DESIGN.md`; plan of record: `PROGRESS.md`.

## Layout

```
app/src/main/kotlin/com/rainbowcockroach/table/tableandroidclient/
  api/        TableClient — typed wrapper over the HTTP API (OkHttp + kotlinx.serialization)
  crypto/     streaming SHA-256
  transfer/   Downloader and Uploader — the conformance-checklist transfer logic
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
