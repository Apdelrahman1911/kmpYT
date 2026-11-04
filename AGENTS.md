# Repository Guidelines

## Project Structure & Module Organization

`kmpyt/` is the Kotlin Multiplatform library. `src/commonMain/kotlin/dev/kmpyt/` contains its public API, YouTube inspection, browsing, captions, player-program analysis and transfer contracts. `jvmAndroidMain/` shares Java file and HTTP adapters; `androidMain/`, `jvmMain/` and `iosMain/` supply platform runtimes and media processing. Tests live in matching test source sets. `.github/workflows/` defines cross-platform and approved-channel validation; `ci/approved-channel.json` records live-test scope.

## Build, Test, and Development Commands

- `./gradlew :kmpyt:jvmTest` runs portable and desktop regression tests.
- `./gradlew :kmpyt:connectedDebugAndroidTest` runs native Android tests on an emulator or connected test device.
- `./gradlew :kmpyt:assembleRelease :kmpyt:lintRelease` builds and checks the Android library.
- On macOS, run `./gradlew :kmpyt:iosSimulatorArm64Test` on Apple Silicon or `:kmpyt:iosX64Test` on Intel.
- `./gradlew :kmpyt:assembleKmpYTReleaseXCFramework` builds the Swift-consumable framework on macOS.

Use `gradlew.bat` on Windows. Keep machine-specific SDK paths in ignored `local.properties`. JVM runtime tests require Node.js 22 and Chromium; FFmpeg enables combined-media tests.

## Coding Style & Naming Conventions

Use four-space Kotlin indentation, descriptive camelCase members and PascalCase types. Keep platform imports out of common code and expose suspending operations for I/O. Prefer explicit bounded resource ownership and actionable errors. Formatting follows Kotlin conventions; no separate formatter is configured.

## Testing Guidelines

Use `kotlin.test` for shared/native tests and JUnit for JVM/Android. Name tests after observable behavior. Verify byte integrity, cancellation, resume, codec compatibility and real runtime behavior. No numeric coverage threshold is configured. Live YouTube tests are opt-in GitHub Actions runs restricted to the configured approved channel; offline tests must not contact YouTube.

## Commit & Pull Request Guidelines

Use concise imperative subjects, such as `Preserve combined download sizes`. Describe API changes, link relevant issues and report tested targets. Never commit credentials, signed media URLs, private captures or downloaded platform media; only authored fixtures belong in source control.
