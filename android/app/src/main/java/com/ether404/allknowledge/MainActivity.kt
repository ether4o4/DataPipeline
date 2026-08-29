package com.ether404.allknowledge

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "ALL KNOWLEDGE"
            textSize = 28f
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "Local-first AI conversation archive\n\nImport your ChatGPT or Claude export to build your searchable knowledge base."
            textSize = 17f
            setPadding(0, 24, 0, 24)
        })
        root.addView(TextView(this).apply {
            text = "IMPORT DATA\n\nComing next: ZIP picker → provider detection → SQLite/FTS5 indexing → full-text search."
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
        })
        setContentView(root)
    }
}
