package com.arva.shining

import com.arva.shining.light.OpenBekenProtocol
import com.arva.tuya.DeviceConfig
import com.arva.tuya.LightKind
import com.arva.tuya.LightMode
import com.arva.tuya.LightState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenBekenProtocolTest {
    private val white = LightState(on = true, mode = LightMode.WHITE, brightness = 0.5f, temperature = 0.3f, colour = null)

    @Test fun `off and toggle`() {
        assertEquals(listOf("led_enableAll 0"), OpenBekenProtocol.commandsFor(LightCommand(power = LightCommand.Power.OFF), null))
        assertEquals(listOf("led_enableAll 0"), OpenBekenProtocol.commandsFor(LightCommand(power = LightCommand.Power.TOGGLE), white))
        assertEquals(listOf("led_enableAll 1"), OpenBekenProtocol.commandsFor(LightCommand(power = LightCommand.Power.TOGGLE), white.copy(on = false)))
        assertEquals(listOf("led_enableAll 1"), OpenBekenProtocol.commandsFor(LightCommand(power = LightCommand.Power.ON), null))
    }

    @Test fun `preset white and colour`() {
        assertEquals(
            listOf("led_enableAll 1", "CT 500", "Dimmer 40"),
            OpenBekenProtocol.commandsFor(LightCommand(preset = "Warm", brightness = 40), null),
        )
        assertEquals(
            listOf("led_enableAll 1", "HSBColor 230,100,100"),
            OpenBekenProtocol.commandsFor(LightCommand(preset = "Blue", brightness = 100), null),
        )
    }

    @Test fun `explicit colour and brightness-only`() {
        assertEquals(
            listOf("led_enableAll 1", "HSBColor 240,80,50"),
            OpenBekenProtocol.commandsFor(LightCommand(hue = 240, saturation = 80, brightness = 50), null),
        )
        // Brightness only keeps the current mode (just Dimmer).
        assertEquals(
            listOf("led_enableAll 1", "Dimmer 70"),
            OpenBekenProtocol.commandsFor(LightCommand(brightness = 70), white),
        )
    }

    @Test fun `keep off sets values then disables`() {
        val cmds = OpenBekenProtocol.commandsFor(LightCommand(preset = "Warm", brightness = 100, keepOff = true), null)
        assertEquals(listOf("CT 500", "Dimmer 100", "led_enableAll 0"), cmds)
        assertTrue("led_enableAll 1" !in cmds)
    }

    @Test fun `ct mapping round trips`() {
        assertEquals(500, OpenBekenProtocol.warmthToCt(0f))
        assertEquals(154, OpenBekenProtocol.warmthToCt(1f))
        assertEquals(0f, OpenBekenProtocol.ctToWarmth(500), 0.001f)
        assertEquals(1f, OpenBekenProtocol.ctToWarmth(154), 0.001f)
    }

    @Test fun `parse colour state`() {
        val s = OpenBekenProtocol.parseState("""{"POWER":"ON","Dimmer":60,"HSBColor":"240,100,60","Color":"0000FF"}""")!!
        assertTrue(s.on)
        assertEquals(LightMode.COLOUR, s.mode)
        val colour = s.colour!!
        assertEquals(240f, colour.h)
        assertEquals(1f, colour.s)
        assertEquals(0.6f, s.brightness, 0.01f)
        assertNull(s.temperature)
    }

    @Test fun `parse white state`() {
        val s = OpenBekenProtocol.parseState("""{"POWER":"OFF","Dimmer":40,"HSBColor":"30,0,40","CT":370}""")!!
        assertTrue(!s.on)
        assertEquals(LightMode.WHITE, s.mode)
        assertEquals(0.4f, s.brightness, 0.01f)
        assertEquals(0.376f, s.temperature!!, 0.01f)
        assertNull(s.colour) // white leaves colour null so the UI keeps its hue/saturation
    }

    @Test fun `parse real openbeken Status 11 nested under StatusSTS`() {
        // Exact shape returned by real OpenBeken 1.18.x to "Status 11".
        val json = """{"StatusSTS":{"Time":"0000-00-00T00:00:00","Uptime":"0T00:12:20","Dimmer":100,"Fade":"OFF","LedTable":"ON","Color":"91,36,1,0,0","HSBColor":"35,82,62","Channel":[35,14,0],"CT":155,"POWER":"ON","Wifi":{"RSSI":84}}}"""
        val s = OpenBekenProtocol.parseState(json)!!
        assertTrue(s.on)
        assertEquals(LightMode.COLOUR, s.mode)
        assertEquals(35f, s.colour!!.h, 0.5f)
        assertEquals(0.82f, s.colour!!.s, 0.01f)
        // In colour mode brightness comes from HSBColor's value (62), NOT the master Dimmer (100).
        assertEquals(0.62f, s.brightness, 0.01f)
    }

    @Test fun `colour brightness ignores a master Dimmer stuck at 100`() {
        // Real OpenBeken: set HSBColor to 50% but master Dimmer stays 100 — UI must read 50, not 100.
        val s = OpenBekenProtocol.parseState("""{"POWER":"ON","Dimmer":100,"HSBColor":"0,100,50"}""")!!
        assertEquals(LightMode.COLOUR, s.mode)
        assertEquals(0.5f, s.brightness, 0.01f)
    }

    @Test fun `white detected from Color channels despite residual HSBColor saturation`() {
        // A warm automation: CT set and the white channels lit, but HSBColor still reports the old hue/sat.
        val json = """{"POWER":"ON","Dimmer":50,"HSBColor":"35,82,62","CT":450,"Color":"0,0,0,200,120"}"""
        val s = OpenBekenProtocol.parseState(json)!!
        assertEquals(LightMode.WHITE, s.mode)
        assertNull(s.colour) // must not look like orange any more
        assertEquals(0.5f, s.brightness, 0.01f) // white brightness = Dimmer
    }

    @Test fun `colour detected from Color channels`() {
        val json = """{"POWER":"ON","Dimmer":100,"HSBColor":"35,82,62","CT":450,"Color":"200,80,10,0,0"}"""
        val s = OpenBekenProtocol.parseState(json)!!
        assertEquals(LightMode.COLOUR, s.mode)
        assertEquals(0.62f, s.brightness, 0.01f)
    }

    @Test fun `parse rejects non-openbeken json`() {
        assertNull(OpenBekenProtocol.parseState("""{"dps":{"20":true}}"""))
        assertNull(OpenBekenProtocol.parseState("not json"))
    }

    @Test fun `openbeken setup link round trips`() {
        val cfg = DeviceConfig("Desk", id = "", ip = "192.168.1.50", localKey = "", version = com.arva.tuya.TuyaVersion.V33, kind = LightKind.OPENBEKEN)
        val back = DeviceConfig.fromSetupLink(cfg.toSetupLink())!!
        assertEquals(LightKind.OPENBEKEN, back.kind)
        assertEquals("192.168.1.50", back.ip)
        assertTrue(back.isComplete)
    }
}
