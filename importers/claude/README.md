# Claude Importer

This adapter ingests an Anthropic/Claude data export and converts it into the ALL KNOWLEDGE normalized schema.

## Input

The known export includes a manifest JSON file. The complete export must be inspected before parsing so the adapter can discover the actual conversation/message files referenced by the manifest.

## Output

- provider = `claude`
- conversations
- messages
- attachments/artifacts where their exported representation is available

## Requirements

- Never modify the source export.
- Preserve original IDs and timestamps when present.
- Preserve message roles and content.
- Be safe to run repeatedly; existing provider IDs must not create duplicates.
- Keep raw personal export files outside Git.
- Report unsupported files/fields rather than silently discarding them.

## Implementation sequence

1. Inspect manifest schema.
2. Enumerate every referenced export file.
3. Identify conversation records and message records.
4. Map them to the shared schema.
5. Add fixtures using sanitized/minimal structures.
6. Test duplicate-safe imports.
7. Integrate into the Android import dispatcher.
