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
import platform.Foundation.NSData
import platform.Foundation.NSDate
import kotlin.io.encoding.Base64

@OptIn(ExperimentalForeignApi::class)
actual class Hmac actual constructor(algorithm: String, private val secretKey: String) {
    private var tokenTtl: Long = 300_000
    private val algorithmType =
        when (algorithm) {
            "HmacSHA1" -> kCCHmacAlgSHA1
            else -> kCCHmacAlgSHA256
        }
    private val digestLength =
        when (algorithmType) {
            kCCHmacAlgSHA1 -> CC_SHA1_DIGEST_LENGTH
            else -> CC_SHA256_DIGEST_LENGTH
        }

    actual fun getMacTimestampPair(uri: String): Pair<String, String> {
        val date = NSDate()
        val timestamp = ((date.timeIntervalSince1970) * 1000.0).toLong().toString()
        val data = "$timestamp$uri"
        return generateHmac(data) to timestamp
    }

    actual fun generateHmac(data: String): String {
        val keyBytes = secretKey.encodeToByteArray()
        val dataBytes = data.encodeToByteArray()
        return memScoped {
            val output = allocArray<UByteVar>(digestLength)
            keyBytes.usePinned { keyPinned ->
                dataBytes.usePinned { dataPinned ->
                    CCHmac(
                        algorithmType,
                        keyPinned.addressOf(0),
                        keyBytes.size.convert(),
                        dataPinned.addressOf(0),
                        dataBytes.size.convert(),
                        output,
                    )
                }
            }
            val bytes = ByteArray(digestLength) { index -> output[index].toInt().toByte() }
            Base64.encode(bytes)
        }
    }

    actual fun validateHmac(
        data: String,
        hmac: String,
    ): Boolean = generateHmac(data) == hmac

    actual fun isValidTimestamp(timestamp: String): Boolean {
        val requestTime = timestamp.toLongOrNull() ?: return false
        val date = NSDate()
        val currentTime = ((date.timeIntervalSince1970) * 1000.0).toLong()
        return (currentTime - requestTime) < tokenTtl
    }
}
