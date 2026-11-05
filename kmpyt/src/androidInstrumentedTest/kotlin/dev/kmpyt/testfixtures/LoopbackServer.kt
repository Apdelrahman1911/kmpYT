package dev.kmpyt.testfixtures

import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress

/** Match desktop loopback fixtures without depending on emulator reverse-DNS configuration. */
internal fun MockWebServer.startLoopback() {
    start(InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1)), 0)
}

internal fun MockWebServer.loopbackUrl(path: String): HttpUrl = requireNotNull(
    HttpUrl.Builder().scheme("http").host("127.0.0.1").port(port).build().resolve(path),
)
