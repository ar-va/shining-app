package com.arva.shining.automation

import com.arva.shining.LightCommand
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Decides what to do with timed and sun runs that were missed while the light was out of reach.
 * Pure, so the rules are unit-tested: the light should end up the way it would have been had the
 * phone been home, without stepping through every look on the way there.
 */
object CatchUp {
    /** A run missed longer ago than this no longer applies (a 07:00 wake-up must not fire at 21:00). */
    const val WINDOW_MS = 8 * 60 * 60_000L

    data class Missed(val automation: Automation, val scheduled: Long)

    data class Plan(
        /** Run these, oldest first. */
        val run: List<Missed>,
        /** Recent, but a later automation sets the look anyway. */
        val replaced: List<Missed>,
        val expired: List<Missed>,
        /** Deleted, disabled or no longer catching up. */
        val gone: List<String>,
    )

    fun plan(pending: Map<String, Long>, automations: List<Automation>, now: Long): Plan {
        val byId = automations.associateBy { it.id }
        val gone = pending.keys.filter { id -> byId[id]?.let { it.enabled && it.catchUp } != true }
        val missed = pending.filterKeys { it !in gone }
            .map { (id, at) -> Missed(byId.getValue(id), at) }
            .sortedBy { it.scheduled }
        val (recent, expired) = missed.partition { now - it.scheduled <= WINDOW_MS }
        if (recent.isEmpty()) return Plan(emptyList(), emptyList(), expired, gone)
        // The newest "off" wins outright; otherwise start from the newest run that sets a look, so a
        // later plain "turn on" still applies on top of it.
        val start = if (recent.last().automation.command.power == LightCommand.Power.OFF) recent.lastIndex
        else recent.indexOfLast { it.automation.command.setsLook }.coerceAtLeast(0)
        return Plan(recent.drop(start), recent.take(start), expired, gone)
    }

    private val LightCommand.setsLook get() = preset != null || hue != null || warmth != null

    fun stamp(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE HH:mm"))
}
