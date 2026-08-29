# ALL KNOWLEDGE Architecture

## Pipeline

`Provider export -> importer -> normalized records -> SQLite -> FTS5 -> native UI`

## Normalized records

### Conversation
- provider
- conversation_id
- title
- create_time
- update_time
- metadata

### Message
- provider
- message_id
- conversation_id
- role
- create_time
- content

### Artifact
- provider
- artifact_id
- conversation_id
- message_id
- title
- kind
- language
- content

## Search

FTS5 indexes should support full-text queries over message content and extracted artifacts. The Android app should query SQLite directly rather than requiring a Python HTTP server.

## Import behavior

Imports are transactional and resumable. Provider IDs are used for deduplication. A later export must not create duplicate records already present in the local database.

## Backup

The app will eventually create a portable `.akb` backup containing the normalized database and manifest. Raw provider exports remain optional source archives and are never required to be checked into Git.
