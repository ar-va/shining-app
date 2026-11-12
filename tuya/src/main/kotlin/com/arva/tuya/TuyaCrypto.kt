package com.arva.tuya

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES / HMAC / CRC primitives used by the Tuya LAN protocol. Uses only JDK/Android built-ins. */
internal object TuyaCrypto {
    private val random = SecureRandom()

    fun ecbEncrypt(key: ByteArray, data: ByteArray, pad: Boolean = true): ByteArray {
        val input = if (pad) pkcs7Pad(data) else data
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(input)
    }

    fun ecbDecrypt(key: ByteArray, data: ByteArray, unpad: Boolean = true): ByteArray {
        if (data.size % 16 != 0) throw TuyaException("Encrypted payload has invalid length ${data.size}")
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val out = cipher.doFinal(data)
        return if (unpad) pkcs7Unpad(out) else out
    }

    /** Returns ciphertext followed by the 16-byte tag. */
    fun gcmEncrypt(key: ByteArray, iv: ByteArray, aad: ByteArray?, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    /** [cipherAndTag] is ciphertext followed by the 16-byte tag. Throws on authentication failure. */
    fun gcmDecrypt(key: ByteArray, iv: ByteArray, aad: ByteArray?, cipherAndTag: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) cipher.updateAAD(aad)
        return try {
            cipher.doFinal(cipherAndTag)
        } catch (e: Exception) {
            throw TuyaException("Could not decrypt message (wrong local key?)", e)
        }
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun crc32(data: ByteArray): Int = CRC32().apply { update(data) }.value.toInt()

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun pkcs7Pad(data: ByteArray): ByteArray {
        val padLen = 16 - (data.size % 16)
        return data + ByteArray(padLen) { padLen.toByte() }
    }

    /** Lenient unpad (like tinytuya): strips padding only when it looks valid. */
    fun pkcs7Unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val padLen = data.last().toInt() and 0xff
        if (padLen !in 1..16 || padLen > data.size) return data
        for (i in data.size - padLen until data.size) {
            if ((data[i].toInt() and 0xff) != padLen) return data
        }
        return data.copyOfRange(0, data.size - padLen)
    }
}

internal fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
