from __future__ import annotations

import csv
import json
import logging
from collections import Counter
from pathlib import Path
from typing import Iterable, Iterator

from .base import ExportNotRecognized, SocialExportParser, UnifiedRecord
from .parsers import DiscordParser, FacebookParser, InstagramParser, SnapchatParser

log = logging.getLogger(__name__)
DEFAULT_PARSERS = [SnapchatParser(), FacebookParser(), InstagramParser(), DiscordParser()]


def discover_exports(paths: Iterable[Path]) -> list[Path]:
    """Return candidate export roots, recursively, while keeping explicit roots."""
    candidates: list[Path] = []
    seen: set[Path] = set()
    markers = ("json", "data", "messages", "chat_history.json", "conversations.json")
    for supplied in paths:
        root = Path(supplied).expanduser().resolve()
        if not root.exists():
            continue
        roots = [root] if root.is_file() else [root]
        if root.is_dir():
            for p in root.rglob("*"):
                if p.is_dir() and any((p / m).exists() for m in markers if m != "json"):
                    roots.append(p)
        for p in roots:
            if p in seen:
                continue
            seen.add(p)
            candidates.append(p)
    return candidates


def detect_platform(export_root: Path, parsers: Iterable[SocialExportParser] = DEFAULT_PARSERS) -> SocialExportParser | None:
    for parser in parsers:
        try:
            if parser.can_handle(export_root):
                return parser
        except OSError:
            log.exception("Parser detection failed for %s", export_root)
    return None


def run_pipeline(export_roots: Iterable[Path], parsers: Iterable[SocialExportParser] = DEFAULT_PARSERS) -> tuple[list[UnifiedRecord], dict[str, object]]:
    records: list[UnifiedRecord] = []
    seen: set[str] = set()
    diagnostics: Counter[str] = Counter()
    roots = discover_exports(export_roots)
    for root in roots:
        parser = detect_platform(root, parsers)
        if parser is None:
            diagnostics["unknown_exports"] += 1
            log.warning("No social parser recognized %s", root)
            continue
        diagnostics[f"detected_{parser.platform}"] += 1
        try:
            for record in parser.parse(root):
                if record.record_id in seen:
                    diagnostics["duplicate_records"] += 1
                    continue
                seen.add(record.record_id)
                records.append(record)
                diagnostics[f"records_{record.record_type.value}"] += 1
        except ExportNotRecognized:
            diagnostics["rejected_exports"] += 1
            log.warning("Parser rejected %s", root)
        except (OSError, ValueError, json.JSONDecodeError):
            diagnostics["malformed_exports"] += 1
            log.exception("Failed parsing %s", root)
    records.sort(key=lambda r: r.timestamp_utc)
    diagnostics["exports_seen"] = len(roots)
    diagnostics["records_emitted"] = len(records)
    return records, dict(diagnostics)


def write_jsonl(records: Iterable[UnifiedRecord], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as fh:
        for record in sorted(records, key=lambda r: r.timestamp_utc):
            fh.write(json.dumps(record.to_dict(), ensure_ascii=False, separators=(",", ":")) + "\n")


def write_csv(records: Iterable[UnifiedRecord], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    fields = ["record_id", "platform", "record_type", "timestamp_utc", "sender", "recipient", "content", "media_refs", "latitude", "longitude", "source_file"]
    with output.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        for r in sorted(records, key=lambda r: r.timestamp_utc):
            d = r.to_dict()
            d["media_refs"] = "; ".join(r.media_refs)
            d.pop("raw", None)
            writer.writerow({k: d.get(k) for k in fields})
