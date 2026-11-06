# Offline Chromium startup discovery

The helper keeps its original browser arguments, renderer sandbox, offline
proxy/Fetch/CSP policy, 30-second startup allowance and individual CDP/JavaScript
limits. It still accepts the launched browser's loopback stderr announcement.
In parallel it polls `DevToolsActivePort` in that session's freshly created,
previously empty profile. No caller-supplied host or WebSocket URL is accepted
from either channel: only port 1–65535 plus the complete browser UUID path can produce
an endpoint, and its host is always literal `127.0.0.1`.
Stderr announcements must be complete newline-terminated lines from a still-live
child, not a prefix of a malformed or partially delivered URL.

Chromium writes this file for ChromeDriver and Telemetry alongside its stderr
announcement; see the upstream [`DevToolsHttpHandler` implementation](https://chromium.googlesource.com/chromium/src/+/main/content/browser/devtools/devtools_http_handler.cc).
This is useful when a GUI executable's inherited stderr is unavailable. It is
not evidence that stderr caused a particular CI failure: safe failure codes and
actual subsequent Windows execution are still required.

The fallback rejects nonempty/reused initial profiles, profile symlinks,
nonregular/linked/oversized endpoint files and changed opened-file identities.
Reads are bounded to 1025 bytes; valid content is at most 1024 bytes. An incomplete
file is retried only within the original allowance. It rejects exited children,
clears all polling/deadline timers on completion, and never reads a port from
another profile or searches running browsers. This is not isolation against a
hostile process already running as the same OS user.

## Bounded startup observations

Discovery failures retain their existing primary `OFFLINE_*` code and may carry
one optional `startup` receipt containing 4–8 distinct, fixed symbolic tokens:

- A spawn event was observed or unobserved. Child state is exit-observed,
  no-exit-observed after a spawn event, or unconfirmed. Null exit fields alone
  do **not** establish a live child. These are event/field observations, not an
  OS process audit, browser readiness, or successful sandbox initialization.
- Stderr had no bytes, some bytes, or a complete but invalid announcement. No
  byte counts, contents, error messages, endpoint, UUID or profile path is sent.
- Owned port-file checks cumulatively observed missing (`ENOENT`), access
  denied (`EACCES`/`EPERM`), busy (`EBUSY`), incomplete/unrecognized bounded
  content, or a rejected file/read. Unchecked means no port-file check occurred.
  A valid endpoint is observed internally on success and cannot accompany a
  before-endpoint failure receipt. Missing followed by access denied is retained
  as both observations, without recording a timeline or increasing retry limits.

The finish path snapshots existing flags and child fields only; it does not
perform another file read, OS probe or post-deadline diagnostic operation.
Success still clears timers and accepts exactly the same endpoint syntax.
Malformed content still retries within the same deadline; unsafe file metadata
still fails closed. A missing initial file is normal and is not alone a cause
diagnosis for a later timeout.

Both helper serialization and the JVM receiver reject an entire malformed,
oversized, unknown, duplicate or contradictory receipt. Only discovery-stage
primary codes qualify. The JVM preserves `YouTubeJavaScriptFailure.code` and
its primary message and adds one stackless, causeless suppressed diagnostic in
the fixed `KMPYT_BROWSER_STARTUP_V1[...]` envelope. CI separately validates that
whole envelope and its matching primary failure before counting tokens; known
words elsewhere in raw failures are not startup evidence. No global diagnostic
state, raw stderr logging, extra process cleanup or live-media access is added.

Run the authored, browser-free regression suite with:

```sh
ci/scoped.sh node --test kmpyt/src/jvmTest/resources/youtube/chromium-runtime-endpoint.test.cjs
ci/scoped.sh python3 -B ci/test-tooling.py
```

The tests use real repository-owned filesystem fixtures and fake child
processes/clocks. They cover stale, linked, replaced, malformed and truncated
endpoint files; arbitrary-host/port/path rejection; stderr/file discovery;
spawn/exit/timeout handling; finite observations and whole-receipt rejection;
no post-deadline file reads; and exact preservation of launch flags. They do not
launch Chromium or establish native, live-source or physical-audio success.
JVM receiver tests additionally inspect full exception/cause/suppressed trees
for authored private sentinels; they require a separate actual JVM test run.
