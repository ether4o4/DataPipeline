# DATA PIPELINE

A private, local-first knowledge engine for importing, indexing, searching, and exploring personal AI conversation exports and extracted code/artifacts.

## Latest Android APK

**[⬇️ Download the latest Data Pipeline APK](https://github.com/ether4o4/DataPipeline/releases/latest/download/data-pipeline.apk)**

This link is intentionally stable. Future releases should publish the APK with the exact asset name `data-pipeline.apk`, so this README never needs another download-link edit.

## Architecture

- `core/` — shared data model and SQLite/FTS5 search schema
- `importers/` — provider-specific archive parsers
- `social_data_pipeline/` — local-first Snapchat/Facebook/Instagram/Discord normalization engine
- `android/` — native Android application
- `docs/` — architecture and implementation notes

## Social export pipeline

The `social_data_pipeline` package turns supported social exports into a deterministic `UnifiedRecord` stream. It recursively discovers candidate export roots, detects the platform, normalizes records to UTC, deduplicates them by stable record ID, and reports malformed/unknown/duplicate counts instead of silently losing them.

Supported adapters:

- Snapchat — chat history, snap history, friends, and location history
- Facebook — best-effort JSON message/activity normalization across export layouts
- Instagram — best-effort JSON message/activity normalization across export layouts
- Discord — best-effort JSON message/activity normalization across export layouts

CLI example:

```bash
python -m social_data_pipeline.cli --export ./exports --out-jsonl ./out/social.jsonl --out-csv ./out/social.csv
```

The pipeline is dependency-free and has regression tests in `social_data_pipeline/tests/`. CI runs those tests automatically when the social pipeline changes.

## Android app

The Android app is now the primary user-facing experience. It is phone-first: portrait layout, no horizontal scrolling, and vertical scrolling only for result lists and long conversations.

The app includes:

- ZIP import from Android's document picker
- ChatGPT export parsing (`conversations.json` / mapping format)
- Claude export parsing (`chat_messages` format)
- Local SQLite storage
- On-device full-text search
- Conversation reader
- Code-block extraction into searchable artifacts
- Import progress and database statistics
- No localhost server required
- No cloud upload of imported data

## Import flow

1. Install the APK.
2. Tap **IMPORT**.
3. Select a ChatGPT or Claude export ZIP.
4. Data is parsed locally and indexed into the app database.
5. Search immediately from the home screen.
6. Tap a result to open the complete conversation.

Imports are designed to be incremental: the normalized database uses provider/message/conversation identifiers to avoid duplicate records when the same export is imported again.

## Provider roadmap

ChatGPT and Claude are the first native importers. Grok, Kimi, and Skywork adapters can be added as their export formats become available.

## Data policy

Raw personal exports and generated databases are **not** committed to this repository. The repository contains application code, schemas, parsers, tests, and documentation only.
