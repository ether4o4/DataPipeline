from __future__ import annotations

import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

from ..base import ExportNotRecognized, RecordType, SocialExportParser, UnifiedRecord


class SnapchatParser(SocialExportParser):
    platform = "snapchat"
    markers = ("json/chat_history.json", "json/snap_history.json", "json/account.json")

    def can_handle(self, export_root: Path) -> bool:
        return any((export_root / m).exists() for m in self.markers) or (export_root / "friends.json").exists()

    def parse(self, export_root: Path) -> Iterator[UnifiedRecord]:
        found = False
        chat = export_root / "json/chat_history.json"
        if chat.exists():
            found = True
            yield from self._chat(chat)
        snap = export_root / "json/snap_history.json"
        if snap.exists():
            found = True
            yield from self._snap(snap)
        friends = export_root / "json/friends.json"
        if friends.exists():
            found = True
            yield from self._friends(friends)
        locations = export_root / "json/location_history.json"
        if locations.exists():
            found = True
            yield from self._locations(locations)
        if not found:
            raise ExportNotRecognized(str(export_root))

    def _chat(self, path: Path) -> Iterator[UnifiedRecord]:
        data = self._safe_json_load(path)
        conversations = data.get("conversation", data) if isinstance(data, dict) else data
        if isinstance(conversations, dict):
            conversations = conversations.items()
        else:
            conversations = ((str(i), v) for i, v in enumerate(conversations or []))
        for peer, messages in conversations:
            if isinstance(messages, dict):
                messages = messages.get("messages", messages.get("chat_history", []))
            for msg in messages or []:
                if not isinstance(msg, dict): continue
                ts = self._timestamp(msg.get("Created") or msg.get("created") or msg.get("timestamp"))
                if ts is None: continue
                media = msg.get("Media Type") or msg.get("media_type")
                yield UnifiedRecord(self.platform, RecordType.MEDIA if media else RecordType.MESSAGE, ts, str(path), msg.get("From") or msg.get("sender"), str(peer), msg.get("Content") or msg.get("Text") or msg.get("content"), [str(media)] if media else [], raw=msg)

    def _snap(self, path: Path) -> Iterator[UnifiedRecord]:
        data = self._safe_json_load(path)
        rows = data if isinstance(data, list) else data.get("snaps", data.get("snap_history", [])) if isinstance(data, dict) else []
        for row in rows:
            if not isinstance(row, dict): continue
            ts = self._timestamp(row.get("Created") or row.get("created") or row.get("timestamp"))
            if ts is None: continue
            media = row.get("Media Type") or row.get("media_type") or row.get("Media")
            yield UnifiedRecord(self.platform, RecordType.MEDIA, ts, str(path), row.get("From") or row.get("sender"), row.get("To") or row.get("recipient"), row.get("Content") or row.get("Text"), [str(media)] if media else [], raw=row)

    def _friends(self, path: Path) -> Iterator[UnifiedRecord]:
        data = self._safe_json_load(path)
        for bucket, rows in (data.items() if isinstance(data, dict) else []):
            if not isinstance(rows, list): continue
            for row in rows:
                if not isinstance(row, dict): continue
                ts = self._timestamp(row.get("Created") or row.get("Last Modified") or row.get("timestamp")) or datetime.fromtimestamp(0, tz=timezone.utc)
                yield UnifiedRecord(self.platform, RecordType.FRIEND_EVENT, ts, str(path), row.get("Username") or row.get("username"), content=str(bucket), raw=row)

    def _locations(self, path: Path) -> Iterator[UnifiedRecord]:
        data = self._safe_json_load(path)
        rows = data if isinstance(data, list) else data.get("locations", []) if isinstance(data, dict) else []
        for row in rows:
            if not isinstance(row, dict): continue
            ts = self._timestamp(row.get("timestamp") or row.get("Created") or row.get("date"))
            try: lat, lon = float(row.get("Latitude", row.get("latitude"))), float(row.get("Longitude", row.get("longitude")))
            except (TypeError, ValueError): continue
            if ts: yield UnifiedRecord(self.platform, RecordType.LOCATION, ts, str(path), latitude=lat, longitude=lon, raw=row)

    @staticmethod
    def _timestamp(value: Any) -> datetime | None:
        if isinstance(value, (int, float)):
            return SocialExportParser._parse_epoch(value)
        if not isinstance(value, str): return None
        value = value.strip()
        for fmt in ("%a, %d %b %Y %H:%M:%S %Z", "%Y-%m-%d %H:%M:%S %Z", "%a %Y-%m-%d %H:%M:%S %Z", "%Y-%m-%dT%H:%M:%S%z"):
            try: return datetime.strptime(value, fmt).astimezone(timezone.utc)
            except ValueError: pass
        return SocialExportParser._parse_epoch(value)
