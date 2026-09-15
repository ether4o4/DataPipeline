package com.ether404.allknowledge

import android.app.Activity
import android.app.AlertDialog
import android.database.Cursor
import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var db: KnowledgeDb
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var search: EditText
    /** File imports land in this project (File by default). */
    private var importProjectKey: String = "file"

    private val bg = Color.rgb(244, 245, 242)
    private val paper = Color.rgb(249, 250, 247)
    private val ink = Color.rgb(24, 25, 24)
    private val muted = Color.rgb(105, 108, 104)
    private val faint = Color.rgb(205, 207, 202)
    private val active = Color.rgb(34, 35, 33)
    private val activeText = Color.WHITE
    private val highlight = Color.rgb(255, 236, 150)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        db = KnowledgeDb(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        showHome()
    }

    private fun baseRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bg)
        setPadding(12, 0, 12, 0)
        if (Build.VERSION.SDK_INT >= 30) {
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                v.setPadding(12, bars.top + 4, 12, bars.bottom + 4)
                insets
            }
        }
    }

    private fun txt(value: String, size: Float, color: Int = ink, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = true
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun line(): View = View(this).apply { setBackgroundColor(faint) }

    private fun outlinedButton(label: String, click: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 10f
        gravity = Gravity.CENTER
        setTextColor(ink)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(11, 0, 11, 0)
        background = GradientDrawable().apply { setColor(paper); setStroke(1, faint) }
        isClickable = true
        setOnClickListener { click() }
    }

    private fun showHome() {
        val root = baseRoot()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(txt("DATA PIPELINE", 22f, ink, true))
        brand.addView(txt("PERSONAL DATA ARCHIVE", 8f, muted, true))
        header.addView(brand, LinearLayout.LayoutParams(0, 56, 1f))
        header.addView(outlinedButton("IMPORT") { pickFile() }, LinearLayout.LayoutParams(74, 34))
        header.addView(Space(this), LinearLayout.LayoutParams(6, 1))
        header.addView(outlinedButton("EXPORT") { Toast.makeText(this, "Export is next.", Toast.LENGTH_SHORT).show() }, LinearLayout.LayoutParams(68, 34))
        root.addView(header)
        root.addView(line(), LinearLayout.LayoutParams(-1, 1))

        val searchRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 8, 0, 8) }
        search = EditText(this).apply {
            hint = "Search AI data"
            textSize = 14f
            setSingleLine(true)
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(12, 0, 12, 0)
            background = GradientDrawable().apply { setColor(paper); setStroke(1, faint) }
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, 42, 1f))
        search.setOnEditorActionListener { _, _, _ -> runSearch(search.text.toString()); true }
        root.addView(searchRow)

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 4, 10, 0) }
        rail.addView(txt("DATA", 8f, muted, true), LinearLayout.LayoutParams(-1, 26))
        railItem(rail, "AI DATA", "ACTIVE", true) { showAiHome() }
        railItem(rail, "SOCIAL MEDIA DATA", "SOON", false) { }
        railItem(rail, "FILE DATA", "SOON", false) { }
        railItem(rail, "MEDIA DATA", "SOON", false) { }
        rail.addView(Space(this), LinearLayout.LayoutParams(-1, 0, 1f))
        rail.addView(txt("LOCAL ARCHIVE", 8f, muted, true), LinearLayout.LayoutParams(-1, 28))
        body.addView(rail, LinearLayout.LayoutParams(0, -1, 0.34f))
        body.addView(line(), LinearLayout.LayoutParams(1, -1))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 0, 0, 0) }
        val contextHead = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 8) }
        contextHead.addView(txt("AI DATA", 19f, ink, true))
        contextHead.addView(txt("Conversations, sessions and extracted artifacts", 10f, muted))
        right.addView(contextHead)
        right.addView(line(), LinearLayout.LayoutParams(-1, 1))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(right, LinearLayout.LayoutParams(0, -1, 0.66f))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        status = txt("READY", 8f, muted, true)
        root.addView(status, LinearLayout.LayoutParams(-1, 24))
        setContentView(root)
        showAiHome()
    }

    private fun railItem(parent: LinearLayout, name: String, tag: String, enabled: Boolean, click: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 9, 6, 9)
            setBackgroundColor(if (enabled) active else Color.TRANSPARENT)
            isClickable = enabled
            setOnClickListener { if (enabled) click() }
        }
        row.addView(txt(name, 11f, if (enabled) activeText else muted, true))
        row.addView(txt(tag, 8f, if (enabled) Color.rgb(190, 193, 189) else muted, true))
        parent.addView(row, LinearLayout.LayoutParams(-1, 60).apply { bottomMargin = 5 })
    }

    private fun showAiHome() {
        importProjectKey = "file"
        content.removeAllViews()
        section("LIBRARY")
        folderRow("ALL CONVERSATIONS", "Every indexed AI conversation", db.stats()[0].toString()) { showAllResults() }
        folderRow("CHATGPT", "OpenAI conversations", "OPENAI") { searchProvider("chatgpt") }
        folderRow("CLAUDE", "Anthropic conversations", "ANTHROPIC") { searchProvider("claude") }
        folderRow("FILES", "Imported TXT · HTML · PDF · ZIP", db.providerCount("file").toString()) { searchProvider("file") }
        folderRow("CLAUDE CODE", "Claude Code sessions", "CODE") { runSearch("code") }
        section("COLLECTIONS")
        folderRow("PROJECTS", "Open organized subjects and work", "›") { showProjects() }
        folderRow("ARTIFACTS", "Extracted code and files", db.stats()[2].toString()) { showAllResults() }
        folderRow("FAVORITES", "Saved items", "★") { showAllResults() }
        section("TOOLS")
        folderRow("RECENT", "Latest imported activity", "›") { showRecent() }
        folderRow("SEARCH", "Search the entire local archive", "⌕") { focusSearch() }
        status.text = "READY  ·  ${db.stats()[0]} conversations  ·  ${db.stats()[1]} messages"
    }

    private fun section(label: String) { content.addView(txt(label, 8f, muted, true), LinearLayout.LayoutParams(-1, 24).apply { topMargin = 8 }) }

    private fun folderRow(title: String, subtitle: String, meta: String, click: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 7, 8, 7)
            setBackgroundColor(paper)
            isClickable = true
            setOnClickListener { click() }
        }
        row.addView(txt("□", 18f, ink, true), LinearLayout.LayoutParams(30, 50))
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        copy.addView(txt(title, 12f, ink, true))
        copy.addView(txt(subtitle, 9f, muted))
        row.addView(copy, LinearLayout.LayoutParams(0, 50, 1f))
        row.addView(txt(meta, 9f, muted, true), LinearLayout.LayoutParams(-2, 50))
        content.addView(row, LinearLayout.LayoutParams(-1, 56).apply { bottomMargin = 4 })
    }

    private fun showProjects() {
        content.removeAllViews()
        section("PROJECTS")
        val projects = db.listProjects()
        if (projects.isEmpty()) {
            content.addView(txt("No projects yet.", 12f, muted))
        } else {
            projects.forEach { p ->
                val kind = if (p.isSystem) "Built-in" else "Custom"
                folderRow(p.label.uppercase(Locale.US), kind, p.count.toString()) {
                    searchProvider(p.key)
                }
            }
        }
        content.addView(outlinedButton("+  NEW PROJECT") { promptNewProject() }, LinearLayout.LayoutParams(-1, 38).apply { topMargin = 8 })
        content.addView(outlinedButton("‹  AI DATA") { showAiHome() }, LinearLayout.LayoutParams(-1, 38).apply { topMargin = 6 })
        status.text = "PROJECTS  ·  ${projects.size}  ·  tap one, then IMPORT"
    }

    private fun promptNewProject() {
        val input = EditText(this).apply {
            hint = "Project name"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("New project")
            .setMessage("Name this project. File imports can land here as threads.")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                try {
                    val created = db.createProject(input.text.toString())
                    importProjectKey = created.key
                    Toast.makeText(this, "Project ${created.label} ready — import a file into it", Toast.LENGTH_LONG).show()
                    searchProvider(created.key)
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "Could not create project", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun showRecent() {
        content.removeAllViews()
        section("RECENT")
        val recent = db.recentConversations(40)
        if (recent.isEmpty()) {
            content.addView(txt("No conversations yet — tap IMPORT to add one.", 12f, muted))
        } else {
            recent.forEach { addResult(it) }
        }
        content.addView(outlinedButton("‹  AI DATA") { showAiHome() }, LinearLayout.LayoutParams(-1, 38).apply { topMargin = 8 })
        status.text = "RECENT  ·  ${recent.size} conversations"
    }

    private fun showAllResults() { if (search.text.toString().isBlank()) focusSearch() else runSearch(search.text.toString()) }

    private fun focusSearch() {
        search.requestFocus()
        search.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun searchProvider(provider: String) {
        importProjectKey = provider
        runSearch("provider:$provider")
    }

    private fun runSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) { focusSearch(); return }
        content.removeAllViews(); section("RESULTS"); status.text = "SEARCHING  ·  $q"
        Thread {
            try {
                val results = if (q.startsWith("provider:", true)) {
                    val p = q.substringAfter(':').trim()
                    db.conversationsByProvider(p)
                } else db.search(q)
                runOnUiThread {
                    content.removeAllViews(); section("${results.size} MATCHES")
                    if (results.isEmpty()) content.addView(txt("No matching conversations found.", 12f, muted)) else results.forEach { addResult(it) }
                    status.text = "RESULTS  ·  Tap a conversation to open it"
                }
            } catch (e: Exception) { runOnUiThread { status.text = "SEARCH ERROR  ·  ${e.message ?: "unknown error"}" } }
        }.start()
    }

    private fun addResult(r: KnowledgeDb.Result) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(10, 9, 10, 9); setBackgroundColor(paper); isClickable = true
            setOnClickListener { showConversation(r.provider, r.conversationId) }
        }
        row.addView(txt(r.title.ifBlank { "Untitled conversation" }, 13f, ink, true))
        row.addView(txt("${r.provider.uppercase()}  ·  ${r.role.uppercase()}", 8f, muted, true))
        row.addView(txt(r.snippet, 11f, muted))
        content.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 4 })
    }

    /**
     * Phone-style conversation viewer:
     * - Full thread stays populated while searching
     * - Match count + prev/next + scroll-to-match
     * - Leaving search does not wipe the conversation
     */
    private fun showConversation(provider: String, conversationId: String) {
        val root = baseRoot()
        val title = db.getConversationTitle(provider, conversationId)
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(outlinedButton("‹") { showHome() }, LinearLayout.LayoutParams(44, 36))
        top.addView(txt(title.ifBlank { "Untitled conversation" }, 16f, ink, true), LinearLayout.LayoutParams(0, 36, 1f).apply { leftMargin = 8 })
        root.addView(top)
        root.addView(line(), LinearLayout.LayoutParams(-1, 1))

        val searchRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 8, 0, 6) }
        val conversationSearch = EditText(this).apply {
            hint = "Search this conversation"
            textSize = 13f
            setSingleLine(true)
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(12, 0, 12, 0)
            background = GradientDrawable().apply { setColor(paper); setStroke(1, faint) }
        }
        searchRow.addView(conversationSearch, LinearLayout.LayoutParams(0, 40, 1f))
        root.addView(searchRow)

        val navRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 6)
            visibility = View.GONE
        }
        val matchLabel = txt("0 matches", 11f, muted, true)
        val prevBtn = outlinedButton("▲") { }
        val nextBtn = outlinedButton("▼") { }
        navRow.addView(matchLabel, LinearLayout.LayoutParams(0, 34, 1f))
        navRow.addView(prevBtn, LinearLayout.LayoutParams(48, 34))
        navRow.addView(Space(this), LinearLayout.LayoutParams(6, 1))
        navRow.addView(nextBtn, LinearLayout.LayoutParams(48, 34))
        root.addView(navRow)
        root.addView(line(), LinearLayout.LayoutParams(-1, 1))

        val list = ListView(this).apply {
            divider = null
            setBackgroundColor(bg)
            clipToPadding = false
            setPadding(0, 8, 0, 8)
            isVerticalScrollBarEnabled = true
            isFastScrollEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val total = db.conversationCount(provider, conversationId)
        val adapter = MessageAdapter(db.conversationCursor(provider, conversationId))
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(txt("LOCAL ARCHIVE  ·  SQLite-backed viewer", 8f, muted, true), LinearLayout.LayoutParams(-1, 24))
        setContentView(root)

        var matchPositions: List<Int> = emptyList()
        var matchIndex = 0

        fun scrollToMatch(i: Int) {
            if (matchPositions.isEmpty()) return
            matchIndex = ((i % matchPositions.size) + matchPositions.size) % matchPositions.size
            val pos = matchPositions[matchIndex]
            adapter.highlightPosition = pos
            adapter.notifyDataSetChanged()
            list.post {
                list.setSelection(pos)
                list.smoothScrollToPosition(pos)
            }
            matchLabel.text = "${matchIndex + 1} of ${matchPositions.size}"
        }

        fun applySearch(q: String) {
            Thread {
                val positions = db.conversationMatchPositions(provider, conversationId, q)
                runOnUiThread {
                    matchPositions = positions
                    if (q.isBlank()) {
                        navRow.visibility = View.GONE
                        adapter.highlightPosition = -1
                        adapter.needle = ""
                        adapter.notifyDataSetChanged()
                        matchLabel.text = "$total MESSAGES"
                    } else {
                        navRow.visibility = View.VISIBLE
                        adapter.needle = q
                        if (positions.isEmpty()) {
                            matchLabel.text = "0 matches"
                            adapter.highlightPosition = -1
                            adapter.notifyDataSetChanged()
                        } else {
                            scrollToMatch(0)
                        }
                    }
                }
            }.start()
        }

        prevBtn.setOnClickListener { if (matchPositions.isNotEmpty()) scrollToMatch(matchIndex - 1) }
        nextBtn.setOnClickListener { if (matchPositions.isNotEmpty()) scrollToMatch(matchIndex + 1) }

        matchLabel.text = "$total MESSAGES"
        conversationSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applySearch(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private data class MessageHolder(val bubble: LinearLayout, val role: TextView, val body: TextView, val time: TextView)

    private inner class MessageAdapter(cursor: Cursor) : CursorAdapter(this, cursor, FLAG_REGISTER_CONTENT_OBSERVER) {
        var highlightPosition: Int = -1
        var needle: String = ""

        override fun newView(context: android.content.Context, cursor: Cursor, parent: ViewGroup): View {
            val row = FrameLayout(context).apply { setPadding(6, 3, 6, 3) }
            val bubble = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(14, 9, 14, 8)
            }
            val role = TextView(context).apply { textSize = 8f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(muted) }
            val body = TextView(context).apply {
                textSize = 14f
                setTextColor(ink)
                setLineSpacing(0f, 1.08f)
                setPadding(0, 3, 0, 0)
                setTextIsSelectable(true)
                setMaxWidth((resources.displayMetrics.widthPixels * 0.80f).toInt())
            }
            val time = TextView(context).apply { textSize = 8f; setTextColor(muted); gravity = Gravity.END; setPadding(0, 3, 0, 0) }
            bubble.addView(role); bubble.addView(body); bubble.addView(time)
            row.addView(bubble, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.tag = MessageHolder(bubble, role, body, time)
            return row
        }

        override fun bindView(view: View, context: android.content.Context, cursor: Cursor) {
            val holder = view.tag as MessageHolder
            val role = cursor.getString(cursor.getColumnIndexOrThrow("role")) ?: "unknown"
            val body = cursor.getString(cursor.getColumnIndexOrThrow("content")) ?: ""
            val created = cursor.getString(cursor.getColumnIndexOrThrow("created_at"))
            val pos = cursor.position
            val isUser = role.equals("user", true) || role.equals("human", true)
            val isHit = highlightPosition == pos
            holder.role.text = if (isUser) "YOU" else role.uppercase(Locale.US)
            holder.body.text = body.ifBlank { "(empty message)" }
            holder.time.text = readableTime(created)
            val fill = when {
                isHit -> highlight
                isUser -> Color.rgb(224, 232, 218)
                else -> Color.WHITE
            }
            holder.bubble.background = GradientDrawable().apply {
                setColor(fill); cornerRadius = 18f
                setStroke(if (isHit) 2 else 1, if (isHit) Color.rgb(200, 160, 40) else faint)
            }
            val lp = holder.bubble.layoutParams as FrameLayout.LayoutParams
            lp.gravity = if (isUser) Gravity.END else Gravity.START
            holder.bubble.layoutParams = lp
        }
    }

    private fun readableTime(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val value = raw.trim(); val numeric = value.toDoubleOrNull()
        if (numeric != null) {
            val millis = if (numeric < 10_000_000_000L) (numeric * 1000.0).toLong() else numeric.toLong()
            return try { SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.US).format(Date(millis)) } catch (_: Exception) { value }
        }
        return value.replace('T', ' ').removeSuffix("Z").take(23)
    }

    override fun onBackPressed() { showHome() }

    /** After a successful import, open the new thread or land on its provider list. */
    private fun openAfterImport(result: ExportImporter.ImportResult) {
        val provider = result.openProvider
        val cid = result.openConversationId
        when {
            !provider.isNullOrBlank() && !cid.isNullOrBlank() -> showConversation(provider, cid)
            !provider.isNullOrBlank() -> {
                showHome()
                searchProvider(provider)
            }
            else -> {
                showHome()
                showRecent()
            }
        }
    }

    private fun pickFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                    "application/json",
                    "application/pdf",
                    "text/plain",
                    "text/html",
                    "text/csv",
                    "text/markdown",
                    "text/*",
                    "*/*"
                )
            )
        }, 42)
    }

    @Deprecated("Android callback API retained for minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        importUri(uri)
    }

    private fun importUri(uri: Uri) {
        val root = baseRoot()
        root.addView(txt("IMPORT FILE / AI DATA", 21f, ink, true))
        root.addView(txt("Reading locally — nothing leaves this device…", 11f, muted))
        root.addView(ProgressBar(this).apply { isIndeterminate = true })
        val progress = txt("Opening…", 11f, muted); root.addView(progress); setContentView(root)
        Thread {
            try {
                val result = ExportImporter(this, db).importAny(uri, importProjectKey) { message -> runOnUiThread { progress.text = message } }
                runOnUiThread {
                    Toast.makeText(this, "Imported ${result.provider}: ${result.conversations} conversations, ${result.messages} messages", Toast.LENGTH_LONG).show()
                    openAfterImport(result)
                }
            } catch (e: Exception) {
                runOnUiThread { showHome(); Toast.makeText(this, "Import failed: ${e.message ?: "unsupported file"}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
