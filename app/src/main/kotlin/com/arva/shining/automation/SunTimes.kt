package com.arva.shining.automation

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** Approximate location used for sunrise/sunset (rounded; never leaves the phone). */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * Sunrise/sunset from the classic "Almanac for Computers" algorithm (official zenith 90.833°).
 * Accurate to about a minute for everyday latitudes; works offline.
 */
object SunTimes {
    enum class Event { SUNRISE, SUNSET }

    /** Local time of [event] on [date], or null when the sun doesn't rise/set that day (polar regions). */
    fun of(event: Event, date: LocalDate, at: GeoPoint, zone: ZoneId): ZonedDateTime? {
        for (shift in listOf(0L, -1L, 1L)) {
            val utc = utcEvent(event, date.plusDays(shift), at) ?: return null
            val local = utc.withZoneSameInstant(zone)
            if (local.toLocalDate() == date) return local
        }
        return null
    }

    private fun utcEvent(event: Event, date: LocalDate, at: GeoPoint): ZonedDateTime? {
        val rad = Math.PI / 180
        val n = date.dayOfYear
        val lngHour = at.longitude / 15
        val t = n + ((if (event == Event.SUNRISE) 6.0 else 18.0) - lngHour) / 24
        val m = 0.9856 * t - 3.289
        val l = norm360(m + 1.916 * sin(m * rad) + 0.020 * sin(2 * m * rad) + 282.634)
        var ra = norm360(atan(0.91764 * tan(l * rad)) / rad)
        ra += floor(l / 90) * 90 - floor(ra / 90) * 90
        ra /= 15
        val sinDec = 0.39782 * sin(l * rad)
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(90.833 * rad) - sinDec * sin(at.latitude * rad)) / (cosDec * cos(at.latitude * rad))
        if (cosH > 1 || cosH < -1) return null
        val h = (if (event == Event.SUNRISE) 360 - acos(cosH) / rad else acos(cosH) / rad) / 15
        val localMeanTime = h + ra - 0.06571 * t - 6.622
        val ut = ((localMeanTime - lngHour) % 24 + 24) % 24
        val seconds = (ut * 3600).toLong()
        return date.atStartOfDay(ZoneOffset.UTC).plusSeconds(seconds)
    }

    private fun norm360(x: Double) = (x % 360 + 360) % 360

    /** True between sunset and the next sunrise. Without a location, 18:00–06:00 counts as dark. */
    fun isDark(now: ZonedDateTime, at: GeoPoint?): Boolean {
        if (at == null) return now.hour >= 18 || now.hour < 6
        val today = now.toLocalDate()
        val rise = of(Event.SUNRISE, today, at, now.zone)
        val set = of(Event.SUNSET, today, at, now.zone)
        if (rise == null || set == null) return false
        return now.isBefore(rise) || !now.isBefore(set)
    }
}
