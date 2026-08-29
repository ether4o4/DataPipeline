# DATA PIPELINE

A private, local-first knowledge engine for importing, indexing, searching, and exploring personal AI conversation exports and extracted code/artifacts.

## Architecture

- `core/` — shared data model and SQLite/FTS5 search schema
- `importers/` — provider-specific archive parsers
- `android/` — native Android application
- `docs/` — architecture and implementation notes

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
