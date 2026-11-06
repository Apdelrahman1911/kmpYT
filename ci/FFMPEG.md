# Scoped FFmpeg test prerequisite

The first scoped PR run established that all three standard JVM runner images
lacked FFmpeg. Android release build/lint passed; its separate native boundary
gate remained red. A missing executable is fixable, not a reason to skip the
actual downloader/remux/decode regression.

`ci/select-tools.sh jvm` prefers an existing executable. Otherwise:

- **Linux/macOS:** official FFmpeg `n8.0.1`, annotated tag
  `d22ecc4f6f3fca77b3e71b18641ceddb25973e97`, peeled source commit
  `894da5ca7d742e4429ffb2af534fcda0103ef593`.
  <https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n8.0.1>
  is exactly 16,408,559 bytes, SHA256
  `679aa13a19415d5ddab91e580084e3ab20c963c8240001e5cbb955a97bdd81b1`.
  The complete source and LGPL2.1 notice remain repository-owned. Existing
  compiler/GNU make, bounded `-j2` (`KMPYT_CI_NATIVE_JOBS=1` for local probes),
  no `make install`, autodetection, external codec libraries, GPL or nonfree
  options. This is LGPL-2.1-or-later under source `LICENSE.md`.
- **Windows x64, CI only:** fixed Gyan8.0.1 essentials ZIP, exactly 106,259,850
  bytes, SHA256
  `e2aaeaa0fdbc397d4794828086424d4aaa2102cef1fb6874f6ffd29c0b88b673`.
  <https://github.com/GyanD/codexffmpeg/releases/tag/8.0.1> publishes this
  digest; [FFmpeg's downloads](https://ffmpeg.org/download.html#build-windows)
  identify Gyan as a Windows build provider. Preserve its full GPL3 `LICENSE`
  and `README.txt`. No binary is checked in, uploaded, or shipped in kmpYT or
  Veyra; this choice is not Veyra's application runtime/source-closure approval.
  The complete archive was independently downloaded/hash-checked and its
  notice/executable extraction exercised on Linux (not Windows execution).

The SHA256 and byte size are checked before any archive-derived files are
written. Paths, duplicate entries, link/special entries and total expanded
size are bounded/rejected. HTTPS certificate validation is never disabled.
No package manager, global cache, installation, PATH configuration file or
system setting is touched. A failed download/build/notice check fails the job.

The POSIX configuration intentionally supports both stream-copy remux and
H.264/AAC decode-to-null, using the repository's authored CC0 video/audio
fixtures. It also has HTTP/TCP for the explicit loopback SABR native test;
this is not external-source evidence. A remux-only executable cannot satisfy
the decoder test. Every selected executable runs this real native preflight;
the full JVM job additionally requires the named downloader media regression
to execute and pass (not assumption-skip). Failures are not reclassified green.

These checks are fixture/native-tool verification only, not application
playback, source-service availability, audible physical output, installation,
or legal closure of a distributable package.

## Local recipe verification (2026-11-06)

The exact Linux source recipe built successfully with one compiler job in
115.7 seconds; maximum child RSS was150,320KiB under a384MiB process-address
limit. The produced executable reported LGPL2.1-or-later and passed authored
H.264/AAC remux plus decode-to-null. The initial preflight's default thread
count exceeded that artificial address limit; explicitly limiting preflight
decoder/filter threads to one passed under the same limit (no test skipped).
Tooling negative/regression checks passed9/9. Windows archive digest and
notices/extraction passed on Linux, **not Windows execution**. macOS source
build and the complete platform JVM suites still require actual runner results.
