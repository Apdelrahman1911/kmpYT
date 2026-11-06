@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.internal.ResumeSha256
import kotlinx.cinterop.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.CoreCrypto.*

/** CoreCrypto state is copied by value; no leaked native allocation or whole-file buffering. */
internal class IosResumeSha256 : ResumeSha256 {
    private var context: CValue<CC_SHA256_CTX> = memScoped {
        val state = alloc<CC_SHA256_CTX>()
        check(CC_SHA256_Init(state.ptr) == 1)
        state.readValue()
    }

    override fun update(bytes: ByteArray, count: Int) {
        require(count in 0..bytes.size)
        if (count == 0) return
        context = memScoped {
            val state = context.getPointer(this)
            bytes.usePinned { check(CC_SHA256_Update(state, it.addressOf(0), count.convert()) == 1) }
            state.pointed.readValue()
        }
    }

    override fun snapshot(): String = memScoped {
        val state = context.getPointer(this)
        val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
        digest.usePinned { check(CC_SHA256_Final(it.addressOf(0).reinterpret(), state) == 1) }
        digest.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}

internal suspend fun iosPrefixHasher(file: IosMediaFile, bytes: Long): IosResumeSha256 {
    require(bytes >= 0 && bytes <= file.length())
    val digest = IosResumeSha256()
    var offset = 0L
    while (offset < bytes) {
        currentCoroutineContext().ensureActive()
        val chunk = file.read(offset, minOf(64 * 1024L, bytes - offset).toInt())
        digest.update(chunk)
        offset += chunk.size
    }
    return digest
}
