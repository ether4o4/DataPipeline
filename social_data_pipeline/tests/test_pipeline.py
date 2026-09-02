import unittest
from datetime import datetime, timezone
from pathlib import Path
from tempfile import TemporaryDirectory

from social_data_pipeline.base import RecordType, UnifiedRecord
from social_data_pipeline.pipeline import run_pipeline
from social_data_pipeline.parsers.snapchat import SnapchatParser


class PipelineTests(unittest.TestCase):
    def test_record_id_is_deterministic(self):
        a = UnifiedRecord("x", RecordType.MESSAGE, datetime(2026, 1, 1, tzinfo=timezone.utc), "a", "u", "v", "hello")
        b = UnifiedRecord("x", RecordType.MESSAGE, datetime(2026, 1, 1, tzinfo=timezone.utc), "b", "u", "v", "hello")
        self.assertEqual(a.record_id, b.record_id)

    def test_snapchat_chat(self):
        with TemporaryDirectory() as temp:
            root = Path(temp) / "export" / "json"
            root.mkdir(parents=True)
            (root / "chat_history.json").write_text('{"bob":[{"Created":"2026-01-02 03:04:05 UTC","From":"me","Content":"hello"}]}', encoding="utf-8")
            records, diagnostics = run_pipeline([root.parent], [SnapchatParser()])
            self.assertEqual(len(records), 1)
            self.assertEqual(records[0].content, "hello")
            self.assertEqual(records[0].platform, "snapchat")
            self.assertEqual(diagnostics["records_emitted"], 1)


if __name__ == "__main__":
    unittest.main()
