package dev.kmpyt.internal

import java.io.File

/**
 * Validate the leaf and every extant ancestor without following private-storage links.
 * An absent leaf/ancestor is allowed only when requested, before creating new state.
 * Permission/provider errors, dangling links and special/reparse entries are never absence.
 * Callers must keep the containing directory private: this is not an atomic defense against
 * a hostile concurrent same-user filesystem mutation on every supported platform.
 */
internal expect fun requirePrivateCheckpointPath(file: File, allowMissing: Boolean = false)
