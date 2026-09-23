"""Parser for the Android Remote Control MCP ARCS binary screen-stream protocol."""

from __future__ import annotations

from dataclasses import dataclass
import struct


HEADER = struct.Struct(">4sBHQL")
MAGIC = b"ARCS"
VERSION = 1
FLAG_KEY_FRAME = 1
FLAG_CODEC_CONFIG = 2


@dataclass(frozen=True, slots=True)
class ScreenPacket:
    pts_us: int
    flags: int
    payload: memoryview

    @property
    def is_codec_config(self) -> bool:
        return bool(self.flags & FLAG_CODEC_CONFIG)

    @property
    def is_key_frame(self) -> bool:
        return bool(self.flags & FLAG_KEY_FRAME)


def parse_packet(message: bytes) -> ScreenPacket:
    """Decode one binary WebSocket message without copying the H.264 payload."""
    if len(message) < HEADER.size:
        raise ValueError(f"ARCS packet is too small: {len(message)} bytes")

    magic, version, flags, pts_us, payload_size = HEADER.unpack_from(message)
    if magic != MAGIC:
        raise ValueError(f"Unexpected ARCS magic: {magic!r}")
    if version != VERSION:
        raise ValueError(f"Unsupported ARCS version: {version}")

    payload_start = HEADER.size
    payload_end = payload_start + payload_size
    if payload_end != len(message):
        raise ValueError("ARCS payload length does not match WebSocket message length")

    return ScreenPacket(
        pts_us=pts_us,
        flags=flags,
        payload=memoryview(message)[payload_start:payload_end],
    )
