package com.maxrave.ktorext.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCHmacAlgSHA1
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.posix.time
import kotlin.io.encoding.Base64

/**
 * Clean, idiomatic iOS implementation of HMAC using CoreCrypto.
 * Eliminates native allocation overhead and uses standard pinned Kotlin ByteArrays.
 */
@OptIn(ExperimentalForeignApi::class)
actual class Hmac actual constructor(
    private val algorithm: String,
    private val secretKey: String,
) {
    private val tokenTtl = 300_000L // 5 minutes in ms
    private val alg = if (algorithm == "HmacSHA1") kCCHmacAlgSHA1 else kCCHmacAlgSHA256
    private val digestLength = if (alg == kCCHmacAlgSHA1) CC_SHA1_DIGEST_LENGTH else CC_SHA256_DIGEST_LENGTH

    actual fun getMacTimestampPair(uri: String): Pair<String, String> {
        val timestamp = (time(null) * 1000L).toString()
        return generateHmac("$timestamp$uri") to timestamp
    }

    actual fun generateHmac(data: String): String {
        val key = secretKey.encodeToByteArray()
        val input = data.encodeToByteArray()
        val out = ByteArray(digestLength)

        key.usePinned { keyPinned ->
            input.usePinned { inputPinned ->
                out.usePinned { outPinned ->
                    CCHmac(
                        alg,
                        keyPinned.addressOf(0),
                        key.size.toULong(),
                        inputPinned.addressOf(0),
                        input.size.toULong(),
                        outPinned.addressOf(0)
                    )
                }
            }
        }
        return Base64.encode(out)
    }

    actual fun validateHmac(data: String, hmac: String): Boolean = generateHmac(data) == hmac

    actual fun isValidTimestamp(timestamp: String): Boolean {
        val requestTime = timestamp.toLongOrNull() ?: return false
        return (time(null) * 1000L - requestTime) < tokenTtl
    }
}
