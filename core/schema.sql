PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS providers (
  id INTEGER PRIMARY KEY,
  key TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS conversations (
  id INTEGER PRIMARY KEY,
  provider TEXT NOT NULL,
  conversation_id TEXT NOT NULL,
  title TEXT,
  created_at TEXT,
  updated_at TEXT,
  metadata_json TEXT,
  UNIQUE(provider, conversation_id)
);

CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY,
  provider TEXT NOT NULL,
  message_id TEXT NOT NULL,
  conversation_id TEXT NOT NULL,
  role TEXT,
  content TEXT NOT NULL DEFAULT '',
  created_at TEXT,
  parent_message_id TEXT,
  metadata_json TEXT,
  UNIQUE(provider, message_id)
);

CREATE TABLE IF NOT EXISTS artifacts (
  id INTEGER PRIMARY KEY,
  provider TEXT NOT NULL,
  artifact_id TEXT NOT NULL,
  conversation_id TEXT,
  message_id TEXT,
  title TEXT,
  kind TEXT,
  language TEXT,
  content TEXT NOT NULL DEFAULT '',
  metadata_json TEXT,
  UNIQUE(provider, artifact_id)
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(provider, conversation_id);
CREATE INDEX IF NOT EXISTS idx_artifacts_conversation ON artifacts(provider, conversation_id);
CREATE INDEX IF NOT EXISTS idx_messages_created ON messages(created_at);

CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
  title, role, content, conversation_id, message_id,
  provider, tokenize='unicode61'
);

CREATE VIRTUAL TABLE IF NOT EXISTS artifact_fts USING fts5(
  title, kind, language, content, conversation_id, message_id,
  provider, tokenize='unicode61'
);
