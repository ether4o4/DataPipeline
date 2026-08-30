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
    private val panel = Color.rgb(16, 20, 24)
    private val panel2 = Color.rgb(12, 16, 19)
    private val accent = Color.rgb(0, 220, 190)
    private val textColor = Color.rgb(235, 240, 242)
    private val muted = Color.rgb(135, 150, 158)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        db = KnowledgeDb(this)
        showHome()
    }

    private fun base() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bg)
        setPadding(16, 20, 16, 12)
    }

    private fun label(s: String, size: Float = 12f, color: Int = muted) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }

    private fun button(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s; textSize = 12f; gravity = Gravity.CENTER
        setTextColor(textColor); typeface = Typeface.DEFAULT_BOLD
        setPadding(14, 0, 14, 0); setBackgroundColor(panel)
        isClickable = true; setOnClickListener { onClick() }
    }

    private fun showHome() {
        val root = base()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(this).apply {
            text = "DATA PIPELINE"; textSize = 22f; setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
        })
        brand.addView(label("LOCAL AI DATA EXPLORER", 9f, accent))
        header.addView(brand, LinearLayout.LayoutParams(0, 58, 1f))
        header.addView(button("IMPORT") { pickZip() }, LinearLayout.LayoutParams(96, 48))
        root.addView(header)
        root.addView(space(12))

        val search = EditText(this).apply {
            hint = "Search conversations, messages, code…"; textSize = 15f
            setSingleLine(true); setTextColor(textColor); setHintTextColor(muted)
            setPadding(16, 0, 16, 0); setBackgroundColor(panel)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, 54))
        root.addView(button("SEARCH") { runSearch(search.text.toString()) }, LinearLayout.LayoutParams(-1, 44).apply { topMargin = 7 })
        search.setOnEditorActionListener { _, _, _ -> runSearch(search.text.toString()); true }

        stats = TextView(this).apply {
            setTextColor(textColor); textSize = 12f; setPadding(2, 14, 2, 10)
        }
        root.addView(stats)
        status = label("READY  ·  Data stays on this device.", 10f, accent)
        root.addView(status)
        root.addView(space(6))

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            addView(content); setFillViewport(true); isFillViewport = true
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refreshStats()
        showEmptyState()
    }

    private fun showEmptyState() {
        content.removeAllViews()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(18, 18, 18, 18)
            setBackgroundColor(panel2)
        }
        box.addView(label("IMPORT YOUR AI DATA", 13f, textColor))
        box.addView(space(8))
        box.addView(label("Choose a ChatGPT or Claude export ZIP. Data Pipeline parses it locally, builds the searchable index, and keeps the source archive untouched.", 12f, muted))
        box.addView(space(12))
        box.addView(button("CHOOSE EXPORT ZIP") { pickZip() }, LinearLayout.LayoutParams(-1, 46))
        content.addView(box)
    }

    private fun refreshStats() {
        val s = db.stats()
        stats.text = "${s[0]} CONVERSATIONS   ·   ${s[1]} MESSAGES   ·   ${s[2]} ARTIFACTS"
    }

    private fun space(h: Int) = Space(this).apply { minimumHeight = h }

    private fun runSearch(q: String) {
        if (q.isBlank()) return
        content.removeAllViews(); status.text = "SEARCHING  ·  ${q.trim()}"
        Thread {
            try {
                val results = db.search(q)
                runOnUiThread {
                    content.removeAllViews(); status.text = "${results.size} RESULTS"
                    if (results.isEmpty()) content.addView(label("No matching messages or artifacts.", 13f, muted))
                    results.forEach { addResult(it) }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "SEARCH ERROR  ·  ${e.message}" }
            }
        }.start()
    }

    private fun addResult(r: KnowledgeDb.Result) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(15, 13, 15, 13)
            setBackgroundColor(panel); isClickable = true
            setOnClickListener { showConversation(r.provider, r.conversationId) }
        }
        card.addView(TextView(this).apply {
            text = r.title.ifBlank { "Untitled conversation" }; textSize = 15f
            setTextColor(textColor); typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(label("${r.provider.uppercase()}  ·  ${r.role.uppercase()}", 9f, accent))
        card.addView(TextView(this).apply {
            text = r.snippet; textSize = 12f; setTextColor(muted)
            setPadding(0, 7, 0, 0); maxLines = 5
        })
        content.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 7 })
    }

    private fun showConversation(provider: String, cid: String) {
        val pair = db.conversation(provider, cid)
        val root = base()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(button("‹ BACK") { showHome() }, LinearLayout.LayoutParams(82, 46))
        top.addView(label(pair.first, 17f, textColor), LinearLayout.LayoutParams(0, 46, 1f).apply { leftMargin = 10 })
        root.addView(top)
        root.addView(label("${provider.uppercase()}  ·  ${pair.second.size} MESSAGES", 9f, accent))
        root.addView(space(10))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pair.second.forEach { m ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(14, 12, 14, 12)
                setBackgroundColor(if (m.role == "user") panel2 else panel)
            }
            box.addView(label(m.role.uppercase(), 9f, if (m.role == "user") accent else muted))
            box.addView(TextView(this).apply {
                text = m.content; textSize = 14f; setTextColor(textColor)
                setPadding(0, 6, 0, 0); setTextIsSelectable(true)
            })
            list.addView(box, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 7 })
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun pickZip() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
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
        root.addView(label("IMPORTING DATA", 20f, textColor)); root.addView(space(8))
        root.addView(ProgressBar(this).apply { isIndeterminate = true })
        val msg = label("Opening export…", 12f, muted); root.addView(msg)
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

// DATA PIPELINE MOBILE UI: portrait-first, import/search/read flow.
