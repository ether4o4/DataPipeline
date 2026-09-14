package com.ether404.allknowledge

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
 * Offline export importer.
 * Never materializes the whole ZIP/JSON export in memory. ZIP entries are copied to
 * temporary storage and parsed one conversation at a time with JsonReader.
 */
class ExportImporter(private val context: Context, private val db: KnowledgeDb) {
    data class ImportResult(val provider: String, val conversations: Int, val messages: Int, val artifacts: Int)

    fun importZip(uri: Uri, progress: (String) -> Unit = {}): ImportResult {
        var chatgptConv = 0; var chatgptMsg = 0; var claudeConv = 0; var claudeMsg = 0; var artifacts = 0
        context.contentResolver.openInputStream(uri)?.use { raw ->
            val input = BufferedInputStream(raw, 64 * 1024)
            input.mark(8)
            val header = ByteArray(4)
            val n = input.read(header)
            input.reset()
            val isZip = n >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
            if (!isZip) {
                val r = importJsonStream(input, progress)
                chatgptConv += r.first.first; chatgptMsg += r.first.second
                claudeConv += r.second.first; claudeMsg += r.second.second; artifacts += r.third
            } else {
                ZipInputStream(input, Charsets.UTF_8).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory || !entry.name.lowercase().endsWith(".json")) continue
                        val temp = File.createTempFile("datapipeline-", ".json", context.cacheDir)
                        try {
                            FileOutputStream(temp).use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count <= 0) break
                                    out.write(buffer, 0, count)
                                }
                            }
                            progress("Reading ${entry.name}…")
                            FileInputStream(temp).use { jsonInput ->
                                val r = importJsonStream(jsonInput, progress)
                                chatgptConv += r.first.first; chatgptMsg += r.first.second
                                claudeConv += r.second.first; claudeMsg += r.second.second; artifacts += r.third
                            }
                        } finally { temp.delete() }
                    }
                }
            }
        } ?: error("Could not open selected file")

        val providers = mutableListOf<String>()
        if (chatgptConv > 0) providers += "ChatGPT"
        if (claudeConv > 0) providers += "Claude"
        if (providers.isEmpty()) throw IllegalArgumentException("No supported ChatGPT or Claude conversation data was found in this file.")
        return ImportResult(providers.joinToString(" + "), chatgptConv + claudeConv, chatgptMsg + claudeMsg, artifacts)
    }

    private fun importJsonStream(input: InputStream, progress: (String) -> Unit): Triple<Pair<Int, Int>, Pair<Int, Int>, Int> {
        var chatgptConv = 0; var chatgptMsg = 0; var claudeConv = 0; var claudeMsg = 0; var artifacts = 0
        InputStreamReader(input, StandardCharsets.UTF_8).use { readerInput ->
            JsonReader(readerInput).use { reader ->
                when (reader.peek()) {
                    JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            val value = readJsonValue(reader)
                            if (value is JSONObject) {
                                val r = importConversation(value, progress)
                                when (r.provider) {
                                    "chatgpt" -> { chatgptConv++; chatgptMsg += r.messages; artifacts += r.artifacts }
                                    "claude" -> { claudeConv++; claudeMsg += r.messages; artifacts += r.artifacts }
                                }
                            }
                        }
                        reader.endArray()
                    }
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            if (name == "conversations" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    val value = readJsonValue(reader)
                                    if (value is JSONObject) {
                                        val r = importConversation(value, progress)
                                        when (r.provider) {
                                            "chatgpt" -> { chatgptConv++; chatgptMsg += r.messages; artifacts += r.artifacts }
                                            "claude" -> { claudeConv++; claudeMsg += r.messages; artifacts += r.artifacts }
                                        }
                                    }
                                }
                                reader.endArray()
                            } else reader.skipValue()
                        }
                        reader.endObject()
                    }
                    else -> Unit
                }
            }
        }
        return Triple(chatgptConv to chatgptMsg, claudeConv to claudeMsg, artifacts)
    }

    /** Only one conversation is retained in memory at a time. */
    private fun readJsonValue(reader: JsonReader): Any? = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val o = JSONObject(); reader.beginObject()
            while (reader.hasNext()) { val name = reader.nextName(); o.put(name, readJsonValue(reader) ?: JSONObject.NULL) }
            reader.endObject(); o
        }
        JsonToken.BEGIN_ARRAY -> {
            val a = JSONArray(); reader.beginArray()
            while (reader.hasNext()) a.put(readJsonValue(reader) ?: JSONObject.NULL)
            reader.endArray(); a
        }
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> {
            val s = reader.nextString(); s.toDoubleOrNull()?.let { d -> if (d.isFinite() && d % 1.0 == 0.0) d.toLong() else d } ?: s
        }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> { reader.nextNull(); null }
        else -> { reader.skipValue(); null }
    }

    private data class ConversationImport(val provider: String, val messages: Int, val artifacts: Int)

    private fun importConversation(c: JSONObject, progress: (String) -> Unit): ConversationImport = when {
        c.has("mapping") -> { val r = importChatGpt(c, progress); ConversationImport("chatgpt", r.first, r.second) }
        c.has("chat_messages") || c.has("messages") && (c.has("uuid") || c.has("name") || c.has("title")) -> { val r = importClaude(c, progress); ConversationImport("claude", r.first, r.second) }
        else -> ConversationImport("", 0, 0)
    }

    private fun importChatGpt(c: JSONObject, progress: (String) -> Unit): Pair<Int, Int> {
        db.ensureProvider("chatgpt")
        val cid = c.optString("conversation_id", c.optString("id", "")); if (cid.isBlank()) return 0 to 0
        val title = c.optString("title", "Untitled")
        db.upsertConversation("chatgpt", cid, title, value(c.opt("create_time")), value(c.opt("update_time")))
        val mapping = c.optJSONObject("mapping") ?: return 1 to 0
        var messages = 0; var artifacts = 0; val keys = mapping.keys()
        while (keys.hasNext()) {
            val node = mapping.optJSONObject(keys.next()) ?: continue
            val m = node.optJSONObject("message") ?: continue
            val mid = m.optString("id", node.optString("id", "")); if (mid.isBlank()) continue
            val role = m.optJSONObject("author")?.optString("role", "unknown") ?: "unknown"
            if (role == "system") continue
            val content = chatGptText(m.optJSONObject("content"))
            db.upsertMessage("chatgpt", mid, cid, role, content, value(m.opt("create_time")), m.optString("parent", null))
            messages++; artifacts += extractCode("chatgpt", cid, mid, title, content)
        }
        progress("ChatGPT: imported $messages messages")
        return messages to artifacts
    }

    private fun chatGptText(content: JSONObject?): String {
        if (content == null) return ""
        val parts = content.optJSONArray("parts") ?: return content.optString("text", "")
        val out = StringBuilder()
        for (i in 0 until parts.length()) when (val p = parts.opt(i)) {
            is String -> out.append(p).append('\n')
            is JSONObject -> out.append(p.optString("text", "")).append('\n')
        }
        return out.toString().trim()
    }

    private fun importClaude(c: JSONObject, progress: (String) -> Unit): Pair<Int, Int> {
        db.ensureProvider("claude")
        val cid = c.optString("uuid", c.optString("id", "")); if (cid.isBlank()) return 0 to 0
        val title = c.optString("name", c.optString("title", "Untitled"))
        db.upsertConversation("claude", cid, title, c.optString("created_at", null), c.optString("updated_at", null))
        val ma = c.optJSONArray("chat_messages") ?: c.optJSONArray("messages") ?: JSONArray()
        var messages = 0; var artifacts = 0
        for (j in 0 until ma.length()) {
            val m = ma.optJSONObject(j) ?: continue
            val mid = m.optString("uuid", m.optString("id", "")); if (mid.isBlank()) continue
            val sender = m.optString("sender", m.optString("role", "unknown"))
            val role = when (sender.lowercase()) { "human", "user" -> "user"; "assistant" -> "assistant"; else -> sender }
            val content = claudeText(m)
            db.upsertMessage("claude", mid, cid, role, content, m.optString("created_at", null), m.optString("parent_message_uuid", null))
            messages++; artifacts += extractCode("claude", cid, mid, title, content)
        }
        progress("Claude: imported $messages messages")
        return messages to artifacts
    }

    private fun claudeText(m: JSONObject): String {
        val text = m.optString("text", ""); if (text.isNotEmpty()) return text
        val content = m.opt("content")
        if (content is String) return content
        if (content is JSONArray) {
            val out = StringBuilder()
            for (i in 0 until content.length()) { val b = content.optJSONObject(i); if (b != null) out.append(b.optString("text", "")) else out.append(content.optString(i, "")); out.append('\n') }
            return out.toString().trim()
        }
        return ""
    }

    private fun extractCode(provider: String, cid: String, mid: String, title: String, content: String): Int {
        val regex = Regex("```([\\w+#.-]*)\\s*\\n([\\s\\S]*?)```"); var n = 0
        for (b in regex.findAll(content)) {
            val body = b.groupValues[2].trim()
            if (body.isNotEmpty()) { db.upsertArtifact(provider, "$mid:$n", cid, mid, "Code from $title", "code", b.groupValues[1].ifBlank { "text" }, body); n++ }
        }
        return n
    }

    private fun value(v: Any?): String? = when (v) { null, JSONObject.NULL -> null; else -> v.toString() }
}
