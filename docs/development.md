# Development

## Build

Needs JDK 17+ (CI uses 21) and an Android SDK with platform 37.
`local.properties` must point at it: `sdk.dir=/path/to/Android/Sdk`.

```bash
./gradlew :app:assembleDebug     # debug APK (fi.tunturi.rows.debug)
./gradlew :app:assembleRelease   # minified release APK
./gradlew :protocol:test :app:testDebugUnitTest
```

## Layout

```
protocol/   pure Kotlin, no Android: FTMS parsing/encoding, heart-rate parsing
app/
  ble/        rower client (FTMS + iConsole), heart-rate strap, simulator
  workout/    the row in progress, recorder, sessions, racers, export,
              video playback, YouTube, offline video store
  ui/         Compose screens: Row, History, Setup
docs/       this site (MkDocs Material)
```

The row in progress (`RowSession`) and the player (`VideoPlayback`) live for the
whole process, not a screen, so rotation and tab switches lose neither.

## Continuous integration

| Workflow | When | What |
|---|---|---|
| `build.yml` | every push and pull request | unit tests, debug and release APKs as artifacts |
| `release.yml` | pushing a `v*` tag | builds the release APK and attaches it to a GitHub Release |
| `pages.yml` | push to `main` touching `docs/` or `mkdocs.yml` | builds this site and publishes it to GitHub Pages |

To cut a release: bump `versionCode`/`versionName` in `app/build.gradle.kts`, then
`git tag v1.1 && git push --tags`.

Release signing uses the `KEYSTORE_PATH`, `KEYSTORE_PASS`, `KEY_ALIAS` and
`KEY_PASSWORD` repository secrets; without them the APK is signed with the debug key,
which installs fine but cannot be updated by a properly signed build later.

## Docs locally

```bash
pip install -r docs/requirements.txt
mkdocs serve
```

## Licence note

YouTube support uses NewPipeExtractor, which is GPLv3. Distributing the app means
distributing it under GPLv3-compatible terms.

## Simulated rower

Debug builds have a **Simulate rower** switch under Setup, which advertises a fake
FTMS rower. Most phones never deliver their own advertisement to their own scanner,
so it is meant to be run on a *second* phone, with Fellwater connecting to it.
