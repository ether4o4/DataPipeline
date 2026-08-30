package com.ether404.allknowledge

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
    private lateinit var stats: TextView
    private lateinit var status: TextView

    private val bg = Color.rgb(238, 239, 236)
    private val panel = Color.rgb(246, 247, 244)
    private val panelStrong = Color.rgb(232, 234, 230)
    private val line = Color.rgb(188, 191, 187)
    private val ink = Color.rgb(20, 22, 22)
    private val muted = Color.rgb(100, 104, 102)
    private val selected = Color.rgb(28, 30, 29)
    private val selectedText = Color.WHITE

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        db = KnowledgeDb(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        showHome()
    }

    private fun base(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bg)
        setPadding(12, 6, 12, 6)
        if (Build.VERSION.SDK_INT >= 30) setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            v.setPadding(12, bars.top + 3, 12, bars.bottom + 3)
            insets
        }
    }

    private fun text(s: String, size: Float = 13f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        includeFontPadding = true
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rule(h: Int = 1) = Space(this).apply {
        minimumHeight = h
        setBackgroundColor(line)
    }

    private fun button(s: String, onClick: () -> Unit, dark: Boolean = false) = TextView(this).apply {
        text = s
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(if (dark) selectedText else ink)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(10, 0, 10, 0)
        setBackgroundColor(if (dark) selected else panel)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun showHome() {
        val root = base()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(text("DATA PIPELINE", 24f, ink, true))
        title.addView(text("PERSONAL AI ARCHIVE", 9f, muted, true))
        header.addView(title, LinearLayout.LayoutParams(0, 52, 1f))
        header.addView(button("IMP", { pickZip() }, true), LinearLayout.LayoutParams(58, 40))
        root.addView(header)
        root.addView(rule())

        val search = EditText(this).apply {
            hint = "Search archive..."
            textSize = 15f
            setSingleLine(true)
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(10, 0, 10, 0)
            setBackgroundColor(panel)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, 46).apply { topMargin = 8; bottomMargin = 5 })
        search.setOnEditorActionListener { _, _, _ -> runSearch(search.text.toString()); true }

        val metric = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        stats = text("", 10f, ink, true)
        metric.addView(stats, LinearLayout.LayoutParams(0, 34, 1f))
        metric.addView(button("FILTER", { showFilterSheet() }), LinearLayout.LayoutParams(68, 32))
        root.addView(metric)
        root.addView(rule())

        val workspace = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(button("LIBRARY", { showLibrary() }, true), LinearLayout.LayoutParams(0, 38, 1f))
        nav.addView(button("PROJECTS", { showProjectFolders() }), LinearLayout.LayoutParams(0, 38, 1f).apply { leftMargin = 4 })
        nav.addView(button("RECENT", { showRecent() }), LinearLayout.LayoutParams(0, 38, 1f).apply { leftMargin = 4 })
        workspace.addView(nav, LinearLayout.LayoutParams(-1, 44))
        workspace.addView(rule())

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        workspace.addView(ScrollView(this).apply { addView(content); setFillViewport(true) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(workspace, LinearLayout.LayoutParams(-1, 0, 1f))

        status = text("READY", 9f, muted, true)
        root.addView(status, LinearLayout.LayoutParams(-1, 26))
        setContentView(root)
        refreshStats()
        showLibrary()
    }

    private fun section(s: String) {
        content.addView(text(s, 9f, muted, true), LinearLayout.LayoutParams(-1, 30).apply { topMargin = 7 })
    }

    private fun folder(name: String, sub: String, right: String, click: () -> Unit, active: Boolean = false) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(9, 5, 9, 5)
            setBackgroundColor(if (active) selected else panel)
            isClickable = true
            setOnClickListener { click() }
        }
        val glyph = text(if (right == "›") "◇" else "□", 19f, if (active) selectedText else ink, true)
        row.addView(glyph, LinearLayout.LayoutParams(31, 52))
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        copy.addView(text(name, 13f, if (active) selectedText else ink, true))
        copy.addView(text(sub, 9f, if (active) Color.rgb(210, 212, 210) else muted))
        row.addView(copy, LinearLayout.LayoutParams(0, 52, 1f))
        row.addView(text(right, 10f, if (active) selectedText else muted, true), LinearLayout.LayoutParams(-2, 52))
        content.addView(row, LinearLayout.LayoutParams(-1, 59).apply { bottomMargin = 4 })
    }

    private fun showLibrary() {
        content.removeAllViews()
        section("ARCHIVE")
        folder("ALL CONVERSATIONS", "Complete indexed archive", "${db.stats()[0]}") { runSearchAll() }
        folder("CHATGPT", "OpenAI conversations", "GPT") { runSearch("provider:chatgpt") }
        folder("CLAUDE", "Anthropic conversations", "CLAUDE") { runSearch("provider:claude") }
        folder("CLAUDE CODE", "Development sessions", "CODE") { runSearch("code") }

        section("COLLECTIONS")
        folder("PROJECTS", "Programming · Music · Research · Personal", "›") { showProjectFolders() }
        folder("ARTIFACTS", "Extracted code and files", "${db.stats()[2]}") { runSearchAll() }
        folder("FAVORITES", "Saved conversations", "★") { runSearchAll() }

        section("BROWSE")
        folder("BY DATE", "Explore conversations chronologically", "›") { showRecent() }
        folder("SEARCH RESULTS", "Full-text indexed messages", "⌕") { runSearchAll() }
        status.text = "READY  ·  ${db.stats()[0]} conversations indexed"
    }

    private fun showProjectFolders() {
        content.removeAllViews()
        section("PROJECTS")
        folder("PROGRAMMING", "Android · Termux · Python · GitHub", "›") { runSearch("code") }
        folder("MUSIC", "Writing · production · recording", "›") { runSearch("music") }
        folder("RESEARCH", "Reference and investigation", "›") { runSearch("research") }
        folder("PERSONAL", "Personal conversations", "›") { runSearch("personal") }
        section("SUBFOLDERS")
        folder("AI / MODELS", "Local LLMs · Ollama · model research", "›") { runSearch("model") }
        folder("OSINT / FORENSICS", "Tools · logs · data analysis", "›") { runSearch("OSINT") }
        content.addView(button("‹ LIBRARY", { showLibrary() }, false), LinearLayout.LayoutParams(-1, 42).apply { topMargin = 8 })
        status.text = "PROJECTS  ·  Choose a collection"
    }

    private fun showRecent() {
        content.removeAllViews()
        section("RECENT")
        val s = db.stats()
        content.addView(text("${s[0]} conversations available", 15f, ink, true), LinearLayout.LayoutParams(-1, 38))
        content.addView(text("Use search to jump directly into any message or conversation.", 10f, muted), LinearLayout.LayoutParams(-1, 34))
        content.addView(button("SEARCH ENTIRE ARCHIVE", { runSearchAll() }, true), LinearLayout.LayoutParams(-1, 42).apply { topMargin = 8 })
        status.text = "BROWSE  ·  Search to open a conversation"
    }

    private fun refreshStats() {
        val s = db.stats()
        stats.text = "${s[0]} CONVERSATIONS   ·   ${s[1]} MESSAGES   ·   ${s[2]} ARTIFACTS"
    }

    private fun runSearchAll() {
        content.removeAllViews()
        section("SEARCH")
        content.addView(text("Type a term in the search field above.", 12f, muted), LinearLayout.LayoutParams(-1, 48))
        status.text = "READY  ·  ${db.stats()[0]} conversations indexed"
    }

    private fun runSearch(q: String) {
        if (q.isBlank()) return
        content.removeAllViews()
        section("SEARCH RESULTS")
        status.text = "SEARCHING  ·  ${q.trim()}"
        Thread {
            try {
                val results = db.search(q)
                runOnUiThread {
                    content.removeAllViews()
                    section("${results.size} RESULTS")
                    if (results.isEmpty()) content.addView(text("No matching messages or conversations.", 12f, muted))
                    results.forEach { addResult(it) }
                    status.text = "RESULTS  ·  Tap an item to open the complete conversation"
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "SEARCH ERROR  ·  ${e.message}" }
            }
        }.start()
    }

    private fun addResult(r: KnowledgeDb.Result) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(11, 9, 11, 9)
            setBackgroundColor(panel)
            isClickable = true
            setOnClickListener { showConversation(r.provider, r.conversationId) }
        }
        card.addView(text(r.title.ifBlank { "Untitled conversation" }, 14f, ink, true))
        card.addView(text("${r.provider.uppercase()}  ·  ${r.role.uppercase()}", 9f, muted, true))
        card.addView(text(r.snippet, 12f, muted))
        content.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 4 })
    }

    private fun showConversation(provider: String, cid: String) {
        val pair = db.conversation(provider, cid)
        val root = base()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(button("‹", { showHome() }, true), LinearLayout.LayoutParams(46, 42))
        top.addView(text(pair.first.ifBlank { "Untitled conversation" }, 16f, ink, true), LinearLayout.LayoutParams(0, 42, 1f).apply { leftMargin = 9 })
        root.addView(top)
        root.addView(rule())
        root.addView(text("${provider.uppercase()}  ·  ${pair.second.size} MESSAGES", 9f, muted, true), LinearLayout.LayoutParams(-1, 30))
        root.addView(rule())
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pair.second.forEach { m ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(11, 10, 11, 10)
                setBackgroundColor(if (m.role == "user") panelStrong else panel)
            }
            box.addView(text(m.role.uppercase(), 9f, muted, true))
            box.addView(text(m.content, 14f, ink))
            list.addView(box, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 4 })
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(text("${pair.second.size} messages  ·  Local archive", 9f, muted), LinearLayout.LayoutParams(-1, 25))
        setContentView(root)
    }

    private fun showFilterSheet() {
        content.removeAllViews()
        section("FILTER")
        folder("CHATGPT", "Only ChatGPT conversations", "GPT") { runSearch("provider:chatgpt") }
        folder("CLAUDE", "Only Claude conversations", "CLAUDE") { runSearch("provider:claude") }
        folder("CLAUDE CODE", "Code sessions", "CODE") { runSearch("code") }
        folder("ALL SOURCES", "Return to the full library", "ALL") { showLibrary() }
    }

    override fun onBackPressed() { showHome() }

    private fun pickZip() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "application/json", "*/*"))
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
        val root = base()
        root.addView(text("IMPORT DATA", 21f, ink, true))
        root.addView(text("Reading export locally…", 11f, muted))
        root.addView(ProgressBar(this).apply { isIndeterminate = true })
        val msg = text("Opening export…", 12f, muted)
        root.addView(msg)
        setContentView(root)
        Thread {
            try {
                val r = ExportImporter(this, db).importZip(uri) { p -> runOnUiThread { msg.text = p } }
                runOnUiThread {
                    showHome()
                    Toast.makeText(this, "Imported ${r.provider}: ${r.conversations} conversations, ${r.messages} messages, ${r.artifacts} artifacts", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showHome()
                    Toast.makeText(this, "Import failed: ${e.message ?: "unsupported export"}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
