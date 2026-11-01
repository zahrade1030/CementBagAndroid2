package com.vea.cementbagcounter

import kotlin.math.abs
import kotlin.math.sqrt

data class TrackedPoint(
    val id: Int,
    val x: Float,
    val y: Float,
    val counted: Boolean
)

class SimpleLineCounter {

    private data class Track(
        val id: Int,
        var x: Float,
        var y: Float,
        var previousY: Float,
        var armed: Boolean = false,
        var counted: Boolean = false,
        var missed: Int = 0
    )

    private val tracks = mutableMapOf<Int, Track>()

    private var nextId = 1

    private var topLine = 150f
    private var bottomLine = 411f

    private val maxMissedFrames = 12
    private val maxDistance = 140f

    fun setLines(top: Float, bottom: Float) {
        topLine = minOf(top, bottom)
        bottomLine = maxOf(top, bottom)
    }

    fun reset() {
        tracks.clear()
        nextId = 1
    }

    fun getTrackedPoints(): List<TrackedPoint> {
        return tracks.values
            .filter { it.missed <= maxMissedFrames }
            .map {
                TrackedPoint(
                    id = it.id,
                    x = it.x,
                    y = it.y,
                    counted = it.counted
                )
            }
    }

    fun update(detections: List<Detection>): Int {

        tracks.values.forEach { it.missed++ }

        var newCounts = 0

        val availableTracks = tracks.values
            .filter { it.missed > 0 && it.missed <= maxMissedFrames }
            .toMutableSet()

        val matchedTracks = mutableSetOf<Int>()

        for (d in detections) {

            val candidate = availableTracks
                .filter { it.id !in matchedTracks }
                .map { track ->
                    val dx = track.x - d.cx
                    val dy = track.y - d.cy
                    track to sqrt(dx * dx + dy * dy)
                }
                .filter { it.second < maxDistance }
                .minByOrNull { it.second }
                ?.first

            val track = if (candidate != null) {
                candidate.also {
                    matchedTracks.add(it.id)
                }
            } else {
                Track(
                    id = nextId++,
                    x = d.cx,
                    y = d.cy,
                    previousY = d.cy
                ).also {
                    tracks[it.id] = it
                }
            }

            val oldY = track.y

            track.previousY = oldY

            // Smooth the center to reduce small detection jumps.
            track.x = track.x * 0.35f + d.cx * 0.65f
            track.y = track.y * 0.35f + d.cy * 0.65f

            track.missed = 0

            // Arm the track after it enters the area between the lines.
            if (
                track.y > topLine &&
                track.y < bottomLine
            ) {
                track.armed = true
            }

            // Count only when crossing the second line downward.
            if (
                track.armed &&
                !track.counted &&
                oldY < bottomLine &&
                track.y >= bottomLine
            ) {
                track.counted = true
                newCounts++
            }
        }

        tracks.entries.removeAll {
            it.value.missed > maxMissedFrames
        }

        return newCounts
    }
}
