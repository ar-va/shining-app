package com.arva.shining

import com.arva.shining.automation.Automation
import com.arva.shining.automation.CatchUp
import com.arva.shining.automation.GeoPoint
import com.arva.shining.automation.Presence
import com.arva.shining.automation.SunTimes
import com.arva.shining.automation.Trigger
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
import java.time.ZoneOffset
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

    @Test fun `quiet hours from 06 00 cover the whole day`() {
        val a = Automation(trigger = Trigger.Arrive(afterDark = false, notAfter = LocalTime.of(6, 0)), command = LightCommand())
        assertFalse(a.arrivalAllowed(now.withHour(3), loc))
        assertFalse(a.arrivalAllowed(now.withHour(12), loc))
    }

    @Test fun `a late alarm is left alone until it counts as lost`() {
        val due = 100 * hour
        val minute = 60_000L
        assertEquals(CatchUp.DueAlarm.NOT_DUE, CatchUp.dueAlarm(null, due, false))
        assertEquals(CatchUp.DueAlarm.NOT_DUE, CatchUp.dueAlarm(due, due - minute, false))
        assertEquals(CatchUp.DueAlarm.STILL_COMING, CatchUp.dueAlarm(due, due + 2 * minute, false))
        assertEquals(CatchUp.DueAlarm.LOST, CatchUp.dueAlarm(due, due + 15 * minute, false))
        assertEquals(CatchUp.DueAlarm.LOST, CatchUp.dueAlarm(due, due + 2 * minute, alarmsCleared = true))
    }

    // Sunday, as in the user's report: sunset orange at 100%, then orange at 50% at 22:30.
    private val hour = 3_600_000L
    private val sunset = Automation(id = "sunset", trigger = Trigger.Sun(SunTimes.Event.SUNSET, 0), command = LightCommand.parse("preset=Orange&brightness=100"))
    private val late = Automation(id = "late", trigger = Trigger.Time(LocalTime.of(22, 30)), command = LightCommand.parse("preset=Orange&brightness=50"))

    @Test fun `catch up home before the second run applies the first`() {
        val plan = CatchUp.plan(mapOf("sunset" to 17 * hour), listOf(sunset, late), 22 * hour, ZoneOffset.UTC)
        assertEquals(listOf("sunset"), plan.run.map { it.automation.id })
    }

    @Test fun `catch up applies only the newest look whatever the queue order`() {
        val plan = CatchUp.plan(mapOf("late" to 22 * hour + hour / 2, "sunset" to 17 * hour), listOf(sunset, late), 23 * hour, ZoneOffset.UTC)
        assertEquals(listOf("late"), plan.run.map { it.automation.id })
        assertEquals(listOf("sunset"), plan.replaced.map { it.automation.id })
    }

    @Test fun `catch up keeps a plain turn on after the look it follows`() {
        val on = Automation(id = "on", trigger = Trigger.Time(LocalTime.of(20, 0)), command = LightCommand(power = LightCommand.Power.ON))
        val plan = CatchUp.plan(mapOf("sunset" to 17 * hour, "on" to 20 * hour), listOf(sunset, on), 21 * hour, ZoneOffset.UTC)
        assertEquals(listOf("sunset", "on"), plan.run.map { it.automation.id })
    }

    @Test fun `catch up lets a later off win`() {
        val off = Automation(id = "off", trigger = Trigger.Time(LocalTime.of(21, 0)), command = LightCommand(power = LightCommand.Power.OFF))
        val plan = CatchUp.plan(mapOf("sunset" to 17 * hour, "off" to 21 * hour), listOf(sunset, off), 22 * hour, ZoneOffset.UTC)
        assertEquals(listOf("off"), plan.run.map { it.automation.id })
    }

    @Test fun `catch up drops old, disabled and deleted runs`() {
        val plan = CatchUp.plan(
            mapOf("sunset" to 7 * hour, "late" to 20 * hour, "deleted" to 20 * hour),
            listOf(sunset, late.copy(enabled = false)),
            16 * hour,
            ZoneOffset.UTC,
        )
        assertEquals(emptyList<CatchUp.Missed>(), plan.run)
        assertEquals(listOf("sunset"), plan.expired.map { it.automation.id })
        assertEquals(setOf("late", "deleted"), plan.gone.toSet())
    }

    @Test fun `a bedtime run stops at 03 00 the next morning`() {
        val missed = mapOf("late" to 22 * hour + hour / 2)
        assertEquals(listOf("late"), CatchUp.plan(missed, listOf(late), 26 * hour, ZoneOffset.UTC).run.map { it.automation.id })
        val dawn = CatchUp.plan(missed, listOf(late), 30 * hour, ZoneOffset.UTC)
        assertEquals(emptyList<CatchUp.Missed>(), dawn.run)
        assertEquals(listOf("late"), dawn.expired.map { it.automation.id })
    }

    @Test fun `only a real trip counts as arriving`() {
        val minute = 60_000L
        val now = 100 * hour
        assertTrue(Presence.isArrival(Presence.State.AWAY, "home-1", "home-2", now - 20 * minute, now))
        assertFalse(Presence.isArrival(Presence.State.AWAY, "home-1", "home-2", now - 9_000, now))
        assertFalse(Presence.isArrival(Presence.State.AWAY, null, "home-2", now - 9_000, now)) // after a restart
        assertFalse(Presence.isArrival(Presence.State.AWAY, "home-1", "home-1", now - 60 * minute, now))
        assertFalse(Presence.isArrival(Presence.State.HOME, "home-1", "home-2", now - 60 * minute, now))
    }

    @Test fun `a preset an automation needs cannot go away`() {
        val mine = Preset("Sunset pink", hue = 340f)
        val uses = Automation(id = "a", trigger = Trigger.Time(LocalTime.NOON), command = LightCommand(preset = "sunset PINK"))
        assertEquals(listOf("a"), automationsUsing(listOf(mine), listOf(uses)).map { it.id })
        val builtIn = uses.copy(command = LightCommand(preset = "Orange"))
        assertEquals(emptyList<Automation>(), automationsUsing(listOf(Preset("Orange", hue = 25f)), listOf(builtIn)))
        assertEquals("Orange 2", freePresetName("Orange"))
        assertEquals("Mauve", freePresetName("Mauve"))
    }

    @Test fun `presets survive being saved`() {
        val list = listOf(Preset("Sunset pink", hue = 340f, saturation = 0.7f), Preset("Soft white", temperature = 0.4f))
        assertEquals(list, presetsFromJson(presetsToJson(list)))
    }

    @Test fun `json round trip`() {
        val list = listOf(
            Automation(trigger = Trigger.Time(LocalTime.of(7, 5)), days = setOf(DayOfWeek.MONDAY), command = LightCommand.parse("preset=Warm&brightness=40")),
            Automation(enabled = false, trigger = Trigger.Sun(SunTimes.Event.SUNRISE, 30), command = LightCommand(power = LightCommand.Power.OFF)),
            Automation(trigger = Trigger.Arrive(true, LocalTime.of(23, 30)), command = LightCommand(power = LightCommand.Power.ON)),
            Automation(trigger = Trigger.Time(LocalTime.of(22, 0)), command = LightCommand.parse("preset=Warm"), catchUp = false),
        )
        assertEquals(list, Automation.listFromJson(Automation.listToJson(list)))
        assertEquals(emptyList<Automation>(), Automation.listFromJson("not json"))
    }

    @Test fun `descriptions`() {
        assertEquals("07:05, weekdays", Automation(trigger = Trigger.Time(LocalTime.of(7, 5)),
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            command = LightCommand()).describeWhen())
        assertEquals("Sunset, 15 min before, every day", Automation(trigger = Trigger.Sun(SunTimes.Event.SUNSET, -15), command = LightCommand()).describeWhen())
    }
}

class LightCommandTest {
    @Test fun `parse and serialize`() {
        val c = LightCommand.parse("?power=on&preset=Warm&brightness=40&junk=1")
        assertEquals(LightCommand(power = LightCommand.Power.ON, preset = "Warm", brightness = 40), c)
        assertEquals(c, LightCommand.parse(c.toQuery()))
        assertEquals(100, LightCommand.parse("brightness=250").brightness)
        assertTrue(LightCommand.parse("").isEmpty)
    }

    @Test fun `malformed input is ignored, not a crash`() {
        assertEquals(LightCommand(hue = 10), LightCommand.parse("preset=%ZZ&brightness=NaN&saturation=Infinity&hue=10").copy(saturation = null))
        assertTrue(LightCommand.parse("preset=%ZZ").isEmpty)
    }

    @Test fun `keep off survives being saved`() {
        val c = LightCommand.parse("preset=Warm&brightness=100&keepoff=1")
        assertTrue(c.keepOff)
        assertEquals(c, LightCommand.parse(c.toQuery()))
    }

    @Test fun `descriptions`() {
        assertEquals("Turn off", LightCommand.parse("power=off").describe())
        assertEquals("Warm at 40%", LightCommand.parse("preset=warm&brightness=40").describe())
        assertEquals("Turn on", LightCommand.parse("power=on").describe())
    }
}
