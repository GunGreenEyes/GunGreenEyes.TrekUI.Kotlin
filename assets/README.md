# assets/ - generated test media

Everything in this folder except this README and `.gitignore` is **generated** and **not committed**;
CI publishes it to GitHub Pages instead.

| Path | Produced by | What |
|---|---|---|
| `<device>-api<N>[-<orientation>]/media/*.png, *.mp4` | `scripts/capture/run-matrix.ps1` (emulators) | one screenshot per scenario step, one video per scenario |
| `<device>-api<N>/junit.xml`, `result.json`, `logs/` | same | per-run JUnit report, results with screen facts, logcat / crash buffer / emulator log |
| `robolectric/<interface>/<size>.png` | `TREKUI_SCREENSHOTS=1 ./gradlew :app:testDevelopDebugUnitTest --tests '*RobolectricScreenshots*' --rerun` | fast JVM renders of every interface at a table of resolutions |
| `bluestacks/*.png` | `scripts/bluestacks.ps1` | install + launch check on BlueStacks |
| `index.json` | `scripts/capture/build-index.ps1` (run automatically) | every run: device, API, size, density, aspect, pass/fail per scenario, media paths |

File names: `<Class>_<scenario>[-<step>]__<device>__api<N>__<portrait|landscape>.png|mp4`.

Rerun: `pwsh scripts/avd/create-avds.ps1` once, then `pwsh scripts/capture/run-matrix.ps1`
(`-Only 'api36'` to filter, `-Parallel 3` max concurrent emulators). See scripts/capture/README.md.
