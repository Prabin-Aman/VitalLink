"""UUIDv7 ids: time-ordered (indexes stay compact), not enumerable (docs/02-architecture.md)."""

import os
import time
import uuid


def uuid7() -> uuid.UUID:
    """RFC 9562 version 7: 48-bit Unix ms timestamp, then 74 random bits."""
    ms = time.time_ns() // 1_000_000
    value = (ms & ((1 << 48) - 1)) << 80 | int.from_bytes(os.urandom(10)) & ((1 << 80) - 1)
    value = value & ~(0xF << 76) | (0x7 << 76)  # version 7
    value = value & ~(0x3 << 62) | (0x2 << 62)  # RFC 4122 variant
    return uuid.UUID(int=value)
