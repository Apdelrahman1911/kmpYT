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
- Stderr had no bytes, some bytes, a complete but invalid announcement, or one
  of the four fixed source messages described below. No byte counts, contents,
  raw error messages, endpoint, UUID or profile path is sent.
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

## Finite Windows startup discriminator

The Windows job at [run 42196734929](https://github.com/Apdelrahman1911/kmpYT/actions/runs/42196734929)
had three startup timeouts, not an established cause. Only two retained validated
receipts: spawn observed, no child exit observed, some stderr, owned port missing.
The compiler assertion flattened the third failure; do not attribute those two
receipts to it. A reported runner-image Chrome version is not the selected PE
file's version. The diagnostic below is not a claimed repair or a reason to
rerun an unchanged full matrix.

The existing bounded stderr parser can replace its one stderr token with:

| Exact source literal observed | Fixed stderr token suffix |
|---|---|
| `Cannot start http server for devtools.` | `DEVTOOLS_BIND_MESSAGE` |
| `Error writing DevTools active port to file ` (trailing space required) | `PORT_WRITE_MESSAGE` |
| `Lock file can not be created` | `PROFILE_LOCK_MESSAGE` |
| `Failed to create a ProcessSingleton for your profile directory.` | `PROCESS_SINGLETON_MESSAGE` |

The prefix is always `OFFLINE_STARTUP_STDERR_`. These are literal observations,
not authenticated log records, OS error codes or proof of a universal root cause.
Sources at Chromium **156.0.8078.80**:
[DevTools bind/port writing](https://chromium.googlesource.com/chromium/src/+/refs/tags/156.0.8078.80/content/browser/devtools/devtools_http_handler.cc#316),
[Windows profile lock](https://chromium.googlesource.com/chromium/src/+/refs/tags/156.0.8078.80/chrome/browser/process_singleton_win.cc#286),
[singleton refusal](https://chromium.googlesource.com/chromium/src/+/refs/tags/156.0.8078.80/chrome/app/chrome_main_delegate.cc#624).

Only complete newline-terminated lines remaining in the **existing 16,000-character
tail** qualify. The first classified line wins; the table order breaks ties within
one line. A later invalid announcement cannot overwrite it, but a valid endpoint
still succeeds through either original channel. Unfinished, discarded, changed or
unknown text stays unclassified; absence of a token does not disprove an error.
The receipt remains 4–8 distinct tokens with exactly one stderr token, the same
primary failure and the same deadline/poll/cleanup behavior. No raw suffix or
post-deadline operation is added.

### Read-only admission, not a Windows containment claim

`ci/windows-browser-preflight.py` accepts only an explicitly selected local-drive
native `chrome.exe` with an AMD64 PE32+ executable header. It rejects UNC/device/
relative paths, wrappers, other browsers, wrong architecture and DLLs rather than
switching tools. The file is bounded to 512 MiB, hashed before and after the
snapshot, and only its fixed basename, byte count, SHA-256 and four-component
numerical file/product versions are reported. This is identity metadata, **not**
signature/vendor authentication or a pinned-browser compatibility claim.

The version reader loads only the system `version.dll` and uses
[`VerQueryValueW`'s root fixed-info block](https://learn.microsoft.com/en-us/windows/win32/api/winver/nf-winver-verqueryvaluew),
with a 1 MiB resource cap, pointer/length/signature checks and no localized strings.
It never runs Chrome, PowerShell, an installer or a version-command subprocess.

Eight fixed registry observations cover `RemoteDebuggingAllowed` and `UserDataDir`
in HKLM/HKCU's native 64-bit `SOFTWARE\Policies\Google\Chrome` and
`SOFTWARE\Policies\Chromium` keys. Opens request only `KEY_QUERY_VALUE` plus the
explicit view. The system `advapi32.dll`'s
[`RegQueryValueExW`](https://learn.microsoft.com/en-us/windows/win32/api/winreg/nf-winreg-regqueryvalueexw)
queries metadata without allocating any string/value buffer. Only an exact
four-byte `REG_DWORD` remote-debugging value gets a second, four-byte read.
Output categories are finite: absent, enabled, disabled, present, invalid or
unreadable. No value, expanded profile directory, registry exception or full
executable path is logged. No registry writes or retries occur.

An explicit remote-debugging denial, any unreadable/invalid policy or **any present
`UserDataDir`**, including empty/wrong-type values, refuses this probe with exit 78.
This is deliberately conservative: Chromium's Windows
[profile policy can override `--user-data-dir`](https://chromium.googlesource.com/chromium/src/+/refs/tags/156.0.8078.80/chrome/install_static/user_data_dir.cc#122),
and its [remote-debugging policy](https://chromium.googlesource.com/chromium/src/+/refs/tags/156.0.8078.80/components/policy/resources/templates/policy_definitions/Miscellaneous/RemoteDebuggingAllowed.yaml)
can deny startup. A refusal never authorizes a policy bypass, alternate profile,
argument change or sandbox/offline relaxation. Missing metadata/unsupported host
also refuses without printing raw exceptions.

This narrow snapshot does **not** establish all effective/cloud policies, profile
ACLs, global Windows write containment or protection against a hostile same-user
process. Registry and executable state can change after admission (including
during Gradle compilation); no atomic policy-to-launch guarantee is claimed.
An admitted run still uses the unchanged production-owned fresh profile and
native spawn/close path. It does not enumerate or attach to other browsers.

### One explicitly authorized manual job

The ordinary push/PR and default manual `full` mode retain every existing job,
status name and native exit-78 gate. Only `workflow_dispatch` with the explicit
`mode=windows-startup-probe` selects the additional **windows-2022** job instead
of the full matrix. Every ordinary job's probe-name branch assigns a distinct
`Probe scope - ... gates not executed` name. GitHub can retain the unevaluated
name expression for a skipped job; neither form is an ordinary required context.
Its skipped conclusion must not masquerade as an ordinary required check.
The probe uses the disjoint concurrency
prefix `ci-windows-startup-probe-${{ github.ref }}`, not a suffix that could
collide with an ordinary run on a branch named `topic-windows-startup-probe`.
Outside explicit probe mode the original effective names and
`ci-${{ github.ref }}` concurrency key are unchanged. These are source guards,
not evidence of GitHub server execution.
Its scope is:

1. License and browser-free tooling/diagnostic tests.
2. Existing JDK/Node/Chrome selection, **no unrelated FFmpeg preparation**.
3. The read-only admission above, then exactly one authored production-path method:
   `DesktopYouTubeJavaScriptRuntimeTest.executesNativeDomAndUrlOperationsInOfflineChromium`.
4. A JUnit gate requiring exactly that one passing testcase, one suite/file, matching
   declared counts, and no skipped/failed/extra/duplicate testcase. The plain method
   name and Kotlin's exact `[jvm]` suffix are accepted, not arbitrary prefixes.

Use a separately reviewed/frozen un-PR'd branch based on the tested public source
when authorizing the single probe; pushing a new non-main branch alone does not
trigger this matrix. Do not dispatch until the owner authorizes it, and verify
the run's actual checkout SHA. No cache/artifact upload or paid runner is added.
A passing probe proves this one method only, not Windows regression/release
closure. A classified failure guides the next investigation (bind, port write,
profile lock/singleton, or explicit policy refusal). If the same timeout has no
matching message, its cause remains **UNKNOWN**: no guessed repair or automatic
retry/full rerun follows. Native mobile execution and physical-audio gates remain
independent and unmet by this probe.

`ci/test-tooling.py` includes the standalone
`ci/test-windows-browser-preflight.py` suite. It uses authored PE bytes and mocked
Win32/registry calls, denies process/network creation, and checks finite output,
read-only access masks, buffer limits, races, refusal and exact test selection.
These tests do not execute the Windows APIs or browser and cannot establish an
actual Windows startup result.

### Windows tooling uses the selected Git Bash, not a bare executable name

[Probe run 42213767443](https://github.com/Apdelrahman1911/kmpYT/actions/runs/42213767443)
checked out `5ade5425492fbb4541412c4fe1f15fb6aa5b2590` but failed **before**
browser admission. License, 18 mocked Windows-API tests and 35 browser-free Node
tests passed; 21 tooling tests had 3 failures and 2 errors at bare-`bash` Python
subprocess sites. The actual log reports that WSL has no installed distributions,
while the Actions step itself ran Git Bash. It does not record the exact child
executable path. The native admission/single JVM step was skipped, with zero
JUnit tests/startup receipts: this is not a Chromium startup diagnosis.

Only the probe's tooling step now sets `KMPYT_TEST_BASH` from its running `$BASH`,
using `cygpath -am` for a native absolute Windows path and adding `.exe` if Bash
omitted that suffix. Native Windows tooling requires that explicit local-drive
`bin/bash.exe` or `usr/bin/bash.exe` plus the same installation's `bin/git.exe`
and `usr/bin/msys-2.0.dll`. Missing/wrong/WSL/relative/UNC paths refuse without
launching another shell or searching PATH. These are read-only layout checks,
not binary signature authentication. Other installation layouts need review,
not a silent fallback. Non-Windows tooling resolves its existing Bash to an
absolute path. No system/PATH configuration, WSL install, test skip or ordinary
workflow command changes.

This follows Python's recommendation to use a
[fully qualified executable path](https://docs.python.org/3.12/library/subprocess.html#subprocess.Popen)
because native Windows search rules differ from POSIX, and MSYS2's documented
[manual Unix/Windows path conversion](https://www.msys2.org/docs/filesystem-paths/).
Finite mocks bind all five subprocess sites to the exact selected executable
and verify malformed/missing/incomplete selection refuses before process launch.
Linux light tests cannot establish the repaired native Windows shell handoff;
that still needs a separately authorized exact-revision probe, not an automatic
redispatch or a claimed Chromium repair.
