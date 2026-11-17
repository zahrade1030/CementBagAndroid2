
package com.vea.cementbagcounter

import kotlin.math.max
import kotlin.math.min

data class ByteTrackResult(
    val id: Int,
    val detection: Detection
)

class ByteTrackTracker(
    private val highThreshold: Float = 0.25f,
    private val lowThreshold: Float = 0.10f,
    private val newTrackThreshold: Float = 0.25f,
    private val trackBuffer: Int = 30,
    private val matchThreshold: Float = 0.8f
) {

    private data class Track(
        val id: Int,
        var box: Detection,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var vw: Float = 0f,
        var vh: Float = 0f,
        var missed: Int = 0,
        var hits: Int = 1
    ) {

        fun predict(framesAhead: Int = 1): Detection {
            val steps = framesAhead.coerceAtLeast(1).toFloat()

            val dx = vx * steps
            val dy = vy * steps
            val dw = vw * steps
            val dh = vh * steps

            return Detection(
                left = box.left + dx,
                top = box.top + dy,
                right = box.right + dx + dw,
                bottom = box.bottom + dy + dh,
                confidence = box.confidence
            )
        }

        fun update(detection: Detection) {
            val old = box
            val elapsedFrames = (missed + 1).toFloat()

            val observedVx =
                (detection.cx - old.cx) / elapsedFrames

            val observedVy =
                (detection.cy - old.cy) / elapsedFrames

            val oldWidth = old.right - old.left
            val oldHeight = old.bottom - old.top

            val newWidth = detection.right - detection.left
            val newHeight = detection.bottom - detection.top

            val observedVw =
                (newWidth - oldWidth) / elapsedFrames

            val observedVh =
                (newHeight - oldHeight) / elapsedFrames

            vx = 0.7f * vx + 0.3f * observedVx
            vy = 0.7f * vy + 0.3f * observedVy
            vw = 0.7f * vw + 0.3f * observedVw
            vh = 0.7f * vh + 0.3f * observedVh

            box = detection
            missed = 0
            hits++
        }
    }

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    fun reset() {
        tracks.clear()
        nextId = 1
    }

    fun update(detections: List<Detection>): List<ByteTrackResult> {

        tracks.forEach { it.missed++ }

        val valid = detections.filter {
            it.confidence.isFinite() &&
                it.cx.isFinite() &&
                it.cy.isFinite() &&
                it.left.isFinite() &&
                it.top.isFinite() &&
                it.right.isFinite() &&
                it.bottom.isFinite() &&
                it.right > it.left &&
                it.bottom > it.top
        }

        val high = valid.filter {
            it.confidence >= highThreshold
        }

        val low = valid.filter {
            it.confidence >= lowThreshold &&
                it.confidence < highThreshold
        }

        val matchedTracks = mutableSetOf<Int>()
        val matchedHigh = mutableSetOf<Int>()
        val matchedLow = mutableSetOf<Int>()

        // First association: high-confidence detections.
        associate(
            high,
            matchedTracks,
            matchedHigh
        )

        // Second association: low-confidence detections.
        associate(
            low,
            matchedTracks,
            matchedLow
        )

        // Create new tracks only from high-confidence detections.
        for (index in high.indices) {
            if (index in matchedHigh) continue

            val detection = high[index]

            if (detection.confidence >= newTrackThreshold) {
                tracks.add(
                    Track(
                        id = nextId++,
                        box = detection
                    )
                )
            }
        }

        // Remove tracks lost for too many frames.
        tracks.removeAll {
            it.missed > trackBuffer
        }

        return tracks
            .filter { it.missed == 0 }
            .map {
                ByteTrackResult(
                    id = it.id,
                    detection = it.box
                )
            }
    }

    private fun associate(
        detections: List<Detection>,
        matchedTracks: MutableSet<Int>,
        matchedDetections: MutableSet<Int>
    ) {

        data class Candidate(
            val track: Track,
            val detectionIndex: Int,
            val iou: Float
        )

        val candidates = mutableListOf<Candidate>()

        for (track in tracks) {

            if (track.id in matchedTracks) continue

            // Predict according to the number of missed frames.
            val predicted = track.predict(track.missed)

            for (index in detections.indices) {

                if (index in matchedDetections) continue

                val overlap = iou(
                    predicted,
                    detections[index]
                )

                val cost = 1f - overlap

                if (cost <= matchThreshold) {
                    candidates.add(
                        Candidate(
                            track = track,
                            detectionIndex = index,
                            iou = overlap
                        )
                    )
                }
            }
        }

        // Match the strongest overlaps first.
        candidates.sortByDescending {
            it.iou
        }

        for (candidate in candidates) {

            val track = candidate.track
            val index = candidate.detectionIndex

            if (track.id in matchedTracks) continue
            if (index in matchedDetections) continue

            track.update(detections[index])

            matchedTracks.add(track.id)
            matchedDetections.add(index)
        }
    }

    private fun iou(
        a: Detection,
        b: Detection
    ): Float {

        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)

        val width = max(0f, x2 - x1)
        val height = max(0f, y2 - y1)

        val intersection = width * height

        val areaA =
            max(0f, a.right - a.left) *
                max(0f, a.bottom - a.top)

        val areaB =
            max(0f, b.right - b.left) *
                max(0f, b.bottom - b.top)

        val union = areaA + areaB - intersection

        if (union <= 0f) return 0f

        return (intersection / union).coerceIn(0f, 1f)
    }
}
