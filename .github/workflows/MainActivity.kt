package com.novelvoz

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

private const val DARK_JS =
    "(function(){if(document.getElementById('nv-dark'))return;var s=document.createElement('style');" +
        "s.id='nv-dark';s.textContent='html{filter:invert(1) hue-rotate(180deg)!important;background:#fff!important}" +
        "img,video,picture,canvas,iframe,svg{filter:invert(1) hue-rotate(180deg)!important}';" +
        "(document.head||document.documentElement).appendChild(s)})()"

private const val SCROLL_JS =
    "(function(){if(window.__nvs)return;window.__nvs=1;function f(){var d=document.documentElement," +
        "b=document.body;var h=Math.max(d.scrollHeight,b?b.scrollHeight:0)-window.innerHeight;" +
        "var y=window.pageYOffset||d.scrollTop||0;NV.scroll(h>0?Math.min(1000,Math.round(y*1000/h)):0)}" +
        "window.addEventListener('scroll',f,{passive:true});setTimeout(f,300)})()"

class Tab(val id: Int, var url: String, var title: String = "", var scroll: Int = 0) {
    var web: WebView? = null
    var btn: Button? = null
    var autoRead = false
    var restore = 0
}

class MainActivity : AppCompatActivity() {

    private val tabs = ArrayList<Tab>()
    private var cur: Tab? = null
    private var nextId = 1

    private lateinit var urlBar: EditText
    private lateinit var tabStrip: LinearLayout
    private lateinit var btnPlus: Button
    private lateinit var scrollBar: ProgressBar
    private lateinit var webHolder: FrameLayout
    private lateinit var readerScroll: ScrollView
    private lateinit var readerText: TextView
    private lateinit var status: TextView
    private lateinit var audioBar: ProgressBar
    private lateinit var novelLabel: TextView
    private lateinit var novelBar: ProgressBar
    private lateinit var btnPlay: Button
    private lateinit var btnSpeed: Button

    private val ui = Handler(Looper.getMainLooper())
    private val home = "https://www.google.com"
    private var lastBarUrl = ""

    private val speeds = floatArrayOf(0.8f, 1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f, 2.5f)

    private val langNames = linkedMapOf(
        "" to "Desligada (ler no idioma original)",
        "pt" to "Português", "en" to "Inglês", "es" to "Espanhol", "fr" to "Francês",
        "de" to "Alemão", "it" to "Italiano", "ja" to "Japonês", "ko" to "Coreano",
        "zh" to "Chinês", "ru" to "Russo", "id" to "Indonésio"
    )

    private val blocked = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google",
        "popads.net", "popcash.net", "propellerads", "adsterra", "exoclick", "juicyads",
        "trafficjunky", "outbrain.com", "taboola.com", "mgid.com", "revcontent.com",
        "onclickads", "clickadu", "adnxs.com"
    )

    // ------------------------------------------------------------------
    // utilidades
    // ------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun button(label: String, click: (View) -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener(click)
    }

    private fun thinBar(color: Int): ProgressBar =
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(color)
            progressBackgroundTintList = ColorStateList.valueOf(0x22888888)
        }

    private fun nightMode(n: Int) = when (n) {
        1 -> AppCompatDelegate.MODE_NIGHT_NO
        2 -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private fun isDark(): Boolean = when (Prefs.night(this)) {
        2 -> true
        1 -> false
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun send(action: String, extra: Intent.() -> Unit = {}) {
        ContextCompat.startForegroundService(
            this, Intent(this, ReaderService::class.java).setAction(action).apply(extra)
        )
    }

    // ------------------------------------------------------------------
    // ciclo de vida
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(nightMode(Prefs.night(this)))
        super.onCreate(savedInstanceState)
        buildUi()
        restoreTabs()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = cur
                when {
                    readerScroll.visibility == View.VISIBLE -> readerScroll.visibility = View.GONE
                    t?.web?.canGoBack() == true -> t.web?.goBack()
                    tabs.size > 1 && t != null -> closeTab(t)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val u = i.getStringExtra("url") ?: i.data?.toString()
        if (u.isNullOrEmpty()) return
        if (i.getBooleanExtra("autoread", false)) {
            val t = cur ?: return
            t.autoRead = true
            t.url = u
            ensureWeb(t).loadUrl(u)
            urlBar.setText(u)
        } else {
            addTab(u, true)
        }
    }

    override fun onStart() {
        super.onStart()
        Bridge.listener = { text, playing, active ->
            ui.post { showStatus(text, playing, active) }
        }
        showStatus(
            Bridge.lastText.ifEmpty { status.text.toString() },
            Bridge.lastPlaying, Bridge.lastActive
        )
    }

    override fun onStop() {
        saveTabs()
        Bridge.listener = null
        super.onStop()
    }

    override fun onDestroy() {
        for (t in tabs) {
            (t.web?.parent as? ViewGroup)?.removeView(t.web)
            t.web?.destroy()
            t.web = null
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // montagem da tela
    // ------------------------------------------------------------------

    private fun buildUi() {
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // endereço
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        urlBar = EditText(this).apply {
            setSingleLine()
            hint = "Endereço do site ou busca"
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ ->
                go(text.toString())
                true
            }
        }
        top.addView(urlBar, LinearLayout.LayoutParams(0, wc, 1f))
        top.addView(button("📖") { toggleReader() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(button("⋮") { showMenu(it) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(top)

        // abas
        tabStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnPlus = button("＋") { addTab(home, true) }
        tabStrip.addView(btnPlus, LinearLayout.LayoutParams(dp(44), dp(36)))
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabStrip)
        }
        root.addView(hs, LinearLayout.LayoutParams(mp, dp(38)))

        // barra de rolagem da página
        scrollBar = thinBar(0xFF26A69A.toInt())
        root.addView(scrollBar, LinearLayout.LayoutParams(mp, dp(3)))

        // conteúdo: páginas + modo texto
        val content = FrameLayout(this)
        webHolder = FrameLayout(this)
        content.addView(webHolder, FrameLayout.LayoutParams(mp, mp))
        readerText = TextView(this).apply {
            textSize = 18f
            setLineSpacing(0f, 1.25f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
        readerScroll = ScrollView(this).apply {
            visibility = View.GONE
            setBackgroundColor(themeColor(android.R.attr.colorBackground))
            addView(readerText)
        }
        content.addView(readerScroll, FrameLayout.LayoutParams(mp, mp))
        root.addView(content, LinearLayout.LayoutParams(mp, 0, 1f))

        // estado e barras de avanço
        status = TextView(this).apply {
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(2))
            text = "Abra o capítulo e toque em Ler"
        }
        root.addView(status)
        audioBar = thinBar(0xFFFF9800.toInt())
        root.addView(audioBar, LinearLayout.LayoutParams(mp, dp(3)))
        novelLabel = TextView(this).apply {
            textSize = 11f
            setPadding(dp(10), dp(3), dp(10), dp(1))
            visibility = View.GONE
        }
        root.addView(novelLabel)
        novelBar = thinBar(0xFF7E57C2.toInt())
        novelBar.visibility = View.GONE
        root.addView(novelBar, LinearLayout.LayoutParams(mp, dp(3)))

        // controles
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(2), dp(4), dp(4))
        }
        bar.addView(button("⏮") { send(ReaderService.A_PREV) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        btnPlay = button("▶ Ler") { onPlay() }
        btnPlay.setOnLongClickListener {
            readPage(false)
            true
        }
        bar.addView(btnPlay, LinearLayout.LayoutParams(0, dp(48), 2f))
        bar.addView(button("⏭") { send(ReaderService.A_NEXT) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        btnSpeed = button(speedLabel()) { cycleSpeed() }
        btnSpeed.setOnLongClickListener {
            voiceDialog()
            true
        }
        bar.addView(btnSpeed, LinearLayout.LayoutParams(0, dp(48), 1.2f))
        bar.addView(button("⏲") { pickTimer() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(bar)

        setContentView(root)
    }

    // ------------------------------------------------------------------
    // abas
    // ------------------------------------------------------------------

    private fun labelFor(t: Tab): String {
        val host = try { Uri.parse(t.url).host } catch (e: Exception) { null }
        val s = if (t.title.isNotBlank()) t.title else (host ?: "")
        return if (s.isBlank()) "Nova aba" else s.take(16)
    }

    private fun styleTab(t: Tab) {
        t.btn?.apply {
            alpha = if (t === cur) 1f else 0.55f
            setTypeface(null, if (t === cur) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun addTab(url: String, select: Boolean, title: String = "", scroll: Int = 0): Tab {
        val t = Tab(nextId++, url, title, scroll)
        val b = button(labelFor(t)) { selectTab(t) }.apply {
            maxWidth = dp(150)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setOnLongClickListener {
                closeTab(t)
                true
            }
        }
        t.btn = b
        tabs.add(t)
        tabStrip.addView(
            b, tabStrip.childCount - 1,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
        )
        if (select) selectTab(t) else styleTab(t)
        return t
    }

    private fun selectTab(t: Tab) {
        cur = t
        val w = ensureWeb(t)
        webHolder.removeAllViews()
        (w.parent as? ViewGroup)?.removeView(w)
        webHolder.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        urlBar.setText(t.url)
        scrollBar.progress = t.scroll
        for (x in tabs) styleTab(x)
        updateNovelBar()
        saveTabs()
    }

    private fun closeTab(t: Tab) {
        if (tabs.size == 1) {
            ensureWeb(t).loadUrl(home)
            return
        }
        val i = tabs.indexOf(t)
        tabs.remove(t)
        tabStrip.removeView(t.btn)
        (t.web?.parent as? ViewGroup)?.removeView(t.web)
        t.web?.destroy()
        t.web = null
        if (cur === t) selectTab(tabs[(i - 1).coerceAtLeast(0)]) else saveTabs()
    }

    private fun saveTabs() {
        val arr = JSONArray()
        for (t in tabs) arr.put(JSONObject().put("u", t.url).put("t", t.title).put("s", t.scroll))
        Prefs.sp(this).edit()
            .putString("tabs", arr.toString())
            .putInt("tabSel", tabs.indexOf(cur).coerceAtLeast(0))
            .apply()
    }

    private fun restoreTabs() {
        val sp = Prefs.sp(this)
        val saved = try { JSONArray(sp.getString("tabs", "[]")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until saved.length()) {
            val o = saved.getJSONObject(i)
            val u = o.optString("u")
            if (u.isNotBlank()) addTab(u, false, o.optString("t"), o.optInt("s"))
        }
        if (tabs.isEmpty()) addTab(home, false)
        selectTab(tabs[sp.getInt("tabSel", 0).coerceIn(0, tabs.size - 1)])
    }

    // ------------------------------------------------------------------
    // navegador
    // ------------------------------------------------------------------

    inner class Js(private val tab: Tab) {
        @JavascriptInterface
        fun scroll(p: Int) {
            tab.scroll = p
            if (tab === cur) ui.post { scrollBar.progress = p }
        }
    }

    private fun ensureWeb(t: Tab, load: Boolean = true): WebView {
        t.web?.let { return it }
        val w = WebView(this)
        setupWeb(t, w)
        t.web = w
        if (load) {
            t.restore = t.scroll
            w.loadUrl(if (t.url.isBlank()) home else t.url)
        }
        return w
    }

    private fun setupWeb(t: Tab, w: WebView) {
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }
        w.addJavascriptInterface(Js(t), "NV")

        w.webChromeClient = object : WebChromeClient() {
            // links que pedem janela nova abrem em aba; pop-ups sem toque são bloqueados
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                if (!isUserGesture) return false
                val nt = addTab("", false)
                val nw = ensureWeb(nt, false)
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = nw
                resultMsg.sendToTarget()
                selectTab(nt)
                return true
            }
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme
                return !(s == "http" || s == "https")
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val h = request.url.host ?: return null
                return if (blocked.any { h.contains(it) }) {
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else null
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (isDark()) view.evaluateJavascript(DARK_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                t.url = url
                t.title = view.title ?: ""
                t.btn?.text = labelFor(t)
                view.evaluateJavascript(SCROLL_JS, null)
                if (isDark()) view.evaluateJavascript(DARK_JS, null)
                if (t.restore > 0) {
                    val p = t.restore
                    t.restore = 0
                    ui.postDelayed({
                        t.web?.evaluateJavascript(
                            "(function(p){var d=document.documentElement,b=document.body;" +
                                "var h=Math.max(d.scrollHeight,b?b.scrollHeight:0)-window.innerHeight;" +
                                "window.scrollTo(0,h*p/1000)})($p)", null
                        )
                    }, 900)
                }
                if (t === cur) {
                    urlBar.setText(url)
                    updateNovelBar()
                }
                saveTabs()
                if (t.autoRead) {
                    t.autoRead = false
                    ui.postDelayed({ if (t === cur) readPage(false) }, 1500)
                }
            }
        }
    }

    private fun go(input: String) {
        val s = input.trim()
        if (s.isEmpty()) return
        val target = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            !s.contains(" ") && s.contains(".") -> "https://$s"
            else -> "https://www.google.com/search?q=" + Uri.encode(s)
        }
        openUrlHere(target)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun openUrlHere(url: String) {
        val t = cur ?: return
        t.url = url
        t.scroll = 0
        ensureWeb(t, false).loadUrl(url)
        urlBar.setText(url)
        readerScroll.visibility = View.GONE
    }

    // ------------------------------------------------------------------
    // leitura
    // ------------------------------------------------------------------

    private fun onPlay() {
        if (Bridge.lastActive) send(ReaderService.A_TOGGLE) else readPage(false)
    }

    private fun readPage(fromStart: Boolean) {
        val t = cur ?: return
        val w = t.web ?: return
        status.text = "Lendo a página…"
        w.evaluateJavascript("(function(){return document.documentElement.outerHTML})()") { v ->
            val html = try {
                JSONTokener(v).nextValue() as? String
            } catch (e: Exception) {
                null
            }
            if (html.isNullOrEmpty()) {
                status.text = "Não consegui ler esta página."
                return@evaluateJavascript
            }
            val url = w.url ?: t.url
            Bridge.html = html
            Bridge.url = url
            Bridge.userAgent = w.settings.userAgentString
            val n = Library.get(this, Library.key(url))
            val resume = !fromStart && n != null && n.seg > 0 &&
                Library.clean(n.url) == Library.clean(url)
            Bridge.startSeg = if (resume && n != null) n.seg else 0
            send(ReaderService.A_LOAD)
        }
    }

    private fun showStatus(text: String, playing: Boolean, active: Boolean) {
        status.text = text
        btnPlay.text = when {
            playing -> "⏸ Pausar"
            active -> "▶ Continuar"
            else -> "▶ Ler"
        }
        audioBar.progress = if (Bridge.count > 0) (Bridge.idx * 1000L / Bridge.count).toInt() else 0
        if (Bridge.url != lastBarUrl) {
            lastBarUrl = Bridge.url
            updateNovelBar()
        }
        refreshReader()
    }

    // ------------------------------------------------------------------
    // avanço da obra
    // ------------------------------------------------------------------

    private fun updateNovelBar() {
        val t = cur
        if (t == null || t.url.isBlank()) {
            novelLabel.visibility = View.GONE
            novelBar.visibility = View.GONE
            return
        }
        val key = Library.key(t.url)
        val n = Library.get(this, key)
        var ch = Library.chapterNum(t.title, t.url)
        if (Bridge.lastActive && Bridge.url.isNotEmpty() && Library.key(Bridge.url) == key &&
            n != null && n.chapter > 0
        ) ch = n.chapter
        if (ch <= 0) {
            novelLabel.visibility = View.GONE
            novelBar.visibility = View.GONE
            return
        }
        novelLabel.visibility = View.VISIBLE
        val total = n?.total ?: 0
        if (total > 0) {
            val pct = (ch * 100L / total).toInt().coerceIn(0, 100)
            novelBar.visibility = View.VISIBLE
            novelBar.progress = (ch * 1000L / total).toInt().coerceIn(0, 1000)
            novelLabel.text = "Cap. $ch de $total · $pct%" + if (pct >= 90) " · falta pouco!" else ""
        } else {
            novelBar.visibility = View.GONE
            novelLabel.text = "Cap. $ch · defina o total no menu ⋮ para ver o avanço da obra"
        }
    }

    private fun currentNovel(create: Boolean): Novel? {
        val t = cur ?: return null
        val key = Library.key(t.url)
        var n = Library.get(this, key)
        if (n == null && create) {
            n = Novel(
                key, Library.name(t.url), t.url, t.title,
                Library.chapterNum(t.title, t.url), 0, 0, false, System.currentTimeMillis()
            )
        }
        return n
    }

    private fun toggleFav() {
        val n = currentNovel(true) ?: return
        val fresh = Library.get(this, n.key) ?: n
        fresh.fav = !fresh.fav
        Library.save(this, fresh)
        toast(if (fresh.fav) "Obra favoritada: ${fresh.name}" else "Removida dos favoritos")
    }

    private fun totalDialog() {
        val n = currentNovel(true) ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Ex.: 450"
            if (n.total > 0) setText(n.total.toString())
        }
        AlertDialog.Builder(this)
            .setTitle("Total de capítulos: ${n.name}")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val fresh = Library.get(this, n.key) ?: n
                fresh.total = input.text.toString().toIntOrNull() ?: 0
                Library.save(this, fresh)
                updateNovelBar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showLibrary() {
        val list = Library.sorted(this)
        if (list.isEmpty()) {
            toast("Sua biblioteca está vazia. Leia um capítulo ou favorite uma obra.")
            return
        }
        val labels = list.map { n ->
            val p = when {
                n.total > 0 && n.chapter > 0 -> " · cap. ${n.chapter}/${n.total} (${n.chapter * 100 / n.total}%)"
                n.chapter > 0 -> " · cap. ${n.chapter}"
                else -> ""
            }
            (if (n.fav) "★ " else "") + n.name + p
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Biblioteca")
            .setItems(labels) { _, i -> openUrlHere(list[i].url) }
            .setNeutralButton("Histórico") { _, _ -> showHistory() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showHistory() {
        val h = Library.history(this)
        if (h.isEmpty()) {
            toast("Ainda não há capítulos no histórico.")
            return
        }
        val labels = h.map {
            it.title.ifBlank { it.url }.take(70) + "\n" + (Uri.parse(it.url).host ?: "")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Histórico")
            .setItems(labels) { _, i -> openUrlHere(h[i].url) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    // ------------------------------------------------------------------
    // modo texto (mostra o capítulo, traduzido se for o caso)
    // ------------------------------------------------------------------

    private fun toggleReader() {
        val show = readerScroll.visibility != View.VISIBLE
        readerScroll.visibility = if (show) View.VISIBLE else View.GONE
        if (show) refreshReader()
    }

    private fun refreshReader() {
        if (readerScroll.visibility != View.VISIBLE) return
        val texts = Bridge.shown
        if (texts.isEmpty()) {
            readerText.text = "Nada está sendo lido ainda. Abra um capítulo e toque em Ler: o texto " +
                "(traduzido, se você ativou a tradução) aparece aqui, e você pode tocar em um parágrafo " +
                "para pular direto para ele."
            return
        }
        val sb = SpannableStringBuilder()
        var hlStart = 0
        for (i in texts.indices) {
            val s = sb.length
            sb.append(texts[i])
            val e = sb.length
            sb.append("\n\n")
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    send(ReaderService.A_SEEK) { putExtra("idx", i) }
                }

                override fun updateDrawState(ds: TextPaint) {}
            }, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (i == Bridge.idx) {
                hlStart = s
                sb.setSpan(BackgroundColorSpan(0x66FFC107), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        readerText.text = sb
        readerText.post {
            val l = readerText.layout ?: return@post
            val y = l.getLineTop(l.getLineForOffset(hlStart))
            readerScroll.smoothScrollTo(0, (y - dp(140)).coerceAtLeast(0))
        }
    }

    // ------------------------------------------------------------------
    // velocidade, tom, voz, timer, tradução, noite
    // ------------------------------------------------------------------

    private fun speedLabel() = String.format("%.1fx", Prefs.rate(this))

    private fun cycleSpeed() {
        val c = Prefs.rate(this)
        val next = speeds.firstOrNull { it > c + 0.01f } ?: speeds.first()
        Prefs.sp(this).edit().putFloat("rate", next).apply()
        btnSpeed.text = speedLabel()
        if (Bridge.lastActive) send(ReaderService.A_APPLY)
    }

    private fun slider(label: String, max: Int, init: Int, fmt: (Int) -> String, done: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lbl = TextView(this).apply {
            textSize = 14f
            text = label + fmt(init)
        }
        val sb = SeekBar(this).apply {
            this.max = max
            progress = init
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    lbl.text = label + fmt(p)
                }

                override fun onStartTrackingTouch(s: SeekBar) {}

                override fun onStopTrackingTouch(s: SeekBar) {
                    done(s.progress)
                }
            })
        }
        box.addView(lbl)
        box.addView(sb)
        return box
    }

    private fun voiceDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        box.addView(
            slider(
                "Velocidade: ", 25, ((Prefs.rate(this) - 0.5f) * 10).roundToInt(),
                { String.format("%.1fx", 0.5f + it / 10f) }
            ) { p ->
                Prefs.sp(this).edit().putFloat("rate", 0.5f + p / 10f).apply()
                btnSpeed.text = speedLabel()
                if (Bridge.lastActive) send(ReaderService.A_APPLY)
            }
        )
        box.addView(
            slider(
                "Tom da voz: ", 10, ((Prefs.pitch(this) - 0.5f) * 10).roundToInt(),
                { String.format("%.1f", 0.5f + it / 10f) }
            ) { p ->
                Prefs.sp(this).edit().putFloat("pitch", 0.5f + p / 10f).apply()
                if (Bridge.lastActive) send(ReaderService.A_APPLY)
            }
        )
        AlertDialog.Builder(this)
            .setTitle("Voz, velocidade e tom")
            .setView(box)
            .setPositiveButton("Escolher voz…") { _, _ -> pickVoice() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun sample(lang: String) = when (lang) {
        "pt" -> "Olá! Assim é a minha voz lendo a sua história."
        "es" -> "¡Hola! Así suena mi voz leyendo tu historia."
        else -> "Hello! This is how my voice sounds reading your story."
    }

    private fun pickVoice() {
        var t: TextToSpeech? = null
        t = TextToSpeech(this) { st ->
            val engine = t
            if (st != TextToSpeech.SUCCESS || engine == null) {
                toast("Voz do Android indisponível.")
                engine?.shutdown()
                return@TextToSpeech
            }
            val prefer = listOf("pt", "en")
            val voices: List<Voice> = (engine.voices ?: emptySet<Voice>())
                .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .sortedWith(
                    compareBy<Voice>(
                        { if (prefer.contains(it.locale.language)) 0 else 1 },
                        { it.locale.displayName },
                        { -it.quality },
                        { it.name }
                    )
                )
            val labels = voices.map { v ->
                val star = if (v.quality >= Voice.QUALITY_HIGH) "★ " else ""
                val online = if (v.isNetworkConnectionRequired) " · online" else ""
                "$star${v.locale.displayName} · ${v.name}$online"
            }.toTypedArray()
            var chosen = -1
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Voz (toque para ouvir)")
                    .setSingleChoiceItems(labels, -1) { _, i ->
                        chosen = i
                        val v = voices[i]
                        engine.stop()
                        engine.voice = v
                        engine.setSpeechRate(Prefs.rate(this))
                        engine.setPitch(Prefs.pitch(this))
                        engine.speak(sample(v.locale.language), TextToSpeech.QUEUE_FLUSH, null, "preview")
                    }
                    .setPositiveButton("Usar esta voz") { _, _ ->
                        if (chosen >= 0) {
                            val v = voices[chosen]
                            Prefs.sp(this).edit().putString("voice_" + v.locale.language, v.name).apply()
                            toast("Voz salva para ${v.locale.displayLanguage}")
                            if (Bridge.lastActive) send(ReaderService.A_APPLY)
                        }
                    }
                    .setNeutralButton("Automática") { _, _ ->
                        val sp = Prefs.sp(this)
                        val ed = sp.edit()
                        for (k in sp.all.keys) if (k.startsWith("voice_")) ed.remove(k)
                        ed.apply()
                        if (Bridge.lastActive) send(ReaderService.A_APPLY)
                    }
                    .setNegativeButton("Cancelar", null)
                    .setOnDismissListener {
                        engine.stop()
                        engine.shutdown()
                    }
                    .show()
            }
        }
    }

    private fun pickTimer() {
        val labels = arrayOf("15 minutos", "30 minutos", "45 minutos", "60 minutos", "90 minutos", "Desligar timer")
        val mins = intArrayOf(15, 30, 45, 60, 90, 0)
        AlertDialog.Builder(this)
            .setTitle("Timer para dormir")
            .setItems(labels) { _, i ->
                if (Bridge.lastActive) {
                    send(ReaderService.A_TIMER) { putExtra("min", mins[i]) }
                } else {
                    toast("Comece a leitura primeiro.")
                }
            }
            .show()
    }

    private fun translateDialog() {
        val codes = langNames.keys.toList()
        val labels = langNames.values.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Traduzir o capítulo para…")
            .setSingleChoiceItems(labels, codes.indexOf(Prefs.target(this)).coerceAtLeast(0)) { d, i ->
                Prefs.sp(this).edit().putString("target", codes[i]).apply()
                d.dismiss()
                toast(
                    if (codes[i].isEmpty()) "Tradução desligada."
                    else "Vale na próxima leitura. Na primeira vez o app baixa o idioma (precisa de internet)."
                )
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun nightDialog() {
        val labels = arrayOf("Seguir o sistema", "Claro", "Escuro")
        AlertDialog.Builder(this)
            .setTitle("Modo noturno")
            .setSingleChoiceItems(labels, Prefs.night(this)) { d, i ->
                Prefs.sp(this).edit().putInt("night", i).apply()
                d.dismiss()
                AppCompatDelegate.setDefaultNightMode(nightMode(i))
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun editDictionary() {
        val input = EditText(this).apply {
            setText(Prefs.dict(this@MainActivity))
            hint = "Uma regra por linha:\npalavra=como falar\nEx.: Xiao Mei=Chiau Mêi"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("Dicionário de pronúncia")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                Prefs.sp(this).edit().putString("dict", input.text.toString()).apply()
                if (Bridge.lastActive) send(ReaderService.A_APPLY)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                toast("Abra Ajustes > Acessibilidade > Saída de texto para voz.")
            }
        }
    }

    // ------------------------------------------------------------------
    // menu
    // ------------------------------------------------------------------

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val fav = currentNovel(false)?.fav == true
        pm.menu.add(0, 1, 0, "Biblioteca e histórico…")
        pm.menu.add(0, 2, 1, "Ler esta página do início")
        pm.menu.add(0, 3, 2, "Abrir o capítulo que está tocando")
        pm.menu.add(0, 4, 3, if (fav) "☆ Tirar dos favoritos" else "★ Favoritar esta obra")
        pm.menu.add(0, 5, 4, "Total de capítulos da obra…")
        pm.menu.add(0, 6, 5, "Voz, velocidade e tom…")
        pm.menu.add(0, 7, 6, "Tradução…")
        pm.menu.add(0, 8, 7, "Modo noturno…")
        pm.menu.add(0, 9, 8, "Vozes do Android (baixar melhores)…")
        pm.menu.add(0, 10, 9, "Dicionário de pronúncia…")
        pm.menu.add(0, 11, 10, "Passar capítulo sozinho").apply {
            isCheckable = true
            isChecked = Prefs.autoNext(this@MainActivity)
        }
        pm.menu.add(0, 12, 11, "Parar leitura")
        pm.menu.add(0, 13, 12, "Fechar esta aba")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> showLibrary()
                2 -> readPage(true)
                3 -> if (Bridge.url.isNotEmpty()) openUrlHere(Bridge.url)
                4 -> toggleFav()
                5 -> totalDialog()
                6 -> voiceDialog()
                7 -> translateDialog()
                8 -> nightDialog()
                9 -> openTtsSettings()
                10 -> editDictionary()
                11 -> Prefs.sp(this).edit().putBoolean("autonext", !it.isChecked).apply()
                12 -> if (Bridge.lastActive) send(ReaderService.A_STOP)
                13 -> cur?.let { t -> closeTab(t) }
            }
            true
        }
        pm.show()
    }
}
