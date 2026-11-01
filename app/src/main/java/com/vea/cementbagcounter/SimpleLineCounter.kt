
package com.vea.cementbagcounter

import kotlin.math.abs

// Lightweight centroid tracker. The count rule mirrors the Python script's two-line arming logic.
class SimpleLineCounter {
    private data class Track(
        var x: Float, var y: Float,
        var armed: Boolean = false,
        var counted: Boolean = false,
        var missed: Int = 0
    )
    private val tracks = mutableMapOf<Int, Track>()
    private var nextId = 1
    private var topLine = 150f
    private var bottomLine = 411f

    fun setLines(top: Float, bottom: Float) {
        topLine = minOf(top, bottom)
        bottomLine = maxOf(top, bottom)
    }

    fun reset() { tracks.clear(); nextId = 1 }

    fun update(detections: List<Detection>): Int {
        tracks.values.forEach { it.missed++ }
        var newCounts = 0
        for (d in detections) {
            val candidate = tracks.entries
                .filter { it.value.missed <= 2 && abs(it.value.x-d.cx) < 120f && abs(it.value.y-d.cy) < 160f }
                .minByOrNull { abs(it.value.x-d.cx) + abs(it.value.y-d.cy) }
            val t = if (candidate != null) candidate.value.also { it.missed = 0 }
                    else Track(d.cx, d.cy).also { tracks[nextId++] = it }
            val oldY = t.y
            t.x = d.cx
            t.y = d.cy
            if (d.cy > topLine && d.cy < bottomLine) t.armed = true
            if (t.armed && !t.counted && oldY > topLine && d.cy <= topLine) {
                t.counted = true
                newCounts++
            }
        }
        tracks.entries.removeAll { it.value.missed > 20 }
        return newCounts
    }
}
