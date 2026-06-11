package com.maxrave.ktorext.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
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

    private val algorithmType: Int
        get() = when (algorithm) {
            "HmacSHA1" -> kCCHmacAlgSHA1
            "HmacSHA256", else -> kCCHmacAlgSHA256
        }

    private val digestLength: Int
        get() = when (algorithmType) {
            kCCHmacAlgSHA1 -> CC_SHA1_DIGEST_LENGTH
            else -> CC_SHA256_DIGEST_LENGTH
        }

    /**
     * Generate an HMAC token paired with current timestamp for API requests.
     *
     * @param uri The URI to include in the HMAC computation
     * @return Pair of (hmacToken, timestamp)
     */
    actual fun getMacTimestampPair(uri: String): Pair<String, String> {
        val timestamp = Clock.System.now().toEpochMilliseconds().toString()
        val data = "$timestamp$uri"
        val hmacToken = generateHmac(data)
        return hmacToken to timestamp
    }

    /**
     * Generate HMAC token for given data using CoreCrypto.
     *
     * @param data The data to generate HMAC for
     * @return Base64 encoded HMAC token
     */
    actual fun generateHmac(data: String): String {
        val keyBytes = secretKey.encodeToByteArray()
        val dataBytes = data.encodeToByteArray()

        return memScoped {
            val output = allocArray<UByteVar>(digestLength)

            keyBytes.usePinned { keyPinned ->
                dataBytes.usePinned { dataPinned ->
                    CCHmac(
                        alg = algorithmType,
                        key = keyPinned.addressOf(0),
                        keyLength = keyBytes.size.convert<UInt>(),
                        data = dataPinned.addressOf(0),
                        dataLength = dataBytes.size.convert<UInt>(),
                        macOut = output,
                    )
                }
            }

            // Convert C UByteArray to Kotlin ByteArray using functional constructor
            val bytes = ByteArray(digestLength) { i -> output[i].convert<Byte>() }
            Base64.encode(bytes)
        }
    }

    /**
     * Validate HMAC token for given data.
     *
     * @param data The data that was used to generate the HMAC
     * @param hmac The HMAC token to validate
     * @return True if the calculated HMAC matches the provided HMAC
     */
    actual fun validateHmac(data: String, hmac: String): Boolean {
        val calculatedHmac = generateHmac(data)
        return calculatedHmac == hmac
    }

    /**
     * Validate timestamp to prevent replay attacks.
     *
     * @param timestamp The timestamp to validate (in milliseconds as String)
     * @return True if timestamp is within the allowed time window (5 minutes)
     */
    actual fun isValidTimestamp(timestamp: String): Boolean {
        val requestTime = timestamp.toLongOrNull() ?: return false
        val currentTime = Clock.System.now().toEpochMilliseconds()
        return (currentTime - requestTime) < tokenTtl
    }
}
