package com.ether404.allknowledge

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var db: KnowledgeDb
    private lateinit var content: LinearLayout
    private lateinit var stats: TextView
    private lateinit var status: TextView
    private val bg = Color.rgb(8, 10, 12)
    private val panel = Color.rgb(17, 21, 25)
    private val accent = Color.rgb(0, 220, 190)
    private val text = Color.rgb(235, 240, 242)
    private val muted = Color.rgb(135, 150, 158)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        db = KnowledgeDb(this)
        showHome()
    }

    private fun base(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bg)
        setPadding(20, 28, 20, 20)
    }

    private fun label(s: String, size: Float = 12f, color: Int = muted) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }

    private fun button(s: String, onClick: () -> Unit): Button = Button(this).apply {
        text = s
        textSize = 13f
        isAllCaps = false
        setTextColor(this@MainActivity.text)
        setBackgroundColor(panel)
        setOnClickListener { onClick() }
    }

    private fun showHome() {
        val root = base()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "DATA PIPELINE"
            textSize = 24f
            setTextColor(this@MainActivity.text)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, 58, 1f))
        header.addView(button("IMPORT", ::pickZip), LinearLayout.LayoutParams(105, 52))
        root.addView(header)
        root.addView(label("LOCAL-FIRST AI DATA ARCHIVE", 11f, accent))
        root.addView(space(14))

        val search = EditText(this).apply {
            hint = "Search messages, code, projects…"
            textSize = 15f
            setSingleLine(true)
            setTextColor(this@MainActivity.text)
            setPadding(18, 0, 18, 0)
            setBackgroundColor(panel)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, 56))
        root.addView(button("SEARCH") { runSearch(search.text.toString()) }, LinearLayout.LayoutParams(-1, 48).apply { topMargin = 8 })
        search.setOnEditorActionListener { _, _, _ -> runSearch(search.text.toString()); true }

        stats = TextView(this).apply {
            setTextColor(this@MainActivity.text)
            textSize = 13f
            setPadding(4, 16, 4, 16)
        }
        root.addView(stats)
        status = label("Ready — your data stays on this device.", 12f)
        root.addView(status)
        root.addView(space(8))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(content)
            setFillViewport(true)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refreshStats()
    }

    private fun refreshStats() {
        val s = db.stats()
        stats.text = "${s[0]} CONVERSATIONS     ${s[1]} MESSAGES     ${s[2]} ARTIFACTS"
    }

    private fun space(h: Int) = Space(this).apply { minimumHeight = h }

    private fun runSearch(q: String) {
        if (q.isBlank()) return
        content.removeAllViews()
        status.text = "Searching…"
        Thread {
            try {
                val results = db.search(q)
                runOnUiThread {
                    content.removeAllViews()
                    status.text = "${results.size} results"
                    results.forEach { addResult(it) }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Search error: ${e.message}" }
            }
        }.start()
    }

    private fun addResult(r: KnowledgeDb.Result) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            setBackgroundColor(panel)
            isClickable = true
        }
        val title = TextView(this).apply {
            text = r.title
            textSize = 15f
            setTextColor(this@MainActivity.text)
            typeface = Typeface.DEFAULT_BOLD
        }
        val meta = label("${r.provider.uppercase()}  ·  ${r.role}", 10f, accent)
        val snippet = TextView(this).apply {
            text = r.snippet
            textSize = 13f
            setTextColor(muted)
            setPadding(0, 7, 0, 0)
            maxLines = 5
        }
        card.addView(title)
        card.addView(meta)
        card.addView(snippet)
        card.setOnClickListener { showConversation(r.provider, r.conversationId) }
        content.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 })
    }

    private fun showConversation(provider: String, cid: String) {
        val pair = db.conversation(provider, cid)
        val root = base()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(button("‹ BACK") { showHome() }, LinearLayout.LayoutParams(90, 50))
        top.addView(label(pair.first, 18f, text), LinearLayout.LayoutParams(0, 50, 1f))
        root.addView(top)
        root.addView(label("${provider.uppercase()}  ·  ${pair.second.size} messages", 11f, accent))
        root.addView(space(12))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (m in pair.second) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(15, 12, 15, 12)
                setBackgroundColor(if (m.role == "user") Color.rgb(13, 18, 22) else panel)
            }
            box.addView(label(m.role.uppercase(), 10f, if (m.role == "user") accent else muted))
            box.addView(TextView(this).apply {
                text = m.content
                textSize = 14f
                setTextColor(this@MainActivity.text)
                setPadding(0, 6, 0, 0)
                setTextIsSelectable(true)
            })
            list.addView(box, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 7 })
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun pickZip() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        startActivityForResult(i, 42)
    }

    @Deprecated("Android callback API retained for minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        importUri(uri)
    }

    private fun importUri(uri: Uri) {
        val root = base()
        root.addView(label("IMPORTING DATA", 20f, text))
        root.addView(space(12))
        root.addView(ProgressBar(this).apply { isIndeterminate = true })
        val msg = label("Reading ZIP…", 13f, muted)
        root.addView(msg)
        setContentView(root)
        Thread {
            try {
                val r = ExportImporter(this, db).importZip(uri) { p -> runOnUiThread { msg.text = p } }
                runOnUiThread {
                    showHome()
                    Toast.makeText(this, "Imported ${r.provider}: ${r.conversations} conversations, ${r.messages} messages", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showHome()
                    Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
