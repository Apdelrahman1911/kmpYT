# kmpYT integration notes

Use the Kotlin client from a coroutine and keep it alive until its operations
finish. See the [README](../README.md) for setup, public APIs and platform requirements.

## Inspection and source selection

Inspect `report.media` to present formats. When a response supplies HLS/DASH catalogues, call `expandFormats(report)` before selecting their tracks; this fetches manifest metadata only. `playbackPlan(report, selection)` provides portable progressive/SABR source contracts. `search`, `channel`, `playlist`, `more` and `captions` expose browsing and subtitle data. Swift callers use `try await` for suspending operations and handle exported errors with `do`/`catch`.

For a music/podcast player, `inspectAudio(videoUrl)` resolves standalone audio without
running unrelated video-format signature transforms. The returned report marks this audio
scope and retains source-declared manifest pointers for explicit expansion. The default
`inspect` method still inspects the full catalogue.

Use the suspending `preparePlayback(report, selection)` before handing a progressive URI to
a native player. It honors `Representation.availableAtEpochMs`, including a source-declared
pre-playback wait. The synchronous `playbackPlan` remains available for inspection; a host
using it directly must honor that timestamp before requesting media. `download` and the
native transfer/SABR adapters honor the same wait with cancellable delays. An early media
HTTP 403 is not proof that a link expired, and refreshing a link can restart its source wait.
No advertising resource or account/integrity grant is fetched to implement this timing.

Large progressive downloads are fetched as exact HTTP byte ranges of at most 8 MiB over up to
four connections (`ProgressiveDownloadTuning`; googlevideo paces a single larger range to about
the media bitrate). Only a contiguous, synced and SHA-256-receipted prefix is checkpointed; every
section must be an exact 206 of the same strong-ETag representation, otherwise the transfer
falls back to one verified request. Unusually slow watch, player and oEmbed reads are duplicated
once after a bounded delay (`HedgedResearchTransport`); media transfers are never hedged.
`publicClientOrder` selects the request order of the pinned public player profiles.

Search submits its **complete query verbatim**, including surrounding whitespace, up to 16,384
well-formed UTF-16 code units. Oversized/invalid queries and titles are rejected, not shortened.
`YouTubeBrowsePage.query` records that submitted query; `firstEntry` records the leading content
entry **before** unavailable/unsupported filtering in the UI-oriented `items` list. Check its
typed `issue` and nullable kind/identity; never substitute `items.first()` when the first result
matters. Private videos, unknown cards, advertisements and unrecognized shelves cannot silently
promote a later video. A navigable album/playlist/channel shelf is a collection result, not one
of its child tracks. Channels/playlists may be browsed, but are not single-media playback or
download representations. Browse receipts preserve complete titles and stable public identities;
they do not certify playback authorization or recording/album equivalence.

### Owned native SABR playback resources

SABR source URLs are POST protocol endpoints, not ordinary MP4/M4A URLs. For an actual
selected static SABR `SourcePlaybackRequest`, native client factories expose
`supportsSabrPlayback` and `suspend openPlayback(request, authorization)`. The caller must
provide its real `SourcePlaybackAuthorization`; no implicit authorization/integrity issuer
is installed. The existing `YouTubeSabrStreamingSession` is the sole media ingestion engine.

```kotlin
val resource = youtube.openPlayback(selectedSabrRequest, applicationAuthorization)
try {
    // Give the native decoder the exact endpoint for the selected AUDIO/VIDEO role.
    // Never pass it the original SABR endpoint or persist/log the capability URL.
    nativePlayer.prepare(resource.endpoints)
    // Keep resource alive throughout playback, seeks and native callbacks.
} finally {
    nativePlayer.release() // Stop native access before releasing its private cache.
    resource.close()
}
```

The owned endpoint binds only numeric IPv4 loopback, uses a random 256-bit capability route,
and supports exact GET/HEAD/single byte ranges with no-store responses. It serves only verified
cached coverage, not sparse-file length, and rechecks the caller policy on actual media requests.
It has bounded headers, connections, chunks and timeouts. `YouTubeNativePlaybackLimits` defaults
to a 1-GiB static cache budget and four connections per resource; the client permits at most
eight active/opening resources. Seeking waits for whole-track ingestion to reach the requested
bytes; there is no invented live window or server-time-seek protocol. Loopback remains **remote
source playback**, not an offline download.

`resource.close()` cancels/joins owned readers and ingestion, closes listeners and removes its
private cache. Client close also disposes any remaining resources before its transport. Unknown
native release stays a failure on repeated close, never a successful deletion authorization.
Custom hosts can inject `createJvmYouTubeSabrPlaybackAdapter`,
`createAndroidYouTubeSabrPlaybackAdapter` or `createIosYouTubeSabrPlaybackAdapter`; they retain
ownership of the HTTP client passed to those adapter factories. Mobile hosts must explicitly
permit only the required local native transport according to their platform security policy.

Retry a cancelled or interrupted download with the same selection and destination to resume
validated private progress. JVM/Android schema-2 checkpoints additionally permit **standalone
progressive** signing-URL refresh only when the selected media/format, exact origin/path/content
query, strong quoted ETag, actual redirected response scope and committed-prefix SHA-256 still
match. Uncommitted tails are truncated; retained complete parts are revalidated too. Query
order, duplicate/empty pairs and raw encoding remain identity-bearing. Only documented exact
lowercase signing keys on Googlevideo (or explicit authored loopback tests) are excluded.
Changed/unverifiable identity restarts; legacy schema-1 checkpoints require the exact old plan.
SABR and multi-resource plans retain their existing exact-plan/generation bindings rather than
borrowing this progressive identity rule. iOS has a separate checkpoint implementation; these
new JVM/Android prefix/scope guarantees are not a claim of iOS parity.

JVM/Android checkpoint roots must remain caller-private. Symlinks, special files and oversized
trees fail before reset/cleanup. After success, media/checkpoint data is purged **while holding
the OS lock**; the empty reusable `.<destination-name>.kmpyt/lock` inode and its parent remain.
Do not unlink/recreate that lock while any process may reuse the destination: doing so can let
two processes lock different inodes for one path. A host may remove a retired private generation
only under a stronger external lifetime fence proving that no current or future writer can
reuse it. The host owns background scheduling and persistent job state. Checkpoints contain
hashes/receipts, not signed URLs or an application library database.
