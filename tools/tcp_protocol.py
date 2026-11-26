"""Wire protocol for the TCP interface mod (reference implementation).

Frame layout (exactly 256 bytes):
    nonce[12] || AES-256-GCM ciphertext[227] || tag[16] || b'\n'

The application plaintext is UTF-8 encoded and padded with b'\n' to exactly 227
bytes before encryption. The encrypted length is therefore constant, and the
receiver needs no plaintext length field. The padding is authenticated by GCM.

Key:
    SHA-256(token encoded as UTF-8)

Plaintext messages:
    type=request,name=<name>,power=<0-15>,requestid=<id>,timestamp=<unix seconds>
    type=response,power=<0-15>,tick=<hold ticks>,requestid=<id>,timestamp=<unix seconds>
    type=keepalive,name=<name>,requestid=<id>,timestamp=<unix seconds>
    type=keepaliveok,requestid=<id>,timestamp=<unix seconds>
"""
from __future__ import annotations

import base64
import hashlib
import os
import re
import time
from dataclasses import dataclass
from typing import Final, Optional

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

FRAME_SIZE: Final = 256
NONCE_SIZE: Final = 12
TAG_SIZE: Final = 16
FRAME_TRAILER_SIZE: Final = 1
PADDED_PLAINTEXT_SIZE: Final = FRAME_SIZE - NONCE_SIZE - TAG_SIZE - FRAME_TRAILER_SIZE
PAD: Final = b"\n"
MAX_CLOCK_SKEW_SECONDS: Final = 10
REPLAY_COUNT_LIMIT: Final = 10
REPLAY_WINDOW_SECONDS: Final = 30
NAME_MAX_BYTES: Final = 80

_REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
_FORBIDDEN_NAME = {",", "=", "\n", "\r"}


class ProtocolError(ValueError):
    """Recoverable wire-format error; the caller drops the frame."""


@dataclass(frozen=True)
class Message:
    type: str
    name: Optional[str]
    power: int
    tick: int
    requestid: str
    timestamp: int


def key_from_token(token: str) -> bytes:
    return hashlib.sha256(token.encode("utf-8")).digest()


def now_seconds() -> int:
    return int(time.time())


def new_request_id() -> str:
    return base64.urlsafe_b64encode(os.urandom(12)).rstrip(b"=").decode("ascii")


def _validate_name(name: str) -> None:
    if not name:
        raise ProtocolError("name must not be empty")
    if len(name.encode("utf-8")) > NAME_MAX_BYTES:
        raise ProtocolError(f"name exceeds {NAME_MAX_BYTES} UTF-8 bytes")
    if any(ch in name for ch in _FORBIDDEN_NAME):
        raise ProtocolError("name contains a forbidden character")


def _validate_request_id(request_id: str) -> None:
    if not _REQUEST_ID_RE.fullmatch(request_id):
        raise ProtocolError("invalid requestid")


def _parse_timestamp(value: str) -> int:
    if not value or not value.isdigit() or len(value) > 19:
        raise ProtocolError("invalid timestamp")
    timestamp = int(value)
    if timestamp <= 0:
        raise ProtocolError("invalid timestamp")
    return timestamp


def _parse_bounded_int(value: str, maximum: int, field: str) -> int:
    if not value or not value.isdigit() or len(value) > 10:
        raise ProtocolError(f"invalid {field}")
    parsed = int(value)
    if parsed < 0 or parsed > maximum:
        raise ProtocolError(f"invalid {field}")
    return parsed


def _field(part: str, expected_key: str) -> str:
    key, sep, value = part.partition("=")
    if not sep or key != expected_key:
        raise ProtocolError(f"expected field {expected_key}")
    return value


def parse_message(text: str) -> Message:
    """Strict parser: fixed field order and field count."""
    parts = text.split(",")
    if not parts:
        raise ProtocolError("empty message")
    message_type = _field(parts[0], "type")

    if message_type == "request":
        if len(parts) != 5:
            raise ProtocolError("wrong field count for request")
        name = _field(parts[1], "name")
        power = _parse_bounded_int(_field(parts[2], "power"), 15, "power")
        requestid = _field(parts[3], "requestid")
        timestamp = _parse_timestamp(_field(parts[4], "timestamp"))
        _validate_name(name)
        _validate_request_id(requestid)
        return Message("request", name, power, 0, requestid, timestamp)

    if message_type == "response":
        if len(parts) != 5:
            raise ProtocolError("wrong field count for response")
        power = _parse_bounded_int(_field(parts[1], "power"), 15, "power")
        tick = _parse_bounded_int(_field(parts[2], "tick"), 2**31 - 1, "tick")
        requestid = _field(parts[3], "requestid")
        timestamp = _parse_timestamp(_field(parts[4], "timestamp"))
        _validate_request_id(requestid)
        return Message("response", None, power, tick, requestid, timestamp)

    if message_type == "keepalive":
        if len(parts) != 4:
            raise ProtocolError("wrong field count for keepalive")
        name = _field(parts[1], "name")
        requestid = _field(parts[2], "requestid")
        timestamp = _parse_timestamp(_field(parts[3], "timestamp"))
        _validate_name(name)
        _validate_request_id(requestid)
        return Message("keepalive", name, 0, 0, requestid, timestamp)

    if message_type == "keepaliveok":
        if len(parts) != 3:
            raise ProtocolError("wrong field count for keepaliveok")
        requestid = _field(parts[1], "requestid")
        timestamp = _parse_timestamp(_field(parts[2], "timestamp"))
        _validate_request_id(requestid)
        return Message("keepaliveok", None, 0, 0, requestid, timestamp)

    raise ProtocolError(f"unknown message type {message_type!r}")


def build_request(name: str, power: int, request_id: Optional[str] = None,
                  timestamp: Optional[int] = None) -> tuple[str, str]:
    request_id = request_id or new_request_id()
    timestamp = now_seconds() if timestamp is None else timestamp
    text = f"type=request,name={name},power={power},requestid={request_id},timestamp={timestamp}"
    parse_message(text)
    return text, request_id


def build_response(power: int, tick: int, request_id: str,
                   timestamp: Optional[int] = None) -> str:
    timestamp = now_seconds() if timestamp is None else timestamp
    text = f"type=response,power={power},tick={tick},requestid={request_id},timestamp={timestamp}"
    parse_message(text)
    return text


def build_keepalive(name: str, request_id: Optional[str] = None,
                    timestamp: Optional[int] = None) -> tuple[str, str]:
    request_id = request_id or new_request_id()
    timestamp = now_seconds() if timestamp is None else timestamp
    text = f"type=keepalive,name={name},requestid={request_id},timestamp={timestamp}"
    parse_message(text)
    return text, request_id


def build_keepaliveok(request_id: str, timestamp: Optional[int] = None) -> str:
    timestamp = now_seconds() if timestamp is None else timestamp
    text = f"type=keepaliveok,requestid={request_id},timestamp={timestamp}"
    parse_message(text)
    return text


def encode_frame(plaintext: str, key: bytes) -> bytes:
    raw = plaintext.encode("utf-8")
    if len(raw) > PADDED_PLAINTEXT_SIZE:
        raise ProtocolError(
            f"plaintext is {len(raw)} bytes, exceeding the {PADDED_PLAINTEXT_SIZE}-byte limit")
    padded = raw + PAD * (PADDED_PLAINTEXT_SIZE - len(raw))
    nonce = os.urandom(NONCE_SIZE)
    ciphertext = AESGCM(key).encrypt(nonce, padded, None)
    frame = nonce + ciphertext + PAD
    if len(frame) != FRAME_SIZE:  # Defensive: AESGCM already returns ciphertext + tag.
        raise ProtocolError(f"internal frame length error: {len(frame)}")
    return frame


def decode_frame(frame: bytes, key: bytes) -> str:
    if len(frame) != FRAME_SIZE:
        raise ProtocolError("frame must be exactly 256 bytes")
    if frame[-1:] != PAD:
        raise ProtocolError("frame is missing its trailing newline")
    nonce, ciphertext = frame[:NONCE_SIZE], frame[NONCE_SIZE:-1]
    try:
        plaintext = AESGCM(key).decrypt(nonce, ciphertext, None)
    except Exception as exc:
        raise ProtocolError("AES-GCM decryption failed") from exc
    if len(plaintext) != PADDED_PLAINTEXT_SIZE:
        raise ProtocolError(f"unexpected plaintext length: {len(plaintext)}")

    newline = plaintext.find(PAD)
    if newline < 0:
        newline = len(plaintext)
    if newline == 0:
        raise ProtocolError("plaintext is empty")
    if any(byte != PAD[0] for byte in plaintext[newline:]):
        raise ProtocolError("invalid plaintext padding")
    try:
        return plaintext[:newline].decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ProtocolError("plaintext is not UTF-8") from exc


class ReplayGuard:
    """Per-connection in-memory requestid count table."""

    def __init__(self) -> None:
        self._counts: dict[str, int] = {}
        self._next_reset = time.monotonic() + REPLAY_WINDOW_SECONDS

    def reset_if_due(self) -> bool:
        now = time.monotonic()
        if now < self._next_reset:
            return False
        self._counts.clear()
        self._next_reset = now + REPLAY_WINDOW_SECONDS
        return True

    def increment(self, request_id: str) -> int:
        count = self._counts.get(request_id, 0) + 1
        self._counts[request_id] = count
        return count

    def contains(self, request_id: str) -> bool:
        return request_id in self._counts

    def record_success(self, request_id: str) -> int:
        return self.increment(request_id)

    def reset(self) -> None:
        self._counts.clear()

    @property
    def size(self) -> int:
        return len(self._counts)


def timestamp_is_valid(timestamp: int, now: Optional[int] = None) -> bool:
    current = now_seconds() if now is None else now
    return abs(current - timestamp) <= MAX_CLOCK_SKEW_SECONDS