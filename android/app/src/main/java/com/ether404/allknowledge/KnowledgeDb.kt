package com.ether404.allknowledge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues

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
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { }

    fun ensureProvider(provider: String) {
        val cv = ContentValues().apply { put("key", provider); put("name", provider.replaceFirstChar { it.uppercase() }) }
        writableDatabase.insertWithOnConflict("providers", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun upsertConversation(provider: String, cid: String, title: String, created: String?, updated: String?, metadata: String? = null) {
        val cv = ContentValues().apply { put("provider", provider); put("conversation_id", cid); put("title", title); put("created_at", created); put("updated_at", updated); put("metadata_json", metadata) }
        writableDatabase.insertWithOnConflict("conversations", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.update("conversations", cv, "provider=? AND conversation_id=?", arrayOf(provider, cid))
    }

    fun upsertMessage(provider: String, mid: String, cid: String, role: String, content: String, created: String?, parent: String?) {
        val db = writableDatabase
        val cv = ContentValues().apply { put("provider", provider); put("message_id", mid); put("conversation_id", cid); put("role", role); put("content", content); put("created_at", created); put("parent_message_id", parent) }
        db.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        val c = db.rawQuery("SELECT id FROM messages WHERE provider=? AND message_id=?", arrayOf(provider, mid))
        if (c.moveToFirst()) {
            val id = c.getLong(0); db.delete("message_fts", "rowid=?", arrayOf(id.toString()))
            val f = ContentValues().apply { put("rowid", id); put("title", conversationTitle(provider, cid)); put("role", role); put("content", content); put("conversation_id", cid); put("message_id", mid); put("provider", provider) }
            db.insert("message_fts", null, f)
        }
        c.close()
    }

    fun upsertArtifact(provider: String, aid: String, cid: String?, mid: String?, title: String, kind: String, language: String, content: String) {
        val db = writableDatabase
        val cv = ContentValues().apply { put("provider", provider); put("artifact_id", aid); put("conversation_id", cid); put("message_id", mid); put("title", title); put("kind", kind); put("language", language); put("content", content) }
        db.insertWithOnConflict("artifacts", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        val c = db.rawQuery("SELECT id FROM artifacts WHERE provider=? AND artifact_id=?", arrayOf(provider, aid))
        if (c.moveToFirst()) {
            val id = c.getLong(0); db.delete("artifact_fts", "rowid=?", arrayOf(id.toString()))
            val f = ContentValues().apply { put("rowid", id); put("title", title); put("kind", kind); put("language", language); put("content", content); put("conversation_id", cid); put("message_id", mid); put("provider", provider) }
            db.insert("artifact_fts", null, f)
        }
        c.close()
    }

    private fun conversationTitle(provider: String, cid: String): String {
        val c = readableDatabase.rawQuery("SELECT COALESCE(title,'Untitled') FROM conversations WHERE provider=? AND conversation_id=?", arrayOf(provider, cid))
        val s = if (c.moveToFirst()) c.getString(0) else "Untitled"; c.close(); return s
    }

    fun stats(): LongArray {
        fun n(table: String): Long = readableDatabase.rawQuery("SELECT count(*) FROM $table", null).use { if (it.moveToFirst()) it.getLong(0) else 0 }
        return longArrayOf(n("conversations"), n("messages"), n("artifacts"))
    }

    data class Result(val provider: String, val conversationId: String, val messageId: String, val role: String, val title: String, val snippet: String)

    fun search(q: String, limit: Int = 60): List<Result> {
        val needle = q.trim()
        if (needle.isBlank()) return emptyList()
        val out = mutableListOf<Result>()
        val db = readableDatabase
        val ftsQuery = needle.replace(Regex("[^A-Za-z0-9_.*-]+"), " ").trim()
        if (ftsQuery.isNotBlank()) {
            try {
                val sql = "SELECT provider,conversation_id,message_id,role,title,snippet(message_fts,2,'<b>','</b>','…',18) FROM message_fts WHERE message_fts MATCH ? LIMIT ?"
                db.rawQuery(sql, arrayOf(ftsQuery, limit.toString())).use { c ->
                    while (c.moveToNext()) out += Result(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4) ?: "Untitled", c.getString(5) ?: "")
                }
            } catch (_: Exception) { }
        }
        if (out.size < limit) {
            val like = "%${needle.replace("%", "\\%").replace("_", "\\_")}%"
            val seen = out.map { "${it.provider}|${it.messageId}" }.toMutableSet()
            val sql = "SELECT m.provider,m.conversation_id,m.message_id,COALESCE(m.role,'message'),COALESCE(c.title,'Untitled'),substr(m.content,1,220) FROM messages m LEFT JOIN conversations c ON c.provider=m.provider AND c.conversation_id=m.conversation_id WHERE (m.content LIKE ? ESCAPE '\\' OR c.title LIKE ? ESCAPE '\\' OR m.message_id LIKE ? ESCAPE '\\' OR m.conversation_id LIKE ? ESCAPE '\\') ORDER BY m.id DESC LIMIT ?"
            db.rawQuery(sql, arrayOf(like, like, like, like, limit.toString())).use { c ->
                while (c.moveToNext()) {
                    val r = Result(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5) ?: "")
                    if (seen.add("${r.provider}|${r.messageId}")) out += r
                }
            }
        }
        if (out.size < limit) {
            val like = "%${needle.replace("%", "\\%").replace("_", "\\_")}%"
            val seen = out.map { "${it.provider}|${it.messageId}|${it.title}" }.toMutableSet()
            val remaining = limit - out.size
            val sql = "SELECT a.provider,COALESCE(a.conversation_id,''),COALESCE(a.message_id,''),COALESCE(a.kind,'artifact'),COALESCE(a.title,'Untitled artifact'),substr(a.content,1,220) FROM artifacts a WHERE (a.title LIKE ? ESCAPE '\\' OR a.content LIKE ? ESCAPE '\\' OR a.kind LIKE ? ESCAPE '\\' OR a.language LIKE ? ESCAPE '\\') ORDER BY a.id DESC LIMIT ?"
            db.rawQuery(sql, arrayOf(like, like, like, like, remaining.toString())).use { c ->
                while (c.moveToNext()) {
                    val r = Result(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5) ?: "")
                    if (seen.add("${r.provider}|${r.messageId}|${r.title}")) out += r
                }
            }
        }
        return out.take(limit)
    }

    data class Msg(val role: String, val content: String, val created: String?)
    fun conversation(provider: String, cid: String): Pair<String, List<Msg>> {
        var title = "Untitled"; val msgs = mutableListOf<Msg>()
        readableDatabase.rawQuery("SELECT title FROM conversations WHERE provider=? AND conversation_id=?", arrayOf(provider, cid)).use { if (it.moveToFirst()) title = it.getString(0) ?: title }
        readableDatabase.rawQuery("SELECT role,content,created_at FROM messages WHERE provider=? AND conversation_id=? ORDER BY CASE WHEN created_at IS NULL THEN 1 ELSE 0 END, created_at,id", arrayOf(provider, cid)).use { c -> while (c.moveToNext()) msgs += Msg(c.getString(0) ?: "unknown", c.getString(1) ?: "", c.getString(2)) }
        return title to msgs
    }
}
