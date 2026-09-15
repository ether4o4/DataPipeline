package com.ether404.allknowledge

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Offline export / file importer.
 * ChatGPT & Claude ZIP/JSON exports stay supported. Arbitrary TXT/HTML/PDF/ZIP
 * (and similar) files are best-effort converted into left/right message turns
 * under the local "file" provider. Never uploads user data.
 */
class ExportImporter(private val context: Context, private val db: KnowledgeDb) {
    data class ImportResult(val provider: String, val conversations: Int, val messages: Int, val artifacts: Int)

    fun importZip(uri: Uri, progress: (String) -> Unit = {}): ImportResult = importAny(uri, progress)

    fun importAny(uri: Uri, progress: (String) -> Unit = {}): ImportResult {
        val displayName = displayName(uri)
        val lowerName = displayName.lowercase()
        progress("Opening $displayName…")

        context.contentResolver.openInputStream(uri)?.use { raw ->
            val input = BufferedInputStream(raw, 64 * 1024)
            input.mark(16)
            val header = ByteArray(8)
            val n = input.read(header)
            input.reset()

            val isZip = n >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
            val isPdf = n >= 5 && header[0] == '%'.code.toByte() && header[1] == 'P'.code.toByte() &&
                header[2] == 'D'.code.toByte() && header[3] == 'F'.code.toByte()

            // Prefer ChatGPT/Claude structured imports when ZIP or JSON looks like an AI export.
            if (isZip) {
                val ai = tryImportAiZip(input, progress)
                if (ai.conversations > 0) return ai
                // Rewind not possible on ContentResolver streams after full consume — re-open for file ZIP.
                return context.contentResolver.openInputStream(uri)?.use { again ->
                    importFileZip(BufferedInputStream(again, 64 * 1024), displayName, progress)
                } ?: error("Could not reopen ZIP")
            }

            if (looksLikeJson(lowerName, header, n)) {
                val ai = tryImportAiJson(input, progress)
                if (ai.conversations > 0) return ai
                return context.contentResolver.openInputStream(uri)?.use { again ->
                    val text = readText(BufferedInputStream(again, 64 * 1024))
                    importPlainAsThread(displayName, text, "json", progress)
                } ?: error("Could not reopen JSON")
            }

            if (isPdf || lowerName.endsWith(".pdf")) {
                val bytes = readBytes(input)
                val text = PdfText.extract(bytes)
                if (text.isBlank()) {
                    throw IllegalArgumentException(
                        "PDF text could not be extracted (encrypted, scanned, or complex layout). " +
                            "Try a TXT/HTML export of the same document."
                    )
                }
                return importPlainAsThread(displayName, text, "pdf", progress)
            }

            val text = readText(input)
            val kind = when {
                lowerName.endsWith(".html") || lowerName.endsWith(".htm") || text.trimStart().startsWith("<") -> "html"
                lowerName.endsWith(".md") || lowerName.endsWith(".markdown") -> "md"
                lowerName.endsWith(".csv") -> "csv"
                lowerName.endsWith(".log") -> "log"
                else -> "txt"
            }
            val body = if (kind == "html") HtmlText.extract(text) else text
            return importPlainAsThread(displayName, body, kind, progress)
        } ?: error("Could not open selected file")
    }

    private fun tryImportAiZip(input: InputStream, progress: (String) -> Unit): ImportResult {
        var chatgptConv = 0; var chatgptMsg = 0; var claudeConv = 0; var claudeMsg = 0; var artifacts = 0
        try {
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
        } catch (_: Exception) {
            return ImportResult("", 0, 0, 0)
        }
        val providers = mutableListOf<String>()
        if (chatgptConv > 0) providers += "ChatGPT"
        if (claudeConv > 0) providers += "Claude"
        if (providers.isEmpty()) return ImportResult("", 0, 0, 0)
        return ImportResult(providers.joinToString(" + "), chatgptConv + claudeConv, chatgptMsg + claudeMsg, artifacts)
    }

    private fun tryImportAiJson(input: InputStream, progress: (String) -> Unit): ImportResult {
        return try {
            val r = importJsonStream(input, progress)
            val chatgptConv = r.first.first; val chatgptMsg = r.first.second
            val claudeConv = r.second.first; val claudeMsg = r.second.second; val artifacts = r.third
            val providers = mutableListOf<String>()
            if (chatgptConv > 0) providers += "ChatGPT"
            if (claudeConv > 0) providers += "Claude"
            if (providers.isEmpty()) ImportResult("", 0, 0, 0)
            else ImportResult(providers.joinToString(" + "), chatgptConv + claudeConv, chatgptMsg + claudeMsg, artifacts)
        } catch (_: Exception) {
            ImportResult("", 0, 0, 0)
        }
    }

    private fun importFileZip(input: InputStream, zipName: String, progress: (String) -> Unit): ImportResult {
        var conversations = 0; var messages = 0; var artifacts = 0
        val providers = linkedSetOf<String>()
        ZipInputStream(input, Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/').ifBlank { entry.name }
                val lower = name.lowercase()
                if (lower.endsWith(".json")) {
                    val temp = File.createTempFile("datapipeline-", ".json", context.cacheDir)
                    try {
                        FileOutputStream(temp).use { out -> zip.copyTo(out) }
                        progress("Reading ${entry.name}…")
                        FileInputStream(temp).use { jsonInput ->
                            val r = importJsonStream(jsonInput, progress)
                            if (r.first.first > 0) { providers += "ChatGPT"; conversations += r.first.first; messages += r.first.second }
                            if (r.second.first > 0) { providers += "Claude"; conversations += r.second.first; messages += r.second.second }
                            artifacts += r.third
                        }
                    } finally { temp.delete() }
                    continue
                }
                val supported = lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".html") ||
                    lower.endsWith(".htm") || lower.endsWith(".log") || lower.endsWith(".csv") ||
                    lower.endsWith(".pdf") || lower.endsWith(".markdown")
                if (!supported) continue
                progress("Importing ${entry.name}…")
                val bytes = zip.readBytes()
                val text = when {
                    lower.endsWith(".pdf") -> PdfText.extract(bytes)
                    lower.endsWith(".html") || lower.endsWith(".htm") -> HtmlText.extract(String(bytes, detectCharset(bytes)))
                    else -> String(bytes, detectCharset(bytes))
                }
                if (text.isBlank()) continue
                val r = importPlainAsThread("$zipName/$name", text, extKind(lower), progress)
                conversations += r.conversations; messages += r.messages; artifacts += r.artifacts
                if (r.conversations > 0) providers += "File"
            }
        }
        if (conversations == 0) {
            throw IllegalArgumentException("No supported ChatGPT/Claude JSON or TXT/HTML/PDF files found in this ZIP.")
        }
        return ImportResult(providers.joinToString(" + ").ifBlank { "File" }, conversations, messages, artifacts)
    }

    private fun importPlainAsThread(title: String, text: String, kind: String, progress: (String) -> Unit): ImportResult {
        val cleaned = text.replace("\u0000", "").trim()
        if (cleaned.isBlank()) throw IllegalArgumentException("File contained no readable text.")
        db.ensureProvider("file")
        val cid = "file-" + UUID.randomUUID().toString()
        val display = title.ifBlank { "Imported $kind" }.take(180)
        progress("Formatting $display as thread…")
        db.upsertConversation("file", cid, display, null, null, """{"source":"$kind"}""")
        val turns = ThreadSplitter.split(cleaned)
        var n = 0
        for ((idx, turn) in turns.withIndex()) {
            val role = turn.first
            val body = turn.second.trim()
            if (body.isBlank()) continue
            val mid = "$cid-$idx"
            db.upsertMessage("file", mid, cid, role, body, null, if (idx == 0) null else "$cid-${idx - 1}")
            n++
        }
        if (n == 0) throw IllegalArgumentException("Could not split file into message turns.")
        progress("Imported $n turns from $display")
        return ImportResult("File", 1, n, 0)
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
        c.has("chat_messages") || c.has("messages") && (c.has("uuid") || c.has("name") || c.has("title")) -> {
            val r = importClaude(c, progress); ConversationImport("claude", r.first, r.second)
        }
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
            for (i in 0 until content.length()) {
                val b = content.optJSONObject(i)
                if (b != null) out.append(b.optString("text", "")) else out.append(content.optString(i, ""))
                out.append('\n')
            }
            return out.toString().trim()
        }
        return ""
    }

    private fun extractCode(provider: String, cid: String, mid: String, title: String, content: String): Int {
        val regex = Regex("```([\\w+#.-]*)\\s*\\n([\\s\\S]*?)```"); var n = 0
        for (b in regex.findAll(content)) {
            val body = b.groupValues[2].trim()
            if (body.isNotEmpty()) {
                db.upsertArtifact(provider, "$mid:$n", cid, mid, "Code from $title", "code", b.groupValues[1].ifBlank { "text" }, body)
                n++
            }
        }
        return n
    }

    private fun value(v: Any?): String? = when (v) { null, JSONObject.NULL -> null; else -> v.toString() }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = c.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "imported-file"
    }

    private fun looksLikeJson(name: String, header: ByteArray, n: Int): Boolean {
        if (name.endsWith(".json")) return true
        if (n <= 0) return false
        var i = 0
        while (i < n && header[i].toInt().toChar().isWhitespace()) i++
        if (i >= n) return false
        val c = header[i].toInt().toChar()
        return c == '{' || c == '['
    }

    private fun readBytes(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val c = input.read(buf)
            if (c <= 0) break
            out.write(buf, 0, c)
        }
        return out.toByteArray()
    }

    private fun readText(input: InputStream): String {
        val bytes = readBytes(input)
        return String(bytes, detectCharset(bytes))
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return StandardCharsets.UTF_8
        }
        return StandardCharsets.UTF_8
    }

    private fun extKind(lower: String): String = when {
        lower.endsWith(".html") || lower.endsWith(".htm") -> "html"
        lower.endsWith(".pdf") -> "pdf"
        lower.endsWith(".md") || lower.endsWith(".markdown") -> "md"
        lower.endsWith(".csv") -> "csv"
        lower.endsWith(".log") -> "log"
        else -> "txt"
    }

    /** Heuristic left/right turn splitter for arbitrary prose. */
    object ThreadSplitter {
        private val label = Regex(
            """(?im)^(?:\s*)(user|you|human|me|sender|from|assistant|ai|bot|chatgpt|claude|system|agent|support|other|them)(?:\s*[:\-]\s*|\s*$)"""
        )
        private val chatLine = Regex(
            """(?im)^(?:\[\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM)?\]\s*)?([^:]{1,40}):\s+(.+)$"""
        )

        fun split(text: String): List<Pair<String, String>> {
            val labeled = splitByLabels(text)
            if (labeled.size >= 2) return labeled
            val chat = splitChatLines(text)
            if (chat.size >= 2) return chat
            return splitParagraphs(text)
        }

        private fun splitByLabels(text: String): List<Pair<String, String>> {
            val matches = label.findAll(text).toList()
            if (matches.isEmpty()) return emptyList()
            val out = ArrayList<Pair<String, String>>()
            for (i in matches.indices) {
                val m = matches[i]
                val start = m.range.last + 1
                val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
                val body = text.substring(start, end).trim()
                if (body.isBlank()) continue
                out += roleFor(m.groupValues[1]) to body
            }
            return out
        }

        private fun splitChatLines(text: String): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            var currentRole: String? = null
            val buf = StringBuilder()
            fun flush() {
                val body = buf.toString().trim()
                if (currentRole != null && body.isNotEmpty()) out += currentRole!! to body
                buf.setLength(0)
            }
            for (line in text.lineSequence()) {
                val m = chatLine.matchEntire(line.trim())
                if (m != null) {
                    flush()
                    currentRole = roleFor(m.groupValues[1])
                    buf.append(m.groupValues[2])
                } else if (currentRole != null) {
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(line)
                }
            }
            flush()
            return out
        }

        private fun splitParagraphs(text: String): List<Pair<String, String>> {
            val blocks = text.split(Regex("\\n\\s*\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
            if (blocks.isEmpty()) return listOf("assistant" to text.trim())
            if (blocks.size == 1) {
                // Single blob: alternate by ~4 paragraphs / line groups for readability
                val lines = blocks[0].split('\n').map { it.trimEnd() }
                if (lines.size <= 6) return listOf("assistant" to blocks[0])
                val out = ArrayList<Pair<String, String>>()
                var i = 0
                var turn = 0
                while (i < lines.size) {
                    val chunk = lines.subList(i, minOf(i + 4, lines.size)).joinToString("\n").trim()
                    if (chunk.isNotEmpty()) {
                        out += (if (turn % 2 == 0) "user" else "assistant") to chunk
                        turn++
                    }
                    i += 4
                }
                return if (out.isEmpty()) listOf("assistant" to blocks[0]) else out
            }
            return blocks.mapIndexed { idx, b -> (if (idx % 2 == 0) "user" else "assistant") to b }
        }

        private fun roleFor(raw: String): String {
            val s = raw.trim().lowercase()
            return when {
                s in setOf("user", "you", "human", "me", "sender", "from") -> "user"
                s in setOf("assistant", "ai", "bot", "chatgpt", "claude", "agent", "support", "system") -> "assistant"
                s in setOf("other", "them") -> "assistant"
                else -> if (s.hashCode() % 2 == 0) "user" else "assistant"
            }
        }
    }

    /** Best-effort HTML → visible text / message-like blocks. */
    object HtmlText {
        fun extract(html: String): String {
            var s = html
            // Prefer explicit chat bubbles when present
            val bubble = Regex("""(?is)<(?:div|li|article|section)[^>]*(?:class|data-role|data-author)=["'][^"']*(?:message|bubble|chat|user|assistant|human)[^"']*["'][^>]*>(.*?)</(?:div|li|article|section)>""")
            val bubbles = bubble.findAll(s).map { stripTags(it.groupValues[1]).trim() }.filter { it.isNotEmpty() }.toList()
            if (bubbles.size >= 2) {
                return bubbles.mapIndexed { i, b ->
                    val role = if (i % 2 == 0) "User" else "Assistant"
                    "$role:\n$b"
                }.joinToString("\n\n")
            }
            s = s.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            s = s.replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            s = s.replace(Regex("(?is)<br\\s*/?>"), "\n")
            s = s.replace(Regex("(?is)</p\\s*>"), "\n\n")
            s = s.replace(Regex("(?is)</div\\s*>"), "\n")
            s = s.replace(Regex("(?is)</h[1-6]\\s*>"), "\n\n")
            s = stripTags(s)
            return s.replace(Regex("[ \\t]+"), " ")
                .replace(Regex(" *\\n *"), "\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }

        private fun stripTags(s: String): String =
            s.replace(Regex("(?is)<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace(Regex("&#(\\d+);")) { m ->
                    m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: " "
                }
    }

    /**
     * Lightweight PDF text extraction (no third-party dependency).
     * Handles simple FlateDecode text streams; encrypted / scanned / complex PDFs may yield little text.
     */
    object PdfText {
        fun extract(bytes: ByteArray): String {
            if (bytes.size < 8) return ""
            val latin = String(bytes, Charsets.ISO_8859_1)
            val out = StringBuilder()
            val streamRe = Regex("""(?s)stream\r?\n(.*?)\r?\nendstream""")
            for (m in streamRe.findAll(latin)) {
                val raw = m.groupValues[1].toByteArray(Charsets.ISO_8859_1)
                val decoded = tryInflate(raw) ?: raw
                val content = String(decoded, Charsets.ISO_8859_1)
                extractOperators(content, out)
            }
            // Fallback: literal parentheses strings near Tj
            if (out.isBlank()) {
                val tj = Regex("""\((?:\\.|[^\\)])*\)\s*Tj""")
                for (m in tj.findAll(latin)) {
                    out.append(unescapePdfString(m.value.substringBeforeLast("Tj").trim().removeSurrounding("(", ")"))).append(' ')
                }
            }
            return out.toString()
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex(" *\\n *"), "\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }

        private fun tryInflate(raw: ByteArray): ByteArray? {
            fun inflate(data: ByteArray, nowrap: Boolean): ByteArray? = try {
                val inflater = Inflater(nowrap)
                inflater.setInput(data)
                val bout = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0) {
                        if (inflater.needsInput()) break
                        if (inflater.needsDictionary()) break
                    } else bout.write(buf, 0, n)
                    if (bout.size() > 8_000_000) break
                }
                inflater.end()
                if (bout.size() == 0) null else bout.toByteArray()
            } catch (_: Exception) { null }

            // Strip possible leading whitespace after stream newline already handled
            inflate(raw, false)?.let { return it }
            // Skip zlib header variants / try raw deflate
            if (raw.size > 2) inflate(raw.copyOfRange(2, raw.size), true)?.let { return it }
            return inflate(raw, true)
        }

        private fun extractOperators(content: String, out: StringBuilder) {
            val token = Regex("""\((?:\\.|[^\\)])*\)|\[(?:[^\[\]]|\[(?:[^\[\]])*\])*\]|Tj|TJ|T\*|'\s|\"\s""")
            var pending: String? = null
            for (m in token.findAll(content)) {
                val t = m.value
                when {
                    t == "Tj" || t == "'" || t.startsWith("\"") -> {
                        pending?.let { out.append(it); out.append(' ') }
                        pending = null
                        if (t == "T*" || t == "'") out.append('\n')
                    }
                    t == "TJ" -> {
                        pending?.let { out.append(it); out.append(' ') }
                        pending = null
                    }
                    t == "T*" -> out.append('\n')
                    t.startsWith("(") -> pending = unescapePdfString(t.removeSurrounding("(", ")"))
                    t.startsWith("[") -> {
                        val parts = Regex("""\((?:\\.|[^\\)])*\)""").findAll(t)
                        pending = parts.joinToString("") { unescapePdfString(it.value.removeSurrounding("(", ")")) }
                    }
                }
            }
        }

        private fun unescapePdfString(s: String): String {
            val b = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        'n' -> { b.append('\n'); i += 2 }
                        'r' -> { b.append('\r'); i += 2 }
                        't' -> { b.append('\t'); i += 2 }
                        'b' -> { b.append('\b'); i += 2 }
                        'f' -> { b.append('\u000C'); i += 2 }
                        '(', ')', '\\' -> { b.append(n); i += 2 }
                        in '0'..'7' -> {
                            var oct = "" + n; i += 2
                            var k = 0
                            while (k < 2 && i < s.length && s[i] in '0'..'7') { oct += s[i]; i++; k++ }
                            b.append(oct.toInt(8).toChar())
                        }
                        else -> { b.append(n); i += 2 }
                    }
                } else { b.append(c); i++ }
            }
            return b.toString()
        }
    }
}
