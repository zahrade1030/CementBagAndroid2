
package com.vea.cementbagcounter

class ByteTrackLineCounter {

    private data class LineState(
        var previousY: Float? = null,
        var armed: Boolean = false,
        var counted: Boolean = false,
        var missed: Int = 0
    )

    private val tracker = ByteTrackTracker()

    private val states = mutableMapOf<Int, LineState>()

    private var topLine = 150f
    private var bottomLine = 411f

    private val maxStateMissed = 30
    private val maxCrossingGap = 5

    private var trackedPoints = emptyList<TrackedPoint>()

    fun setLines(top: Float, bottom: Float) {
        val newTop = minOf(top, bottom)
        val newBottom = maxOf(top, bottom)

        if (newBottom - newTop < 20f) return

        if (newTop != topLine || newBottom != bottomLine) {
            topLine = newTop
            bottomLine = newBottom
            reset()
        }
    }

    fun reset() {
        tracker.reset()
        states.clear()
        trackedPoints = emptyList()
    }

    fun getTrackedPoints(): List<TrackedPoint> {
        return trackedPoints
    }

    fun update(detections: List<Detection>): Int {

        val results = tracker.update(detections)

        var newCounts = 0

        // Mark existing states as temporarily missing.
        states.values.forEach {
            it.missed++
        }

        val points = mutableListOf<TrackedPoint>()

        for (result in results) {

            val id = result.id
            val detection = result.detection
            val centerY = detection.cy

            val state = states.getOrPut(id) {
                LineState()
            }

            val previousMissed = state.missed

            state.missed = 0

            // Arm the track when it enters the counting area.
            if (
                centerY > topLine &&
                centerY < bottomLine
            ) {
                state.armed = true
            }

            val oldY = state.previousY

            // Only bridge short tracking gaps.
            val canCheckCrossing =
                oldY != null &&
                previousMissed <= maxCrossingGap

            // Count upward crossing of the top line.
            val crossedTopUpward =
                canCheckCrossing &&
                oldY!! > topLine &&
                centerY <= topLine

            if (
                state.armed &&
                !state.counted &&
                crossedTopUpward
            ) {
                state.counted = true
                newCounts++
            }

            // If the tracking gap was too long,
            // require the object to enter the area again.
            if (previousMissed > maxCrossingGap) {
                state.armed =
                    centerY > topLine &&
                    centerY < bottomLine
            }

            state.previousY = centerY

            points.add(
                TrackedPoint(
                    id = id,
                    x = detection.cx,
                    y = detection.cy,
                    counted = state.counted
                )
            )
        }

        // Remove states that are no longer recoverable.
        states.entries.removeAll {
            it.value.missed > maxStateMissed
        }

        trackedPoints = points

        return newCounts
    }
}
