package dev.kmpyt.extraction

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*

/** Shared bounded HTTP transport; native code supplies only its engine and origin-bound session headers. */
class KtorResearchTransport(private val client: HttpClient, private val maximumBytes: Int = EmbeddedData.MAX_BODY,
    private val sessionHeaders: (ResearchRequest) -> Map<String,String> = { emptyMap() }) : ResearchTransport {
    override suspend fun execute(request: ResearchRequest): ResearchResponse {
        ResearchAccess.origin(request.url)
        require(request.method in setOf("GET","POST","HEAD")) { "Unsupported research request method" }
        require(maximumBytes in 1..8_000_000) { "Invalid response byte limit" }
        val headers = sessionHeaders(request)
        // The response block calls Ktor's streaming path (skipSaveBody). A plain
        // request() can buffer the entire body before this transport sees headers.
        return client.prepareRequest(request.url) {
            method = HttpMethod.parse(request.method)
            header("User-Agent", "kmpYT-Research/1.0")
            (request.headers + headers).forEach { (key,value) -> header(key,value) }
            request.body?.let { setBody(it) }
        }.execute { response ->
            require(request.method=="HEAD" || (response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) <= maximumBytes) { "Response exceeds the research byte limit" }
            val channel=response.bodyAsChannel();val buffer=ByteArray(8192);val chunks=mutableListOf<ByteArray>();var size=0
            try {
                while(true) { val n=channel.readAvailable(buffer);if(n<0)break
                    require(size+n<=maximumBytes) { "Response exceeds the research byte limit" }
                    if(n>0) { chunks+=buffer.copyOf(n);size+=n }
                }
            } finally { channel.cancel() }
            val bytes=ByteArray(size);var offset=0;chunks.forEach { it.copyInto(bytes,offset);offset+=it.size }
            ResearchResponse(response.call.request.url.toString(),response.status.value,bytes.decodeToString(),response.headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") })
        }
    }
}
