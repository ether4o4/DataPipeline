package com.ether404.allknowledge

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*

class MainActivity : Activity() {
    private lateinit var db: KnowledgeDb
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var search: EditText

    private val bg = Color.rgb(244, 245, 242)
    private val paper = Color.rgb(249, 250, 247)
    private val ink = Color.rgb(24, 25, 24)
    private val muted = Color.rgb(105, 108, 104)
    private val faint = Color.rgb(205, 207, 202)
    private val active = Color.rgb(34, 35, 33)
    private val activeText = Color.WHITE

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        db = KnowledgeDb(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 23) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
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

    private fun line(): View = View(this).apply {
        setBackgroundColor(faint)
    }

    private fun outlinedButton(label: String, click: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 10f
        gravity = Gravity.CENTER
        setTextColor(ink)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(11, 0, 11, 0)
        background = GradientDrawable().apply {
            setColor(paper)
            setStroke(1, faint)
        }
        isClickable = true
        setOnClickListener { click() }
    }

    private fun showHome() {
        val root = baseRoot()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(txt("DATA PIPELINE", 22f, ink, true))
        brand.addView(txt("PERSONAL DATA ARCHIVE", 8f, muted, true))
        header.addView(brand, LinearLayout.LayoutParams(0, 56, 1f))
        header.addView(outlinedButton("IMPORT") { pickZip() }, LinearLayout.LayoutParams(74, 34))
        header.addView(Space(this), LinearLayout.LayoutParams(6, 1))
        header.addView(outlinedButton("EXPORT") {
            Toast.makeText(this, "Export is next.", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(68, 34))
        root.addView(header)
        root.addView(line(), LinearLayout.LayoutParams(-1, 1))

        val searchRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        search = EditText(this).apply {
            hint = "Search AI data"
            textSize = 14f
            setSingleLine(true)
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(12, 0, 12, 0)
            background = GradientDrawable().apply {
                setColor(paper)
                setStroke(1, faint)
            }
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, 42, 1f))
        search.setOnEditorActionListener { _, _, _ -> runSearch(search.text.toString()); true }
        root.addView(searchRow)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 10, 0)
        }
        rail.addView(txt("DATA", 8f, muted, true), LinearLayout.LayoutParams(-1, 26))
        railItem(rail, "AI DATA", "ACTIVE", true) { showAiHome() }
        railItem(rail, "SOCIAL MEDIA DATA", "SOON", false) { }
        railItem(rail, "FILE DATA", "SOON", false) { }
        railItem(rail, "MEDIA DATA", "SOON", false) { }
        rail.addView(Space(this), LinearLayout.LayoutParams(-1, 0, 1f))
        rail.addView(txt("LOCAL ARCHIVE", 8f, muted, true), LinearLayout.LayoutParams(-1, 28))
        body.addView(rail, LinearLayout.LayoutParams(0, -1, 0.34f))
        body.addView(line(), LinearLayout.LayoutParams(1, -1))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 0, 0, 0)
        }

        val contextHead = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        contextHead.addView(txt("AI DATA", 19f, ink, true))
        contextHead.addView(txt("Conversations, sessions and extracted artifacts", 10f, muted))
        right.addView(contextHead)
        right.addView(line(), LinearLayout.LayoutParams(-1, 1))

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        right.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

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
        content.removeAllViews()
        section("LIBRARY")
        folderRow("ALL CONVERSATIONS", "Every indexed AI conversation", db.stats()[0].toString()) { showAllResults() }
        folderRow("CHATGPT", "OpenAI conversations", "OPENAI") { searchProvider("chatgpt") }
        folderRow("CLAUDE", "Anthropic conversations", "ANTHROPIC") { searchProvider("claude") }
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

    private fun section(label: String) {
        content.addView(txt(label, 8f, muted, true), LinearLayout.LayoutParams(-1, 24).apply { topMargin = 8 })
    }

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
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        copy.addView(txt(title, 12f, ink, true))
        copy.addView(txt(subtitle, 9f, muted))
        row.addView(copy, LinearLayout.LayoutParams(0, 50, 1f))
        row.addView(txt(meta, 9f, muted, true), LinearLayout.LayoutParams(-2, 50))
        content.addView(row, LinearLayout.LayoutParams(-1, 56).apply { bottomMargin = 4 })
    }

    private fun showProjects() {
        content.removeAllViews()
        section("PROJECTS")
        folderRow("PROGRAMMING", "Android · Termux · Python · GitHub", "›") { runSearch("android") }
        folderRow("MUSIC", "Writing · production · recording", "›") { runSearch("music") }
        folderRow("RESEARCH", "Reference and investigation", "›") { runSearch("research") }
        folderRow("PERSONAL", "Personal conversations", "›") { runSearch("personal") }
        section("SUBFOLDERS")
        folderRow("AI / MODELS", "Local LLMs · Ollama · model work", "›") { runSearch("model") }
        folderRow("OSINT / FORENSICS", "Logs · tools · data analysis", "›") { runSearch("OSINT") }
        content.addView(outlinedButton("‹  AI DATA") { showAiHome() }, LinearLayout.LayoutParams(-1, 38).apply { topMargin = 8 })
        status.text = "PROJECTS  ·  Select a subject"
    }

    private fun showRecent() {
        content.removeAllViews()
        section("RECENT")
        content.addView(txt("${db.stats()[0]} conversations indexed", 15f, ink, true), LinearLayout.LayoutParams(-1, 34))
        content.addView(txt("Search above to jump directly into any conversation.", 10f, muted), LinearLayout.LayoutParams(-1, 32))
        content.addView(outlinedButton("SEARCH ARCHIVE") { focusSearch() }, LinearLayout.LayoutParams(-1, 38).apply { topMargin = 6 })
        status.text = "RECENT  ·  Local archive"
    }

    private fun showAllResults() {
        if (search.text.toString().isBlank()) {
            focusSearch()
        } else {
            runSearch(search.text.toString())
        }
    }

    private fun focusSearch() {
        search.requestFocus()
        search.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun searchProvider(provider: String) {
        runSearch("provider:$provider")
    }

    private fun runSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) {
            focusSearch()
            return
        }
        content.removeAllViews()
        section("RESULTS")
        status.text = "SEARCHING  ·  $q"
        Thread {
            try {
                val results = db.search(q)
                runOnUiThread {
                    content.removeAllViews()
                    section("${results.size} MATCHES")
                    if (results.isEmpty()) {
                        content.addView(txt("No matching conversations found.", 12f, muted))
                    } else {
                        results.forEach { result -> addResult(result) }
                    }
                    status.text = "RESULTS  ·  Tap a conversation to open it"
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "SEARCH ERROR  ·  ${e.message ?: "unknown error"}" }
            }
        }.start()
    }

    private fun addResult(r: KnowledgeDb.Result) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 9, 10, 9)
            setBackgroundColor(paper)
            isClickable = true
            setOnClickListener { showConversation(r.provider, r.conversationId) }
        }
        row.addView(txt(r.title.ifBlank { "Untitled conversation" }, 13f, ink, true))
        row.addView(txt("${r.provider.uppercase()}  ·  ${r.role.uppercase()}", 8f, muted, true))
        row.addView(txt(r.snippet, 11f, muted))
        content.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 4 })
    }

    private fun showConversation(provider: String, conversationId: String) {
        val pair = db.conversation(provider, conversationId)
        val root = baseRoot()

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(outlinedButton("‹") { showHome() }, LinearLayout.LayoutParams(44, 36))
        top.addView(txt(pair.first.ifBlank { "Untitled conversation" }, 16f, ink, true), LinearLayout.LayoutParams(0, 36, 1f).apply { leftMargin = 8 })
        root.addView(top)
        root.addView(line(), LinearLayout.LayoutParams(-1, 1))
        root.addView(txt("${provider.uppercase()}  ·  ${pair.second.size} MESSAGES", 8f, muted, true), LinearLayout.LayoutParams(-1, 28))
        root.addView(line(), LinearLayout.LayoutParams(-1, 1))

        val messageList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pair.second.forEach { message ->
            val block = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10, 9, 10, 9)
                setBackgroundColor(if (message.role == "user") Color.rgb(235, 237, 232) else paper)
            }
            block.addView(txt(message.role.uppercase(), 8f, muted, true))
            block.addView(txt(message.content, 13f, ink))
            messageList.addView(block, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 4 })
        }

        root.addView(ScrollView(this).apply { addView(messageList) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(txt("LOCAL ARCHIVE  ·  ${pair.second.size} messages", 8f, muted, true), LinearLayout.LayoutParams(-1, 24))
        setContentView(root)
    }

    override fun onBackPressed() {
        showHome()
    }

    private fun pickZip() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
                "application/json",
                "*/*"
            ))
        }, 42)
    }

    @Deprecated("Android callback API retained for minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }
        importUri(uri)
    }

    private fun importUri(uri: Uri) {
        val root = baseRoot()
        root.addView(txt("IMPORT AI DATA", 21f, ink, true))
        root.addView(txt("Reading export locally…", 11f, muted))
        root.addView(ProgressBar(this).apply { isIndeterminate = true })
        val progress = txt("Opening export…", 11f, muted)
        root.addView(progress)
        setContentView(root)

        Thread {
            try {
                val result = ExportImporter(this, db).importZip(uri) { message ->
                    runOnUiThread { progress.text = message }
                }
                runOnUiThread {
                    showHome()
                    Toast.makeText(
                        this,
                        "Imported ${result.provider}: ${result.conversations} conversations, ${result.messages} messages",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showHome()
                    Toast.makeText(
                        this,
                        "Import failed: ${e.message ?: "unsupported export"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }
}
