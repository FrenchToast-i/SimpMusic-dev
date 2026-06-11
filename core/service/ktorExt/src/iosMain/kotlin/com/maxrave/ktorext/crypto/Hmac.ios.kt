package com.maxrave.ktorext.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCHmacAlgSHA1
import platform.CoreCrypto.kCCHmacAlgSHA256
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/**
 * iOS implementation of HMAC using CoreCrypto framework.
 * Provides HMAC-SHA1 and HMAC-SHA256 functionality for secure API authentication.
 */
@OptIn(ExperimentalForeignApi::class)
actual class Hmac actual constructor(
    private val algorithm: String,
    private val secretKey: String,
) {
    private val tokenTtl: Long = 300_000 // 5 minutes in milliseconds

    // algorithmType must match the native C constant types (UInt)
    private val algorithmType: UInt
        get() = when (algorithm) {
            "HmacSHA1" -> kCCHmacAlgSHA1
            "HmacSHA256" -> kCCHmacAlgSHA256
            else -> kCCHmacAlgSHA256
        }

    // digestLength remains an Int (CC_*_DIGEST_LENGTH constants are Int)
    private val digestLength: Int
        get() = when (algorithmType) {
            kCCHmacAlgSHA1 -> CC_SHA1_DIGEST_LENGTH
            else -> CC_SHA256_DIGEST_LENGTH
        }

    actual fun getMacTimestampPair(uri: String): Pair<String, String> {
        val timestamp = Clock.System.now().toEpochMilliseconds().toString()
        val data = "$timestamp$uri"
        val hmacToken = generateHmac(data)
        return hmacToken to timestamp
    }

    actual fun generateHmac(data: String): String {
        val keyBytes = secretKey.encodeToByteArray()
        val dataBytes = data.encodeToByteArray()

        return memScoped {
            val output = allocArray<UByteVar>(digestLength)

            keyBytes.usePinned { keyPinned ->
                dataBytes.usePinned { dataPinned ->
                    // Call CCHmac with positional args and use correct length types (ULong)
                    CCHmac(
                        algorithmType,
                        keyPinned.addressOf(0),
                        keyBytes.size.toULong(),
                        dataPinned.addressOf(0),
                        dataBytes.size.toULong(),
                        output
                    )
                }
            }

            // Convert native UByte array to Kotlin ByteArray
            val bytes = ByteArray(digestLength) { i -> output[i].toByte() }
            Base64.encode(bytes)
        }
    }

    actual fun validateHmac(data: String, hmac: String): Boolean {
        val calculatedHmac = generateHmac(data)
        return calculatedHmac == hmac
    }

    actual fun isValidTimestamp(timestamp: String): Boolean {
        val requestTime = timestamp.toLongOrNull() ?: return false
        val currentTime = Clock.System.now().toEpochMilliseconds()
        return (currentTime - requestTime) < tokenTtl
    }
}
