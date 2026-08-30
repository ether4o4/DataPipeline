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
    private val bg = Color.rgb(244, 245, 243)
    private val panel = Color.rgb(250, 250, 248)
    private val line = Color.rgb(205, 208, 204)
    private val ink = Color.rgb(24, 27, 28)
    private val muted = Color.rgb(105, 110, 109)
    private val accent = Color.rgb(40, 45, 46)
    private val selected = Color.rgb(25, 28, 29)

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
        setPadding(14, 8, 14, 8)
        if (Build.VERSION.SDK_INT >= 30) setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            v.setPadding(14, bars.top + 4, 14, bars.bottom + 4)
            insets
        }
    }

    private fun text(s: String, size: Float = 13f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text = s; textSize = size; setTextColor(color); includeFontPadding = true
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rule(h: Int = 1) = Space(this).apply { minimumHeight = h; setBackgroundColor(line) }

    private fun action(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s; textSize = 11f; gravity = Gravity.CENTER; setTextColor(ink)
        typeface = Typeface.DEFAULT_BOLD; setPadding(12, 0, 12, 0)
        setBackgroundColor(panel); isClickable = true; setOnClickListener { onClick() }
    }

    private fun showHome() {
        val root = base()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(text("DATA PIPELINE", 23f, ink, true))
        title.addView(text("LOCAL AI ARCHIVE", 9f, muted, true))
        header.addView(title, LinearLayout.LayoutParams(0, 54, 1f))
        header.addView(action("IMPORT") { pickZip() }, LinearLayout.LayoutParams(86, 44))
        root.addView(header)
        root.addView(rule())

        val search = EditText(this).apply {
            hint = "Search conversations, messages, code…"; textSize = 15f
            setSingleLine(true); setTextColor(ink); setHintTextColor(muted)
            setPadding(12, 0, 12, 0); setBackgroundColor(panel)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, 50).apply { topMargin = 10 })
        search.setOnEditorActionListener { _, _, _ -> runSearch(search.text.toString()); true }

        val metricRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        stats = text("", 11f, ink, true)
        metricRow.addView(stats, LinearLayout.LayoutParams(0, 42, 1f))
        metricRow.addView(action("FILTER") { showFilterSheet() }, LinearLayout.LayoutParams(76, 38))
        root.addView(metricRow)
        root.addView(rule())

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content); setFillViewport(true) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refreshStats()
        status = text("READY  ·  Everything stays on this device.", 9f, muted)
        root.addView(status, root.indexOfChild(content.parent as View) + 1, LinearLayout.LayoutParams(-1, 28))
        showLibrary()
    }

    private fun showLibrary() {
        content.removeAllViews()
        section("LIBRARY")
        folder("ALL CONVERSATIONS", "Browse the complete archive", "1,716") { runSearchAll() }
        folder("CHATGPT", "Imported conversations", "GPT") { runSearch("provider:chatgpt") }
        folder("CLAUDE", "Imported conversations", "CLAUDE") { runSearch("provider:claude") }
        folder("CLAUDE CODE", "Code sessions and development work", "CODE") { runSearch("code") }

        section("COLLECTIONS")
        folder("PROJECTS", "Programming · Music · Research · Personal", "›") { showProjectFolders() }
        folder("ARTIFACTS", "Code · Documents · Images · Other", "26,833") { runSearchAll() }
        folder("FAVORITES", "Saved conversations and items", "★") { runSearchAll() }

        section("RECENT")
        addResultHeader("Recent conversations")
        status.text = "READY  ·  ${db.stats()[0]} conversations indexed"
    }

    private fun section(s: String) { content.addView(text(s, 9f, muted, true), LinearLayout.LayoutParams(-1, 30).apply { topMargin = 8 }) }

    private fun folder(name: String, sub: String, right: String, click: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 10, 8); setBackgroundColor(panel); isClickable = true; setOnClickListener { click() }
        }
        row.addView(text("□", 20f, ink), LinearLayout.LayoutParams(30, 54))
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        copy.addView(text(name, 13f, ink, true)); copy.addView(text(sub, 10f, muted))
        row.addView(copy, LinearLayout.LayoutParams(0, 54, 1f))
        row.addView(text(right, 10f, muted, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 54))
        content.addView(row, LinearLayout.LayoutParams(-1, 62).apply { bottomMargin = 5 })
    }

    private fun showProjectFolders() {
        content.removeAllViews(); section("PROJECTS")
        folder("PROGRAMMING", "Code, Android, Termux and development", "›") { runSearch("code") }
        folder("MUSIC", "Writing, production and recording", "›") { runSearch("music") }
        folder("RESEARCH", "Research and reference conversations", "›") { runSearch("research") }
        folder("PERSONAL", "Personal conversations", "›") { runSearch("personal") }
        content.addView(action("‹ BACK TO LIBRARY") { showLibrary() }, LinearLayout.LayoutParams(-1, 44).apply { topMargin = 8 })
    }

    private fun addResultHeader(s: String) { content.addView(text(s, 14f, ink, true), LinearLayout.LayoutParams(-1, 42).apply { topMargin = 4 }) }

    private fun refreshStats() { val s = db.stats(); stats.text = "${s[0]} CONVERSATIONS   ·   ${s[1]} MESSAGES   ·   ${s[2]} ARTIFACTS" }

    private fun runSearchAll() {
        content.removeAllViews(); section("SEARCH")
        val hint = text("Enter a term above to search the entire archive.", 12f, muted)
        content.addView(hint, LinearLayout.LayoutParams(-1, 52))
        status.text = "READY  ·  ${db.stats()[0]} conversations indexed"
    }

    private fun runSearch(q: String) {
        if (q.isBlank()) return
        content.removeAllViews(); section("RESULTS")
        status.text = "SEARCHING  ·  ${q.trim()}"
        Thread {
            try {
                val results = db.search(q)
                runOnUiThread {
                    content.removeAllViews(); section("${results.size} RESULTS")
                    if (results.isEmpty()) content.addView(text("No matching messages or conversations.", 12f, muted))
                    results.forEach { addResult(it) }
                    status.text = "RESULTS  ·  Tap an item to open the full conversation"
                }
            } catch (e: Exception) { runOnUiThread { status.text = "SEARCH ERROR  ·  ${e.message}" } }
        }.start()
    }

    private fun addResult(r: KnowledgeDb.Result) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(12, 10, 12, 10); setBackgroundColor(panel)
            isClickable = true; setOnClickListener { showConversation(r.provider, r.conversationId) }
        }
        card.addView(text(r.title.ifBlank { "Untitled conversation" }, 14f, ink, true))
        card.addView(text("${r.provider.uppercase()}  ·  ${r.role.uppercase()}", 9f, muted, true))
        card.addView(text(r.snippet, 12f, muted))
        content.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 5 })
    }

    private fun showConversation(provider: String, cid: String) {
        val pair = db.conversation(provider, cid)
        val root = base()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(action("‹ BACK") { showHome() }, LinearLayout.LayoutParams(76, 42))
        top.addView(text(pair.first, 16f, ink, true), LinearLayout.LayoutParams(0, 42, 1f).apply { leftMargin = 10 })
        root.addView(top); root.addView(rule()); root.addView(text("${provider.uppercase()}  ·  ${pair.second.size} MESSAGES", 9f, muted, true)); root.addView(rule());
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pair.second.forEach { m ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 11, 12, 11); setBackgroundColor(if (m.role == "user") Color.rgb(235, 237, 234) else panel) }
            box.addView(text(m.role.uppercase(), 9f, muted, true))
            box.addView(text(m.content, 14f, ink))
            list.addView(box, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 5 })
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showFilterSheet() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 10, 16, 10); setBackgroundColor(panel) }
        box.addView(text("FILTERS", 16f, ink, true)); box.addView(rule());
        box.addView(action("CHATGPT") { runSearch("provider:chatgpt") }, LinearLayout.LayoutParams(-1, 44).apply { topMargin = 6 })
        box.addView(action("CLAUDE") { runSearch("provider:claude") }, LinearLayout.LayoutParams(-1, 44).apply { topMargin = 6 })
        box.addView(action("ALL SOURCES") { showLibrary() }, LinearLayout.LayoutParams(-1, 44).apply { topMargin = 6 })
        content.removeAllViews(); content.addView(box)
    }

    override fun onBackPressed() { showHome() }

    private fun pickZip() {
        // Let Android's document provider show ZIP exports even when a provider labels them generically.
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
        val root = base(); root.addView(text("IMPORTING DATA", 20f, ink, true)); root.addView(text("Reading export locally…", 11f, muted)); root.addView(ProgressBar(this).apply { isIndeterminate = true }); val msg = text("Opening export…", 12f, muted); root.addView(msg); setContentView(root)
        Thread {
            try {
                val r = ExportImporter(this, db).importZip(uri) { p -> runOnUiThread { msg.text = p } }
                runOnUiThread { showHome(); Toast.makeText(this, "Imported ${r.provider}: ${r.conversations} conversations, ${r.messages} messages, ${r.artifacts} artifacts", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { runOnUiThread { showHome(); Toast.makeText(this, "Import failed: ${e.message ?: "unsupported export"}", Toast.LENGTH_LONG).show() } }
        }.start()
    }
}
