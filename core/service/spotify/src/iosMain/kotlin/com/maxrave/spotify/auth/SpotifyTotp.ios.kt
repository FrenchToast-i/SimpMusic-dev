package com.maxrave.spotify.auth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCHmacAlgSHA1

private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

@OptIn(ExperimentalForeignApi::class)
actual fun generateTotp(secret: String, timestamp: Long): String {
    val key = base32Decode(secret)
    val counter = timestamp / 1000 / 30
    val counterBytes =
        ByteArray(8) { index ->
            ((counter shr (56 - index * 8)) and 0xFF).toByte()
        }
    val hash =
        memScoped {
            val output = allocArray<UByteVar>(20)
            key.usePinned { keyPinned ->
                counterBytes.usePinned { counterPinned ->
                    CCHmac(
                        kCCHmacAlgSHA1,
                        keyPinned.addressOf(0),
                        key.size.convert(),
                        counterPinned.addressOf(0),
                        counterBytes.size.convert(),
                        output,
                    )
                }
            }
            ByteArray(20) { index -> output[index].toInt().toByte() }
        }
    val offset = hash.last().toInt() and 0x0F
    val binary =
        ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
    return (binary % 1_000_000).toString().padStart(6, '0')
}

private fun base32Decode(input: String): ByteArray {
    val normalized = input.uppercase().trimEnd('=')
    val output = mutableListOf<Byte>()
    var buffer = 0
    var bitsLeft = 0
    for (char in normalized) {
        val value = BASE32_ALPHABET.indexOf(char)
        if (value < 0) continue
        buffer = (buffer shl 5) or value
        bitsLeft += 5
        if (bitsLeft >= 8) {
            bitsLeft -= 8
            output.add(((buffer shr bitsLeft) and 0xFF).toByte())
        }
    }
    return output.toByteArray()
}
