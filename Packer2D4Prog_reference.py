
import cv2
from ultralytics import YOLO
import datetime
import torch
import time
import os

# =========================
# SETTINGS
# =========================

MODEL_PATH = r".\best.pt"

VIDEO_PATH = r"WhatsApp Video 2026-10-16 at 6.32.09 AM.mp4"

OUTPUT_PATH = r"counted_output.mp4"

LINE_1 = 150
LINE_2 = 411

CONFIDENCE = 0.40
IMAGE_SIZE = 640

# =========================
# LOAD MODEL
# =========================

if not os.path.isfile(MODEL_PATH):
    raise FileNotFoundError(f"Model not found: {MODEL_PATH}")

if not os.path.isfile(VIDEO_PATH):
    raise FileNotFoundError(f"Video not found: {VIDEO_PATH}")

device = 0 if torch.cuda.is_available() else "cpu"

print("Device:", device, flush=True)
print("Loading model...", flush=True)

model = YOLO(MODEL_PATH)

print("Model loaded successfully.", flush=True)

# =========================
# OPEN VIDEO
# =========================

cap = cv2.VideoCapture(VIDEO_PATH)

if not cap.isOpened():
    raise RuntimeError("Video could not be opened.")

video_fps = cap.get(cv2.CAP_PROP_FPS)
if video_fps <= 0:
    video_fps = 30.0

width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

os.makedirs(os.path.dirname(OUTPUT_PATH) or ".", exist_ok=True)

writer = cv2.VideoWriter(
    OUTPUT_PATH,
    cv2.VideoWriter_fourcc(*"mp4v"),
    video_fps,
    (width, height)
)

if not writer.isOpened():
    cap.release()
    raise RuntimeError("Could not create output video.")

print("Video FPS:", video_fps)
print("Video resolution:", width, "x", height)
print("Total frames:", total_frames)
print("Press ESC to stop.")

# =========================
# COUNTING VARIABLES
# =========================

count = 0
ready_ids = set()
counted_ids = set()

frame_number = 0
start_time = time.perf_counter()

# =========================
# MAIN LOOP
# =========================

try:
    while True:
        ret, frame = cap.read()

        if not ret:
            break

        frame_number += 1

        # Run detection and tracking on the current frame.
        results = model.track(
            frame,
            persist=True,
            tracker="bytetrack.yaml",
            conf=CONFIDENCE,
            imgsz=IMAGE_SIZE,
            device=device,
            verbose=False
        )

        result = results[0]
        output_image = frame.copy()

        # =========================
        # DETECTIONS
        # =========================

        if (
            result.boxes is not None
            and result.boxes.id is not None
        ):
            boxes = result.boxes.xyxy.cpu().numpy()
            ids = result.boxes.id.int().cpu().tolist()
            confidences = result.boxes.conf.cpu().numpy()

            for box, track_id, confidence in zip(
                boxes, ids, confidences
            ):
                x1, y1, x2, y2 = map(int, box)
                track_id = int(track_id)

                cx = (x1 + x2) // 2
                cy = (y1 + y2) // 2

                # =========================
                # READY AREA
                # =========================

                if LINE_1 < cy < LINE_2:
                    if track_id not in counted_ids:
                        ready_ids.add(track_id)

                # =========================
                # COUNT WHEN CROSSING LINE 1
                # =========================

                if (
                    track_id in ready_ids
                    and track_id not in counted_ids
                    and cy <= LINE_1
                ):
                    count += 1
                    counted_ids.add(track_id)

                    print("-" * 35)
                    print("COUNT:", count)
                    print("TRACK ID:", track_id)
                    print(
                        "TIME:",
                        datetime.datetime.now().replace(
                            microsecond=0
                        )
                    )
                    print("-" * 35)

                # =========================
                # BOX COLOR
                # =========================

                if track_id in counted_ids:
                    color = (255, 255, 0)
                    status = "COUNTED"
                    thickness = 2

                elif track_id in ready_ids:
                    color = (0, 255, 0)
                    status = "READY"
                    thickness = 3

                else:
                    color = (255, 0, 0)
                    status = "TRACKING"
                    thickness = 2

                cv2.rectangle(
                    output_image,
                    (x1, y1),
                    (x2, y2),
                    color,
                    thickness
                )

                cv2.circle(
                    output_image,
                    (cx, cy),
                    5,
                    (0, 0, 255),
                    -1
                )

                label = (
                    f"ID:{track_id} "
                    f"{confidence:.2f} {status}"
                )

                cv2.putText(
                    output_image,
                    label,
                    (x1, max(y1 - 10, 20)),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.55,
                    color,
                    2
                )

        # =========================
        # DRAW LINES
        # =========================

        cv2.line(
            output_image,
            (0, LINE_1),
            (width, LINE_1),
            (0, 0, 255),
            3
        )

        cv2.putText(
            output_image,
            "LINE 1",
            (20, max(20, LINE_1 - 10)),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.7,
            (0, 0, 255),
            2
        )

        cv2.line(
            output_image,
            (0, LINE_2),
            (width, LINE_2),
            (0, 0, 255),
            3
        )

        cv2.putText(
            output_image,
            "LINE 2",
            (20, LINE_2 - 10),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.7,
            (0, 0, 255),
            2
        )

        # =========================
        # DRAW COUNT AND PROGRESS
        # =========================

        cv2.putText(
            output_image,
            f"COUNT: {count}",
            (20, 55),
            cv2.FONT_HERSHEY_SIMPLEX,
            1.4,
            (0, 0, 255),
            4,
            cv2.LINE_AA
        )

        if total_frames > 0:
            progress = frame_number / total_frames * 100

            cv2.putText(
                output_image,
                f"Progress: {progress:.1f}%",
                (20, 95),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.7,
                (255, 255, 255),
                2
            )

        # Save and display the same processed frame.
        writer.write(output_image)

        cv2.imshow("Bag Detection and Counting", output_image)

        # Keep approximately the original playback speed.
        target_time = start_time + frame_number / video_fps
        remaining = target_time - time.perf_counter()

        wait_ms = max(1, min(30, int(remaining * 1000)))

        if cv2.waitKey(wait_ms) & 0xFF == 27:
            break

        if frame_number % 30 == 0:
            elapsed = time.perf_counter() - start_time
            print(
                f"Frames: {frame_number}/{total_frames} | "
                f"Count: {count} | "
                f"Elapsed: {elapsed:.1f}s",
                flush=True
            )

finally:
    cap.release()
    writer.release()
    cv2.destroyAllWindows()

print("\nProcessing finished.")
print("Total frames processed:", frame_number)
print("Final count:", count)
print("Output video:", os.path.abspath(OUTPUT_PATH))
print("Total time:", round(time.perf_counter() - start_time, 2), "seconds")
