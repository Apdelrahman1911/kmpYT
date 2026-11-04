package dev.kmpyt.extraction

enum class SabrResponseProtectionAction {
    NONE,
    OPTIONAL_REFRESH_WITH_MEDIA,
    REQUIRED_REFRESH_EMPTY,
    OPTIONAL_REFRESH_EMPTY_BACKOFF,
    ADVANCE_EMPTY_PROTOCOL,
    REJECT_EMPTY,
    REJECT_UNKNOWN_STATUS,
}

/**
 * Numeric 2/3 are recovered protocol values, not invented token-error names.
 * Retained UmpRequestHelper.A/B/M/N/O distinguishes empty media from a response
 * which already returned media. This pure decision never creates credentials.
 */
object YouTubeSabrResponsePolicy {
    fun classify(protectionStatus: Int?, mediaPresent: Boolean, protocolCanAdvance: Boolean): SabrResponseProtectionAction {
        if (protectionStatus != null && protectionStatus !in 0..3) return SabrResponseProtectionAction.REJECT_UNKNOWN_STATUS
        if (mediaPresent) return if (protectionStatus == 2 || protectionStatus == 3)
            SabrResponseProtectionAction.OPTIONAL_REFRESH_WITH_MEDIA else SabrResponseProtectionAction.NONE
        return when (protectionStatus) {
            3 -> SabrResponseProtectionAction.REQUIRED_REFRESH_EMPTY
            2 -> SabrResponseProtectionAction.OPTIONAL_REFRESH_EMPTY_BACKOFF
            else -> if (protocolCanAdvance) SabrResponseProtectionAction.ADVANCE_EMPTY_PROTOCOL else SabrResponseProtectionAction.REJECT_EMPTY
        }
    }
}

/** Source header structure only: no video identity, URLs, xtags, contexts or media payloads. */
data class SabrTransferMediaHeaderDiagnostic(
    val headerId: UInt?,
    val itag: Int?,
    val offset: ULong?,
    val length: Long?,
    val isInitialization: Boolean?,
    val sequenceNumber: Long?,
    val startMs: Int?,
    val durationMs: Int?,
    val compression: Int?,
    val hasFormatId: Boolean,
    val hasVideoId: Boolean,
    val hasTimeRange: Boolean,
) {
    /** The optional INT64 was absent; rjb.r in the original APK uses protocol default 0L. */
    val sequenceDefaulted: Boolean get() = sequenceNumber == null
}

enum class SabrTransferFailureStage {
    HTTP_REQUEST, RESPONSE_HEADERS, BODY_READ, UMP_PARSE, RESPONSE_FINALIZE, MEDIA_COMMIT, CALLBACK,
}

/** A bounded, redacted receipt for one physical HTTP response, including late SPS records. */
data class SabrTransferResponseDiagnostic(
    val requestNumber: Int,
    val httpStatus: Int?,
    val receivedBytes: Long,
    val completeFraming: Boolean,
    val mediaPayloadBytes: Long,
    val committedBytes: Long,
    val protectionStatuses: List<Int>,
    val protectionRecordWithoutStatus: Boolean,
    val serverErrorCodes: List<Int?>,
    val action: SabrResponseProtectionAction?,
    val code: String,
    val integrityCode: String? = null,
    val mediaHeaders: List<SabrTransferMediaHeaderDiagnostic> = emptyList(),
    val mediaHeaderCount: Int = mediaHeaders.size,
    /** Fixed internal reason or a bounded exception class name; never a message, cause or URL. */
    val transportFailureType: String? = null,
    val failureStage: SabrTransferFailureStage? = null,
    /** At most eight static class.method names; may be empty for stripped/unsupported runtime symbols. No locations, messages, URLs or causes. */
    val failureFrames: List<String> = emptyList(),
    /** Actual discarded extra-track bytes and complete packets; never committed output. */
    val ignoredMediaPayloadBytes: Long = 0,
    val ignoredMediaPacketCount: Int = 0,
) {
    init {
        require(failureFrames.size <= 8 && failureFrames.all {
            it.length in 1..256 && '.' in it && it.matches(Regex("[A-Za-z0-9_.$<>]+"))
        }) { "Failure frames must contain bounded class.method names only" }
    }
    override fun toString() = "SabrTransferResponseDiagnostic(request=$requestNumber,httpStatus=$httpStatus,receivedBytes=$receivedBytes,completeFraming=$completeFraming,mediaBytes=$mediaPayloadBytes,committedBytes=$committedBytes,ignoredMediaBytes=$ignoredMediaPayloadBytes,ignoredMediaPackets=$ignoredMediaPacketCount,protectionStatuses=$protectionStatuses,missingStatus=$protectionRecordWithoutStatus,serverErrors=$serverErrorCodes,action=$action,code=$code,integrityCode=$integrityCode,transportFailureType=$transportFailureType,failureStage=$failureStage,failureFrames=$failureFrames,mediaHeaderCount=$mediaHeaderCount,mediaHeaders=$mediaHeaders,payloads=redacted)"
}
