package dev.kmpyt.testfixtures

import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress

/** Never derive transport-test destinations from machine-specific reverse DNS/hosts aliases. */
internal fun MockWebServer.startLoopback() {
    start(InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1)), 0)
}

internal fun MockWebServer.loopbackUrl(path: String): HttpUrl = requireNotNull(
    HttpUrl.Builder().scheme("http").host("127.0.0.1").port(port).build().resolve(path),
)
