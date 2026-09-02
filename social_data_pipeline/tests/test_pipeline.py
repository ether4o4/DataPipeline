from datetime import datetime, timezone
from pathlib import Path

from social_data_pipeline.base import RecordType, UnifiedRecord
from social_data_pipeline.pipeline import run_pipeline
from social_data_pipeline.parsers.snapchat import SnapchatParser


def test_record_id_is_deterministic():
    a = UnifiedRecord("x", RecordType.MESSAGE, datetime(2026, 1, 1, tzinfo=timezone.utc), "a", "u", "v", "hello")
    b = UnifiedRecord("x", RecordType.MESSAGE, datetime(2026, 1, 1, tzinfo=timezone.utc), "b", "u", "v", "hello")
    assert a.record_id == b.record_id


def test_snapchat_chat(tmp_path: Path):
    root = tmp_path / "export" / "json"
    root.mkdir(parents=True)
    (root / "chat_history.json").write_text('{"bob":[{"Created":"2026-01-02 03:04:05 UTC","From":"me","Content":"hello"}]}', encoding="utf-8")
    records, diagnostics = run_pipeline([root.parent], [SnapchatParser()])
    assert len(records) == 1
    assert records[0].content == "hello"
    assert records[0].platform == "snapchat"
    assert diagnostics["records_emitted"] == 1
