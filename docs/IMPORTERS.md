# Provider Importers

ALL KNOWLEDGE uses provider adapters that convert exports into one normalized local schema.

## ChatGPT

Initial reference format: exported ZIP containing `conversations-*.json`, conversation metadata, and asset files. Existing prototype successfully indexes this format into SQLite/FTS5.

## Claude

The received export includes an Anthropic manifest JSON. The importer will inspect the complete export at runtime and map its conversation/message structures into the normalized schema without modifying the source files.

## Grok / Kimi / Skywork

Adapters will be added after their complete exports are available. Each adapter must:

- preserve the provider identifier
- preserve original timestamps where available
- preserve conversation/message IDs where available
- extract attachments/artifacts when practical
- never upload raw personal data
- support repeatable imports without duplicate records

## Rule

Never commit raw user exports, local databases, or generated personal-content indexes to GitHub. Provider exports are input data, not source code.
