package com.ether404.allknowledge

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import kotlin.math.min

class ExportImporter(private val context: Context, private val db: KnowledgeDb) {
    data class ImportResult(val provider: String, val conversations: Int, val messages: Int, val artifacts: Int)

    fun importZip(uri: Uri, progress: (String) -> Unit = {}): ImportResult {
        var chatgptConv = 0; var chatgptMsg = 0; var claudeConv = 0; var claudeMsg = 0; var artifacts = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory || !e.name.lowercase().endsWith(".json")) continue
                    val bytes = zip.readBytes()
                    if (bytes.size > 50 * 1024 * 1024) continue
                    val text = bytes.toString(Charsets.UTF_8)
                    try {
                        val parsed = JSONObject(text)
                        if (isClaudeManifest(parsed, e.name)) { progress("Reading Claude export…"); continue }
                        val arr = JSONArray(text)
                        if (arr.length() == 0) continue
                        val first = arr.optJSONObject(0) ?: continue
                        if (first.has("mapping")) {
                            val r = importChatGpt(arr, progress); chatgptConv += r.first; chatgptMsg += r.second; artifacts += r.third
                        } else if (first.has("chat_messages")) {
                            val r = importClaude(arr, progress); claudeConv += r.first; claudeMsg += r.second; artifacts += r.third
                        }
                    } catch (_: Exception) { }
                }
            }
        } ?: error("Could not open selected file")
        return when {
            chatgptConv + chatgptMsg > 0 -> ImportResult("ChatGPT", chatgptConv, chatgptMsg, artifacts)
            claudeConv + claudeMsg > 0 -> ImportResult("Claude", claudeConv, claudeMsg, artifacts)
            else -> throw IllegalArgumentException("No supported ChatGPT or Claude conversation data was found in this ZIP.")
        }
    }

    private fun isClaudeManifest(o: JSONObject, name: String): Boolean = name.contains("manifest", true) || o.has("export_id") || o.has("export_type")

    private fun importChatGpt(arr: JSONArray, progress: (String) -> Unit): Triple<Int, Int, Int> {
        db.ensureProvider("chatgpt"); var convs = 0; var msgs = 0; var arts = 0
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val cid = c.optString("conversation_id", c.optString("id", "")); if (cid.isBlank()) continue
            val title = c.optString("title", "Untitled")
            db.upsertConversation("chatgpt", cid, title, iso(c.opt("create_time")), iso(c.opt("update_time")))
            convs++
            val mapping = c.optJSONObject("mapping") ?: continue
            val nodes = mutableListOf<JSONObject>()
            val keys = mapping.keys(); while (keys.hasNext()) { mapping.optJSONObject(keys.next())?.let(nodes::add) }
            nodes.sortBy { it.optJSONObject("message")?.optDouble("create_time", Double.MAX_VALUE) ?: Double.MAX_VALUE }
            for (node in nodes) {
                val m = node.optJSONObject("message") ?: continue
                val mid = m.optString("id", node.optString("id", "")); if (mid.isBlank()) continue
                val author = m.optJSONObject("author")?.optString("role", "unknown") ?: "unknown"
                if (author == "system") continue
                val content = chatGptText(m.optJSONObject("content"))
                db.upsertMessage("chatgpt", mid, cid, author, content, iso(m.opt("create_time")), m.optString("parent", null))
                msgs++
                val blocks = Regex("```([\\w+#.-]*)\\n([\\s\\S]*?)```").findAll(content)
                var n = 0
                for (b in blocks) { val body = b.groupValues[2].trim(); if (body.isNotEmpty()) { db.upsertArtifact("chatgpt", "$mid:$n", cid, mid, "Code from $title", "code", b.groupValues[1].ifBlank { "text" }, body); arts++; n++ } }
            }
            if (i % 25 == 0) progress("ChatGPT: ${i + 1}/${arr.length()} conversations")
        }
        return Triple(convs, msgs, arts)
    }

    private fun chatGptText(content: JSONObject?): String {
        if (content == null) return ""
        val parts = content.optJSONArray("parts") ?: return content.optString("text", "")
        val out = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.opt(i)
            if (p is String) out.append(p).append('\n')
            else if (p is JSONObject) out.append(p.optString("text", "")).append('\n')
        }
        return out.toString().trim()
    }

    private fun importClaude(arr: JSONArray, progress: (String) -> Unit): Triple<Int, Int, Int> {
        db.ensureProvider("claude"); var convs = 0; var msgs = 0; var arts = 0
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val cid = c.optString("uuid", c.optString("id", "")); if (cid.isBlank()) continue
            val title = c.optString("name", c.optString("title", "Untitled"))
            db.upsertConversation("claude", cid, title, c.optString("created_at", null), c.optString("updated_at", null))
            convs++
            val ma = c.optJSONArray("chat_messages") ?: JSONArray()
            for (j in 0 until ma.length()) {
                val m = ma.optJSONObject(j) ?: continue
                val mid = m.optString("uuid", m.optString("id", "")); if (mid.isBlank()) continue
                val sender = m.optString("sender", m.optString("role", "unknown"))
                val role = when (sender.lowercase()) { "human", "user" -> "user"; "assistant" -> "assistant"; else -> sender }
                val content = claudeText(m)
                db.upsertMessage("claude", mid, cid, role, content, m.optString("created_at", null), m.optString("parent_message_uuid", null))
                msgs++
                val blocks = Regex("```([\\w+#.-]*)\\n([\\s\\S]*?)```").findAll(content)
                var n = 0
                for (b in blocks) { val body = b.groupValues[2].trim(); if (body.isNotEmpty()) { db.upsertArtifact("claude", "$mid:$n", cid, mid, "Code from $title", "code", b.groupValues[1].ifBlank { "text" }, body); arts++; n++ } }
            }
            if (i % 25 == 0) progress("Claude: ${i + 1}/${arr.length()} conversations")
        }
        return Triple(convs, msgs, arts)
    }

    private fun claudeText(m: JSONObject): String {
        val text = m.optString("text", ""); if (text.isNotEmpty()) return text
        val content = m.opt("content")
        if (content is String) return content
        if (content is JSONArray) {
            val out = StringBuilder()
            for (i in 0 until content.length()) {
                val b = content.optJSONObject(i); if (b != null) out.append(b.optString("text", "" )).append('\n') else out.append(content.optString(i, "")).append('\n')
            }
            return out.toString().trim()
        }
        return ""
    }

    private fun iso(v: Any?): String? = when (v) { null, JSONObject.NULL -> null; is Number -> v.toDouble().toString(); else -> v.toString() }
}
