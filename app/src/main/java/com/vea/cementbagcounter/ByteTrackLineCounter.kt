
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

        // Mark all states as temporarily missing.
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

            state.missed = 0

            // Python logic: entering the area arms the track.
            if (
                centerY > topLine &&
                centerY < bottomLine
            ) {
                state.armed = true
            }

            val oldY = state.previousY

            // Count only upward crossing of the first line.
            val crossedTopUpward =
                oldY != null &&
                oldY > topLine &&
                centerY <= topLine

            if (
                state.armed &&
                !state.counted &&
                crossedTopUpward
            ) {
                state.counted = true
                newCounts++
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

        // If a track disappears temporarily, do not assume
        // it crossed a line while it was invisible.
        states.values.forEach { state ->
            if (state.missed > 0) {
                state.previousY = null
            }
        }

        // Keep states while ByteTrack may still recover their IDs.
        states.entries.removeAll {
            it.value.missed > maxStateMissed
        }

        trackedPoints = points

        return newCounts
    }
}
