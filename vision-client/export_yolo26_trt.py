"""Export YOLO26 to an NVIDIA TensorRT engine for the fast vision loop."""

from __future__ import annotations

import argparse

from ultralytics import YOLO


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="yolo26n.pt")
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--device", type=int, default=0)
    parser.add_argument("--output", default=None, help="Optional output path; Ultralytics chooses the .engine name by default.")
    args = parser.parse_args()

    model = YOLO(args.model)
    engine = model.export(
        format="engine",
        imgsz=args.imgsz,
        batch=1,
        device=args.device,
        quantize=16,
        nms=False,
        dynamic=False,
    )

    print(f"TensorRT engine created: {engine}")
    if args.output:
        print("Ultralytics controls the generated engine filename; rename/copy it to the requested path after export.")


if __name__ == "__main__":
    main()
