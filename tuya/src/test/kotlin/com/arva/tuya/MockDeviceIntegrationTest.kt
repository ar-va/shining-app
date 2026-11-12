package com.arva.tuya

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Talks to tools/mock_device.py (tinytuya-based fake light). Skipped unless TUYA_MOCK_PORT is set:
 *
 *   python3 tools/mock_device.py --version 3.4 --port 16668 &
 *   TUYA_MOCK_PORT=16668 TUYA_MOCK_VERSION=3.4 ./gradlew :tuya:test
 */
class MockDeviceIntegrationTest {
    private val port = System.getenv("TUYA_MOCK_PORT")?.toIntOrNull()
    private val version = TuyaVersion.parse(System.getenv("TUYA_MOCK_VERSION")) ?: TuyaVersion.V33

    @Test fun `status and control against mock device`() {
        assumeTrue("TUYA_MOCK_PORT not set", port != null)
        TuyaDevice("bf0123456789abcdefgh", "127.0.0.1", "0123456789abcdef", version, port!!).use { dev ->
            val before = dev.status()
            println("[$version] status: $before")
            val schema = LightDps.detect(before)
            assertEquals(LightSchema.V2, schema)

            dev.setDps(LightDps.colour(schema!!, Hsv(240f, 1f, 0.5f)))
            dev.setDps(LightDps.power(schema, false))
            dev.setDps(LightDps.power(schema, true))

            val after = LightDps.parse(schema, dev.status())
            println("[$version] parsed: $after")
            assertEquals(true, after.on)
            assertEquals(LightMode.COLOUR, after.mode)
            assertEquals(240f, after.colour!!.h)
            assertEquals(0.5f, after.brightness, 0.01f)
        }
        // A fresh connection must work too (new session key for 3.4/3.5).
        TuyaDevice("bf0123456789abcdefgh", "127.0.0.1", "0123456789abcdef", version, port).use { dev ->
            assertEquals(true, dev.status()["20"])
        }
    }

    @Test fun `wrong local key fails cleanly`() {
        assumeTrue("TUYA_MOCK_PORT not set", port != null)
        val result = runCatching {
            TuyaDevice("bf0123456789abcdefgh", "127.0.0.1", "WRONGKEYWRONGKEY", version, port!!, timeoutMs = 1500).use { it.status() }
        }
        println("[$version] wrong key -> ${result.exceptionOrNull()}")
        assertEquals(true, result.isFailure)
    }
}
