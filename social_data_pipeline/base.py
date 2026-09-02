from __future__ import annotations

import hashlib
import json
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Any, Iterator, Optional


class RecordType(str, Enum):
    MESSAGE = "message"
    MEDIA = "media"
    LOCATION = "location"
    CALL = "call"
    FRIEND_EVENT = "friend_event"
    LOGIN_EVENT = "login_event"
    OTHER = "other"


@dataclass
class UnifiedRecord:
    platform: str
    record_type: RecordType
    timestamp_utc: datetime
    source_file: str
    sender: Optional[str] = None
    recipient: Optional[str] = None
    content: Optional[str] = None
    media_refs: list[str] = field(default_factory=list)
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    raw: dict[str, Any] = field(default_factory=dict)
    record_id: str = field(default="", init=False)

    def __post_init__(self) -> None:
        if self.timestamp_utc.tzinfo is None:
            self.timestamp_utc = self.timestamp_utc.replace(tzinfo=timezone.utc)
        self.timestamp_utc = self.timestamp_utc.astimezone(timezone.utc)
        key = "|".join([
            self.platform, self.record_type.value, self.timestamp_utc.isoformat(),
            self.sender or "", self.recipient or "", self.content or "",
        ])
        self.record_id = hashlib.sha256(key.encode("utf-8")).hexdigest()[:16]

    def to_dict(self) -> dict[str, Any]:
        return {
            "record_id": self.record_id, "platform": self.platform,
            "record_type": self.record_type.value,
            "timestamp_utc": self.timestamp_utc.isoformat(),
            "source_file": self.source_file, "sender": self.sender,
            "recipient": self.recipient, "content": self.content,
            "media_refs": self.media_refs, "latitude": self.latitude,
            "longitude": self.longitude, "raw": self.raw,
        }


class ExportNotRecognized(Exception):
    pass


class SocialExportParser(ABC):
    platform = "unknown"

    @abstractmethod
    def can_handle(self, export_root: Path) -> bool: ...

    @abstractmethod
    def parse(self, export_root: Path) -> Iterator[UnifiedRecord]: ...

    @staticmethod
    def _safe_json_load(path: Path) -> Any:
        with path.open("r", encoding="utf-8-sig") as fh:
            return json.load(fh)

    @staticmethod
    def _parse_epoch(value: Any) -> Optional[datetime]:
        if value is None or value == "":
            return None
        try:
            n = float(value)
        except (TypeError, ValueError):
            return None
        # Detect seconds, milliseconds and microseconds without guessing a date.
        if abs(n) > 1e14:
            n /= 1_000_000
        elif abs(n) > 1e11:
            n /= 1_000
        try:
            return datetime.fromtimestamp(n, tz=timezone.utc)
        except (OverflowError, OSError, ValueError):
            return None
