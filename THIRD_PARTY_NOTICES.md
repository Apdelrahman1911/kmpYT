# Licensing and source provenance

kmpYT's independently authored library code is available under the [MIT License](LICENSE).
This grant does not replace the licenses of dependencies, tooling, operating-system
frameworks, fetched player programs, or media. Distributors must retain the notices
and meet the obligations of the particular components they actually distribute.

## Ownership/contribution review — 2026-11-06

The repository owner, **Apdelrahman1911**, explicitly authorized adoption of MIT
and confirmed authority to license kmpYT, subject to a contribution/dependency
review. The review baseline is commit
`533562f781304338f72b27d32cfe5fb7a83dc3a8`:

- The complete reachable project history contains 15 commits, all authored by
  **abdelrahman fahmy**. The GitHub contributors endpoint identifies the same
  owner account as the only contributor (15 contributions). No additional
  co-author/sign-off attribution was found in those commit messages. These are
  repository-provenance checks, not a claim to independently prove authorship
  outside the repository's history.
- The copyright year 2026 reflects the earliest recorded project commit
  (2026-11-04). The copyright line preserves that recorded author's name and
  includes future kmpYT contributors; it does not assert ownership of dependencies.
- All 151 baseline tracked paths and the source attribution references were
  inventoried. No vendored third-party extractor/JavaScript implementation,
  SnapTube code/binaries, or platform-downloaded media was found. The README
  records extraction from ClearMedia's independently implemented engine; the
  owner's grant covers this repository's own code, not unrelated projects.
- `YouTubePublicPlayerClients.kt` preserves its reference to yt-dlp
  `2026.10.26` for public client-profile facts. No yt-dlp executable is bundled.
  That upstream tag's root LICENSE is the Unlicense; the source reference is
  retained, not replaced by the new MIT grant.
- The independently generated audio/video fixtures and response documents
  retain their **CC0** notices in both `kmpyt/src/jvmTest/resources/research/`
  and `kmpyt/src/androidInstrumentedTest/assets/research/`. No fixture notice
  or existing copyright was removed or relicensed.
- The checked-in Gradle wrapper JAR and scripts retain **Apache-2.0** notices;
  the JAR contains its own `META-INF/LICENSE` (this wrapper JAR has no separate NOTICE entry).

No third-party contribution requiring a separate relicensing permission was
identified in this baseline review. Future contributions and dependency changes
must be reviewed under their own terms; report conflicting provenance rather
than assuming that this audit grants rights to it.

## Declared and resolved dependency families

The module's Gradle declarations and the published POM license/parent metadata
for the resolved JVM/Android/iOS dependency families were checked. Platform
variants inherit the same upstream project's license. These dependencies are
referenced, not copied into the repository or relicensed as MIT.

| Component / version | Role | Upstream license |
| --- | --- | --- |
| Kotlin 2.2.21 (stdlib and Kotlin test) | Runtime / tests | Apache-2.0 |
| kotlinx.coroutines 1.10.2, serialization 1.9.0 | Runtime | Apache-2.0 |
| Ktor 3.2.3 core, CIO, Android, Darwin, encoding and transitives | Runtime | Apache-2.0 |
| kotlinx-io 0.7.0; atomicfu 0.28.0 (platform transitives) | Runtime | Apache-2.0 |
| JetBrains annotations | JVM transitive | Apache-2.0 |
| SLF4J API 2.0.17 | JVM transitive | MIT |
| AndroidX test runner 1.6.2, core 1.6.1, ext JUnit 1.2.1 | Instrumentation tests only | Apache-2.0 |
| OkHttp / MockWebServer 4.12.0, Okio 3.6.0 | Tests only | Apache-2.0 |
| JUnit 4.13.2 | Tests only, **not a library runtime dependency** | EPL-1.0 |
| Hamcrest 1.3 | Tests only | BSD-3-Clause |
| Gradle wrapper 8.13 / Gradle tooling; Android Gradle plugin | Build tooling | Apache-2.0 (tool distributions retain additional third-party notices) |

The exact dependency graph of a consuming application can differ. In
particular, do not include EPL-1.0 test tools in a GPL-combined production
runtime and then treat them as if the MIT grant covered them. Produce a final
resolved dependency inventory for each binary distribution, preserve each
applicable copyright/license/NOTICE and provide required source where applicable.

## External runtimes and services

- **Node.js, Chromium/Chrome/Edge and FFmpeg are not bundled in kmpYT.** Node
  includes MIT and third-party components; Chromium contains BSD-style and
  many other third-party licenses. Branded browsers have their own terms.
  FFmpeg's license depends on its build configuration (LGPL/GPL and possibly
  non-redistributable combinations). A caller that ships these must audit the
  exact runtime and its source/notices; merely calling an installed executable
  does not relicense it.
- Android WebView/MediaExtractor/MediaMuxer and Apple WebKit/AVFoundation are
  platform services governed by their platform terms. kmpYT does not grant
  redistribution rights for operating-system frameworks.
- Downloaded public player programs, YouTube content, and other media remain
  under their original rights and service terms. MIT licensing of this library
  does not authorize copying media, defeating access restrictions or claiming
  another recording's license.

## Compatibility with GPL-3.0-or-later consumers

The standard MIT/Expat permission used here is GPL-compatible. The GNU license
list explicitly confirms [Expat/MIT compatibility](https://www.gnu.org/licenses/license-list.html#Expat)
and [Apache-2.0 compatibility with GPLv3](https://www.gnu.org/licenses/license-list.html#apache2).
A consumer such as Veyra may distribute its combined work under
GPL-3.0-or-later while preserving kmpYT's MIT copyright/permission/warranty
notice. kmpYT itself remains MIT; this does not convert its license to GPL or
waive the consumer's other source/distribution obligations.

References: [official MIT text](https://opensource.org/license/mit),
[Kotlin license](https://github.com/JetBrains/kotlin/blob/v2.2.21/license/LICENSE.txt),
[Ktor license](https://github.com/ktorio/ktor/blob/3.2.3/LICENSE),
[SLF4J license](https://www.slf4j.org/license.html),
[yt-dlp pinned license](https://github.com/yt-dlp/yt-dlp/blob/2026.10.26/LICENSE).
