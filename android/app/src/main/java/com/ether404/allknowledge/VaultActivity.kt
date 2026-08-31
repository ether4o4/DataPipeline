package com.ether404.allknowledge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

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
            settings.allowFileAccess = true
            addJavascriptInterface(Bridge(), "Android")
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    installVaultInteractions()
                    refreshStats()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            web.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        } else {
            web.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/vault.html")
    }

    inner class Bridge {
        @JavascriptInterface
        fun pickImport() = runOnUiThread {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/zip", "application/x-zip-compressed", "application/octet-stream", "application/json", "*/*"
                ))
            }, REQ_IMPORT)
        }

        @JavascriptInterface
        fun exportData() = runOnUiThread {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, "data-pipeline-knowledge.db")
            }, REQ_EXPORT)
        }

        @JavascriptInterface
        fun search(query: String, provider: String): String {
            val q = query.trim()
            if (q.isBlank()) return "[]"
            val needle = if (q.startsWith("provider:", true)) q.substringAfter(':').trim() else q
            val all = db.search(needle)
            val results = if (provider.isBlank()) all else all.filter { it.provider.equals(provider, true) }
            return resultsJson(results)
        }

        @JavascriptInterface
        fun content(kind: String, provider: String): String {
            val out = mutableListOf<KnowledgeDb.Result>()
            val d = db.readableDatabase
            val p = provider.trim()
            when (kind.lowercase()) {
                "conversations", "projects" -> {
                    val sql = if (p.isBlank())
                        "SELECT provider,conversation_id,title,updated_at,created_at FROM conversations ORDER BY COALESCE(updated_at,created_at) DESC,id DESC LIMIT 100"
                    else
                        "SELECT provider,conversation_id,title,updated_at,created_at FROM conversations WHERE provider=? ORDER BY COALESCE(updated_at,created_at) DESC,id DESC LIMIT 100"
                    d.rawQuery(sql, if (p.isBlank()) null else arrayOf(p)).use { c ->
                        while (c.moveToNext()) out += KnowledgeDb.Result(c.getString(0), c.getString(1), "", "conversation", c.getString(2) ?: "Untitled conversation", c.getString(3) ?: c.getString(4) ?: "")
                    }
                }
                "messages" -> {
                    val sql = if (p.isBlank())
                        "SELECT provider,conversation_id,message_id,COALESCE(role,'message'),COALESCE((SELECT title FROM conversations cv WHERE cv.provider=m.provider AND cv.conversation_id=m.conversation_id),'Untitled'),substr(content,1,220) FROM messages m ORDER BY id DESC LIMIT 100"
                    else
                        "SELECT provider,conversation_id,message_id,COALESCE(role,'message'),COALESCE((SELECT title FROM conversations cv WHERE cv.provider=m.provider AND cv.conversation_id=m.conversation_id),'Untitled'),substr(content,1,220) FROM messages m WHERE provider=? ORDER BY id DESC LIMIT 100"
                    d.rawQuery(sql, if (p.isBlank()) null else arrayOf(p)).use { c ->
                        while (c.moveToNext()) out += KnowledgeDb.Result(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5) ?: "")
                    }
                }
                else -> {
                    val filter = when (kind.lowercase()) {
                        "code" -> "(lower(kind) LIKE '%code%' OR (language IS NOT NULL AND trim(language)<>''))"
                        "files" -> "(lower(kind) LIKE '%file%' OR lower(kind) LIKE '%document%')"
                        "images" -> "(lower(kind) LIKE '%image%' OR lower(language) IN ('png','jpg','jpeg','gif','webp'))"
                        "artifacts" -> "1=1"
                        "tool calls" -> "(lower(content) LIKE '%tool call%' OR lower(kind) LIKE '%tool%')"
                        "commands" -> "(lower(content) LIKE '%command%' OR lower(kind) LIKE '%command%')"
                        "results" -> "(lower(content) LIKE '%result%' OR lower(kind) LIKE '%result%')"
                        "shared links" -> "(content LIKE '%http://%' OR content LIKE '%https://%')"
                        "memories" -> "(lower(content) LIKE '%memor%' OR lower(kind) LIKE '%memor%')"
                        else -> "1=0"
                    }
                    val sql = if (p.isBlank())
                        "SELECT provider,COALESCE(conversation_id,''),COALESCE(message_id,''),COALESCE(kind,'artifact'),COALESCE(title,'Untitled artifact'),substr(content,1,220) FROM artifacts WHERE $filter ORDER BY id DESC LIMIT 100"
                    else
                        "SELECT provider,COALESCE(conversation_id,''),COALESCE(message_id,''),COALESCE(kind,'artifact'),COALESCE(title,'Untitled artifact'),substr(content,1,220) FROM artifacts WHERE provider=? AND $filter ORDER BY id DESC LIMIT 100"
                    d.rawQuery(sql, if (p.isBlank()) null else arrayOf(p)).use { c ->
                        while (c.moveToNext()) out += KnowledgeDb.Result(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5) ?: "")
                    }
                }
            }
            return resultsJson(out)
        }

        @JavascriptInterface
        fun conversation(provider: String, conversationId: String): String {
            val pair = db.conversation(provider, conversationId)
            return JSONObject().apply {
                put("provider", provider)
                put("title", pair.first)
                put("messages", JSONArray().apply {
                    pair.second.forEach { m -> put(JSONObject().apply {
                        put("role", m.role); put("content", m.content); put("created", m.created)
                    }) }
                })
            }.toString()
        }

        private fun resultsJson(results: List<KnowledgeDb.Result>): String = JSONArray().apply {
            results.forEach { r -> put(JSONObject().apply {
                put("provider", r.provider); put("conversationId", r.conversationId); put("messageId", r.messageId)
                put("role", r.role); put("title", r.title); put("snippet", r.snippet)
            }) }
        }.toString()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_IMPORT -> importSelected(uri)
            REQ_EXPORT -> exportSelected(uri)
        }
    }

    private fun importSelected(uri: Uri) {
        web.evaluateJavascript("toast('Importing…')", null)
        Thread {
            try {
                val r = importer.importZip(uri) { msg ->
                    runOnUiThread { web.evaluateJavascript("toast(${js(msg)})", null) }
                }
                runOnUiThread {
                    refreshStats()
                    web.evaluateJavascript("toast(${js("Imported ${r.provider}: ${r.conversations} conversations, ${r.messages} messages")})", null)
                }
            } catch (e: Exception) {
                runOnUiThread { web.evaluateJavascript("toast(${js("Import failed: ${e.message ?: "unknown error"}")})", null) }
            }
        }.start()
    }

    private fun exportSelected(uri: Uri) {
        Thread {
            try {
                getDatabasePath("knowledge.db").inputStream().use { input ->
                    contentResolver.openOutputStream(uri).use { out ->
                        if (out == null) error("Could not open export destination")
                        input.copyTo(out)
                    }
                }
                runOnUiThread { web.evaluateJavascript("toast('Export complete')", null) }
            } catch (e: Exception) {
                runOnUiThread { web.evaluateJavascript("toast(${js("Export failed: ${e.message ?: "unknown error"}")})", null) }
            }
        }.start()
    }

    private fun refreshStats() {
        val s = db.stats()
        val code = db.readableDatabase.rawQuery("SELECT count(*) FROM artifacts WHERE lower(kind) LIKE '%code%' OR (language IS NOT NULL AND trim(language)<>'')", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val script = "setStats(${s[0]},${s[1]},${s[2]},$code,${providerCount("chatgpt")},${providerCount("claude")});"
        runOnUiThread { web.evaluateJavascript(script, null) }
    }

    private fun providerCount(provider: String): Long = db.readableDatabase.rawQuery(
        "SELECT count(*) FROM conversations WHERE lower(provider)=?", arrayOf(provider)
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun installVaultInteractions() {
        val script = """
        (function(){
          if(window.__vaultFixed){return;} window.__vaultFixed=true;
          var providerName='ChatGPT';
          function q(s){return Array.prototype.slice.call(document.querySelectorAll(s));}
          function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;');}
          function activate(list,el){list.forEach(function(x){x.classList.remove('on')});if(el)el.classList.add('on');}
          function showResults(raw,title){try{var data=JSON.parse(raw||'[]'),box=document.getElementById('vaultResults');if(!box){box=document.createElement('div');box.id='vaultResults';box.style.cssText='width:100%;margin-top:8px;max-height:420px;overflow:auto;';document.querySelector('.center').appendChild(box);}box.innerHTML='<div style="font-size:10px;color:var(--faint);font-weight:800;letter-spacing:2px;text-transform:uppercase;margin:10px 0">'+esc(title||'Results')+' · '+data.length+'</div>';if(!data.length){box.innerHTML+='<div style="color:var(--dim);font-size:11px;padding:10px">No data found.</div>';return;}data.forEach(function(r){var e=document.createElement('div');e.style.cssText='border:1px solid var(--stroke);border-radius:14px;padding:10px;margin-bottom:7px;background:rgba(255,255,255,.03);cursor:pointer';e.innerHTML='<b style="font-size:12px">'+esc(r.title||'Untitled')+'</b><div style="font-size:9px;color:var(--dim);margin-top:3px">'+esc((r.provider||'').toUpperCase())+' · '+esc(r.role||'')+'</div><div style="font-size:10px;color:var(--dim);margin-top:5px;line-height:1.35">'+esc(r.snippet||'')+'</div>';e.onclick=function(){openConv(r.provider,r.conversationId)};box.appendChild(e);});}catch(e){toast('Unable to display data');}}
          function openConv(p,c){try{var r=JSON.parse(Android.conversation(p,c));var box=document.getElementById('vaultResults');if(!box){box=document.createElement('div');box.id='vaultResults';document.querySelector('.center').appendChild(box);}box.innerHTML='<button onclick="window.closeVaultConversation()" style="margin:6px 0 10px;padding:7px 10px;border:1px solid var(--stroke);border-radius:12px;background:rgba(255,255,255,.04);color:var(--txt)">‹ Back</button><div style="font-size:14px;font-weight:800;margin-bottom:8px">'+esc(r.title||'Untitled')+'</div>';var msgs=r.messages||[];msgs.forEach(function(m){var e=document.createElement('div');e.style.cssText='border:1px solid var(--stroke);border-radius:14px;padding:10px;margin-bottom:7px;background:'+(String(m.role).toLowerCase()==='user'?'rgba(91,140,255,.08)':'rgba(255,255,255,.03)');e.innerHTML='<div style="font-size:8px;color:var(--faint);font-weight:800;letter-spacing:1.5px;margin-bottom:4px">'+esc(String(m.role||'').toUpperCase())+'</div><div style="font-size:12px;line-height:1.45;white-space:pre-wrap">'+esc(m.content||'')+'</div>';box.appendChild(e)});window.closeVaultConversation=function(){box.innerHTML='';};}catch(e){toast('Unable to open conversation');}}
          window.openVaultConversation=openConv;
          function runSearch(){var input=document.getElementById('vaultSearch');if(!input)return;var t=input.value.trim();if(!t){toast('Enter a search');return;}showResults(Android.search(t,providerName),'Search');}
          function runKind(k){document.getElementById('pvName').textContent=k.toUpperCase();document.getElementById('pvInfo').textContent=providerName+' · '+k;showResults(Android.content(k,providerName),k);toast('View: '+k);}
          window.provider=function(name,el){providerName=name;q('#provList .prov-item').forEach(function(x){x.classList.remove('active')});el.classList.add('active');document.getElementById('pvInfo').textContent=name+' · LOCAL';toast('Provider: '+name);};
          window.quick=runKind;
          var forms=document.querySelector('.forms');if(forms&&!document.getElementById('vaultSearchWrap')){var w=document.createElement('div');w.id='vaultSearchWrap';w.style.cssText='display:flex;gap:7px;margin-top:8px';w.innerHTML='<input id="vaultSearch" placeholder="Search conversations, messages, code…" style="flex:1;min-width:0;height:36px;padding:0 12px;border:1px solid var(--stroke);border-radius:13px;background:rgba(255,255,255,.035);color:var(--txt);outline:none;font-size:12px"><button id="vaultSearchBtn" style="height:36px;padding:0 12px;border:1px solid var(--stroke);border-radius:13px;background:rgba(255,255,255,.05);color:var(--txt);font-weight:700">Search</button>';forms.appendChild(w);document.getElementById('vaultSearchBtn').onclick=runSearch;document.getElementById('vaultSearch').onkeydown=function(e){if(e.key==='Enter')runSearch()};}
          q('.chips .chip').forEach(function(c){c.onclick=function(){q('.chips .chip').forEach(function(x){x.classList.remove('on')});c.classList.add('on');runKind(c.textContent.trim())};});
          q('#contentGrid .card').forEach(function(c){c.onclick=function(){q('#contentGrid .card').forEach(function(x){x.classList.remove('on')});c.classList.add('on');runKind(c.querySelector('.cl').textContent.trim())};});
          q('.pill').forEach(function(p){p.onclick=function(){activate(q('.pill',p.parentElement),p);toast(p.textContent.trim());}});
          var preview=document.querySelector('.preview');if(preview){preview.onclick=function(){var k=(document.querySelector('.chip.on')||{}).textContent||'Files';runKind(k.trim());};}
          window.toast=window.toast||function(m){var t=document.getElementById('toast');if(!t)return;t.textContent=m;t.classList.add('show');clearTimeout(window.__vaultToast);window.__vaultToast=setTimeout(function(){t.classList.remove('show')},1600)};
          var input=document.getElementById('vaultSearch'); if(input){input.value='';}
        })();
        """.trimIndent()
        web.evaluateJavascript("$script;void(0)", null)
    }

    private fun js(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'"

    companion object { const val REQ_IMPORT = 42; const val REQ_EXPORT = 43 }
}
