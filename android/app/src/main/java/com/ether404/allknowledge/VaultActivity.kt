package com.ether404.allknowledge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
            settings.allowContentAccess = true
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    installVaultInteractions()
                    refreshStats()
                }
            }
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/vault.html")
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.post { refreshStats() }
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
            val needle = query.trim()
            if (needle.isBlank()) return "[]"
            val all = db.search(needle)
            val p = provider.trim()
            val filtered = if (p.isBlank()) all else all.filter { it.provider.equals(p, true) }
            return resultsJson(filtered)
        }

        @JavascriptInterface
        fun content(kind: String, provider: String): String {
            val key = kind.trim().lowercase()
            val p = provider.trim().lowercase()
            val d = db.readableDatabase
            val argsProvider = if (p.isBlank()) null else arrayOf(p)
            val out = mutableListOf<KnowledgeDb.Result>()

            fun convSql(order: String = "ORDER BY COALESCE(updated_at,created_at) DESC,id DESC") =
                if (p.isBlank()) "SELECT lower(provider),conversation_id,COALESCE(title,'Untitled'),updated_at,created_at FROM conversations $order LIMIT 100"
                else "SELECT lower(provider),conversation_id,COALESCE(title,'Untitled'),updated_at,created_at FROM conversations WHERE lower(provider)=? $order LIMIT 100"

            when (key) {
                "conversations", "recent", "favorites", "organization", "projects", "by date", "by provider", "by project", "by type" -> {
                    d.rawQuery(convSql(), argsProvider).use { c ->
                        while (c.moveToNext()) out += KnowledgeDb.Result(
                            c.getString(0), c.getString(1), "", "conversation", c.getString(2), c.getString(3) ?: c.getString(4) ?: ""
                        )
                    }
                }
                "messages" -> {
                    val sql = if (p.isBlank())
                        "SELECT lower(m.provider),m.conversation_id,m.message_id,COALESCE(m.role,'message'),COALESCE(c.title,'Untitled'),substr(m.content,1,240) FROM messages m LEFT JOIN conversations c ON c.provider=m.provider AND c.conversation_id=m.conversation_id ORDER BY m.id DESC LIMIT 100"
                    else
                        "SELECT lower(m.provider),m.conversation_id,m.message_id,COALESCE(m.role,'message'),COALESCE(c.title,'Untitled'),substr(m.content,1,240) FROM messages m LEFT JOIN conversations c ON c.provider=m.provider AND c.conversation_id=m.conversation_id WHERE lower(m.provider)=? ORDER BY m.id DESC LIMIT 100"
                    d.rawQuery(sql, argsProvider).use { c -> while (c.moveToNext()) out += KnowledgeDb.Result(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5) ?: "") }
                }
                "artifacts", "code", "files", "images" -> {
                    val where = when (key) {
                        "code" -> "(lower(COALESCE(a.kind,'')) LIKE '%code%' OR lower(COALESCE(a.language,'')) NOT IN ('','text'))"
                        "files" -> "lower(COALESCE(a.kind,'')) NOT LIKE '%code%'"
                        "images" -> "lower(COALESCE(a.kind,'')) LIKE '%image%' OR lower(COALESCE(a.language,'')) IN ('png','jpg','jpeg','gif','webp','svg')"
                        else -> "1=1"
                    }
                    val sql = if (p.isBlank())
                        "SELECT lower(a.provider),COALESCE(a.conversation_id,''),COALESCE(a.message_id,''),COALESCE(a.kind,'artifact'),COALESCE(a.title,'Untitled artifact'),substr(a.content,1,240) FROM artifacts a WHERE $where ORDER BY a.id DESC LIMIT 100"
                    else
                        "SELECT lower(a.provider),COALESCE(a.conversation_id,''),COALESCE(a.message_id,''),COALESCE(a.kind,'artifact'),COALESCE(a.title,'Untitled artifact'),substr(a.content,1,240) FROM artifacts a WHERE lower(a.provider)=? AND $where ORDER BY a.id DESC LIMIT 100"
                    d.rawQuery(sql, argsProvider).use { c -> while (c.moveToNext()) out += KnowledgeDb.Result(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5) ?: "") }
                }
                "tool calls", "commands", "results", "shared links", "memories" -> {
                    val needle = when (key) {
                        "tool calls" -> "%tool%"
                        "commands" -> "%command%"
                        "results" -> "%result%"
                        "memories" -> "%memor%"
                        else -> "%http%"
                    }
                    val sql = if (key == "shared links") {
                        if (p.isBlank())
                            "SELECT lower(provider),conversation_id,message_id,COALESCE(role,'message'),COALESCE((SELECT title FROM conversations c WHERE c.provider=m.provider AND c.conversation_id=m.conversation_id),'Untitled'),substr(content,1,240) FROM messages m WHERE content LIKE '%http://%' OR content LIKE '%https://%' ORDER BY id DESC LIMIT 100"
                        else
                            "SELECT lower(provider),conversation_id,message_id,COALESCE(role,'message'),COALESCE((SELECT title FROM conversations c WHERE c.provider=m.provider AND c.conversation_id=m.conversation_id),'Untitled'),substr(content,1,240) FROM messages m WHERE lower(provider)=? AND (content LIKE '%http://%' OR content LIKE '%https://%') ORDER BY id DESC LIMIT 100"
                    } else if (p.isBlank()) {
                        "SELECT lower(provider),conversation_id,message_id,COALESCE(role,'message'),COALESCE((SELECT title FROM conversations c WHERE c.provider=m.provider AND c.conversation_id=m.conversation_id),'Untitled'),substr(content,1,240) FROM messages m WHERE lower(content) LIKE ? ORDER BY id DESC LIMIT 100"
                    } else {
                        "SELECT lower(provider),conversation_id,message_id,COALESCE(role,'message'),COALESCE((SELECT title FROM conversations c WHERE c.provider=m.provider AND c.conversation_id=m.conversation_id),'Untitled'),substr(content,1,240) FROM messages m WHERE lower(provider)=? AND lower(content) LIKE ? ORDER BY id DESC LIMIT 100"
                    }
                    val args = when {
                        key == "shared links" && p.isBlank() -> null
                        key == "shared links" -> arrayOf(p)
                        p.isBlank() -> arrayOf(needle)
                        else -> arrayOf(p, needle)
                    }
                    d.rawQuery(sql, args).use { c -> while (c.moveToNext()) out += KnowledgeDb.Result(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5) ?: "") }
                }
                else -> {
                    val sql = if (p.isBlank())
                        "SELECT lower(provider),conversation_id,message_id,COALESCE(role,'message'),COALESCE((SELECT title FROM conversations c WHERE c.provider=m.provider AND c.conversation_id=m.conversation_id),'Untitled'),substr(content,1,240) FROM messages m ORDER BY id DESC LIMIT 100"
                    else
                        "SELECT lower(provider),conversation_id,message_id,COALESCE(role,'message'),COALESCE((SELECT title FROM conversations c WHERE c.provider=m.provider AND c.conversation_id=m.conversation_id),'Untitled'),substr(content,1,240) FROM messages m WHERE lower(provider)=? ORDER BY id DESC LIMIT 100"
                    d.rawQuery(sql, argsProvider).use { c -> while (c.moveToNext()) out += KnowledgeDb.Result(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5) ?: "") }
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

        private fun resultsJson(results: List<KnowledgeDb.Result>) = JSONArray().apply {
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
                val r = importer.importZip(uri) { msg -> runOnUiThread { web.evaluateJavascript("toast(${js(msg)})", null) } }
                runOnUiThread { refreshStats(); web.evaluateJavascript("toast(${js("Imported ${r.provider}: ${r.conversations} conversations, ${r.messages} messages")})", null) }
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
        val code = db.readableDatabase.rawQuery("SELECT count(*) FROM artifacts WHERE lower(COALESCE(kind,'')) LIKE '%code%' OR (language IS NOT NULL AND trim(language)<>'' AND lower(language)<>'text')", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val gpt = providerCount("chatgpt")
        val claude = providerCount("claude")
        val script = "setStats(${s[0]},${s[1]},${s[2]},$code,$gpt,$claude);"
        runOnUiThread { web.evaluateJavascript(script, null) }
    }

    private fun providerCount(provider: String) = db.readableDatabase.rawQuery("SELECT count(*) FROM conversations WHERE lower(provider)=?", arrayOf(provider)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun installVaultInteractions() {
        val script = """
            (function(){
              if(window.__vaultFixed2)return; window.__vaultFixed2=true;
              var providerName='ChatGPT';
              function q(s){return Array.prototype.slice.call(document.querySelectorAll(s));}
              function esc(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;').replace(/'/g,'&#39;');}
              function toast(m){var t=document.getElementById('toast');if(!t){t=document.createElement('div');t.id='toast';t.className='toast';document.body.appendChild(t);}t.textContent=m;t.classList.add('show');clearTimeout(window.__t);window.__t=setTimeout(function(){t.classList.remove('show')},1600);}
              window.toast=toast;
              function renderResults(raw,title){
                var data=[];try{data=JSON.parse(raw||'[]')}catch(e){}
                var box=document.getElementById('vaultResults');
                if(!box){box=document.createElement('div');box.id='vaultResults';box.style.cssText='width:100%;margin-top:8px;max-height:44vh;overflow:auto;padding-right:2px';document.querySelector('.center').appendChild(box)}
                box.innerHTML='<div style="font-size:10px;color:var(--faint);font-weight:800;letter-spacing:2px;text-transform:uppercase;margin:6px 0 9px">'+esc(title)+' · '+data.length+'</div>';
                if(!data.length){box.innerHTML+='<div style="padding:12px;color:var(--dim);font-size:12px">No matching data.</div>';return;}
                data.forEach(function(r){var e=document.createElement('div');e.style.cssText='border:1px solid var(--stroke);border-radius:14px;padding:11px;margin-bottom:7px;background:rgba(255,255,255,.03);cursor:pointer';e.innerHTML='<div style="font-size:12px;font-weight:800">'+esc(r.title||'Untitled')+'</div><div style="margin-top:3px;font-size:9px;color:var(--dim);letter-spacing:.5px">'+esc((r.provider||'').toUpperCase())+' · '+esc(r.role||'')+'</div><div style="margin-top:6px;font-size:11px;color:var(--dim);line-height:1.4">'+esc(r.snippet||'')+'</div>';e.onclick=function(){openConversation(r.provider,r.conversationId)};box.appendChild(e);});
              }
              function openConversation(p,c){
                try{var r=JSON.parse(Android.conversation(p,c));var box=document.getElementById('vaultResults');if(!box)return;box.innerHTML='<button id="vaultBack" style="margin:4px 0 10px;padding:8px 11px;border:1px solid var(--stroke);border-radius:12px;background:rgba(255,255,255,.04);color:var(--txt)">‹ Back</button><div style="font-size:14px;font-weight:800;margin-bottom:9px">'+esc(r.title||'Untitled')+'</div>';document.getElementById('vaultBack').onclick=function(){box.innerHTML='';};(r.messages||[]).forEach(function(m){var e=document.createElement('div');e.style.cssText='border:1px solid var(--stroke);border-radius:14px;padding:10px;margin-bottom:7px;background:'+(String(m.role).toLowerCase()==='user'?'rgba(91,140,255,.08)':'rgba(255,255,255,.03)');e.innerHTML='<div style="font-size:8px;font-weight:800;letter-spacing:1.5px;color:var(--faint);margin-bottom:4px">'+esc(String(m.role||'').toUpperCase())+'</div><div style="font-size:12px;line-height:1.45;white-space:pre-wrap">'+esc(m.content||'')+'</div>';box.appendChild(e);});}catch(e){toast('Unable to open conversation');}}
              window.provider=function(name,el){providerName=name;q('#provList .prov-item').forEach(function(x){x.classList.remove('active')});if(el)el.classList.add('active');document.getElementById('pvInfo').textContent=name+' · LOCAL';toast('Provider: '+name);};
              function activeQuick(k){q('.chips .chip').forEach(function(x){x.classList.toggle('on',x.textContent.trim().toLowerCase()===k.toLowerCase())});}
              function runKind(k){activeQuick(k);document.getElementById('pvName').textContent=k.toUpperCase();document.getElementById('pvInfo').textContent=providerName+' · '+k;try{renderResults(Android.content(k,providerName),k)}catch(e){toast('Could not load '+k)};}
              window.quick=runKind;
              var forms=document.querySelector('.forms');
              if(forms&&!document.getElementById('vaultSearchWrap')){
                var w=document.createElement('div');w.id='vaultSearchWrap';w.style.cssText='display:flex;gap:7px;margin-top:8px';
                w.innerHTML='<input id="vaultSearch" type="search" autocomplete="off" placeholder="Search conversations, messages, code…" style="flex:1;min-width:0;height:36px;padding:0 12px;border:1px solid var(--stroke);border-radius:13px;background:rgba(255,255,255,.035);color:var(--txt);outline:none;font-size:12px"><button id="vaultSearchBtn" style="height:36px;padding:0 12px;border:1px solid var(--stroke);border-radius:13px;background:rgba(255,255,255,.05);color:var(--txt);font-weight:800">Search</button>';
                forms.appendChild(w);
                function doSearch(){var i=document.getElementById('vaultSearch');var t=(i&&i.value||'').trim();if(!t){toast('Enter a search');return;}toast('Searching…');try{renderResults(Android.search(t,providerName),'Search')}catch(e){toast('Search failed')}}
                document.getElementById('vaultSearchBtn').onclick=doSearch;document.getElementById('vaultSearch').onkeydown=function(e){if(e.key==='Enter')doSearch()};
              }
              q('#provList .prov-item').forEach(function(el,i){el.onclick=function(){provider(el.childNodes.length?el.textContent.trim().replace(/—.*$/,'').replace(/\d+[.\d]*$/,'').trim():providerName,el)}});
              q('.chips .chip').forEach(function(c){c.onclick=function(){runKind(c.textContent.trim())};});
              q('#contentGrid .card').forEach(function(c){c.onclick=function(){var k=c.querySelector('.cl')?c.querySelector('.cl').textContent.trim():c.textContent.trim();q('#contentGrid .card').forEach(function(x){x.classList.remove('on')});c.classList.add('on');runKind(k)}});
              q('.filter-row .pill').forEach(function(p){p.onclick=function(){q('.filter-row .pill').forEach(function(x){x.classList.remove('on')});p.classList.add('on');var k=p.textContent.trim();if(k!=='By Date'&&k!=='By Provider'&&k!=='By Project'&&k!=='By Type'&&k!=='Organization'&&k!=='All'&&k!=='Recent'&&k!=='Favorites'){runKind(k)}else{renderResults(Android.content(k,providerName),k);toast(k)}}});
              var preview=document.querySelector('.preview');if(preview)preview.onclick=function(){runKind((document.querySelector('.chip.on')||{}).textContent||'Files')};
              var initial=document.querySelector('.chip.on'); if(initial) document.getElementById('pvName').textContent=initial.textContent.trim().toUpperCase();
            })();
        """.trimIndent()
        web.evaluateJavascript("$script;void(0)", null)
    }

    private fun js(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'"

    companion object { const val REQ_IMPORT = 42; const val REQ_EXPORT = 43 }
}
