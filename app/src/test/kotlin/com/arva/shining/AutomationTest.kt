package com.arva.shining

import com.arva.shining.automation.Automation
import com.arva.shining.automation.GeoPoint
import com.arva.shining.automation.SunTimes
import com.arva.shining.automation.Trigger
import com.arva.tuya.LightMode
import com.arva.tuya.LightSchema
import com.arva.tuya.LightState
import com.arva.tuya.Hsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class SunTimesTest {
    private val newYork = GeoPoint(40.71, -74.01)
    private val london = GeoPoint(51.51, -0.13)
    private val ny = ZoneId.of("America/New_York")

    private fun assertNear(expected: String, actual: ZonedDateTime?) {
        val exp = LocalTime.parse(expected)
        val diff = Duration.between(exp, actual!!.toLocalTime()).abs()
        assertTrue("expected ~$expected, got ${actual.toLocalTime()}", diff.toMinutes() <= 3)
    }

    // Reference times: timeanddate.com.
    @Test fun `new york midsummer`() {
        val d = LocalDate.of(2026, 6, 21)
        assertNear("05:25", SunTimes.of(SunTimes.Event.SUNRISE, d, newYork, ny))
        assertNear("20:31", SunTimes.of(SunTimes.Event.SUNSET, d, newYork, ny))
    }

    @Test fun `london midwinter`() {
        val d = LocalDate.of(2026, 12, 21)
        val zone = ZoneId.of("Europe/London")
        assertNear("08:04", SunTimes.of(SunTimes.Event.SUNRISE, d, london, zone))
        assertNear("15:53", SunTimes.of(SunTimes.Event.SUNSET, d, london, zone))
    }

    @Test fun `polar night has no sunrise`() {
        assertNull(SunTimes.of(SunTimes.Event.SUNRISE, LocalDate.of(2026, 12, 21), GeoPoint(69.65, 18.96), ZoneId.of("Europe/Oslo")))
    }

    @Test fun `dark between sunset and sunrise`() {
        assertTrue(SunTimes.isDark(ZonedDateTime.of(2026, 6, 21, 22, 0, 0, 0, ny), newYork))
        assertFalse(SunTimes.isDark(ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, ny), newYork))
        assertTrue(SunTimes.isDark(ZonedDateTime.of(2026, 6, 21, 4, 0, 0, 0, ny), newYork))
    }
}

class AutomationTest {
    private val ny = ZoneId.of("America/New_York")
    private val loc = GeoPoint(40.71, -74.01)
    // Wednesday 2026-11-11 10:00
    private val now = ZonedDateTime.of(2026, 11, 11, 10, 0, 0, 0, ny)

    @Test fun `time trigger picks the next matching day`() {
        val weekdaysAt7 = Automation(trigger = Trigger.Time(LocalTime.of(7, 0)), days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), command = LightCommand())
        assertEquals(ZonedDateTime.of(2026, 11, 13, 7, 0, 0, 0, ny), weekdaysAt7.nextRun(now, loc))
        val laterToday = weekdaysAt7.copy(trigger = Trigger.Time(LocalTime.of(23, 30)), days = DayOfWeek.entries.toSet())
        assertEquals(ZonedDateTime.of(2026, 11, 11, 23, 30, 0, 0, ny), laterToday.nextRun(now, loc))
    }

    @Test fun `sun trigger applies offset and needs a location`() {
        val a = Automation(trigger = Trigger.Sun(SunTimes.Event.SUNSET, -15), command = LightCommand())
        val next = a.nextRun(now, loc)!!
        val sunset = SunTimes.of(SunTimes.Event.SUNSET, now.toLocalDate(), loc, ny)!!
        assertEquals(sunset.minusMinutes(15), next)
        assertNull(a.nextRun(now, null))
    }

    @Test fun `disabled and arrival automations have no alarm`() {
        assertNull(Automation(enabled = false, trigger = Trigger.Time(LocalTime.NOON), command = LightCommand()).nextRun(now, loc))
        assertNull(Automation(trigger = Trigger.Arrive(true, null), command = LightCommand()).nextRun(now, loc))
    }

    @Test fun `arrival respects after-dark and quiet hours`() {
        val a = Automation(trigger = Trigger.Arrive(afterDark = true, notAfter = LocalTime.of(23, 30)), command = LightCommand())
        assertFalse(a.arrivalAllowed(now, loc)) // 10:00, daylight
        assertTrue(a.arrivalAllowed(now.withHour(19), loc))
        assertFalse(a.arrivalAllowed(now.withHour(23).withMinute(45), loc))
        assertFalse(a.arrivalAllowed(now.withHour(3), loc)) // quiet until morning
        val early = a.copy(trigger = Trigger.Arrive(afterDark = false, notAfter = LocalTime.of(1, 0)))
        assertTrue(early.arrivalAllowed(now.withHour(23), loc))
        assertFalse(early.arrivalAllowed(now.withHour(2), loc))
    }

    @Test fun `json round trip`() {
        val list = listOf(
            Automation(trigger = Trigger.Time(LocalTime.of(7, 5)), days = setOf(DayOfWeek.MONDAY), command = LightCommand.parse("preset=Warm&brightness=40")),
            Automation(enabled = false, trigger = Trigger.Sun(SunTimes.Event.SUNRISE, 30), command = LightCommand(power = LightCommand.Power.OFF)),
            Automation(trigger = Trigger.Arrive(true, LocalTime.of(23, 30)), command = LightCommand(power = LightCommand.Power.ON)),
        )
        assertEquals(list, Automation.listFromJson(Automation.listToJson(list)))
        assertEquals(emptyList<Automation>(), Automation.listFromJson("not json"))
    }

    @Test fun `descriptions`() {
        assertEquals("07:05 · Weekdays", Automation(trigger = Trigger.Time(LocalTime.of(7, 5)),
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            command = LightCommand()).describeWhen())
        assertEquals("Sunset, 15 min before · Every day", Automation(trigger = Trigger.Sun(SunTimes.Event.SUNSET, -15), command = LightCommand()).describeWhen())
    }
}

class LightCommandTest {
    private val white = LightState(on = true, mode = LightMode.WHITE, brightness = 0.5f, temperature = 0.3f, colour = Hsv(200f, 1f, 1f))
    private val colour = white.copy(mode = LightMode.COLOUR, colour = Hsv(120f, 1f, 0.5f))

    @Test fun `parse and serialize`() {
        val c = LightCommand.parse("?power=on&preset=Warm&brightness=40&junk=1")
        assertEquals(LightCommand(power = LightCommand.Power.ON, preset = "Warm", brightness = 40), c)
        assertEquals(c, LightCommand.parse(c.toQuery()))
        assertEquals(100, LightCommand.parse("brightness=250").brightness)
        assertTrue(LightCommand.parse("").isEmpty)
    }

    @Test fun `off and toggle`() {
        assertEquals(mapOf("20" to false), LightCommand.parse("power=off").toDps(LightSchema.V2, null))
        assertEquals(mapOf("20" to false), LightCommand.parse("power=toggle").toDps(LightSchema.V2, white))
        assertEquals(mapOf("20" to true), LightCommand.parse("power=toggle").toDps(LightSchema.V2, white.copy(on = false)))
    }

    @Test fun `preset sets mode and brightness`() {
        assertEquals(mapOf("20" to true, "21" to "white", "22" to 406, "23" to 0), LightCommand.parse("preset=warm&brightness=40").toDps(LightSchema.V2, null))
        val blue = LightCommand.parse("preset=Blue&brightness=100").toDps(LightSchema.V2, null)
        assertEquals("colour", blue["21"])
    }

    @Test fun `brightness only keeps the current mode`() {
        assertEquals("white", LightCommand.parse("brightness=80").toDps(LightSchema.V2, white)["21"])
        val c = LightCommand.parse("brightness=80").toDps(LightSchema.V2, colour)
        assertEquals("colour", c["21"])
        assertEquals("007803e80322", c["24"]) // hue 120, sat 1000, value 10 + 0.8 × 990 = 802
    }

    @Test fun `descriptions`() {
        assertEquals("Turn off", LightCommand.parse("power=off").describe())
        assertEquals("Warm at 40%", LightCommand.parse("preset=warm&brightness=40").describe())
        assertEquals("Turn on", LightCommand.parse("power=on").describe())
    }
}
