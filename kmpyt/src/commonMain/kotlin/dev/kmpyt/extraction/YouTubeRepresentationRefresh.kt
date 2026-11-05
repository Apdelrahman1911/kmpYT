package dev.kmpyt.extraction

/** Resource refresh preserves the selected track, including language and playback characteristics. */
internal fun sameRefreshedTrack(previous: Representation, candidate: Representation): Boolean = candidate.selectable &&
    TransferPlanning.sameRepresentation(previous, candidate) &&
    previous.mimeType.substringBefore(';').trim().lowercase() == candidate.mimeType.substringBefore(';').trim().lowercase() &&
    previous.frameRate == candidate.frameRate && previous.audioGroupId == candidate.audioGroupId &&
    previous.audioTrackId == candidate.audioTrackId && previous.audioLanguage == candidate.audioLanguage &&
    previous.audioChannels == candidate.audioChannels && previous.audioSampleRate == candidate.audioSampleRate &&
    previous.colorInfo == candidate.colorInfo && previous.isHdr == candidate.isHdr && previous.isDrc == candidate.isDrc

/** Catalogue suffixes may change between fresh responses, but the selected track must not. */
internal fun refreshedProgressiveRepresentation(previous: Representation,
    offered: List<Representation>): Representation {
    require(previous.transport == TransportKind.PROGRESSIVE)
    fun sameIdentity(candidate: Representation) = sameRefreshedTrack(previous, candidate.copy(id = previous.id))
    val exact = offered.filter { it.id == previous.id && sameIdentity(it) }
    if (exact.size == 1) return exact.single()
    require(exact.isEmpty()) { "The refreshed representation identity is ambiguous." }
    require(previous.segments.isEmpty() && previous.initialization == null) { "The selected representation is no longer offered." }
    val suffix = Regex("#progressive(?:-[0-9]+)?$")
    val originalId = previous.id.replace(suffix, "")
    val alternatives = offered.filter {
        it.transport == TransportKind.PROGRESSIVE && it.segments.isEmpty() && it.initialization == null &&
            it.id.replace(suffix, "") == originalId && sameIdentity(it)
    }
    require(alternatives.size == 1) { "The selected representation is no longer offered unambiguously." }
    return alternatives.single().copy(id = previous.id)
}
