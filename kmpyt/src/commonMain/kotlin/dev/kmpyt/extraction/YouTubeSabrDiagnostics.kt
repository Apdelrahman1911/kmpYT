package dev.kmpyt.extraction

fun PlannedDownload.withSabrDiagnostic(response: SabrTransferResponseDiagnostic): PlannedDownload =
    copy(sabrDiagnostics = (sabrDiagnostics + response.toString().take(8192)).takeLast(16))

/** Safe task error text preserves the distinction between protection and absent media. */
fun SabrTransferException.safeSummary(): String {
    val statuses = responseDiagnostics.flatMap { it.protectionStatuses }
    val displayed = statuses.take(32).joinToString("|")
    val fullyClassified = responseDiagnostics.count { it.completeFraming }
    val media = responseDiagnostics.sumOf { it.mediaPayloadBytes }
    val committed = responseDiagnostics.sumOf { it.committedBytes }
    val headers = responseDiagnostics.flatMap { it.mediaHeaders }.take(4).joinToString("|") {
        "id=${it.headerId},itag=${it.itag},offset=${it.offset},length=${it.length},init=${it.isInitialization},sequence=${it.sequenceNumber},compression=${it.compression}"
    }
    return "SABR transfer stopped ($code${httpStatus?.let { ", HTTP $it" }.orEmpty()}; " +
        "protectionStatuses=$displayed; protectionRecordCount=${statuses.size}; " +
        "classifiedResponses=$fullyClassified; responseCount=${responseDiagnostics.size}; " +
        "matchingMediaBytes=$media; committedMediaBytes=$committed; headers=[$headers])."
}
