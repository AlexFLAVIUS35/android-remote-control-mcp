"""Real-time Android H.264 stream receiver and YOLO26 TensorRT loop."""

from __future__ import annotations

import argparse
from collections import deque
import queue
import threading
import time
from typing import Callable

from arcs_protocol import parse_packet


class H264Feeder:
    """Thread-safe callback source for PyNvVideoCodec CreateDemuxer()."""

    def __init__(self, max_packets: int = 8) -> None:
        self._packets: queue.Queue[bytes | memoryview | None] = queue.Queue(maxsize=max_packets)
        self._current = memoryview(b"")
        self._closed = False

    def push(self, payload: bytes | memoryview) -> None:
        if self._closed:
            return
        self._packets.put(payload, block=True)

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self._packets.put(None, block=True)

    def feed_chunk(self, destination) -> int:
        """PyNvVideoCodec callback: fill its native buffer and return byte count."""
        while not self._current:
            packet = self._packets.get(block=True)
            if packet is None:
                return 0
            self._current = memoryview(packet)

        count = min(len(destination), len(self._current))
        destination[:count] = self._current[:count]
        self._current = self._current[count:]
        return count


class ScreenStreamReceiver:
    def __init__(
        self,
        ws_url: str,
        bearer_token: str | None,
        gpu_id: int,
    ) -> None:
        self.ws_url = ws_url
        self.bearer_token = bearer_token
        self.gpu_id = gpu_id
        self.feeder = H264Feeder()
        self.last_packet_time = time.perf_counter()
        self.packet_count = 0
        self.keyframe_count = 0
        self.bytes_received = 0
        self._packet_times = deque(maxlen=120)

    def _receive_loop(self) -> None:
        from websockets.sync.client import connect

        headers = {}
        if self.bearer_token:
            headers["Authorization"] = f"Bearer {self.bearer_token}"

        try:
            with connect(
                self.ws_url,
                additional_headers=headers or None,
                compression=None,
                max_size=16 * 1024 * 1024,
                max_queue=8,
                ping_interval=5,
                ping_timeout=5,
            ) as websocket:
                print(f"Connected to {self.ws_url}")
                for message in websocket:
                    if not isinstance(message, bytes):
                        continue
                    packet = parse_packet(message)
                    self.packet_count += 1
                    self.bytes_received += len(packet.payload)
                    self.last_packet_time = time.perf_counter()
                    self._packet_times.append(self.last_packet_time)
                    if packet.is_key_frame:
                        self.keyframe_count += 1
                    self.feeder.push(packet.payload)
        except Exception as exc:
            print(f"Screen stream receiver stopped: {exc}")
        finally:
            self.feeder.close()

    def run(
        self,
        on_frame: Callable[[object], None],
    ) -> None:
        """Decode H.264 on NVDEC and invoke on_frame for every decoded GPU frame."""
        import PyNvVideoCodec as nvc

        receiver_thread = threading.Thread(target=self._receive_loop, name="ScreenStreamRx", daemon=True)
        receiver_thread.start()

        demuxer = nvc.CreateDemuxer(self.feeder.feed_chunk)
        codec = demuxer.GetNvCodecId()
        print(f"Decoder codec: {codec}")

        decoder = nvc.CreateDecoder(
            gpuid=self.gpu_id,
            codec=codec,
            usedevicememory=True,
            outputcolortype=nvc.OutputColorType.RGBP,
            lowlatency=True,
        )

        for packet in demuxer:
            if hasattr(nvc, "VideoPacketFlag") and hasattr(nvc.VideoPacketFlag, "ENDOFPICTURE"):
                packet.decode_flag = nvc.VideoPacketFlag.ENDOFPICTURE
            for frame in decoder.Decode(packet):
                on_frame(frame)

        for frame in decoder.Flush():
            on_frame(frame)


class YOLO26TensorRT:
    def __init__(self, engine_path: str, device: int = 0, imgsz: int = 640, conf: float = 0.25) -> None:
        import torch
        from ultralytics import YOLO

        if not torch.cuda.is_available():
            raise RuntimeError("CUDA is not available; install a CUDA-enabled PyTorch build and an NVIDIA driver.")
        self.device = device
        self.imgsz = imgsz
        self.conf = conf
        self.model = YOLO(engine_path)

    def infer(self, decoded_frame) -> object:
        import torch

        tensor = torch.from_dlpack(decoded_frame)
        if tensor.ndim == 3:
            tensor = tensor.unsqueeze(0)
        return self.model.predict(
            source=tensor,
            imgsz=self.imgsz,
            conf=self.conf,
            device=self.device,
            nms=False,
            verbose=False,
        )[0]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ws-url", required=True, help="ws:// or wss:// Android /screen/stream URL")
    parser.add_argument("--token", default=None, help="Bearer token from the Android MCP server settings")
    parser.add_argument("--engine", required=True, help="YOLO26 TensorRT .engine file")
    parser.add_argument("--gpu", type=int, default=0)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--conf", type=float, default=0.25)
    args = parser.parse_args()

    detector = YOLO26TensorRT(args.engine, args.gpu, args.imgsz, args.conf)
    inference_times: deque[float] = deque(maxlen=120)

    def handle(frame) -> None:
        start = time.perf_counter()
        result = detector.infer(frame)
        elapsed_ms = (time.perf_counter() - start) * 1000
        inference_times.append(elapsed_ms)
        if len(inference_times) == inference_times.maxlen:
            avg = sum(inference_times) / len(inference_times)
            boxes = 0 if result.boxes is None else len(result.boxes)
            print(f"YOLO26: {avg:.2f} ms avg | detections={boxes}", flush=True)

    receiver = ScreenStreamReceiver(args.ws_url, args.token, args.gpu)
    receiver.run(handle)


if __name__ == "__main__":
    main()
