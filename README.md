<p align="center">
  <img src="docs/assets/logo.svg" alt="Fellwater" width="120">
</p>

<h1 align="center">Fellwater</h1>

<p align="center"><b>Row through video, on your own terms.</b><br>
An Android app for Tunturi R60/R80 (iConsole) and FTMS rowing machines.<br>
No account, no subscription, no cloud.</p>

<p align="center"><img src="docs/assets/screens/row.jpg" alt="The Row screen" width="720"></p>

- **Video that follows you**: local files, streams or YouTube links play at your pace
  (smoothed over a few seconds), pause when you stop and resume on your next stroke.
- **Race yourself**: your best row for the distance, your recent rows and virtual
  pacers, on a course beside the video.
- **Never lose a row**: survives rotation and app switches, reconnects dropped
  Bluetooth links, autosaves every 30 s and recovers after a crash.
- **Your data as files**: plain CSV on the device; export CSV or TCX for Strava and
  Garmin Connect.
- **Heart rate** from the console or any Bluetooth chest strap.

The user guide, hardware notes and protocol write-ups are in [`docs/`](docs/index.md),
published as the project's GitHub Pages site.

## Hardware

The Tunturi console turned out **not** to be FTMS: it is an iConsole (BoQun) serial
bridge, reverse engineered from the vendor app and verified on real hardware
([protocol](docs/protocol/iconsole.md)). Standard FTMS rowers use a full FTMS client
that is tested against the spec but has not yet met a real machine
([details](docs/protocol/ftms.md)).

## Build

```bash
./gradlew :app:assembleDebug     # debug APK (fi.tunturi.rows.debug)
./gradlew :app:assembleRelease   # minified release APK
./gradlew :protocol:test :app:testDebugUnitTest
```

Needs JDK 17+ (CI uses 21) and an Android SDK with platform 37;
`local.properties` must point at it: `sdk.dir=/path/to/Android/Sdk`.
More in [Development](docs/development.md).

## CI

- `build.yml`: tests and both APKs on every push and pull request.
- `release.yml`: push a `v*` tag to attach a release APK to a GitHub Release.
- `pages.yml`: publishes `docs/` to GitHub Pages (Settings → Pages → Source:
  *GitHub Actions*).

Release signing uses the `KEYSTORE_PATH` / `KEYSTORE_PASS` / `KEY_ALIAS` /
`KEY_PASSWORD` secrets; without them the APK is signed with the debug key.

## Not built

- **Racing other people.** Kinomap does it with their account API plus Agora RTC;
  doing it honestly needs our own backend (session discovery and a telemetry relay).
- **Interval workouts.** The FTMS control-point encoding exists
  (`protocol/…/FtmsControl.kt`, `workout/Programs.kt`) but there is no screen for it,
  and the Tunturi console accepts no settings anyway.

## Notes

- The Android package stays `fi.tunturi.rows` so existing installs keep their data.
- YouTube support uses [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
  (GPLv3). YouTube's terms do not allow downloading except through their own
  features; only save videos you have the right to keep.
- Not affiliated with Tunturi, First Degree Fitness or Kinomap.
