
package com.vea.cementbagcounter

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
        var velocityX: Float = 0f,
        var velocityY: Float = 0f,
        var armed: Boolean = false,
        var counted: Boolean = false,
        var missed: Int = 0
    )

    private data class Match(
        val track: Track,
        val detectionIndex: Int,
        val distance: Float
    )

    private val tracks = mutableMapOf<Int, Track>()
    private var nextId = 1

    private var topLine = 150f
    private var bottomLine = 411f

    private val maxMissedFrames = 12
    private val maxDistance = 140f
    private val maxAllowedDistance = 220f

    private val positionSmoothing = 0.65f
    private val velocitySmoothing = 0.5f
    private val minimumLineGap = 20f

    fun setLines(top: Float, bottom: Float) {
        val newTop = minOf(top, bottom)
        val newBottom = maxOf(top, bottom)

        if (newBottom - newTop < minimumLineGap) return

        if (newTop != topLine || newBottom != bottomLine) {
            topLine = newTop
            bottomLine = newBottom
            reset()
        }
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

        val validDetections = detections.filter {
            it.cx.isFinite() && it.cy.isFinite()
        }

        val candidates = mutableListOf<Match>()

        for (track in tracks.values) {
            if (track.missed > maxMissedFrames) continue

            val elapsed = track.missed.coerceAtLeast(1).toFloat()

            val predictedX = track.x + track.velocityX * elapsed
            val predictedY = track.y + track.velocityY * elapsed

            val allowedDistance = minOf(
                maxDistance + track.missed * 6f,
                maxAllowedDistance
            )

            for (index in validDetections.indices) {
                val detection = validDetections[index]

                val dx = predictedX - detection.cx
                val dy = predictedY - detection.cy
                val distance = sqrt(dx * dx + dy * dy)

                if (distance <= allowedDistance) {
                    candidates.add(Match(track, index, distance))
                }
            }
        }

        candidates.sortBy { it.distance }

        val matchedTrackIds = mutableSetOf<Int>()
        val matchedDetectionIndices = mutableSetOf<Int>()
        val assignments = mutableListOf<Match>()

        for (match in candidates) {
            if (match.track.id in matchedTrackIds) continue
            if (match.detectionIndex in matchedDetectionIndices) continue

            matchedTrackIds.add(match.track.id)
            matchedDetectionIndices.add(match.detectionIndex)
            assignments.add(match)
        }

        // Update existing tracks.
        for (match in assignments) {
            val track = match.track
            val detection = validDetections[match.detectionIndex]

            val oldY = track.y
            val elapsed = track.missed.coerceAtLeast(1).toFloat()

            val measuredVelocityX = (detection.cx - track.x) / elapsed
            val measuredVelocityY = (detection.cy - track.y) / elapsed

            track.velocityX =
                track.velocityX * (1f - velocitySmoothing) +
                measuredVelocityX * velocitySmoothing

            track.velocityY =
                track.velocityY * (1f - velocitySmoothing) +
                measuredVelocityY * velocitySmoothing

            track.x =
                track.x * (1f - positionSmoothing) +
                detection.cx * positionSmoothing

            track.y =
                track.y * (1f - positionSmoothing) +
                detection.cy * positionSmoothing

            track.missed = 0

            val newY = detection.cy

            // Python logic: entering the band arms the track.
            if (newY > topLine && newY < bottomLine) {
                track.armed = true
            }

            // Python logic: count when moving upward across LINE_1.
            val crossedTopUpward =
                oldY > topLine && newY <= topLine

            if (track.armed && !track.counted && crossedTopUpward) {
                track.counted = true
                newCounts++
            }
        }

        // Create tracks for detections not matched to an existing track.
        for (index in validDetections.indices) {
            if (index in matchedDetectionIndices) continue

            val detection = validDetections[index]

            val track = Track(
                id = nextId++,
                x = detection.cx,
                y = detection.cy,
                armed = detection.cy > topLine &&
                        detection.cy < bottomLine
            )

            tracks[track.id] = track
        }

        tracks.entries.removeAll {
            it.value.missed > maxMissedFrames
        }

        return newCounts
    }
}
