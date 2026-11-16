@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*

/** Authored bytes and MockEngine only; no platform network requests. */
class IosProgressiveRangeTest {
    private val bytes = ByteArray(512) { (it % 251).toByte() }
    private fun plan() = PlannedDownload(Representation("140",
        "https://r1---fixture.googlevideo.com/videoplayback", TrackRole.AUDIO,
        mimeType = "audio/mp4", codecs = "mp4a.40.2", sizeBytes = bytes.size.toLong()),
        inspectionUrl = "https://www.youtube.com/watch?v=AUTHORED001", mediaId = "AUTHORED001")

    @Test fun initialKnownObjectUsesAnExactRangeAndCanAcceptAnIgnoredFullResponse() = iosTest {
        for (ignored in listOf(false, true)) inIosTestDirectory { directory ->
            val selected = plan()
            val part = TransferPlanning.parts(selected).single()
            val http = HttpClient(MockEngine { request ->
                assertEquals("bytes=0-${bytes.lastIndex}", request.headers[HttpHeaders.Range])
                respond(bytes, if (ignored) HttpStatusCode.OK else HttpStatusCode.PartialContent,
                    Headers.build {
                        append(HttpHeaders.ContentType, "audio/mp4")
                        append(HttpHeaders.ContentLength, bytes.size.toString())
                        append(HttpHeaders.ETag, "\"authored-v1\"")
                        if (!ignored) append(HttpHeaders.ContentRange, "bytes 0-${bytes.lastIndex}/${bytes.size}")
                    })
            })
            val store = IosDownloadResumeStore.open("$directory/output.m4a", iosSelectionDigest(selected))
            try {
                store.load(selected, listOf(part))
                val result = IosHttpPartTransfer(http, store, selected, false).fetch(part) { _, _ -> }
                assertTrue(result.complete)
                assertEquals(bytes.size.toLong(), result.bytes)
                val file = IosMediaFile(store.partPath(part.key), existing = true)
                try { assertContentEquals(bytes, file.read(0, bytes.size)) } finally { file.close() }
            } finally { store.close(discard = true); http.close() }
        }
    }

    @Test fun aPartialPrefixCannotBePublishedAsTheKnownCompleteObject() = iosTest {
        inIosTestDirectory { directory ->
            val selected = plan()
            val part = TransferPlanning.parts(selected).single()
            val http = HttpClient(MockEngine { request ->
                assertEquals("bytes=0-${bytes.lastIndex}", request.headers[HttpHeaders.Range])
                respond(bytes.copyOfRange(0, 128), HttpStatusCode.PartialContent, Headers.build {
                    append(HttpHeaders.ContentType, "audio/mp4")
                    append(HttpHeaders.ContentLength, "128")
                    append(HttpHeaders.ContentRange, "bytes 0-127/${bytes.size}")
                })
            })
            val store = IosDownloadResumeStore.open("$directory/output.m4a", iosSelectionDigest(selected))
            try {
                store.load(selected, listOf(part))
                assertFailsWith<IllegalStateException> { IosHttpPartTransfer(http, store, selected, false).fetch(part) { _, _ -> } }
                assertTrue(store.parts.none { it.complete })
            } finally { store.close(discard = true); http.close() }
        }
    }

    private fun rangeEngine(requests: MutableList<String?>, etag: String?) = MockEngine { request ->
        val range = request.headers[HttpHeaders.Range]
        requests += range
        val (first, last) = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range!!)!!.destructured.let { (a, b) -> a.toInt() to b.toInt() }
        respond(bytes.copyOfRange(first, last + 1), HttpStatusCode.PartialContent, Headers.build {
            append(HttpHeaders.ContentType, "audio/mp4")
            append(HttpHeaders.ContentLength, (last - first + 1).toString())
            etag?.let { append(HttpHeaders.ETag, it) }
            append(HttpHeaders.ContentRange, "bytes $first-$last/${bytes.size}")
        })
    }

    @Test fun largeObjectsAreFetchedAsConsecutiveValidatedSections() = iosTest {
        inIosTestDirectory { directory ->
            val selected = plan()
            val part = TransferPlanning.parts(selected).single()
            val requests = mutableListOf<String?>()
            val http = HttpClient(rangeEngine(requests, "\"authored-v1\""))
            val store = IosDownloadResumeStore.open("$directory/output.m4a", iosSelectionDigest(selected))
            try {
                store.load(selected, listOf(part))
                val result = IosHttpPartTransfer(http, store, selected, false, sectionBytes = 200).fetch(part) { _, _ -> }
                assertTrue(result.complete)
                assertEquals(listOf<String?>("bytes=0-199", "bytes=200-399", "bytes=400-511"), requests)
                val file = IosMediaFile(store.partPath(part.key), existing = true)
                try { assertContentEquals(bytes, file.read(0, bytes.size)) } finally { file.close() }
            } finally { store.close(discard = true); http.close() }
        }
    }

    @Test fun sectionsWithoutAStrongValidatorFallBackToOneRequest() = iosTest {
        inIosTestDirectory { directory ->
            val selected = plan()
            val part = TransferPlanning.parts(selected).single()
            val requests = mutableListOf<String?>()
            val http = HttpClient(rangeEngine(requests, null))
            val store = IosDownloadResumeStore.open("$directory/output.m4a", iosSelectionDigest(selected))
            try {
                store.load(selected, listOf(part))
                val result = IosHttpPartTransfer(http, store, selected, false, sectionBytes = 200).fetch(part) { _, _ -> }
                assertTrue(result.complete)
                assertEquals(listOf<String?>("bytes=0-199", "bytes=0-511"), requests)
                val file = IosMediaFile(store.partPath(part.key), existing = true)
                try { assertContentEquals(bytes, file.read(0, bytes.size)) } finally { file.close() }
            } finally { store.close(discard = true); http.close() }
        }
    }
}
