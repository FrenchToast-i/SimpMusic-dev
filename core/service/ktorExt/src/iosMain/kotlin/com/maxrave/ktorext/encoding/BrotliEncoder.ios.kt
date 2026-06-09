package com.maxrave.ktorext.encoding

import io.ktor.util.ContentEncoder
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlin.coroutines.CoroutineContext

private object UnsupportedBrotliEncoder : ContentEncoder {
    override val name: String = "br"

    override fun decode(
        source: ByteReadChannel,
        coroutineContext: CoroutineContext,
    ): ByteReadChannel = throw UnsupportedOperationException("Brotli decoding is not supported on iOS")

    override fun encode(
        source: ByteReadChannel,
        coroutineContext: CoroutineContext,
    ): ByteReadChannel = throw UnsupportedOperationException("Brotli encoding is not supported on iOS")

    override fun encode(
        source: ByteWriteChannel,
        coroutineContext: CoroutineContext,
    ): ByteWriteChannel = throw UnsupportedOperationException("Brotli encoding is not supported on iOS")
}

actual fun createBrotliEncoder(): ContentEncoder = UnsupportedBrotliEncoder
