# ALL KNOWLEDGE

A private, local-first knowledge engine for importing, indexing, searching, and exploring personal AI conversation exports and extracted code/artifacts.

## Architecture

- `core/` — shared data model and SQLite/FTS5 search layer
- `importers/` — provider-specific archive parsers
- `android/` — native Android application
- `dashboard/` — browser dashboard/prototype
- `backup/` — portable backup and restore format
- `docs/` — architecture and implementation notes
- `tests/` — parser and indexing tests

## Design goals

1. Local-first: personal conversation data stays on-device.
2. One-click import: select an export ZIP and let the app parse/index it.
3. Incremental imports: deduplicate existing conversations/messages/artifacts.
4. Full-text search: SQLite FTS5 across conversations, messages, code, commands, and extracted artifacts.
5. Portable recovery: export/import a complete knowledge-base backup on a new device.
6. No manually started localhost server in the Android app.

## Supported source direction

The first importer targets ChatGPT's exported ZIP structure. Additional adapters will target Claude, Grok, Kimi, and Skywork as their exports become available.

## Data policy

Raw personal exports and generated databases are **not** committed to this repository. The repository contains application code, schemas, parsers, tests, and documentation only.

## Current prototype

The prototype has successfully indexed a ChatGPT export into SQLite/FTS5 with tens of thousands of messages and extracted artifacts. The native application will build on that proven data model.
