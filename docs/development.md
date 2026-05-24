# Development

How to build, extend, and ship the app yourself.

## Project layout

```
localllm/
├── app/                               main Gradle module
│   ├── build.gradle.kts
│   ├── proguard-rules.pro             LiteRT-LM keeps; minify disabled in release for now
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml    foregroundServiceType=specialUse
│       │   ├── java/com/localllm/app/ Kotlin sources (see Architecture page)
│       │   └── res/
│       │       ├── drawable/          ic_launcher_foreground + ic_launcher_background (adaptive)
│       │       ├── mipmap-anydpi-v26/ adaptive icon manifests
│       │       └── values/            strings, themes, colors
│       └── test/                      JVM unit tests
├── docs/                              this mkdocs site
├── gradle/libs.versions.toml          Version catalog
├── mkdocs.yml
├── settings.gradle.kts
└── .github/workflows/build.yml        CI: lint + test + assembleDebug
```

## Local build

```bash
git clone https://github.com/mlnomadpy/localllm.git
cd localllm
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements:

- **JDK 17** (`temurin` works; AGP 8.7 needs >=17, the source target
  is Java 11).
- **Android SDK API 35** installed.
- **Gradle** is wrapper-pinned (`8.x`) — don't install separately.

Runtime versions are all locked in `gradle/libs.versions.toml`:

| | |
|---|---|
| Kotlin | 2.2.21 |
| AGP | 8.7.3 |
| Ktor | 3.4.3 |
| Compose BOM | 2024.09.02 |
| LiteRT-LM | 0.12.0 |
| ML Kit GenAI Prompt | 1.0.0-beta2 |
| ONNX Runtime Android | 1.18.0 |
| ObjectBox | 4.0.3 |

## Adding a model to the catalog

`ModelCatalog.kt` is the source of truth.

```kotlin
ModelInfo(
    id          = "gemma-4-e2b",                     // bare name shown in /v1/models
    name        = "Gemma 4 E2B IT",                  // human label in the Catalog UI
    description = "Instruction tuned, ~2.6 GB.",
    url         = "https://.../gemma-4-E2B-it.litertlm",
    filename    = "gemma-4-e2b.litertlm",            // .litertlm extension required
    sha256      = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
),
```

A few rules:

- `filename` must end in `.litertlm`. Anything else is ignored by the
  on-device file scan.
- `sha256` is optional. When set, the file is verified after
  download; on mismatch the file is deleted and the user is toasted.
  Skip for non-HF mirrors where you don't control the integrity.
- For HuggingFace xet-backed artifacts the `x-linked-etag` HTTP
  header is the SHA-256 — copy it from `curl -sI <url>` rather than
  downloading 2.6 GB to hash.

## Running on a real device

The app runs fine on emulators, but inference is *very* slow on
emulated x86 — figure 5+ seconds per token on Gemma 4 E2B on an
`x86_64` AVD. For development, use a real ARM phone. Anything
post-2022 should manage 10–30 tok/s on CPU.

Tested on Pixel-class devices. NPU acceleration via the Qualcomm
`.litertlm` variants requires a Snapdragon device.

## Picking a backend on your device

LiteRT-LM AUTO tries **NPU → GPU → CPU** and records every step. The
NPU attempt is only meaningful when the device has a vendor delegate
reachable from `nativeLibraryDir` (QAIRT, NeuroPilot) or, on Pixel
6/9/10, the bundled `libLiteRtDispatch_GoogleTensor.so`. The most
common GPU failure on stock Pixel images is `dlopen failed: library
libvndksupport.so not found`. Force a specific backend in **Settings
→ Backend** if you want to skip the chain.

AICore (`gemini-nano-aicore`) doesn't expose a backend selector —
the AICore system service picks NPU/GPU/CPU internally.

To see what your LiteRT engine picked, `curl /health`:

```bash
curl -s http://localhost:8099/health | jq '.engines[0]'
# {
#   "key": "gemma-4-e2b_model_AUTO",
#   "backend": "CPU",
#   "attempts": [
#     {"backend":"NPU","result":"failed: TF_LITE_AUX not found in the model","duration_ms":5394},
#     {"backend":"GPU","result":"skipped: known SIGSEGV on Tensor","duration_ms":0},
#     {"backend":"CPU","result":"ok","duration_ms":3168}
#   ]
# }
```

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Two unit-test files today, both JVM (Robolectric):

- `SettingsTest.kt` — preference clamping (`maxTokens`, `temperature`,
  etc.), `bindHost` derivation, backend normalization.
- `RequestTrackerTest.kt` — atomic queue cap, stats derivation,
  cancel/error counters.

There is **no on-device instrumentation test** today. End-to-end
verification is a manual `curl` against a real `adb forward`. If you
add an `androidTest` source set, expect the model fixture to be the
hard part — a 2.6 GB binary blob doesn't belong in git.

## Baseline profiles & macrobenchmark

The `:macrobenchmark` module (under `macrobenchmark/`) holds the
Macrobenchmark + Baseline Profile generator. It uses the `com.android.test`
plugin and `androidx.baselineprofile`, targets `:app` via
`targetProjectPath = ":app"`, and runs alongside the target with
`android.experimental.self-instrumenting = true`.

Two test classes are wired up:

- `StartupBenchmark` — cold-start `StartupTimingMetric` with
  `CompilationMode.None / Partial / Full`, five iterations each.
- `BaselineProfileGenerator` — walks Catalog → Dashboard → Console
  → Chat → Settings so the produced profile covers the hot
  composables.

Both require a **connected device** (USB or wireless ADB) — they
are not part of `assembleDebug`. To regenerate the profile:

```bash
./gradlew :app:generateReleaseBaselineProfile
# Outputs app/src/main/baseline-prof.txt, consumed by R8 at release-build time.
```

Baseline profiles **only** apply to release builds (R8-compiled),
so the perf win is invisible in `:app:assembleDebug` — ship a
release variant to feel it.

## Continuous integration

`.github/workflows/build.yml` runs on every push and PR against
`main`:

```yaml
- ./gradlew lint testDebugUnitTest assembleDebug --no-daemon --stacktrace
```

Gradle is cached on `gradle/libs.versions.toml` + `**/*.gradle.kts`.
Lint report + the debug APK are uploaded as workflow artifacts (14
day retention). JDK 17, Temurin. No daemon — fresh VMs don't benefit
and the Gradle daemon's resident heap occasionally OOMs the 7 GB
runner.

!!! note "GitHub Actions currently unavailable on this repo"
    The hosting account has Actions administratively restricted
    (Trust & Safety review in progress with GitHub Support). While
    that's pending, **CI parity is enforced locally**:

    ```bash
    ./gradlew lint testDebugUnitTest assembleDebug --no-daemon
    ```

    Contributors run this before opening a PR; maintainers run the
    same command before merging. The `build.yml` workflow stays in
    tree and will auto-resume once Actions is re-enabled — no
    changes needed.

## Docs deploy (manual, while Actions is out)

The mkdocs Material site publishes to GitHub Pages from the
`gh-pages` branch (legacy branch-source mode, bypassing Actions).
One-time setup per machine:

```bash
python3 -m venv .venv-docs
.venv-docs/bin/pip install -r docs/requirements.txt
```

To publish the current `docs/` state:

```bash
.venv-docs/bin/mkdocs gh-deploy --force --remote-branch gh-pages
```

That builds the site, commits to `gh-pages`, and pushes. Pages picks
it up within a minute at the repo's custom domain
(`http://www.tahabouhsine.com/localllm/`). When Actions is restored,
flip Pages source back to "GitHub Actions" and the existing
`docs.yml` workflow takes over.

## Cutting a release

One command:

```bash
./scripts/release.sh v1.2.3
```

`scripts/release.sh` (see source for details):

1. Refuses to run on a dirty working tree.
2. Runs `./gradlew :app:assembleDebug`.
3. Runs `mkdocs gh-deploy` to publish the docs.
4. Tags the current commit and pushes the tag.
5. Reads release notes from the matching `## [1.2.3]` section in
   `CHANGELOG.md` and calls `gh release create` with the APK
   attached.

The tag prefix must be `v<semver>`. Notes are sourced from
`CHANGELOG.md` so the changelog stays the source of truth — forget
to add a section and the release goes out with a placeholder note
and a stderr warning.

Don't try to ship to the Play Store from this debug APK — minify is
disabled (`isMinifyEnabled = false`), there's no signing config, and
the version code is hardcoded. Production-ready signing + R8 are
roadmap items.

## Extending the HTTP API

The route handler lives in `LLMServerService.kt` inside the
`embeddedServer(Netty, ...) { routing { ... } }` block. A new
endpoint is one route registration + (usually) one Gson DTO in
`ApiTypes.kt`.

For anything that's actually inference-shaped, mind the existing
contract:

1. Call `authorize(call)` first if you want the bearer-token gate.
2. Atomic admission via `RequestTracker.tryEnqueue(...)` so the
   global queue cap is honored.
3. Use `inferenceMutex.withLock { ... }` — only one inference at a
   time per device.
4. Wrap the inference in `withWakeLock(needWakeLock, timeoutMs) {
   ... }`.
5. Use `withTimeout(timeoutMs) { ... }` to enforce the budget. On
   timeout, call `conversation.cancelProcess()` so the native engine
   actually stops burning compute.
6. Mind the SSE error path: if you start a streaming response,
   capture the writer (`streamWriter = this@respondBytesWriter`) and
   emit a `writeSseError` chunk on failure — *don't* try to
   `call.respond` after headers have committed.

## Release builds and signing

The release build pipeline is wired in `app/build.gradle.kts`:

- `isMinifyEnabled = true` + `isShrinkResources = true` — R8 + the
  resource shrinker run on every `:app:assembleRelease`.
- `signingConfigs.release` reads four properties from
  `~/.gradle/gradle.properties` *or* environment variables. If any of
  the four is missing the config is silently empty and the release
  build falls back to the **debug** signing key, so
  `:app:assembleRelease` completes for every contributor without
  needing access to the production keystore.
- `splits.abi` ships per-ABI APKs for `arm64-v8a` plus a universal
  APK (`isUniversalApk = true`). `armeabi-v7a` and the x86 family are
  intentionally dropped — see the gotcha below.

### One-time keystore setup

```bash
keytool -genkey -v -keystore localllm-release.keystore \
  -alias localllm -keyalg RSA -keysize 4096 -validity 10000
```

Move it somewhere outside the repo (the project `.gitignore` blocks
`*.keystore` and `*.jks`, but keeping it out of the source tree is
safer still). Then add the four properties to your **user-level**
`~/.gradle/gradle.properties`:

```properties
LOCALLLM_KEYSTORE_PATH=/Users/you/keys/localllm-release.keystore
LOCALLLM_KEYSTORE_PASSWORD=********
LOCALLLM_KEY_ALIAS=localllm
LOCALLLM_KEY_PASSWORD=********
```

Environment variables with the same names also work — handy for CI.
If both are set, the Gradle property wins.

### Build outputs

```bash
./gradlew :app:assembleRelease   # per-ABI + universal APKs
./gradlew :app:bundleRelease     # .aab for Play Store / Internal App Sharing
```

`app/build/outputs/apk/release/` will contain:

- `app-arm64-v8a-release.apk` — per-ABI, smallest (~28 MB)
- `app-universal-release.apk` — fat APK with all included ABIs (~39 MB)

Verify the JNI payload of each split:

```bash
unzip -l app/build/outputs/apk/release/app-arm64-v8a-release.apk | grep '\.so$'
```

Only `lib/arm64-v8a/...` entries should appear in the per-ABI APK.

### LiteRT-LM ABI gotcha

The `com.google.ai.edge.litertlm:litertlm-android:0.12.0` AAR ships
JNI `.so` files for **`arm64-v8a` and `x86_64` only**:

- `lib/arm64-v8a/`: `libLiteRt.so`, `libLiteRtClGlAccelerator.so`,
  `liblitertlm_jni.so`
- `lib/x86_64/`: same three libs (emulator-only convenience)
- `lib/armeabi-v7a/`: **none** — LiteRT-LM doesn't target 32-bit ARM.

`splits.abi.include` is set to `arm64-v8a` only. Re-adding
`armeabi-v7a` would produce an APK that crashes on first inference
with `UnsatisfiedLinkError`. `x86_64` is omitted from the per-ABI
split list because emulator inference is unusably slow, but the
**universal APK still carries x86_64** so an emulator install via
the universal APK works for smoke tests.

## Roadmap

Tracked in [GitHub Issues](https://github.com/mlnomadpy/localllm/issues).
Big items:

- [ ] Multimodal content blocks (`Content.ImageBytes` /
      `Content.AudioBytes`) — LiteRT-LM already supports them.
- [ ] Tool / function calling (`ConversationConfig.tools`).
- [ ] Qualcomm NPU catalog entries
      (`gemma-4-E2B-it_qualcomm_sm8750.litertlm`).
- [ ] Per-IP token-bucket rate limiting.
- [ ] Persistent log buffer + Sentry/Crashlytics integration.
- [ ] `androidTest` end-to-end with a tiny fixture model.
- [ ] Release signing + R8 production build.
