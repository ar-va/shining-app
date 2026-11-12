package com.arva.tuya

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory

open class TuyaException(message: String, cause: Throwable? = null) : IOException(message, cause)

enum class TuyaVersion(val text: String) {
    V33("3.3"), V34("3.4"), V35("3.5");

    internal val header: ByteArray get() = text.toByteArray() + ByteArray(12)

    companion object {
        fun parse(text: String?): TuyaVersion? = entries.firstOrNull { it.text == text?.trim() }
    }
}

/**
 * Blocking client for one Tuya device on the LAN (TCP 6668). Not thread-safe; callers serialize access.
 *
 * Data points ("DPs") are returned as String id → Boolean / Long / Double / String / null.
 */
class TuyaDevice(
    val deviceId: String,
    val host: String,
    localKey: String,
    val version: TuyaVersion,
    val port: Int = 6668,
    private val timeoutMs: Int = 5000,
    /** Creates the TCP socket; lets Android pin the connection to a specific network (e.g. Wi-Fi). */
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
) : Closeable {
    private val localKey: ByteArray = localKey.toByteArray(Charsets.UTF_8)
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var seq = 1
    private var sessionKey: ByteArray? = null

    /** Some 3.3 devices ("device22") need a different status query. Auto-detected. */
    var device22: Boolean = false
        private set

    /** DPs pushed by the device between our requests (merged). */
    private val pushed = mutableMapOf<String, Any?>()

    init {
        require(this.localKey.size == 16) { "Local key must be exactly 16 characters (got ${this.localKey.size})" }
    }

    val isConnected: Boolean get() = socket?.let { it.isConnected && !it.isClosed } == true

    private val frameKey: ByteArray get() = sessionKey ?: localKey

    fun connect() {
        close()
        val s = socketFactory.createSocket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            socket = s
            input = DataInputStream(BufferedInputStream(s.getInputStream()))
            output = s.getOutputStream()
            seq = 1
            if (version != TuyaVersion.V33) negotiateSession()
        } catch (e: Exception) {
            close()
            throw friendly(e)
        }
    }

    private fun friendly(e: Exception): TuyaException = when (e) {
        is TuyaException -> e
        is EOFException -> TuyaException("The light closed the connection — check the local key and protocol version", e)
        is SocketTimeoutException -> TuyaException("The light did not answer — is it on and at $host?", e)
        else -> TuyaException("Cannot reach the light at $host (${e.message ?: e.javaClass.simpleName})", e)
    }

    /** Runs [block]; on any I/O error drops the connection so the next call reconnects. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        close()
        throw friendly(e)
    }

    override fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        input = null
        output = null
        sessionKey = null
    }

    /** Returns all current DPs. */
    fun status(): Map<String, Any?> = guarded {
        ensureConnected()
        drainPushed()
        if (device22) return@guarded statusDevice22()
        val (cmd, json) = when (version) {
            TuyaVersion.V33 -> TuyaCommand.DP_QUERY to buildJsonObject {
                put("gwId", deviceId); put("devId", deviceId); put("uid", deviceId); put("t", now().toString())
            }.toString()
            else -> TuyaCommand.DP_QUERY_NEW to "{}"
        }
        send(cmd, json.toByteArray())
        try {
            awaitDps()
        } catch (e: DataUnvalidException) {
            device22 = true
            statusDevice22()
        }
    }

    /** Sets DPs. Returns once the device acknowledges (or after a short timeout). */
    fun setDps(dps: Map<String, Any>): Unit = guarded {
        ensureConnected()
        drainPushed()
        val dpsJson = JsonObject(dps.mapValues { toJson(it.value) })
        val (cmd, json) = when (version) {
            TuyaVersion.V33 -> TuyaCommand.CONTROL to buildJsonObject {
                put("devId", deviceId); put("uid", deviceId); put("t", now().toString()); put("dps", dpsJson)
            }
            else -> TuyaCommand.CONTROL_NEW to buildJsonObject {
                put("protocol", 5); put("t", now()); put("data", buildJsonObject { put("dps", dpsJson) })
            }
        }
        send(cmd, json.toString().toByteArray())
        // Wait for the ack (or a STATUS push). Some devices don't ack; don't fail in that case.
        val s = socket ?: return@guarded
        s.soTimeout = 1500
        try {
            val frame = readFrame()
            decodeDps(frame)?.let { pushed.putAll(it) }
        } catch (_: SocketTimeoutException) {
            close() // stream may be mid-frame; reconnect next time
        } finally {
            socket?.soTimeout = timeoutMs
        }
    }

    // ---- internals ----

    private class DataUnvalidException : TuyaException("Device rejected the request (data unvalid)")

    private fun ensureConnected() {
        if (!isConnected) connect()
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

    private fun statusDevice22(): Map<String, Any?> {
        val wanted = buildJsonObject {
            (LightDps.V1_DPS + LightDps.V2_DPS).forEach { put(it, JsonNull) }
        }
        val json = buildJsonObject {
            put("devId", deviceId); put("uid", deviceId); put("t", now().toString()); put("dps", wanted)
        }
        send(TuyaCommand.CONTROL_NEW, json.toString().toByteArray())
        return awaitDps()
    }

    /** Reads frames until one carries DPs. */
    private fun awaitDps(): Map<String, Any?> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val frame = readFrame()
            if (frame.cmd == TuyaCommand.HEART_BEAT) continue
            val dps = decodeDps(frame) ?: continue
            return pushed.apply { putAll(dps) }.toMap().also { pushed.clear() }
        }
        throw TuyaException("Device did not report its status")
    }

    private fun drainPushed() {
        val inp = input ?: return
        try {
            while (inp.available() >= 16) {
                decodeDps(readFrame())?.let { pushed.putAll(it) }
            }
        } catch (e: IOException) {
            close()
            connect()
        }
    }

    private fun send(cmd: Int, plain: ByteArray) {
        val out = output ?: throw TuyaException("Not connected")
        val payload = encodePayload(cmd, plain)
        val frame = when (version) {
            TuyaVersion.V33 -> Frames.pack55AA(seq++, cmd, payload, hmacKey = null)
            TuyaVersion.V34 -> Frames.pack55AA(seq++, cmd, payload, hmacKey = frameKey)
            TuyaVersion.V35 -> Frames.pack6699(seq++, cmd, payload, key = frameKey)
        }
        out.write(frame)
        out.flush()
    }

    private fun encodePayload(cmd: Int, plain: ByteArray): ByteArray {
        val needsHeader = cmd !in TuyaCommand.NO_VERSION_HEADER
        return when (version) {
            // 3.3: encrypt, then prepend the cleartext version header
            TuyaVersion.V33 -> TuyaCrypto.ecbEncrypt(localKey, plain).let { if (needsHeader) version.header + it else it }
            // 3.4: prepend header, then encrypt with the session key
            TuyaVersion.V34 -> TuyaCrypto.ecbEncrypt(frameKey, if (needsHeader) version.header + plain else plain)
            // 3.5: prepend header; the frame itself is AES-GCM encrypted
            TuyaVersion.V35 -> if (needsHeader) version.header + plain else plain
        }
    }

    private fun readFrame(): TuyaFrame {
        val inp = input ?: throw TuyaException("Not connected")
        val raw = Frames.readRaw(inp)
        return when (version) {
            TuyaVersion.V33 -> Frames.unpack(raw, null, useHmac = false)
            TuyaVersion.V34 -> Frames.unpack(raw, frameKey, useHmac = true)
            TuyaVersion.V35 -> Frames.unpack(raw, frameKey, useHmac = false)
        }
    }

    /** Decrypts a frame payload to plaintext bytes (empty for acks). */
    private fun decodePayload(frame: TuyaFrame, unpad: Boolean = true): ByteArray {
        var p = frame.payload
        when (version) {
            TuyaVersion.V33 -> {
                if (p.startsWith(version.text.toByteArray())) p = p.copyOfRange(15, p.size)
                // device22 replies may carry a different 15-byte header
                else if (p.size % 16 == 15) p = p.copyOfRange(15, p.size)
                if (p.isEmpty()) return p
                if (p.size % 16 != 0) return p // plaintext error message
                p = TuyaCrypto.ecbDecrypt(localKey, p, unpad)
            }
            TuyaVersion.V34 -> {
                if (p.isEmpty()) return p
                p = TuyaCrypto.ecbDecrypt(frameKey, p, unpad)
                if (p.startsWith(version.text.toByteArray())) p = p.copyOfRange(15, p.size)
            }
            TuyaVersion.V35 -> {
                if (p.startsWith(version.text.toByteArray())) p = p.copyOfRange(15, p.size)
            }
        }
        return p
    }

    /** Returns the DPs carried by a frame, or null if it carries none (ack, heartbeat). */
    private fun decodeDps(frame: TuyaFrame): Map<String, Any?>? {
        val text = decodePayload(frame).toString(Charsets.UTF_8).trim()
        if (text.isEmpty()) return null
        if (!text.startsWith("{")) {
            if (text.contains("unvalid", ignoreCase = true)) throw DataUnvalidException()
            throw TuyaException("Unexpected reply from device (wrong local key?)")
        }
        val obj = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            throw TuyaException("Could not parse reply (wrong local key?)", e)
        } ?: return null
        val dps = (obj["dps"] as? JsonObject)
            ?: ((obj["data"] as? JsonObject)?.get("dps") as? JsonObject)
            ?: return null
        return dps.mapValues { fromJson(it.value) }
    }

    private fun negotiateSession() {
        sessionKey = null
        val localNonce = TuyaCrypto.randomBytes(8).toHex().toByteArray() // 16 ASCII bytes
        send(TuyaCommand.SESS_KEY_NEG_START, localNonce)
        var frame = readFrame()
        while (frame.cmd != TuyaCommand.SESS_KEY_NEG_RESP) frame = readFrame()
        val body = decodePayload(frame, unpad = false)
        if (body.size < 48) throw TuyaException("Session negotiation failed (wrong local key or version?)")
        val remoteNonce = body.copyOfRange(0, 16)
        val remoteHmac = body.copyOfRange(16, 48)
        if (!TuyaCrypto.hmacSha256(localKey, localNonce).contentEquals(remoteHmac)) {
            throw TuyaException("Session negotiation failed: wrong local key")
        }
        send(TuyaCommand.SESS_KEY_NEG_FINISH, TuyaCrypto.hmacSha256(localKey, remoteNonce))
        val mixed = ByteArray(16) { (localNonce[it].toInt() xor remoteNonce[it].toInt()).toByte() }
        sessionKey = when (version) {
            TuyaVersion.V34 -> TuyaCrypto.ecbEncrypt(localKey, mixed, pad = false)
            else -> TuyaCrypto.gcmEncrypt(localKey, localNonce.copyOfRange(0, 12), null, mixed).copyOfRange(0, 16)
        }
    }

    companion object {
        internal fun toJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }

        internal fun fromJson(e: JsonElement): Any? = when {
            e is JsonNull -> null
            e is JsonPrimitive && e.isString -> e.content
            e is JsonPrimitive -> e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull ?: e.content
            else -> e.toString()
        }
    }
}
