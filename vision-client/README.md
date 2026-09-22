# Android screen stream vision client

This directory is the PC-side fast perception loop for the Android Remote Control MCP fork.

```text
Android display
    -> MediaProjection
    -> hardware H.264
    -> binary WebSocket /screen/stream
    -> NVIDIA NVDEC
    -> GPU RGBP tensor via DLPack
    -> YOLO26 TensorRT
    -> controller / higher-level agent
```

The Android side keeps the existing MCP screenshot tools unchanged. The new stream is a separate path designed for continuous frames.

## Install

Use a Windows x64 Python environment on the NVIDIA PC. Install a CUDA-enabled PyTorch build appropriate for the installed NVIDIA driver, then:

```powershell
python -m pip install -r vision-client/requirements.txt
```

Verify:

```powershell
python -c "import torch; print(torch.cuda.is_available()); print(torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'NO CUDA GPU')"
```

PyNvVideoCodec uses NVIDIA's NVDEC hardware decoder and supports device-memory decoded frames. Its DLPack interface can hand those frames to PyTorch without a GPU-to-CPU copy. NVIDIA documents the low-latency decoder modes and DLPack integration. 

## Build the YOLO26 TensorRT engine

Build the engine on the actual deployment GPU rather than downloading an engine built for a different GPU:

```powershell
python vision-client/export_yolo26_trt.py --model yolo26n.pt --imgsz 640 --device 0
```

The exporter uses the YOLO26 one-to-one (`nms=False`) head, FP16 TensorRT, batch 1, and a fixed 640-pixel input. Ultralytics documents YOLO26 TensorRT export and the NMS-free one-to-one head.

## Connect to the phone

Start the **AI screen stream** in the Android app. Android will show the system screen-capture consent dialog.

Then run:

```powershell
python vision-client/stream_yolo26.py --ws-url ws://PHONE_IP:8080/screen/stream --token YOUR_MCP_BEARER_TOKEN --engine yolo26n.engine
```

Use `wss://` instead when the Android MCP server is configured for HTTPS.

The WebSocket carries binary ARCS packets. The packet header is 19 bytes:

```text
ARCS | version | flags | presentation timestamp (us) | payload length | H.264 payload
 4B      1B         2B              8B                      4B
```

The receiver deliberately disables WebSocket compression because the payload is already H.264-compressed.

## Important limitation

The stock `yolo26n.pt` model is a generic object detector. It is useful for validating the GPU pipeline, but it will not automatically understand Roblox-specific concepts such as DOORS entities, room geometry, items, or game UI. The next model stage is a custom YOLO26 dataset/training pass for the visual classes the agent actually needs.

Latency is measured rather than assumed. The target is a low-latency loop, but the actual Android encoder, Wi-Fi path, NVDEC, TensorRT inference, and controller timings must be benchmarked on the real hardware.
