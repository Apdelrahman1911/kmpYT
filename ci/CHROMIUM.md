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

Run the authored, browser-free regression suite with:

```sh
ci/scoped.sh node --test kmpyt/src/jvmTest/resources/youtube/chromium-runtime-endpoint.test.cjs
ci/scoped.sh python3 -B ci/test-tooling.py
```

The tests use real repository-owned filesystem fixtures and fake child
processes/clocks. They cover stale, linked, replaced, malformed and truncated
endpoint files; arbitrary-host/port/path rejection; stderr/file discovery;
spawn/exit/timeout handling; and exact preservation of launch flags. They do not
launch Chromium or establish native, live-source or physical-audio success.
