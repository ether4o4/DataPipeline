package com.ether404.allknowledge

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject

class VaultActivity : Activity() {
    private lateinit var db: KnowledgeDb
    private lateinit var importer: ExportImporter
    private lateinit var web: WebView
    /** Currently selected project key; file imports land here. */
    private var pendingImportProjectKey: String = "chatgpt"

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
        fun pickImport() = pickImportFor(pendingImportProjectKey)

        @JavascriptInterface
        fun pickImportFor(projectKey: String) = runOnUiThread {
            pendingImportProjectKey = projectKey.trim().ifBlank { pendingImportProjectKey }
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/json",
                    "application/octet-stream",
                    "application/pdf",
                    "text/plain",
                    "text/html",
                    "text/csv",
                    "text/markdown",
                    "text/*",
                    "*/*"
                ))
            }, REQ_IMPORT)
        }

        @JavascriptInterface
        fun selectProject(key: String) {
            pendingImportProjectKey = key.trim().ifBlank { pendingImportProjectKey }
        }

        @JavascriptInterface
        fun listProjects(): String = projectsJson()

        @JavascriptInterface
        fun promptNewProject() = runOnUiThread { showNewProjectDialog() }

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
            val rows = db.search(needle, 5000)
            val p = normalizeProvider(provider)
            return resultsJson(if (p.isBlank()) rows else rows.filter { it.provider.equals(p, true) })
        }

        @JavascriptInterface
        fun content(kind: String, provider: String): String {
            val key = kind.trim().lowercase()
            val p = normalizeProvider(provider)
            val d = db.readableDatabase
            val out = mutableListOf<KnowledgeDb.Result>()
            val limit = 50000
            val providerArgs = if (p.isBlank()) null else arrayOf(p)

            fun conversationSql() = if (p.isBlank()) {
                "SELECT lower(provider),conversation_id,COALESCE(title,'Untitled'),updated_at,created_at FROM conversations ORDER BY COALESCE(updated_at,created_at) DESC,id DESC LIMIT $limit"
            } else {
                "SELECT lower(provider),conversation_id,COALESCE(title,'Untitled'),updated_at,created_at FROM conversations WHERE lower(provider)=? ORDER BY COALESCE(updated_at,created_at) DESC,id DESC LIMIT $limit"
            }

            fun readConversations(sql: String, args: Array<String>?) {
                d.rawQuery(sql, args).use { c ->
                    while (c.moveToNext()) {
                        out += KnowledgeDb.Result(
                            c.getString(0), c.getString(1), "", "conversation",
                            c.getString(2) ?: "Untitled",
                            c.getString(3) ?: c.getString(4) ?: ""
                        )
                    }
                }
            }

            when (key) {
                "conversations", "recent", "favorites", "organization", "projects", "all", "by date", "by provider", "by source", "by project", "by type" -> {
                    readConversations(conversationSql(), providerArgs)
                }

                "messages" -> {
                    val sql = if (p.isBlank()) {
                        "SELECT lower(m.provider),m.conversation_id,m.message_id,COALESCE(m.role,'message'),COALESCE(c.title,'Untitled'),substr(m.content,1,320) FROM messages m LEFT JOIN conversations c ON c.provider=m.provider AND c.conversation_id=m.conversation_id ORDER BY m.id DESC LIMIT $limit"
                    } else {
                        "SELECT lower(m.provider),m.conversation_id,m.message_id,COALESCE(m.role,'message'),COALESCE(c.title,'Untitled'),substr(m.content,1,320) FROM messages m LEFT JOIN conversations c ON c.provider=m.provider AND c.conversation_id=m.conversation_id WHERE lower(m.provider)=? ORDER BY m.id DESC LIMIT $limit"
                    }
                    d.rawQuery(sql, providerArgs).use { c ->
                        while (c.moveToNext()) out += row(c)
                    }
                }

                "artifacts", "code", "files", "images" -> {
                    val kindWhere = when (key) {
                        "code" -> "(lower(COALESCE(a.kind,'')) LIKE '%code%' OR lower(COALESCE(a.language,'')) NOT IN ('','text'))"
                        "files" -> "lower(COALESCE(a.kind,'')) NOT LIKE '%code%' AND lower(COALESCE(a.kind,'')) NOT LIKE '%image%'"
                        "images" -> "lower(COALESCE(a.kind,'')) LIKE '%image%' OR lower(COALESCE(a.language,'')) IN ('png','jpg','jpeg','gif','webp','svg')"
                        else -> "1=1"
                    }
                    val sql = if (p.isBlank()) {
                        "SELECT lower(a.provider),COALESCE(a.conversation_id,''),COALESCE(a.message_id,''),COALESCE(a.kind,'artifact'),COALESCE(a.title,'Untitled artifact'),substr(a.content,1,320) FROM artifacts a WHERE $kindWhere ORDER BY a.id DESC LIMIT $limit"
                    } else {
                        "SELECT lower(a.provider),COALESCE(a.conversation_id,''),COALESCE(a.message_id,''),COALESCE(a.kind,'artifact'),COALESCE(a.title,'Untitled artifact'),substr(a.content,1,320) FROM artifacts a WHERE lower(a.provider)=? AND $kindWhere ORDER BY a.id DESC LIMIT $limit"
                    }
                    d.rawQuery(sql, providerArgs).use { c ->
                        while (c.moveToNext()) out += row(c)
                    }
                }

                "tool calls", "commands", "results", "memories" -> {
                    val needle = when (key) {
                        "tool calls" -> "%tool%"
                        "commands" -> "%command%"
                        "results" -> "%result%"
                        else -> "%memor%"
                    }
                    val sql = if (p.isBlank()) {
                        "SELECT lower(m.provider),m.conversation_id,m.message_id,COALESCE(m.role,'message'),COALESCE(c.title,'Untitled'),substr(m.content,1,320) FROM messages m LEFT JOIN conversations c ON c.provider=m.provider AND c.conversation_id=m.conversation_id WHERE lower(m.content) LIKE ? ORDER BY m.id DESC LIMIT $limit"
                    } else {
                        "SELECT lower(m.provider),m.conversation_id,m.message_id,COALESCE(m.role,'message'),COALESCE(c.title,'Untitled'),substr(m.content,1,320) FROM messages m LEFT JOIN conversations c ON c.provider=m.provider AND c.conversation_id=m.conversation_id WHERE lower(m.provider)=? AND lower(m.content) LIKE ? ORDER BY m.id DESC LIMIT $limit"
                    }
                    val args = if (p.isBlank()) arrayOf(needle) else arrayOf(p, needle)
                    d.rawQuery(sql, args).use { c -> while (c.moveToNext()) out += row(c) }
                }

                "shared links" -> {
                    val sql = if (p.isBlank()) {
                        "SELECT lower(m.provider),m.conversation_id,m.message_id,COALESCE(m.role,'message'),COALESCE(c.title,'Untitled'),substr(m.content,1,320) FROM messages m LEFT JOIN conversations c ON c.provider=m.provider AND c.conversation_id=m.conversation_id WHERE m.content LIKE '%http://%' OR m.content LIKE '%https://%' ORDER BY m.id DESC LIMIT $limit"
                    } else {
                        "SELECT lower(m.provider),m.conversation_id,m.message_id,COALESCE(m.role,'message'),COALESCE(c.title,'Untitled'),substr(m.content,1,320) FROM messages m LEFT JOIN conversations c ON c.provider=m.provider AND c.conversation_id=m.conversation_id WHERE lower(m.provider)=? AND (m.content LIKE '%http://%' OR m.content LIKE '%https://%') ORDER BY m.id DESC LIMIT $limit"
                    }
                    d.rawQuery(sql, providerArgs).use { c -> while (c.moveToNext()) out += row(c) }
                }

                else -> {
                    val sql = if (p.isBlank()) {
                        "SELECT lower(m.provider),m.conversation_id,m.message_id,COALESCE(m.role,'message'),COALESCE(c.title,'Untitled'),substr(m.content,1,320) FROM messages m LEFT JOIN conversations c ON c.provider=m.provider AND c.conversation_id=m.conversation_id ORDER BY m.id DESC LIMIT $limit"
                    } else {
                        "SELECT lower(m.provider),m.conversation_id,m.message_id,COALESCE(m.role,'message'),COALESCE(c.title,'Untitled'),substr(m.content,1,320) FROM messages m LEFT JOIN conversations c ON c.provider=m.provider AND c.conversation_id=m.conversation_id WHERE lower(m.provider)=? ORDER BY m.id DESC LIMIT $limit"
                    }
                    d.rawQuery(sql, providerArgs).use { c -> while (c.moveToNext()) out += row(c) }
                }
            }
            return resultsJson(out)
        }

        private fun row(c: android.database.Cursor) = KnowledgeDb.Result(
            c.getString(0) ?: "",
            c.getString(1) ?: "",
            c.getString(2) ?: "",
            c.getString(3) ?: "message",
            c.getString(4) ?: "Untitled",
            c.getString(5) ?: ""
        )

        @JavascriptInterface
        fun conversation(provider: String, conversationId: String): String {
            val (title, messages) = db.conversation(provider, conversationId)
            return JSONObject().apply {
                put("provider", provider)
                put("title", title)
                put("messages", JSONArray().apply {
                    messages.forEach { m ->
                        put(JSONObject().apply {
                            put("role", m.role)
                            put("content", m.content)
                            put("created", m.created)
                        })
                    }
                })
            }.toString()
        }

        private fun resultsJson(results: List<KnowledgeDb.Result>) = JSONArray().apply {
            results.forEach { r ->
                put(JSONObject().apply {
                    put("provider", r.provider)
                    put("conversationId", r.conversationId)
                    put("messageId", r.messageId)
                    put("role", r.role)
                    put("title", r.title)
                    put("snippet", r.snippet)
                })
            }
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
                val r = importer.importAny(uri, pendingImportProjectKey) { msg ->
                    runOnUiThread { web.evaluateJavascript("toast(${js(msg)})", null) }
                }
                runOnUiThread {
                    if (!r.openProvider.isNullOrBlank()) pendingImportProjectKey = r.openProvider!!
                    refreshStats()
                    web.evaluateJavascript(
                        "toast(${js("Imported ${r.provider}: ${r.conversations} conversations, ${r.messages} messages")})",
                        null
                    )
                    installVaultInteractions()
                    openAfterImport(r)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    web.evaluateJavascript("toast(${js("Import failed: ${e.message ?: "unknown error"}")})", null)
                }
            }
        }.start()
    }

    /** Navigate vault UI to the newly imported thread (or its project conversation list). */
    private fun openAfterImport(r: ExportImporter.ImportResult) {
        val provider = r.openProvider?.trim().orEmpty()
        val cid = r.openConversationId?.trim().orEmpty()
        if (provider.isEmpty()) return
        val label = db.findProject(provider)?.label ?: when (provider.lowercase()) {
            "file" -> "File"
            "chatgpt" -> "ChatGPT"
            "claude" -> "Claude"
            else -> provider.replaceFirstChar { it.uppercase() }
        }
        val keyJs = js(provider)
        val labelJs = js(label)
        val selJs = js("#provList .prov-item[data-provider=\"$provider\"]")
        val script = if (cid.isNotEmpty()) {
            """
            (function(){
              try {
                var el=document.querySelector($selJs);
                if(window.provider) window.provider($labelJs, el||null, $keyJs);
                if(window.openConversation) window.openConversation($keyJs, ${js(cid)});
                else toast('Imported — open Conversations to view');
              } catch(e) { toast('Imported — tap the project to view'); }
            })();
            """.trimIndent()
        } else {
            """
            (function(){
              try {
                var el=document.querySelector($selJs);
                if(window.provider) window.provider($labelJs, el||null, $keyJs);
                if(window.quick) window.quick('Conversations');
              } catch(e) { toast('Imported — tap Conversations to view'); }
            })();
            """.trimIndent()
        }
        web.evaluateJavascript(script, null)
    }

    private fun exportSelected(uri: Uri) {
        Thread {
            try {
                getDatabasePath("knowledge.db").inputStream().use { input ->
                    contentResolver.openOutputStream(uri).use { output ->
                        requireNotNull(output) { "Could not open export destination" }
                        input.copyTo(output)
                    }
                }
                runOnUiThread { web.evaluateJavascript("toast('Export complete')", null) }
            } catch (e: Exception) {
                runOnUiThread {
                    web.evaluateJavascript("toast(${js("Export failed: ${e.message ?: "unknown error"}")})", null)
                }
            }
        }.start()
    }

    private fun refreshStats() {
        val s = db.stats()
        val code = db.readableDatabase.rawQuery(
            "SELECT count(*) FROM artifacts WHERE lower(COALESCE(kind,'')) LIKE '%code%' OR (language IS NOT NULL AND trim(language)<>'' AND lower(language)<>'text')",
            null
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val projects = db.listProjects()
        val gpt = projects.firstOrNull { it.key.equals("chatgpt", true) }?.count ?: db.providerCount("chatgpt")
        val claude = projects.firstOrNull { it.key.equals("claude", true) }?.count ?: db.providerCount("claude")
        val file = projects.firstOrNull { it.key.equals("file", true) }?.count ?: db.providerCount("file")
        val json = projectsJson()
        val selected = pendingImportProjectKey
        runOnUiThread {
            web.evaluateJavascript("setStats(${s[0]},${s[1]},${s[2]},$code,$gpt,$claude,$file);", null)
            web.evaluateJavascript("if(window.renderProjects)renderProjects($json,${js(selected)});", null)
        }
    }

    private fun installVaultInteractions() {
        val script = """
            (function(){
              if(window.__vaultLoopV6)return;
              window.__vaultLoopV6=true;
              var projectKey=window.projectKey||'chatgpt';
              var projectName=window.projectName||'ChatGPT';
              function q(s){return Array.prototype.slice.call(document.querySelectorAll(s));}
              function esc(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;').replace(/'/g,'&#39;');}
              function toast(m){var t=document.getElementById('toast');if(!t){t=document.createElement('div');t.id='toast';t.className='toast';document.body.appendChild(t);}t.textContent=m;t.classList.add('show');clearTimeout(window.__vaultToast);window.__vaultToast=setTimeout(function(){t.classList.remove('show')},1700);}
              window.toast=toast;
              function ensureResults(){
                var box=document.getElementById('vaultResults');
                if(!box){box=document.createElement('div');box.id='vaultResults';box.style.cssText='width:100%;margin-top:8px;max-height:52vh;overflow:auto;padding-right:2px';var center=document.querySelector('.center');if(center)center.appendChild(box);}
                return box;
              }
              function renderResults(raw,title){
                var data=[];try{data=JSON.parse(raw||'[]')}catch(e){toast('Data parse failed');return;}
                var box=ensureResults();
                box.innerHTML='<div style="font-size:10px;color:var(--faint);font-weight:800;letter-spacing:2px;text-transform:uppercase;margin:6px 0 9px">'+esc(title)+' · '+data.length+'</div>';
                if(!data.length){box.innerHTML+='<div style="padding:12px;color:var(--dim);font-size:12px">No matching data.</div>';return;}
                var frag=document.createDocumentFragment();
                data.forEach(function(r){
                  var e=document.createElement('div');
                  e.style.cssText='border:1px solid var(--stroke);border-radius:14px;padding:11px;margin-bottom:7px;background:rgba(255,255,255,.03);cursor:pointer';
                  e.innerHTML='<div style="font-size:12px;font-weight:800">'+esc(r.title||'Untitled')+'</div><div style="margin-top:3px;font-size:9px;color:var(--dim);letter-spacing:.5px">'+esc((r.provider||'').toUpperCase())+' · '+esc(r.role||'')+'</div><div style="margin-top:6px;font-size:11px;color:var(--dim);line-height:1.45;white-space:pre-wrap">'+esc(r.snippet||'')+'</div>';
                  e.onclick=function(){openConversation(r.provider,r.conversationId)};
                  frag.appendChild(e);
                });
                box.appendChild(frag);
              }
              function openConversation(p,c){
                try{
                  var r=JSON.parse(Android.conversation(p,c));
                  var box=ensureResults();
                  var msgs=r.messages||[];
                  box.innerHTML='';
                  var head=document.createElement('div');
                  head.innerHTML='<button id="vaultBack" style="margin:4px 0 10px;padding:8px 11px;border:1px solid var(--stroke);border-radius:12px;background:rgba(255,255,255,.04);color:var(--txt)">‹ Back</button><div style="font-size:14px;font-weight:800;margin-bottom:8px">'+esc(r.title||'Untitled')+'</div>';
                  box.appendChild(head);
                  document.getElementById('vaultBack').onclick=function(){runKind('Conversations')};
                  var srow=document.createElement('div');
                  srow.style.cssText='display:flex;gap:6px;align-items:center;margin-bottom:8px;flex-wrap:wrap';
                  srow.innerHTML='<input id="threadSearch" type="search" placeholder="Search this conversation" style="flex:1;min-width:120px;height:34px;padding:0 10px;border:1px solid var(--stroke);border-radius:12px;background:rgba(255,255,255,.035);color:var(--txt);outline:none;font-size:12px"><span id="threadMatch" style="font-size:10px;color:var(--dim);font-weight:800;min-width:72px">'+msgs.length+' MSG</span><button id="threadPrev" style="height:34px;padding:0 10px;border:1px solid var(--stroke);border-radius:12px;background:rgba(255,255,255,.05);color:var(--txt)">▲</button><button id="threadNext" style="height:34px;padding:0 10px;border:1px solid var(--stroke);border-radius:12px;background:rgba(255,255,255,.05);color:var(--txt)">▼</button>';
                  box.appendChild(srow);
                  var list=document.createElement('div');list.id='threadList';box.appendChild(list);
                  var nodes=[];
                  msgs.forEach(function(m,i){
                    var e=document.createElement('div');
                    e.setAttribute('data-idx',String(i));
                    e.style.cssText='border:1px solid var(--stroke);border-radius:14px;padding:10px;margin-bottom:7px;background:'+(String(m.role).toLowerCase()==='user'||String(m.role).toLowerCase()==='human'?'rgba(91,140,255,.08)':'rgba(255,255,255,.03)');
                    e.innerHTML='<div style="font-size:8px;font-weight:800;letter-spacing:1.5px;color:var(--faint);margin-bottom:4px">'+esc(String(m.role||'').toUpperCase())+'</div><div class="tbody" style="font-size:12px;line-height:1.45;white-space:pre-wrap">'+esc(m.content||'')+'</div>';
                    list.appendChild(e);nodes.push(e);
                  });
                  var hits=[];var hitAt=0;
                  function paint(){
                    nodes.forEach(function(n,i){
                      n.style.outline='';
                      var role=String((msgs[i]&&msgs[i].role)||'').toLowerCase();
                      n.style.background=(role==='user'||role==='human')?'rgba(91,140,255,.08)':'rgba(255,255,255,.03)';
                    });
                    if(!hits.length){document.getElementById('threadMatch').textContent=(document.getElementById('threadSearch').value||'').trim()?'0 matches':(msgs.length+' MSG');return;}
                    document.getElementById('threadMatch').textContent=(hitAt+1)+' of '+hits.length;
                    var el=nodes[hits[hitAt]];if(!el)return;
                    el.style.outline='2px solid #e0b040';
                    el.style.background='rgba(224,176,64,.18)';
                    el.scrollIntoView({behavior:'smooth',block:'center'});
                  }
                  function runThreadSearch(){
                    var q=(document.getElementById('threadSearch').value||'').trim().toLowerCase();
                    hits=[];hitAt=0;
                    if(!q){paint();return;}
                    msgs.forEach(function(m,i){
                      var blob=((m.content||'')+' '+(m.role||'')+' '+(m.created||'')).toLowerCase();
                      if(blob.indexOf(q)>=0)hits.push(i);
                    });
                    paint();
                  }
                  document.getElementById('threadSearch').oninput=runThreadSearch;
                  document.getElementById('threadPrev').onclick=function(){if(!hits.length)return;hitAt=(hitAt-1+hits.length)%hits.length;paint();};
                  document.getElementById('threadNext').onclick=function(){if(!hits.length)return;hitAt=(hitAt+1)%hits.length;paint();};
                }catch(e){toast('Unable to open conversation');}
              }
              window.openConversation=openConversation;
              window.provider=function(name,el,key){
                projectName=name||projectName||'ChatGPT';
                projectKey=key||(el&&el.getAttribute('data-provider'))||projectKey||'chatgpt';
                window.projectName=projectName;
                window.projectKey=projectKey;
                q('#provList .prov-item').forEach(function(x){x.classList.remove('active')});
                if(el)el.classList.add('active');
                var info=document.getElementById('pvInfo');if(info)info.textContent=projectName+' · LOCAL';
                try{Android.selectProject(projectKey);}catch(e){}
                toast('Project: '+projectName);
                var initial=document.querySelector('.chip.on');if(initial)runKind(initial.textContent.trim());
              };
              window.renderProjects=function(raw,selectedKey){
                var data=[];try{data=JSON.parse(raw||'[]')}catch(e){return;}
                var box=document.getElementById('provList'); if(!box)return;
                var sel=selectedKey||projectKey||'chatgpt';
                projectKey=sel; window.projectKey=sel;
                box.innerHTML='';
                data.forEach(function(p){
                  var el=document.createElement('div');
                  el.className='prov-item'+(p.key===sel?' active':'');
                  el.setAttribute('data-provider',p.key);
                  el.innerHTML='<span class="ic">'+esc(p.icon||(p.label||'P').charAt(0))+'</span>'+esc(p.label||p.key)+'<span class="badge">'+esc(String(p.count==null?0:p.count))+'</span>';
                  el.onclick=function(){window.provider(p.label,el,p.key)};
                  box.appendChild(el);
                  if(p.key===sel){projectName=p.label||projectName;window.projectName=projectName;}
                });
                var cnt=document.getElementById('provcnt'); if(cnt)cnt.textContent=String(data.length);
                var info=document.getElementById('pvInfo');
                if(info)info.textContent=projectName+' · LOCAL';
              };
              function activeQuick(k){q('.chips .chip').forEach(function(x){x.classList.toggle('on',x.textContent.trim().toLowerCase()===k.toLowerCase())});}
              function runKind(k){
                activeQuick(k);
                var pn=document.getElementById('pvName');if(pn)pn.textContent=String(k).toUpperCase();
                var pi=document.getElementById('pvInfo');if(pi)pi.textContent=projectName+' · '+k;
                try{renderResults(Android.content(k,projectKey),k);}catch(e){toast('Could not load '+k);}
              }
              window.quick=runKind;
              var forms=document.querySelector('.forms');
              if(forms&&!document.getElementById('vaultSearchWrap')){
                var w=document.createElement('div');w.id='vaultSearchWrap';w.style.cssText='display:flex;gap:7px;margin-top:8px';
                w.innerHTML='<input id="vaultSearch" type="search" autocomplete="off" placeholder="Search conversations, messages, code…" style="flex:1;min-width:0;height:36px;padding:0 12px;border:1px solid var(--stroke);border-radius:13px;background:rgba(255,255,255,.035);color:var(--txt);outline:none;font-size:12px"><button id="vaultSearchBtn" style="height:36px;padding:0 12px;border:1px solid var(--stroke);border-radius:13px;background:rgba(255,255,255,.05);color:var(--txt);font-weight:800">Search</button>';
                forms.appendChild(w);
                function doSearch(){var i=document.getElementById('vaultSearch');var t=(i&&i.value||'').trim();if(!t){toast('Enter a search');return;}toast('Searching…');try{renderResults(Android.search(t,projectKey),'Search');}catch(e){toast('Search failed');}}
                document.getElementById('vaultSearchBtn').onclick=doSearch;
                document.getElementById('vaultSearch').onkeydown=function(e){if(e.key==='Enter'){e.preventDefault();doSearch();}};
              }
              q('#provList .prov-item').forEach(function(el){el.onclick=function(){window.provider((el.getAttribute('data-provider')||'chatgpt'),el,el.getAttribute('data-provider'));};});
              var np=document.getElementById('newProjectBtn');if(np)np.onclick=function(){try{Android.promptNewProject();}catch(e){toast('Cannot create project');}};
              q('.chips .chip').forEach(function(c){c.onclick=function(){runKind(c.textContent.trim());};});
              q('.content-grid .card').forEach(function(c){c.onclick=function(){var k=c.querySelector('.cl')?c.querySelector('.cl').textContent.trim():c.textContent.trim();q('.content-grid .card').forEach(function(x){x.classList.remove('on')});c.classList.add('on');runKind(k);};});
              q('.filter-row .pill').forEach(function(p){p.onclick=function(){q('.filter-row .pill').forEach(function(x){x.classList.remove('on')});p.classList.add('on');var k=p.textContent.trim();renderResults(Android.content(k,projectKey),k);};});
              var preview=document.querySelector('.preview');if(preview)preview.onclick=function(){runKind((document.querySelector('.chip.on')||{}).textContent||'Files');};
              var initial=document.querySelector('.chip.on');if(initial){var pn=document.getElementById('pvName');if(pn)pn.textContent=initial.textContent.trim().toUpperCase();}
              window.__vaultInstalledProvider=projectName;
            })();
        """.trimIndent()
        web.evaluateJavascript("$script;void(0)", null)
    }

    /**
     * Map vault display provider names to DB keys.
     * Blank / All / Other AI → no provider filter.
     */
    private fun normalizeProvider(provider: String): String {
        val raw = provider.trim()
        if (raw.isEmpty()) return ""
        when (raw.lowercase()) {
            "all", "other ai", "other", "any" -> return ""
            "chatgpt", "openai" -> return "chatgpt"
            "claude", "anthropic", "claude code" -> return "claude"
            "file", "files", "local", "imported" -> return "file"
        }
        db.findProject(raw)?.let { return it.key }
        return raw.lowercase()
    }

    private fun projectsJson(): String {
        val arr = JSONArray()
        db.listProjects().forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("key", p.key)
                put("label", p.label)
                put("isSystem", p.isSystem)
                put("count", p.count)
                put("icon", when (p.key.lowercase()) {
                    "chatgpt" -> "G"
                    "claude" -> "C"
                    "file" -> "F"
                    else -> p.label.firstOrNull()?.uppercaseChar()?.toString() ?: "P"
                })
            })
        }
        return arr.toString()
    }

    private fun showNewProjectDialog() {
        val input = EditText(this).apply {
            hint = "Project name"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("New project")
            .setMessage("Name this project. File imports will land here as threads.")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                try {
                    val created = db.createProject(input.text.toString())
                    pendingImportProjectKey = created.key
                    refreshStats()
                    web.evaluateJavascript(
                        """
                        (function(){
                          toast(${js("Project: ${created.label}")});
                          var el=document.querySelector(${js("#provList .prov-item[data-provider=\"${created.key}\"]")});
                          if(window.provider) window.provider(${js(created.label)}, el||null, ${js(created.key)});
                        })();
                        """.trimIndent(),
                        null
                    )
                } catch (e: Exception) {
                    web.evaluateJavascript("toast(${js(e.message ?: "Could not create project")})", null)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun js(s: String) = "'" + s
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", " ") + "'"

    companion object {
        const val REQ_IMPORT = 42
        const val REQ_EXPORT = 43
    }
}
