package com.ether404.allknowledge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView

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
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    installVaultInteractions()
                    refreshStats()
                }
            }
        }
        setContentView(web)
    }

    inner class Bridge {
        @JavascriptInterface
        fun pickImport() = runOnUiThread {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/json",
                    "*/*"
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
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
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
                    web.evaluateJavascript(
                        "toast(${js("Imported ${r.provider}: ${r.conversations} conversations, ${r.messages} messages")})",
                        null
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    web.evaluateJavascript("toast(${js("Import failed: ${e.message ?: "unknown error"}")})", null)
                }
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
                runOnUiThread {
                    web.evaluateJavascript("toast(${js("Export failed: ${e.message ?: "unknown error"}")})", null)
                }
            }
        }.start()
    }

    private fun refreshStats() {
        val s = db.stats()
        val codeCount = countCodeArtifacts()
        runOnUiThread {
            val script = """
                setStats(${s[0]},${s[1]},${s[2]},${s[2]},${providerCount("chatgpt")},${providerCount("claude")},${codeCount});
            """.trimIndent()
            web.evaluateJavascript(script, null)
        }
    }

    private fun providerCount(provider: String): Long {
        return db.readableDatabase.rawQuery(
            "SELECT count(*) FROM conversations WHERE lower(provider)=?",
            arrayOf(provider.lowercase())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    private fun countCodeArtifacts(): Long {
        return db.readableDatabase.rawQuery(
            "SELECT count(*) FROM artifacts WHERE lower(kind) LIKE '%code%' OR lower(language) LIKE '%'",
            null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    private fun installVaultInteractions() {
        val script = """
            (function(){
              if(window.__vaultWired){ return; }
              window.__vaultWired = true;

              function qsa(s){ return Array.prototype.slice.call(document.querySelectorAll(s)); }
              function setActive(list, el){ list.forEach(function(x){x.classList.remove('on');}); if(el) el.classList.add('on'); }
              function selectedProvider(){
                var p=qsa('#provList .prov-item').find(function(x){return x.classList.contains('active');});
                return p ? p.textContent.replace(/\s+\S+$/,'').trim() : 'ChatGPT';
              }
              function selectedType(){
                var c=qsa('.chips .chip').find(function(x){return x.classList.contains('on');});
                return c ? c.textContent.trim() : 'Files';
              }
              function selectQuick(type, announce){
                var chips=qsa('.chips .chip');
                var chip=chips.find(function(x){return x.textContent.trim().toLowerCase()===type.toLowerCase();});
                setActive(chips, chip);
                var pv=document.getElementById('pvName');
                var info=document.getElementById('pvInfo');
                if(pv){ pv.textContent = type.toUpperCase(); }
                if(info){ info.textContent = selectedProvider() + ' · ' + type; }
                var big=document.querySelector('.val');
                if(big){ big.innerHTML = type + ' <span>· tap to enlarge</span>'; }
                var cards=qsa('.card');
                cards.forEach(function(card){
                  card.classList.toggle('on', card.querySelector('.cl') && card.querySelector('.cl').textContent.trim().toLowerCase()===type.toLowerCase());
                });
                var status=document.getElementById('status');
                if(status){ status.textContent='QUICK VIEW · '+type+' · '+selectedProvider(); }
                if(announce && window.toast){ toast('View: '+type); }
              }

              qsa('.chips .chip').forEach(function(chip){
                chip.addEventListener('click', function(){ selectQuick(chip.textContent.trim(), true); });
              });

              qsa('.content-grid .card').forEach(function(card){
                card.addEventListener('click', function(){
                  var name=card.querySelector('.cl') ? card.querySelector('.cl').textContent.trim() : 'Content';
                  var count=card.querySelector('.cn') ? card.querySelector('.cn').textContent.trim() : '—';
                  var type=name;
                  if(['Projects','Tool Calls','Commands','Results','Shared Links','Memories','Conversations','Messages','Artifacts','Files','Images','Code'].indexOf(type)>=0){
                    var quick = ['Files','Images','Code','Artifacts','Projects'].indexOf(type)>=0 ? type : null;
                    if(quick){ selectQuick(quick, false); }
                  }
                  qsa('.content-grid .card').forEach(function(x){x.classList.remove('on');});
                  card.classList.add('on');
                  var pv=document.getElementById('pvName');
                  var info=document.getElementById('pvInfo');
                  if(pv){ pv.textContent = name.toUpperCase(); }
                  if(info){ info.textContent = selectedProvider() + ' · ' + name + ' · ' + count; }
                  var big=document.querySelector('.val');
                  if(big){ big.innerHTML = name + ' <span>· tap to enlarge</span>'; }
                  var status=document.getElementById('status');
                  if(status){ status.textContent='CONTENT · '+name+' · '+count; }
                  if(window.toast){ toast('Content: '+name); }
                });
              });

              qsa('#provList .prov-item').forEach(function(row){
                row.addEventListener('click', function(){
                  setTimeout(function(){
                    var p=row.textContent.replace(/\s+\S+$/,'').trim();
                    var info=document.getElementById('pvInfo');
                    if(info){ info.textContent=p+' · '+selectedType(); }
                    var status=document.getElementById('status');
                    if(status){ status.textContent='PROVIDER · '+p+' · '+selectedType(); }
                  },0);
                });
              });

              qsa('.pill').forEach(function(pill){
                pill.addEventListener('click', function(){
                  var group=pill.closest('.filter-row');
                  if(group){ qsa('.pill', group).forEach(function(x){x.classList.remove('on');}); }
                  pill.classList.add('on');
                  if(window.toast){ toast(pill.textContent.trim()); }
                });
              });

              var preview=document.querySelector('.preview');
              if(preview){
                preview.addEventListener('click', function(){
                  var pv=document.getElementById('pvName');
                  var type=pv ? pv.textContent.trim() : selectedType();
                  if(window.toast){ toast('Preview: '+type); }
                });
              }

              window.setStats = function(convos,messages,artifacts,ignored,gpt,claude,code){
                function put(id,val){var e=document.getElementById(id); if(e){e.textContent=val;}}
                put('convos',convos); put('messages',messages); put('artifacts',artifacts); put('gptCount',gpt); put('claudeCount',claude); put('code',code);
              };

              window.toast = window.toast || function(m){
                var t=document.getElementById('toast'); if(!t)return;
                var s=t.querySelector('#toastMsg'); if(s)s.textContent=m;
                t.classList.add('show');
                clearTimeout(window.__vaultToast);
                window.__vaultToast=setTimeout(function(){t.classList.remove('show');},1600);
              };

              selectQuick('Files', false);
            })();
        """.trimIndent()
        web.evaluateJavascript("$script;void(0)", null)
    }

    private fun js(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'"

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    companion object {
        const val REQ_IMPORT = 42
        const val REQ_EXPORT = 43
    }
}
