
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

            // Old tracks belong to the previous line configuration.
            tracks.clear()
            nextId = 1
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

        tracks.values.forEach {
            it.missed++
        }

        var newCounts = 0

        // Ignore invalid detection coordinates.
        val validDetections = detections.filter {
            it.cx.isFinite() &&
                it.cy.isFinite()
        }

        val candidates = mutableListOf<Match>()

        // Predict the next position of each existing track.
        for (track in tracks.values) {

            if (track.missed > maxMissedFrames) continue

            val elapsed = track.missed.coerceAtLeast(1).toFloat()

            val predictedX =
                track.x + track.velocityX * elapsed

            val predictedY =
                track.y + track.velocityY * elapsed

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
                    candidates.add(
                        Match(track, index, distance)
                    )
                }
            }
        }

        // Match the closest track-detection pairs first.
        candidates.sortBy { it.distance }

        val matchedTrackIds = mutableSetOf<Int>()
        val matchedDetectionIndices = mutableSetOf<Int>()

        val assignments = mutableListOf<Match>()

        for (match in candidates) {

            if (match.track.id in matchedTrackIds) continue

            if (match.detectionIndex in matchedDetectionIndices) {
                continue
            }

            matchedTrackIds.add(match.track.id)
            matchedDetectionIndices.add(match.detectionIndex)

            assignments.add(match)
        }

        // Update matched tracks and check line crossing.
        for (match in assignments) {

            val track = match.track
            val detection = validDetections[match.detectionIndex]

            val oldX = track.x
            val oldY = track.y

            val elapsed =
                track.missed.coerceAtLeast(1).toFloat()

            val measuredVelocityX =
                (detection.cx - oldX) / elapsed

            val measuredVelocityY =
                (detection.cy - oldY) / elapsed

            track.velocityX =
                track.velocityX * (1f - velocitySmoothing) +
                    measuredVelocityX * velocitySmoothing

            track.velocityY =
                track.velocityY * (1f - velocitySmoothing) +
                    measuredVelocityY * velocitySmoothing

            // Smooth displayed tracking position.
            track.x =
                oldX * (1f - positionSmoothing) +
                    detection.cx * positionSmoothing

            track.y =
                oldY * (1f - positionSmoothing) +
                    detection.cy * positionSmoothing

            track.missed = 0

            val newY = detection.cy

            // Arm the track when it enters the counting zone
            // from above, including when it crosses both lines
            // between two consecutive detections.
            val enteredCountingZone =
                oldY <= topLine && newY > topLine

            val currentlyInsideZone =
                newY > topLine && newY < bottomLine

            if (enteredCountingZone || currentlyInsideZone) {
                track.armed = true
            }

            // Count only downward crossing of the bottom line.
            if (
                track.armed &&
                !track.counted &&
                oldY < bottomLine &&
                newY >= bottomLine
            ) {
                track.counted = true
                newCounts++
            }
        }

        // Create new tracks for unmatched detections.
        for (index in validDetections.indices) {

            if (index in matchedDetectionIndices) continue

            val detection = validDetections[index]

            val track = Track(
                id = nextId++,
                x = detection.cx,
                y = detection.cy
            )

            // A newly detected object inside the counting zone
            // is ready to be counted when it crosses the bottom line.
            if (
                track.y > topLine &&
                track.y < bottomLine
            ) {
                track.armed = true
            }

            tracks[track.id] = track
        }

        // Remove tracks missing for too many frames.
        tracks.entries.removeAll {
            it.value.missed > maxMissedFrames
        }

        return newCounts
    }
}
