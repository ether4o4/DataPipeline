package com.ether404.allknowledge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.io.File

class VaultActivity : Activity() {
    private lateinit var db: KnowledgeDb
    private lateinit var importer: ExportImporter
    private lateinit var web: WebView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        db = KnowledgeDb(this)
        importer = ExportImporter(this, db)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "Android")
            loadUrl("file:///android_asset/vault.html")
        }
        setContentView(web)
    }
    inner class Bridge {
        @JavascriptInterface fun pickImport() = runOnUiThread { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip","application/x-zip-compressed","application/json","*/*")) }, REQ_IMPORT) }
        @JavascriptInterface fun exportData() = runOnUiThread { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="application/octet-stream"; putExtra(Intent.EXTRA_TITLE,"data-pipeline-knowledge.db") }, REQ_EXPORT) }
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        val uri=data?.data ?: return
        if(resultCode!=RESULT_OK) return
        when(requestCode){ REQ_IMPORT -> importSelected(uri); REQ_EXPORT -> exportSelected(uri) }
    }
    private fun importSelected(uri:Uri){
        web.evaluateJavascript("toast('Importing…')",null)
        Thread {
            try {
                val r=importer.importZip(uri){ msg -> runOnUiThread { web.evaluateJavascript("toast(${js(msg)})",null) } }
                val s=db.stats()
                runOnUiThread { web.evaluateJavascript("setStats(${s[0]},${s[1]},${s[2]},${r.artifacts},${r.conversations},${if(r.provider.contains("Claude")) r.conversations else 0});toast(${js("Imported ${r.provider}: ${r.conversations} conversations")})",null) }
            } catch(e:Exception){ runOnUiThread { web.evaluateJavascript("toast(${js("Import failed: ${e.message ?: "unknown error"}")})",null) } }
        }.start()
    }
    private fun exportSelected(uri:Uri){
        Thread {
            try {
                getDatabasePath("knowledge.db").inputStream().use { input -> contentResolver.openOutputStream(uri).use { out -> if(out==null) error("Could not open export destination"); input.copyTo(out) } }
                runOnUiThread { web.evaluateJavascript("toast('Export complete')",null) }
            } catch(e:Exception){ runOnUiThread { web.evaluateJavascript("toast(${js("Export failed: ${e.message ?: "unknown error"}")})",null) } }
        }.start()
    }
    private fun js(s:String)="'"+s.replace("\\","\\\\").replace("'","\\'").replace("\n"," ")+"'"
    companion object { const val REQ_IMPORT=42; const val REQ_EXPORT=43 }
}
