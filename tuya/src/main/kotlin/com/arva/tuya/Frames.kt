package com.arva.tuya

import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer

/** Tuya LAN command codes (subset we use). */
object TuyaCommand {
    const val SESS_KEY_NEG_START = 0x03
    const val SESS_KEY_NEG_RESP = 0x04
    const val SESS_KEY_NEG_FINISH = 0x05
    const val CONTROL = 0x07
    const val STATUS = 0x08
    const val HEART_BEAT = 0x09
    const val DP_QUERY = 0x0a
    const val CONTROL_NEW = 0x0d
    const val UDP_NEW = 0x13
    const val DP_QUERY_NEW = 0x10
    const val UPDATEDPS = 0x12

    /** Commands whose payload does NOT get the "3.x" + 12 zero bytes version header. */
    val NO_VERSION_HEADER = setOf(
        DP_QUERY, DP_QUERY_NEW, UPDATEDPS, HEART_BEAT,
        SESS_KEY_NEG_START, SESS_KEY_NEG_RESP, SESS_KEY_NEG_FINISH,
    )
}

/**
 * A decoded frame. For 55AA frames [payload] is still encrypted (version-specific);
 * for 6699 frames it is already AES-GCM decrypted.
 */
class TuyaFrame(val seq: Int, val cmd: Int, val retcode: Int?, val payload: ByteArray)

/**
 * Packs/unpacks the two Tuya frame formats:
 *
 * 55AA (protocol 3.1–3.4):
 *   000055AA | seq | cmd | len | payload | crc32 (or HMAC-SHA256 for 3.4) | 0000AA55
 *   where len = payload + check + suffix.
 *
 * 6699 (protocol 3.5):
 *   00006699 | 0000 | seq | cmd | len | iv(12) | ciphertext | tag(16) | 00009966
 *   where len = iv + ciphertext + tag and AAD = the 14 header bytes after the prefix.
 */
object Frames {
    const val PREFIX_55AA = 0x000055AA
    const val SUFFIX_55AA = 0x0000AA55
    const val PREFIX_6699 = 0x00006699
    const val SUFFIX_6699 = 0x00009966
    private const val MAX_FRAME = 64 * 1024

    fun pack55AA(seq: Int, cmd: Int, payload: ByteArray, hmacKey: ByteArray?): ByteArray {
        val checkLen = if (hmacKey != null) 32 else 4
        val header = ByteBuffer.allocate(16)
            .putInt(PREFIX_55AA).putInt(seq).putInt(cmd).putInt(payload.size + checkLen + 4)
            .array()
        val body = header + payload
        val check = if (hmacKey != null) {
            TuyaCrypto.hmacSha256(hmacKey, body)
        } else {
            ByteBuffer.allocate(4).putInt(TuyaCrypto.crc32(body)).array()
        }
        return body + check + ByteBuffer.allocate(4).putInt(SUFFIX_55AA).array()
    }

    fun pack6699(
        seq: Int,
        cmd: Int,
        payload: ByteArray,
        key: ByteArray,
        iv: ByteArray = TuyaCrypto.randomBytes(12),
    ): ByteArray {
        val header = ByteBuffer.allocate(18)
            .putInt(PREFIX_6699).putShort(0).putInt(seq).putInt(cmd).putInt(12 + payload.size + 16)
            .array()
        val aad = header.copyOfRange(4, 18)
        val encrypted = TuyaCrypto.gcmEncrypt(key, iv, aad, payload)
        return header + iv + encrypted + ByteBuffer.allocate(4).putInt(SUFFIX_6699).array()
    }

    /**
     * Unpacks one complete frame. [key] is the HMAC key for 3.4 (null → CRC32 is checked)
     * or the AES-GCM key for 6699 frames.
     */
    fun unpack(data: ByteArray, key: ByteArray?, useHmac: Boolean): TuyaFrame {
        val buf = ByteBuffer.wrap(data)
        return when (val prefix = buf.getInt()) {
            PREFIX_55AA -> {
                val seq = buf.getInt()
                val cmd = buf.getInt()
                val len = buf.getInt()
                if (data.size < 16 + len) throw TuyaException("Truncated frame")
                val checkLen = if (useHmac) 32 else 4
                val payloadEnd = 16 + len - checkLen - 4
                if (payloadEnd < 16) throw TuyaException("Frame too short")
                val body = data.copyOfRange(0, payloadEnd)
                val check = data.copyOfRange(payloadEnd, payloadEnd + checkLen)
                if (useHmac) {
                    val expected = TuyaCrypto.hmacSha256(key ?: throw TuyaException("Missing HMAC key"), body)
                    if (!expected.contentEquals(check)) throw TuyaException("HMAC mismatch (wrong local key or protocol version?)")
                } else {
                    val expected = ByteBuffer.allocate(4).putInt(TuyaCrypto.crc32(body)).array()
                    if (!expected.contentEquals(check)) throw TuyaException("CRC mismatch")
                }
                val (retcode, payload) = splitRetcode(data.copyOfRange(16, payloadEnd))
                TuyaFrame(seq, cmd, retcode, payload)
            }
            PREFIX_6699 -> {
                buf.getShort()
                val seq = buf.getInt()
                val cmd = buf.getInt()
                val len = buf.getInt()
                if (len < 28 || data.size < 18 + len) throw TuyaException("Truncated frame")
                val aad = data.copyOfRange(4, 18)
                val iv = data.copyOfRange(18, 30)
                val enc = data.copyOfRange(30, 18 + len)
                val plain = TuyaCrypto.gcmDecrypt(key ?: throw TuyaException("Missing key"), iv, aad, enc)
                val (retcode, payload) = splitRetcode(plain)
                TuyaFrame(seq, cmd, retcode, payload)
            }
            else -> throw TuyaException("Unknown frame prefix 0x%08x".format(prefix))
        }
    }

    /**
     * Device → client frames usually start with a 4-byte return code. Like tinytuya,
     * detect it heuristically: real payloads never start with three zero bytes.
     */
    private fun splitRetcode(p: ByteArray): Pair<Int?, ByteArray> {
        if (p.size >= 4 && p[0].toInt() == 0 && p[1].toInt() == 0 && p[2].toInt() == 0) {
            return ByteBuffer.wrap(p, 0, 4).int to p.copyOfRange(4, p.size)
        }
        return null to p
    }

    /** Reads exactly one raw frame (55AA or 6699) from the stream, skipping junk before the prefix. */
    fun readRaw(input: DataInputStream): ByteArray {
        var word = input.readInt()
        while (word != PREFIX_55AA && word != PREFIX_6699) {
            word = (word shl 8) or input.readUnsignedByte()
        }
        return if (word == PREFIX_55AA) {
            val rest = ByteArray(12).also { input.readFully(it) }
            val len = ByteBuffer.wrap(rest, 8, 4).int
            if (len !in 8..MAX_FRAME) throw IOException("Bad frame length $len")
            val body = ByteArray(len).also { input.readFully(it) }
            ByteBuffer.allocate(4).putInt(word).array() + rest + body
        } else {
            val rest = ByteArray(14).also { input.readFully(it) }
            val len = ByteBuffer.wrap(rest, 10, 4).int
            if (len !in 28..MAX_FRAME) throw IOException("Bad frame length $len")
            val body = ByteArray(len + 4).also { input.readFully(it) }
            ByteBuffer.allocate(4).putInt(word).array() + rest + body
        }
    }
}
