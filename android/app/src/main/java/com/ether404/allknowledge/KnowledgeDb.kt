package com.ether404.allknowledge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class KnowledgeDb(context: Context) : SQLiteOpenHelper(context, "knowledge.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE providers(id INTEGER PRIMARY KEY, key TEXT UNIQUE NOT NULL, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE conversations(id INTEGER PRIMARY KEY AUTOINCREMENT, provider TEXT NOT NULL, conversation_id TEXT NOT NULL, title TEXT, created_at TEXT, updated_at TEXT, metadata_json TEXT, UNIQUE(provider, conversation_id))")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, provider TEXT NOT NULL, message_id TEXT NOT NULL, conversation_id TEXT NOT NULL, role TEXT, content TEXT NOT NULL DEFAULT '', created_at TEXT, parent_message_id TEXT, metadata_json TEXT, UNIQUE(provider, message_id))")
        db.execSQL("CREATE TABLE artifacts(id INTEGER PRIMARY KEY AUTOINCREMENT, provider TEXT NOT NULL, artifact_id TEXT NOT NULL, conversation_id TEXT, message_id TEXT, title TEXT, kind TEXT, language TEXT, content TEXT NOT NULL DEFAULT '', metadata_json TEXT, UNIQUE(provider, artifact_id))")
        db.execSQL("CREATE INDEX idx_messages_conversation ON messages(provider, conversation_id)")
        db.execSQL("CREATE INDEX idx_conversations_provider ON conversations(provider)")
        db.execSQL("CREATE INDEX idx_messages_provider ON messages(provider)")
        db.execSQL("CREATE INDEX idx_artifacts_provider ON artifacts(provider)")
        db.execSQL("CREATE VIRTUAL TABLE message_fts USING fts4(title, role, content, conversation_id, message_id, provider)")
        db.execSQL("CREATE VIRTUAL TABLE artifact_fts USING fts4(title, kind, language, content, conversation_id, message_id, provider)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun ensureProvider(provider: String) {
        val cv = ContentValues().apply {
            put("key", provider)
            put("name", provider.replaceFirstChar { it.uppercase() })
        }
        writableDatabase.insertWithOnConflict("providers", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun upsertConversation(provider: String, cid: String, title: String, created: String?, updated: String?, metadata: String? = null) {
        val cv = ContentValues().apply {
            put("provider", provider)
            put("conversation_id", cid)
            put("title", title)
            put("created_at", created)
            put("updated_at", updated)
            put("metadata_json", metadata)
        }
        writableDatabase.insertWithOnConflict("conversations", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.update("conversations", cv, "provider=? AND conversation_id=?", arrayOf(provider, cid))
    }

    fun upsertMessage(provider: String, mid: String, cid: String, role: String, content: String, created: String?, parent: String?) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("provider", provider)
            put("message_id", mid)
            put("conversation_id", cid)
            put("role", role)
            put("content", content)
            put("created_at", created)
            put("parent_message_id", parent)
        }
        db.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        db.update("messages", cv, "provider=? AND message_id=?", arrayOf(provider, mid))
        db.rawQuery("SELECT id FROM messages WHERE provider=? AND message_id=?", arrayOf(provider, mid)).use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                db.delete("message_fts", "rowid=?", arrayOf(id.toString()))
                db.insert("message_fts", null, ContentValues().apply {
                    put("rowid", id)
                    put("title", conversationTitle(provider, cid))
                    put("role", role)
                    put("content", content)
                    put("conversation_id", cid)
                    put("message_id", mid)
                    put("provider", provider)
                })
            }
        }
    }

    fun upsertArtifact(provider: String, aid: String, cid: String?, mid: String?, title: String, kind: String, language: String, content: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("provider", provider)
            put("artifact_id", aid)
            put("conversation_id", cid)
            put("message_id", mid)
            put("title", title)
            put("kind", kind)
            put("language", language)
            put("content", content)
        }
        db.insertWithOnConflict("artifacts", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        db.update("artifacts", cv, "provider=? AND artifact_id=?", arrayOf(provider, aid))
        db.rawQuery("SELECT id FROM artifacts WHERE provider=? AND artifact_id=?", arrayOf(provider, aid)).use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                db.delete("artifact_fts", "rowid=?", arrayOf(id.toString()))
                db.insert("artifact_fts", null, ContentValues().apply {
                    put("rowid", id)
                    put("title", title)
                    put("kind", kind)
                    put("language", language)
                    put("content", content)
                    put("conversation_id", cid)
                    put("message_id", mid)
                    put("provider", provider)
                })
            }
        }
    }

    private fun conversationTitle(provider: String, cid: String): String {
        return readableDatabase.rawQuery(
            "SELECT COALESCE(title,'Untitled') FROM conversations WHERE lower(provider)=lower(?) AND conversation_id=?",
            arrayOf(provider, cid)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else "Untitled" }
    }

    fun stats(): LongArray {
        fun count(table: String): Long = readableDatabase.rawQuery("SELECT count(*) FROM $table", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        return longArrayOf(count("conversations"), count("messages"), count("artifacts"))
    }

    data class Result(
        val provider: String,
        val conversationId: String,
        val messageId: String,
        val role: String,
        val title: String,
        val snippet: String
    )

    fun search(q: String, limit: Int = 5000): List<Result> {
        val needle = q.trim()
        if (needle.isBlank()) return emptyList()
        val db = readableDatabase
        val out = ArrayList<Result>(minOf(limit, 256))
        val seen = HashSet<String>()
        val like = "%${needle.replace("%", "\\%").replace("_", "\\_")}%"

        val titleSql = "SELECT lower(c.provider),c.conversation_id,'', 'conversation', COALESCE(c.title,'Untitled'), COALESCE(c.title,'') FROM conversations c WHERE c.title LIKE ? ESCAPE '\\' OR c.conversation_id LIKE ? ESCAPE '\\' ORDER BY COALESCE(c.updated_at,c.created_at) DESC,c.id DESC LIMIT ?"
        db.rawQuery(titleSql, arrayOf(like, like, limit.toString())).use { c ->
            while (c.moveToNext() && out.size < limit) {
                val r = Result(c.getString(0) ?: "", c.getString(1) ?: "", "", "conversation", c.getString(4) ?: "Untitled", c.getString(5) ?: "")
                val key = "${r.provider}|${r.conversationId}|conversation"
                if (seen.add(key)) out += r
            }
        }

        if (out.size < limit) {
            val sql = "SELECT lower(m.provider),m.conversation_id,m.message_id,COALESCE(m.role,'message'),COALESCE(c.title,'Untitled'),substr(m.content,1,320) FROM messages m LEFT JOIN conversations c ON lower(c.provider)=lower(m.provider) AND c.conversation_id=m.conversation_id WHERE m.content LIKE ? ESCAPE '\\' OR c.title LIKE ? ESCAPE '\\' OR m.message_id LIKE ? ESCAPE '\\' OR m.conversation_id LIKE ? ESCAPE '\\' ORDER BY m.id DESC LIMIT ?"
            db.rawQuery(sql, arrayOf(like, like, like, like, (limit - out.size).toString())).use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val r = Result(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "message", c.getString(4) ?: "Untitled", c.getString(5) ?: "")
                    if (seen.add("${r.provider}|${r.messageId}")) out += r
                }
            }
        }

        if (out.size < limit) {
            val sql = "SELECT lower(a.provider),COALESCE(a.conversation_id,''),COALESCE(a.message_id,''),COALESCE(a.kind,'artifact'),COALESCE(a.title,'Untitled artifact'),substr(a.content,1,320) FROM artifacts a WHERE a.title LIKE ? ESCAPE '\\' OR a.content LIKE ? ESCAPE '\\' OR a.kind LIKE ? ESCAPE '\\' OR a.language LIKE ? ESCAPE '\\' ORDER BY a.id DESC LIMIT ?"
            db.rawQuery(sql, arrayOf(like, like, like, like, (limit - out.size).toString())).use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val r = Result(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "artifact", c.getString(4) ?: "Untitled artifact", c.getString(5) ?: "")
                    if (seen.add("${r.provider}|${r.messageId}|${r.title}")) out += r
                }
            }
        }

        return out
    }

    data class Msg(val role: String, val content: String, val created: String?)

    fun conversation(provider: String, cid: String): Pair<String, List<Msg>> {
        var title = "Untitled"
        val msgs = ArrayList<Msg>()
        readableDatabase.rawQuery(
            "SELECT title FROM conversations WHERE lower(provider)=lower(?) AND conversation_id=?",
            arrayOf(provider, cid)
        ).use { c -> if (c.moveToFirst()) title = c.getString(0) ?: title }
        readableDatabase.rawQuery(
            "SELECT role,content,created_at FROM messages WHERE lower(provider)=lower(?) AND conversation_id=? ORDER BY CASE WHEN created_at IS NULL THEN 1 ELSE 0 END,created_at,id",
            arrayOf(provider, cid)
        ).use { c -> while (c.moveToNext()) msgs += Msg(c.getString(0) ?: "unknown", c.getString(1) ?: "", c.getString(2)) }
        return title to msgs
    }
}
