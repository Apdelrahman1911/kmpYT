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
  of the six fixed source messages described below. No byte counts, contents,
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

The later single-test [probe 42219037209](https://github.com/Apdelrahman1911/kmpYT/actions/runs/42219037209)
at **f2a87cc35910684bdacee9c86e4bf017b2ddc5f4** passed its repaired tooling and
read-only Chrome156 admission, then failed its one actual Chromium test with the
same four observation categories. The two restriction-message classifiers below
were added **after** that run; its discarded stderr cannot be reclassified.

The existing bounded stderr parser can replace its one stderr token with:

| Exact source literal observed | Fixed stderr token suffix |
|---|---|
| `Cannot start http server for devtools.` | `DEVTOOLS_BIND_MESSAGE` |
| `Error writing DevTools active port to file ` (trailing space required) | `PORT_WRITE_MESSAGE` |
| `Lock file can not be created` | `PROFILE_LOCK_MESSAGE` |
| `Failed to create a ProcessSingleton for your profile directory.` | `PROCESS_SINGLETON_MESSAGE` |
| `DevTools remote debugging is disallowed by the system admin.` | `DEVTOOLS_POLICY_MESSAGE` |
| `DevTools remote debugging requires a non-default data directory. Specify this using --user-data-dir.` | `DEVTOOLS_DATA_DIR_MESSAGE` |

The prefix is always `OFFLINE_STARTUP_STDERR_`. These are literal observations,
not authenticated log records, OS error codes or proof of a universal root cause.
Sources at Chromium **156.0.8078.80**:
[DevTools bind/port writing](https://chromium.googlesource.com/chromium/src/+/refs/tags/156.0.8078.80/content/browser/devtools/devtools_http_handler.cc#316),
[Windows profile lock](https://chromium.googlesource.com/chromium/src/+/refs/tags/156.0.8078.80/chrome/browser/process_singleton_win.cc#286),
[singleton refusal](https://chromium.googlesource.com/chromium/src/+/refs/tags/156.0.8078.80/chrome/app/chrome_main_delegate.cc#624),
[policy/data-directory messages](https://chromium.googlesource.com/chromium/src/+/refs/tags/156.0.8078.80/chrome/browser/browser_process_impl.cc#1074).

Chrome's [136+ restriction](https://developer.chrome.com/blog/remote-debugging-port)
requires a non-default profile; this helper already supplies a fresh
`--user-data-dir`. In the exact156 source, the
[data-directory comparison](https://chromium.googlesource.com/chromium/src/+/refs/tags/156.0.8078.80/chrome/common/chrome_paths.cc#524)
can also return unknown if either current/default directory lookup fails, and
the branded-Chrome [guard rejects unknown as well as default](https://chromium.googlesource.com/chromium/src/+/refs/tags/156.0.8078.80/chrome/browser/devtools/remote_debugging_server.cc#160).
`DEVTOOLS_DATA_DIR_MESSAGE` cannot distinguish those cases or prove a command-line
or path-conversion defect. `DEVTOOLS_POLICY_MESSAGE` does not identify the policy
provider, registry key or effective value. Neither token is a new primary failure
or authorization to change the browser, environment, registry, sandbox or profile.

Only complete newline-terminated lines remaining in the **existing 16,000-character
tail** qualify. The first classified line wins; the table order breaks ties within
one line. A later invalid announcement cannot overwrite it, but a valid endpoint
still succeeds through either original channel. A truncated private prefix does
not prevent classification if the entire exact literal and terminating newline
remain in the tail; no prefix/suffix authenticates the message. Unfinished or
discarded literals, changed or unknown text stay unclassified; absence of a token
does not disprove an error. Browser-free fixtures cover both truncation directions,
split/CRLF messages, near misses, first-message/tie ordering, both success channels,
unchanged timeout/exit failures, post-deadline immutability and private sentinels.
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
status name and native exit-78 gate. `workflow_dispatch` with the explicit
`mode=windows-startup-probe` selects the additional **windows-2022** job instead
of the full matrix. Every ordinary job's probe-name branch assigns a distinct
`Probe scope - ... gates not executed` name. GitHub can retain the unevaluated
name expression for a skipped job; neither form is an ordinary required context.
Its skipped conclusion must not masquerade as an ordinary required check.
The probe uses the disjoint concurrency
prefix `ci-windows-startup-probe-${{ github.ref }}`, not a suffix that could
collide with an ordinary run on a branch named `topic-windows-startup-probe`.
Outside the two explicit diagnostic modes the original effective names and
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

### Separate known-folder diagnostic: no application runtime or repair

The branded Chrome156
[default-directory guard](https://chromium.googlesource.com/chromium/src/+/refs/tags/156.0.8078.80/chrome/browser/devtools/remote_debugging_server.cc#160)
rejects an **unknown** comparison as well as a default directory. The
[Windows default-directory provider](https://chromium.googlesource.com/chromium/src/+/refs/tags/156.0.8078.80/chrome/common/chrome_paths_win.cc#42)
gets `base::DIR_LOCAL_APP_DATA`; the exact
[base provider](https://chromium.googlesource.com/chromium/src/+/refs/tags/156.0.8078.80/base/base_paths_win.cc#151)
uses `SHGetFolderPath(NULL, CSIDL_LOCAL_APPDATA, NULL, SHGFP_TYPE_CURRENT, buffer)`.
It does not simply read `LOCALAPPDATA`, nor require the final Chrome `User Data`
directory to exist. The existing scoped environment makes HOME/USERPROFILE and
LOCALAPPDATA repository-owned but does not create `home/AppData/Local`.
An unavailable native known-folder location is therefore a **hypothesis**, not
the established cause of any retained failed browser run. No old-run HRESULT
has been recovered or reconstructed.

The separate explicit manual mode `windows-known-folder-diagnostic` asks only
that question. It selects one standard public **windows-2022** job. The four
ordinary groups keep their distinct nonrequired `Probe scope - ... not executed`
skip names; the startup-test job is also skipped under its own nonrequired name.
Full-mode names, matrix labels, native exit-78 gates, policy preflight, production
browser flags, startup deadlines and process ownership are unchanged. A disjoint
`ci-windows-known-folder-diagnostic-${{ github.ref }}` concurrency prefix cannot
replace a full or startup-test run. GitHub may display an unevaluated name for
a skipped job; neither form is an ordinary required context.

The job uses the existing pinned checkout action, the unchanged scoped
environment wrapper, license verification, the **standalone Python-only mocked
suite**, and `ci/windows-known-folder-diagnostic.py`. It does not run
`ci/test-tooling.py`, tool selection, application Node, browser, Java/Gradle,
FFmpeg, media/source access or a subsequent startup test. The pinned checkout
action itself uses GitHub's standard **action-runtime Node**; that infrastructure
is not an application/runtime/fixture Node invocation. No alternative bootstrap,
cache/upload action, paid runner or automatic retry is introduced.

Both the Python-only check step and the diagnostic step have a **one-minute**
Actions timeout; the whole job has five minutes including checkout. The existing
Actions job owns cancellation. There is no second killer, background owner or
deadline extension. A finite count of synchronous Win32 calls is **not** a native
API wall-clock guarantee. Timeout/missing output means failed/unknown evidence,
not a missing directory, and never triggers a repair or another launch.

#### Exact read-only observations

On native64 Windows only (64-bit pointers, two-byte `wchar_t`), two separately
recorded calls use explicit 32-bit HRESULT/int and DWORD declarations and a
zeroed 260-wide-character buffer:

1. `SHGetFolderPathW(NULL, 0x001c, NULL, 0, buffer)` — normal current lookup.
2. `SHGetFolderPathW(NULL, 0x401c, NULL, 0, buffer)` — the same current lookup with
   `CSIDL_FLAG_DONT_VERIFY`, **diagnosis only**, never browser admission.

The fixed flags never include `CSIDL_FLAG_CREATE`. There is no SHSet/registry
write, environment mutation, token change, folder redirection or directory
repair. Both system DLLs (`shell32.dll`, `kernel32.dll`) and their dependencies
use only
[`LOAD_LIBRARY_SEARCH_SYSTEM32`](https://learn.microsoft.com/en-us/windows/win32/api/libloaderapi/nf-libloaderapi-loadlibraryexw),
not PATH, the checkout, a browser directory, or an environment-derived DLL path.

The exact HRESULT is eight uppercase hexadecimal digits and has a separate
FAILED bit. Failed-call output is never read. Positive nonzero HRESULT is not
S_OK; its buffer is deliberately not consumed either. S_OK with empty output,
unterminated output, invalid UTF-16 or a nonzero suffix after NUL is distinct from
a usable path. No output buffer, path, path hash, username, error message or
arbitrary environment value is printed.

The executing source's lexical checkout root, with matching CWD, is the existing
trust anchor, not an ambient project-root override. Ten boolean assertions check
that root/CWD, the scoped marker, HOME, USERPROFILE, LOCALAPPDATA, APPDATA and
TEMP/TMP/TMPDIR match the unchanged contract. They do not assert directory
existence or Java's actual runtime settings. Existing `ci/scoped.sh` initialization
still creates its ordinary base directories; `environmentChanged=false` and
`directoriesCreated=0` describe the **diagnostic**, not checkout or that wrapper.
In particular, no AppData, Local, Roaming or Chrome User Data directory is added
to the wrapper. LOCALAPPDATA existence is not claimed if it was not observed.

Paths are bounded ASCII local-drive forms with component-wise case-insensitive
comparison, not raw string prefixes or Unicode casefold aliasing. UNC/device,
relative, dot components, ADS, DOS-device names, trailing-dot/space aliases,
non-ASCII and overlong forms are unsupported. Outside paths remain uninspected;
`OUTSIDE_REPOSITORY_LOCAL` means **local-drive syntax**, not proof that an unknown
outside drive is fixed media. No drive-type call is made for an outside return.

One explicit
[`GetDriveTypeW`](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-getdrivetypew)
requires the checkout drive to be fixed media. A read-only root attribute check
must show a non-reparse directory. Only then may
[`GetFileAttributesW`](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-getfileattributesw)
inspect either returned path **inside** the root or the two fixed candidates
`.work/home/AppData` and `.work/home/AppData/Local`. The API reports a final
symbolic link's own attributes. Every in-repo ancestor is checked before descent;
an observed reparse point is rejected without reading descendants. No listing,
contents, lock, `resolve()` or outside-path metadata read occurs. The root is one
of at most **16 ancestor checks**, with at most **4 leaf checks** globally;
case-equivalent paths and ancestors are deduplicated. An ancestor failure carries
`at=ANCESTOR`, not a fabricated leaf lookup. A bound reached gives NOT_PROBED and
INCOMPLETE, never MISSING.

These are finite **point-in-time pathname checks**, not handle-relative atomic
containment against hostile same-user renames or a check of ancestors above the
trusted checkout root. Known-folder configuration and metadata can change after
observation. No whole-Windows write containment or inferred browser diagnosis is
claimed.

#### Fixed receipt and interpretation

Exactly one compact JSON line, including newline at most **4096 bytes**, has this
complete field allowlist (all strings are fixed categories, never native values):

- Top level: `schemaVersion`, `diagnostic`, `outcome`, `reason`, `browserLaunched`,
  `environmentChanged`, `directoriesCreated`, `noBrowserAdmission`,
  `environmentChecks`, `boundary`, `current`, `dontVerify`, `candidates`, `counts`.
- `environmentChecks`: `scopedMarker`, `repositoryRoot`, `workingDirectory`,
  `home`, `userProfile`, `localAppData`, `appData`, `temp`, `tmp`, `tmpdir` (booleans).
- `boundary`: `driveType` (numeric/null) and `root` (metadata record).
- `current` / `dontVerify`: `flags`, `attempted`, `hresult`, `failed`, `buffer`,
  `nulTerminated`, `nonempty`, `pathClass`, `metadata`, `winError`.
- `candidates`: `homeAppData`, `homeAppDataLocal` (metadata records).
- Every metadata record: `state`, `winError`, `at` (`SELF`, `ANCESTOR`, `NONE`).
- `counts`: `knownFolderAttempts`, `driveTypeChecks`, `leafChecks`, `ancestorChecks`.

The six path classes are `EXACT_SCOPED_HOME_APPDATA_LOCAL`,
`EXACT_SCOPED_LOCALAPPDATA_ENV`, `OTHER_INSIDE_REPOSITORY`,
`OUTSIDE_REPOSITORY_LOCAL`, `NONLOCAL_OR_UNSUPPORTED`, `NO_USABLE_PATH`.
Metadata states are `DIRECTORY`, `MISSING`, `NON_DIRECTORY`, `REPARSE_REJECTED`,
`ACCESS_DENIED`, `OTHER_ERROR`, `NOT_PROBED`. Missing Win32 errors 2/3 are distinct
from denied 5; other error codes are bounded unsigned integers or null. Unknown
fields, added values, duplicate JSON keys, contradictory HRESULT/classification,
invented admission and oversized receipts are rejected by the pure strict parser.
If the report itself is lost/invalid, the fixed INVALID_REPORT fallback uses
UNKNOWN buffers and **null** attempts/counts, not a false assertion of zero calls.

`outcome=OBSERVED` / exit 0 means this diagnostic collected its finite observations,
**including possibly failing HRESULTs**. It does not mean any test, browser,
source, media or release gate passed. Refused/incomplete returns 78. Safety markers
are always `browserLaunched=false`, `environmentChanged=false`,
`directoriesCreated=0`, `noBrowserAdmission=true`. The Python tests exercise mocked
System32 loading, exact flags/widths/buffers, metadata bounds/refusals, environment
checks and privacy; no native DLL or known-folder call is used by those tests.

Normal-fails/DONT_VERIFY-S_OK/exact-owned-missing-path evidence may motivate a
**separate** minimal directory-initialization proposal. Denied/reparse/unknown,
outside/unsupported paths, an already successful normal lookup, or a missing
receipt do not authorize that repair. Even supported evidence requires review of
the owned ancestors and scoped base directories, a separately authorized native
before/after comparison, and only then a separately authorized unchanged original
one-method Chromium test. Never create Chrome's default profile, restore a host
home, bypass policy, weaken sandbox/offline flags, substitute DONT_VERIFY for the
normal lookup, or automatically dispatch the next step. A later experiment cannot
manufacture the unavailable HRESULT from the older failed run.
