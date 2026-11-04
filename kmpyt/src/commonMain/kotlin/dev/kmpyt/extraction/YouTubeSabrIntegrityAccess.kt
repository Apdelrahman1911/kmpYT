package dev.kmpyt.extraction

/**
 * Explicit caller wiring for an actual authorized integrity issuer. MEDIA_RESOURCE
 * bytes are attached only to the recovered streamer_context.po_token protobuf field;
 * no query/header/base64-token contract is inferred. Default transfers have no issuer.
 */
data class YouTubeSabrIntegrityAccess(
    val manager: YouTubeIntegrityContextManager,
    val issuerId: String,
    val binding: YouTubeIntegrityBinding,
) {
    fun requireSource(source: YouTubeSabrSource, clientInfo: SabrClientInfo) {
        require(binding == YouTubeIntegrityBinding.forSabr(source, clientInfo, binding.visitorBinding)) {
            "The SABR integrity issuer binding differs from the actual source/client/resource generation"
        }
    }
    override fun toString() = "YouTubeSabrIntegrityAccess(issuerId=$issuerId,binding=$binding,context=redacted)"
}
