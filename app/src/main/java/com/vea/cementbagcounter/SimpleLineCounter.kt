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
        var previousY: Float,
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
    private val positionSmoothing = 0.65f
    private val velocitySmoothing = 0.5f

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

        val candidates = mutableListOf<Match>()

        // Predict each track's next position and compare it
        // with all current detections.
        for (track in tracks.values) {

            if (track.missed > maxMissedFrames) continue

            val predictedX =
                track.x + track.velocityX * track.missed

            val predictedY =
                track.y + track.velocityY * track.missed

            val allowedDistance = minOf(
                maxDistance + track.missed * 5f,
                180f
            )

            for (index in detections.indices) {

                val detection = detections[index]

                val dx = predictedX - detection.cx
                val dy = predictedY - detection.cy

                val distance = sqrt(dx * dx + dy * dy)

                if (distance < allowedDistance) {
                    candidates.add(
                        Match(track, index, distance)
                    )
                }
            }
        }

        // Assign the closest pairs first.
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

        // Update matched tracks.
        for (match in assignments) {

            val track = match.track
            val detection = detections[match.detectionIndex]

            val oldY = track.y
            val elapsedFrames = track.missed.coerceAtLeast(1).toFloat()

            track.previousY = oldY

            val measuredVelocityX =
                (detection.cx - track.x) / elapsedFrames

            val measuredVelocityY =
                (detection.cy - track.y) / elapsedFrames

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

            if (track.y > topLine && track.y < bottomLine) {
                track.armed = true
            }

            // Count only one downward crossing per track.
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

        // Create tracks for unmatched detections.
        for (index in detections.indices) {

            if (index in matchedDetectionIndices) continue

            val detection = detections[index]

            val track = Track(
                id = nextId++,
                x = detection.cx,
                y = detection.cy,
                previousY = detection.cy
            )

            if (track.y > topLine && track.y < bottomLine) {
                track.armed = true
            }

            tracks[track.id] = track
        }

        // Remove tracks that have been missing too long.
        tracks.entries.removeAll {
            it.value.missed > maxMissedFrames
        }

        return newCounts
    }
}
