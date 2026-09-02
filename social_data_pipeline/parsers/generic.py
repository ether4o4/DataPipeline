from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

from ..base import RecordType, SocialExportParser, UnifiedRecord


class GenericJsonParser(SocialExportParser):
    """Best-effort parser for platform exports whose schema varies by export version."""
    marker_names: tuple[str, ...] = ()
    message_keys = ("messages", "message", "chat_history", "direct_messages", "conversation")

    def can_handle(self, export_root: Path) -> bool:
        return any((export_root / name).exists() for name in self.marker_names)

    def parse(self, export_root: Path) -> Iterator[UnifiedRecord]:
        files = [export_root / n for n in self.marker_names if (export_root / n).is_file()]
        if not files:
            for p in export_root.rglob("*.json"):
                if p.stat().st_size <= 10_000_000:
                    files.append(p)
        for path in files:
            try: data = self._safe_json_load(path)
            except (OSError, ValueError): continue
            yield from self._walk(data, path)

    def _walk(self, node: Any, path: Path) -> Iterator[UnifiedRecord]:
        if isinstance(node, list):
            for item in node: yield from self._walk(item, path)
            return
        if not isinstance(node, dict): return
        ts = self._timestamp(node)
        content = self._first(node, "content", "text", "message", "body")
        sender = self._first(node, "sender", "from", "author", "username")
        recipient = self._first(node, "recipient", "to")
        if ts and (content is not None or sender is not None):
            media = self._first(node, "media", "media_url", "attachment", "attachments")
            refs = [str(media)] if media and not isinstance(media, (dict, list)) else []
            yield UnifiedRecord(self.platform, RecordType.MEDIA if refs else RecordType.MESSAGE, ts, str(path), str(sender) if sender is not None else None, str(recipient) if recipient is not None else None, str(content) if content is not None else None, refs, raw=node)
        for value in node.values():
            if isinstance(value, (dict, list)): yield from self._walk(value, path)

    @staticmethod
    def _first(row: dict[str, Any], *keys: str) -> Any:
        for key in keys:
            if key in row and row[key] not in (None, "", []): return row[key]
        return None

    @staticmethod
    def _timestamp(row: dict[str, Any]) -> datetime | None:
        for key in ("timestamp", "created_at", "created", "date", "time", "created_time"):
            value = row.get(key)
            if isinstance(value, (int, float)):
                return SocialExportParser._parse_epoch(value)
            if isinstance(value, str):
                value = value.strip()
                try: return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)
                except ValueError: pass
                parsed = SocialExportParser._parse_epoch(value)
                if parsed: return parsed
        return None
