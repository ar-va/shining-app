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
