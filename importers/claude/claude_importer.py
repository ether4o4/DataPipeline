#!/usr/bin/env python3
"""Import the Claude export packages into the ALL KNOWLEDGE normalized model.

This module is intentionally dependency-free. It accepts the five Claude export
ZIP packages and emits normalized JSONL records suitable for the core importer.
It never modifies the source archives.
"""

from __future__ import annotations

import argparse
import json
import zipfile
from pathlib import Path
from typing import Any, Iterable


PROVIDER = "claude"


def read_json_zip(path: Path) -> list[tuple[str, Any]]:
    out = []
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if name.endswith("/") or not name.lower().endswith(".json"):
                continue
            with z.open(name) as fh:
                out.append((name, json.load(fh)))
    return out


def text_from_message(message: dict[str, Any]) -> str:
    """Prefer Claude's exported display text, then reconstruct content blocks."""
    text = message.get("text")
    if isinstance(text, str):
        return text
    content = message.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for block in content:
            if isinstance(block, dict) and isinstance(block.get("text"), str):
                parts.append(block["text"])
            elif isinstance(block, str):
                parts.append(block)
        return "\n".join(parts)
    return ""


def conversation_records(data: Any) -> Iterable[dict[str, Any]]:
    if not isinstance(data, list):
        return
    for conv in data:
        if not isinstance(conv, dict):
            continue
        cid = conv.get("uuid") or conv.get("id")
        if not cid:
            continue
        yield {
            "record_type": "conversation",
            "provider": PROVIDER,
            "conversation_id": str(cid),
            "title": conv.get("name") or conv.get("title") or "Untitled",
            "summary": conv.get("summary") or "",
            "create_time": conv.get("created_at"),
            "update_time": conv.get("updated_at"),
            "project_id": (conv.get("project") or {}).get("uuid") if isinstance(conv.get("project"), dict) else conv.get("project_uuid"),
        }


def message_records(data: Any) -> Iterable[dict[str, Any]]:
    if not isinstance(data, list):
        return
    for conv in data:
        if not isinstance(conv, dict):
            continue
        cid = conv.get("uuid") or conv.get("id")
        if not cid:
            continue
        for msg in conv.get("chat_messages") or []:
            if not isinstance(msg, dict):
                continue
            mid = msg.get("uuid") or msg.get("id")
            if not mid:
                continue
            sender = msg.get("sender") or msg.get("role") or "unknown"
            role = {"human": "user", "assistant": "assistant", "user": "user"}.get(str(sender), str(sender))
            yield {
                "record_type": "message",
                "provider": PROVIDER,
                "message_id": str(mid),
                "conversation_id": str(cid),
                "role": role,
                "create_time": msg.get("created_at"),
                "update_time": msg.get("updated_at"),
                "content": text_from_message(msg),
                "parent_message_id": msg.get("parent_message_uuid"),
                "attachments": msg.get("attachments") or [],
                "files": msg.get("files") or [],
            }


def project_records(documents: Iterable[tuple[str, Any]]) -> Iterable[dict[str, Any]]:
    for name, data in documents:
        if not isinstance(data, dict) or not data.get("uuid"):
            continue
        yield {
            "record_type": "project",
            "provider": PROVIDER,
            "project_id": str(data["uuid"]),
            "title": data.get("name") or "Untitled",
            "description": data.get("description") or "",
            "prompt_template": data.get("prompt_template") or "",
            "created_at": data.get("created_at"),
            "updated_at": data.get("updated_at"),
            "docs": data.get("docs") or [],
            "source_file": name,
        }


def design_chat_records(documents: Iterable[tuple[str, Any]]) -> Iterable[dict[str, Any]]:
    for name, data in documents:
        if not isinstance(data, dict) or not data.get("uuid"):
            continue
        cid = str(data["uuid"])
        yield {
            "record_type": "conversation",
            "provider": PROVIDER,
            "conversation_id": f"design:{cid}",
            "title": data.get("title") or "Untitled design chat",
            "summary": "",
            "create_time": data.get("created_at"),
            "update_time": data.get("updated_at"),
            "project_id": (data.get("project") or {}).get("uuid") if isinstance(data.get("project"), dict) else data.get("project"),
            "source_kind": "design_chat",
        }
        for msg in data.get("messages") or []:
            if not isinstance(msg, dict):
                continue
            mid = msg.get("uuid") or msg.get("id")
            if not mid:
                continue
            yield {
                "record_type": "message",
                "provider": PROVIDER,
                "message_id": f"design:{mid}",
                "conversation_id": f"design:{cid}",
                "role": {"human": "user", "assistant": "assistant"}.get(str(msg.get("sender") or msg.get("role")), str(msg.get("sender") or msg.get("role") or "unknown")),
                "create_time": msg.get("created_at"),
                "update_time": msg.get("updated_at"),
                "content": text_from_message(msg),
                "parent_message_id": msg.get("parent_message_uuid"),
                "attachments": msg.get("attachments") or [],
                "files": msg.get("files") or [],
            }


def import_export(conversations_zip: Path, projects_zip: Path | None, design_zip: Path | None, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    conv_docs = read_json_zip(conversations_zip)
    if not conv_docs:
        raise ValueError("No JSON data found in conversations ZIP")
    data = conv_docs[0][1]

    records: list[dict[str, Any]] = []
    records.extend(conversation_records(data))
    records.extend(message_records(data))

    if projects_zip:
        records.extend(project_records(read_json_zip(projects_zip)))
    if design_zip:
        records.extend(design_chat_records(read_json_zip(design_zip)))

    with output.open("w", encoding="utf-8") as fh:
        for record in records:
            fh.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")

    counts = {}
    for r in records:
        counts[r["record_type"]] = counts.get(r["record_type"], 0) + 1
    print(json.dumps({"provider": PROVIDER, "records": counts, "output": str(output)}, indent=2))


def main() -> None:
    ap = argparse.ArgumentParser(description="Normalize a Claude export into ALL KNOWLEDGE JSONL")
    ap.add_argument("conversations", type=Path)
    ap.add_argument("output", type=Path)
    ap.add_argument("--projects", type=Path)
    ap.add_argument("--design-chats", type=Path)
    args = ap.parse_args()
    import_export(args.conversations, args.projects, args.design_chats, args.output)


if __name__ == "__main__":
    main()
