package com.arva.shining.automation

import com.arva.shining.LightCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/** What starts an automation. */
sealed interface Trigger {
    /** A clock time on the chosen days. */
    data class Time(val time: LocalTime) : Trigger

    /** Sunrise or sunset, shifted by [offsetMinutes] (negative = before). */
    data class Sun(val event: SunTimes.Event, val offsetMinutes: Int) : Trigger

    /**
     * The phone joins Wi-Fi and can reach the light. Fires only if the light is off.
     * [afterDark]: only between sunset and sunrise. [notAfter]: quiet from this time until morning.
     */
    data class Arrive(val afterDark: Boolean, val notAfter: LocalTime?) : Trigger
}

data class Automation(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val trigger: Trigger,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val command: LightCommand,
) {
    /** Next time this automation should fire after [now], or null for event-based triggers. */
    fun nextRun(now: ZonedDateTime, location: GeoPoint?): ZonedDateTime? {
        if (!enabled || days.isEmpty()) return null
        for (d in 0L..7L) {
            val date = now.toLocalDate().plusDays(d)
            if (date.dayOfWeek !in days) continue
            val at = when (trigger) {
                is Trigger.Time -> date.atTime(trigger.time).atZone(now.zone)
                is Trigger.Sun -> SunTimes.of(trigger.event, date, location ?: return null, now.zone)
                    ?.plusMinutes(trigger.offsetMinutes.toLong())
                is Trigger.Arrive -> return null
            } ?: continue
            if (at.isAfter(now)) return at
        }
        return null
    }

    /** Whether an arrival at [now] should run this automation. */
    fun arrivalAllowed(now: ZonedDateTime, location: GeoPoint?): Boolean {
        val t = trigger as? Trigger.Arrive ?: return false
        if (!enabled || now.dayOfWeek !in days) return false
        if (t.afterDark && !SunTimes.isDark(now, location)) return false
        val quietFrom = t.notAfter ?: return true
        // Quiet from `notAfter` until 06:00 (or until sunrise when the light is "after dark" only).
        val time = now.toLocalTime()
        val morning = LocalTime.of(6, 0)
        return if (quietFrom.isAfter(morning)) time.isBefore(quietFrom) && !time.isBefore(morning)
        else !(time >= quietFrom && time.isBefore(morning))
    }

    /** "07:00 · Weekdays", "Sunset, 15 min before · Every day", "Arriving home · after dark". */
    fun describeWhen(): String {
        val whenText = when (trigger) {
            is Trigger.Time -> "%02d:%02d".format(trigger.time.hour, trigger.time.minute)
            is Trigger.Sun -> {
                val name = if (trigger.event == SunTimes.Event.SUNSET) "Sunset" else "Sunrise"
                when {
                    trigger.offsetMinutes < 0 -> "$name, ${-trigger.offsetMinutes} min before"
                    trigger.offsetMinutes > 0 -> "$name, ${trigger.offsetMinutes} min after"
                    else -> name
                }
            }
            is Trigger.Arrive -> buildString {
                append("Arriving home")
                if (trigger.afterDark) append(", after dark")
                trigger.notAfter?.let { append(", not after %02d:%02d".format(it.hour, it.minute)) }
            }
        }
        return "$whenText · ${describeDays(days)}"
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("enabled", enabled)
        put("days", buildJsonArray { days.sorted().forEach { add(JsonPrimitive(it.value)) } })
        put("command", command.toQuery())
        put("trigger", when (trigger) {
            is Trigger.Time -> buildJsonObject {
                put("type", "time"); put("hour", trigger.time.hour); put("minute", trigger.time.minute)
            }
            is Trigger.Sun -> buildJsonObject {
                put("type", "sun"); put("event", trigger.event.name.lowercase()); put("offset", trigger.offsetMinutes)
            }
            is Trigger.Arrive -> buildJsonObject {
                put("type", "arrive"); put("afterDark", trigger.afterDark)
                trigger.notAfter?.let { put("notAfterHour", it.hour); put("notAfterMinute", it.minute) }
            }
        })
    }

    companion object {
        fun fromJson(o: JsonObject): Automation? = runCatching {
            val t = o["trigger"]!!.jsonObject
            fun int(key: String) = t[key]?.jsonPrimitive?.int
            val trigger = when (t["type"]?.jsonPrimitive?.content) {
                "time" -> Trigger.Time(LocalTime.of(int("hour")!!, int("minute")!!))
                "sun" -> Trigger.Sun(SunTimes.Event.valueOf(t["event"]!!.jsonPrimitive.content.uppercase()), int("offset") ?: 0)
                "arrive" -> Trigger.Arrive(
                    afterDark = t["afterDark"]?.jsonPrimitive?.boolean ?: false,
                    notAfter = int("notAfterHour")?.let { h -> LocalTime.of(h, int("notAfterMinute") ?: 0) },
                )
                else -> return null
            }
            Automation(
                id = o["id"]!!.jsonPrimitive.content,
                enabled = o["enabled"]?.jsonPrimitive?.boolean ?: true,
                trigger = trigger,
                days = o["days"]?.jsonArray?.map { DayOfWeek.of(it.jsonPrimitive.int) }?.toSet() ?: DayOfWeek.entries.toSet(),
                command = LightCommand.parse(o["command"]?.jsonPrimitive?.content),
            )
        }.getOrNull()

        fun listToJson(list: List<Automation>): String = JsonArray(list.map { it.toJson() }).toString()

        fun listFromJson(text: String?): List<Automation> = runCatching {
            Json.parseToJsonElement(text ?: "[]").jsonArray.mapNotNull { fromJson(it.jsonObject) }
        }.getOrDefault(emptyList())

        private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        fun describeDays(days: Set<DayOfWeek>): String = when (days) {
            DayOfWeek.entries.toSet() -> "Every day"
            WEEKDAYS -> "Weekdays"
            WEEKEND -> "Weekends"
            emptySet<DayOfWeek>() -> "Never"
            else -> days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        }
    }
}
