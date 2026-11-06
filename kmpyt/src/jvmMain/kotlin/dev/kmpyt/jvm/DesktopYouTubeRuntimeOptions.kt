package dev.kmpyt.jvm

/**
 * Host-vetted local runtime locations. Explicit values must be absolute existing executable
 * files / a writable temporary directory. An invalid explicit value fails, never falls back
 * to another runtime. Absent executables retain the KMPYT_NODE/KMPYT_CHROMIUM/default lookup.
 *
 * The temporary directory scopes both Java-owned helper/profile files and the CHILD process's
 * TMPDIR, TMP and TEMP. This does not mutate the application's global environment/properties.
 * Node/Chromium are external dependencies, not downloaded or bundled by this library.
 */
data class DesktopYouTubeRuntimeOptions(
    val nodeExecutable: String? = null,
    val chromiumExecutable: String? = null,
    val temporaryDirectory: String? = null,
)
