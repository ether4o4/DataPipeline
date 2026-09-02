from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

from .pipeline import run_pipeline, write_csv, write_jsonl


def main() -> int:
    ap = argparse.ArgumentParser(description="Normalize Snapchat/Facebook/Instagram/Discord exports")
    ap.add_argument("--export", action="append", required=True, help="Export directory or parent directory; repeatable")
    ap.add_argument("--out-jsonl", type=Path)
    ap.add_argument("--out-csv", type=Path)
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, format="%(levelname)s %(message)s", stream=sys.stderr)
    records, diagnostics = run_pipeline(Path(p) for p in args.export)
    if args.out_jsonl: write_jsonl(records, args.out_jsonl)
    if args.out_csv: write_csv(records, args.out_csv)
    result = {"status": "ok", "records": len(records), "platforms": {}, "diagnostics": diagnostics}
    for record in records:
        result["platforms"][record.platform] = result["platforms"].get(record.platform, 0) + 1
    if args.out_jsonl or args.out_csv:
        print(json.dumps(result, indent=2), file=sys.stderr)
    else:
        for record in records:
            print(json.dumps(record.to_dict(), ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
