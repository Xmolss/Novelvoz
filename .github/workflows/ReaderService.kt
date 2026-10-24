package com.novelvoz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

// =====================================================================
// Estado compartilhado entre a tela e o serviço (mesmo processo)
// =====================================================================

object Bridge {
    @Volatile var html: String = ""
    @Volatile var url: String = ""
    @Volatile var userAgent: String = ""
    @Volatile var startSeg: Int = 0

    // texto que está sendo lido (já traduzido, quando for o caso) e posição
    var shown: Array<String> = emptyArray()
    var idx: Int = 0
    var count: Int = 0
    var version: Int = 0

    var lastText: String = ""
    var lastPlaying: Boolean = false
    var lastActive: Boolean = false
    var listener: ((String, Boolean, Boolean) -> Unit)? = null

    fun publish(text: String, playing: Boolean, active: Boolean) {
        lastText = text
        lastPlaying = playing
        lastActive = active
        listener?.invoke(text, playing, active)
    }
}

object Prefs {
    fun sp(c: Context) = c.getSharedPreferences("novelvoz", Context.MODE_PRIVATE)
    fun rate(c: Context) = sp(c).getFloat("rate", 1.0f)
    fun pitch(c: Context) = sp(c).getFloat("pitch", 1.0f)
    fun voiceFor(c: Context, lang: String) = sp(c).getString("voice_$lang", "") ?: ""
    fun target(c: Context) = sp(c).getString("target", "") ?: ""
    fun autoNext(c: Context) = sp(c).getBoolean("autonext", true)
    fun dict(c: Context) = sp(c).getString("dict", "") ?: ""
    fun night(c: Context) = sp(c).getInt("night", 0)
}

// =====================================================================
// Biblioteca (obras), progresso por obra e histórico
// =====================================================================

data class Novel(
    val key: String,
    var name: String,
    var url: String,
    var title: String = "",
    var chapter: Int = 0,
    var seg: Int = 0,
    var total: Int = 0,
    var fav: Boolean = false,
    var ts: Long = 0L
)

data class HistoryItem(val url: String, val title: String, val ts: Long)

object Library {
    private val digits = Regex("\\d+")
    private val chapRe = Regex(
        "(?i)\\b(chapter|cap[ií]tulo|cap\\.?|ch\\.?|episode|ep\\.?)\\s*[-:#]?\\s*(\\d{1,5})"
    )
    private val tailNum = Regex("(?:^|[-_/])(\\d{1,5})$")

    private fun sp(c: Context) = c.getSharedPreferences("novelvoz_library", Context.MODE_PRIVATE)

    fun clean(url: String): String = url.substringBefore('#')

    /** Identifica a obra: o endereço com todos os números trocados por #. */
    fun key(url: String): String {
        return try {
            val u = URI(clean(url))
            val host = (u.host ?: "").removePrefix("www.")
            host + (u.path ?: "").replace(digits, "#")
        } catch (e: Exception) {
            clean(url).replace(digits, "#")
        }
    }

    fun name(url: String): String {
        return try {
            val u = URI(clean(url))
            val segs = (u.path ?: "").split('/').filter { it.isNotBlank() }
            val pick = when {
                segs.size >= 2 -> segs[segs.size - 2]
                segs.size == 1 -> segs[0].replace(
                    Regex("(?i)[-_ ]*(chapter|cap[ií]tulo|ch|cap)?[-_ ]*\\d+.*$"), ""
                )
                else -> ""
            }
            val n = pick.replace('-', ' ').replace('_', ' ').trim()
            if (n.isEmpty() || n.all { it.isDigit() || it == ' ' }) {
                (u.host ?: url).removePrefix("www.")
            } else {
                n.split(' ').filter { it.isNotEmpty() }
                    .joinToString(" ") { w -> w.replaceFirstChar { ch -> ch.uppercase() } }
            }
        } catch (e: Exception) {
            url
        }
    }

    fun chapterNum(title: String, url: String): Int {
        chapRe.find(title)?.groupValues?.get(2)?.toIntOrNull()?.let { return it }
        val path = try { URI(clean(url)).path ?: "" } catch (e: Exception) { url }
        chapRe.find(path)?.groupValues?.get(2)?.toIntOrNull()?.let { return it }
        return tailNum.find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun all(c: Context): MutableMap<String, Novel> {
        val out = LinkedHashMap<String, Novel>()
        try {
            val arr = JSONArray(sp(c).getString("novels", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val n = Novel(
                    o.getString("key"), o.optString("name"), o.optString("url"),
                    o.optString("title"), o.optInt("chapter"), o.optInt("seg"),
                    o.optInt("total"), o.optBoolean("fav"), o.optLong("ts")
                )
                out[n.key] = n
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun write(c: Context, m: Map<String, Novel>) {
        val arr = JSONArray()
        for (n in m.values) {
            arr.put(
                JSONObject().put("key", n.key).put("name", n.name).put("url", n.url)
                    .put("title", n.title).put("chapter", n.chapter).put("seg", n.seg)
                    .put("total", n.total).put("fav", n.fav).put("ts", n.ts)
            )
        }
        sp(c).edit().putString("novels", arr.toString()).apply()
    }

    fun get(c: Context, key: String): Novel? = all(c)[key]

    fun save(c: Context, n: Novel) {
        val m = all(c)
        m[n.key] = n
        write(c, m)
    }

    fun sorted(c: Context): List<Novel> =
        all(c).values.sortedWith(compareByDescending<Novel> { it.fav }.thenByDescending { it.ts })

    /** Guarda onde a leitura parou (capítulo e parágrafo). */
    fun progress(c: Context, url: String, title: String, seg: Int) {
        val k = key(url)
        val m = all(c)
        val n = m[k] ?: Novel(k, name(url), url)
        n.url = url
        n.title = title
        n.chapter = chapterNum(title, url)
        n.seg = seg
        n.ts = System.currentTimeMillis()
        m[k] = n
        write(c, m)
    }

    fun addHistory(c: Context, url: String, title: String) {
        val list = history(c).toMutableList()
        list.removeAll { clean(it.url) == clean(url) }
        list.add(0, HistoryItem(url, title, System.currentTimeMillis()))
        val arr = JSONArray()
        for (h in list.take(100)) arr.put(JSONObject().put("u", h.url).put("t", h.title).put("ts", h.ts))
        sp(c).edit().putString("history", arr.toString()).apply()
    }

    fun history(c: Context): List<HistoryItem> {
        val out = ArrayList<HistoryItem>()
        try {
            val arr = JSONArray(sp(c).getString("history", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(HistoryItem(o.optString("u"), o.optString("t"), o.optLong("ts")))
            }
        } catch (e: Exception) {
        }
        return out
    }
}

// =====================================================================
// Extração do texto do capítulo
// =====================================================================

data class Chapter(
    val title: String,
    val paras: List<String>,
    val nextUrl: String?,
    val lang: String
)

object Extractor {
    private val junk = Regex(
        "(?i)(read (the )?(latest|newest|new) chapters? (at|on|only)|please bookmark|" +
            "translator\\s*:|editor\\s*:|proofreader\\s*:|report chapter|support (us|the author)|" +
            "buy me a coffee|patreon\\.com|ko-fi\\.com)"
    )
    private val nextWords = listOf(
        "next chapter", "next", "próximo capítulo", "proximo capitulo", "próximo", "proximo",
        "siguiente", "capítulo seguinte", "capitulo seguinte", "下一章"
    )
    private val chapterWord = Regex("(?i)(chapter|cap[ií]tulo|cap\\.|ch\\.|episode|ep\\.)")

    fun parse(html: String, baseUrl: String): Chapter? {
        val doc = Jsoup.parse(html, baseUrl)
        val lang = doc.select("html").attr("lang").take(8)
        val next = findNext(doc)
        val title = findTitle(doc)

        doc.select(
            "script,style,nav,header,footer,aside,noscript,iframe,svg,button,select," +
                "#comments,.comments,.comment-list,#disqus_thread,.disqus,.adsbygoogle,.advertisement"
        ).remove()
        // <br> vira um marcador de quebra de linha
        doc.select("br").after("\\n")

        val containers = doc.select("div,article,section,main,td")
        val paras = ArrayList<String>()

        // Estratégia 1: elemento cujos filhos diretos <p> têm mais texto
        var best: Element? = null
        var bestScore = 0
        for (el in containers) {
            var s = 0
            for (c in el.children()) if (c.tagName() == "p") s += c.text().length
            if (s > bestScore) {
                bestScore = s
                best = el
            }
        }
        if (best != null && bestScore >= 300) {
            for (c in best.children()) if (c.tagName() == "p") addPara(paras, c.text())
        } else {
            // Estratégia 2: elemento onde quase todo o texto está dentro de <p>
            var dense: Element? = null
            var denseScore = 0
            for (el in containers) {
                val total = el.text().length
                if (total == 0) continue
                var pText = 0
                for (p in el.select("p")) pText += p.text().length
                if (pText > denseScore && pText.toDouble() / total > 0.7) {
                    denseScore = pText
                    dense = el
                }
            }
            if (dense != null && denseScore >= 300) {
                for (p in dense.select("p")) addPara(paras, p.text())
            } else {
                // Estratégia 3: texto solto separado por <br>
                var b3: Element? = null
                var s3 = 0
                for (el in containers) {
                    val s = el.ownText().length
                    if (s > s3) {
                        s3 = s
                        b3 = el
                    }
                }
                if (b3 == null || s3 < 300) return null
                addPara(paras, b3.ownText())
            }
        }
        if (paras.isEmpty()) return null
        return Chapter(title, paras, next, lang)
    }

    private fun addPara(list: MutableList<String>, raw: String) {
        for (part in raw.split("\\n")) {
            val t = part.trim()
            if (t.length > 1 && t.any { it.isLetterOrDigit() } && !junk.containsMatchIn(t)) list.add(t)
        }
    }

    private fun findTitle(doc: Document): String {
        for (h in doc.select("h1,h2,h3")) {
            val t = h.text().trim()
            if (t.length in 3..140 && chapterWord.containsMatchIn(t)) return t
        }
        val h1 = doc.select("h1").firstOrNull()?.text()?.trim()
        if (h1 != null && h1.length in 3..140) return h1
        return doc.title().take(100)
    }

    private fun okLink(doc: Document, h: String): Boolean =
        h.isNotBlank() && !h.startsWith("javascript") && !h.endsWith("#") && h != doc.location()

    private fun findNext(doc: Document): String? {
        for (a in doc.select("a[rel=next]")) {
            val h = a.absUrl("href")
            if (okLink(doc, h)) return h
        }
        val tail = Regex("[›»→>\\s]+$")
        for (a in doc.select("a[href]")) {
            val cls = a.className().lowercase()
            if (cls.contains("prev") || cls.contains("disabled")) continue
            val t = a.text().trim().lowercase().replace(tail, "").trim()
            val h = a.absUrl("href")
            if (t.length <= 30 && nextWords.any { t == it } && okLink(doc, h)) return h
        }
        for (a in doc.select("a[id*=next],a[class*=next]")) {
            val cls = a.className().lowercase()
            if (cls.contains("prev") || cls.contains("disabled")) continue
            val h = a.absUrl("href")
            if (okLink(doc, h)) return h
        }
        return null
    }
}

/** Limpa o texto antes de falar e aplica o dicionário de pronúncia do usuário. */
object Speech {
    private val noise = Regex("[*#~_=^|\\[\\]{}<>]+")
    private val spaces = Regex("\\s+")
    private val dots = Regex("\\.{4,}")

    fun prepare(raw: String, dict: String): String {
        var s = raw.replace(noise, " ")
            .replace("—", ", ")
            .replace("–", ", ")
            .replace("…", "...")
            .replace(dots, "...")
            .replace(spaces, " ")
            .trim()
        for (line in dict.lines()) {
            val i = line.indexOf('=')
            if (i > 0) {
                val from = line.substring(0, i).trim()
                val to = line.substring(i + 1).trim()
                if (from.isNotEmpty()) s = s.replace(from, to, ignoreCase = true)
            }
        }
        return s
    }

    fun splitLong(s: String, max: Int = 3000): List<String> {
        if (s.length <= max) return listOf(s)
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (sent in s.split(Regex("(?<=[.!?。！？])\\s+"))) {
            if (cur.isNotEmpty() && cur.length + sent.length + 1 > max) {
                out.add(cur.toString())
                cur = StringBuilder()
            }
            var x = sent
            while (x.length > max) {
                out.add(x.take(max))
                x = x.drop(max)
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(x)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }
}

// =====================================================================
// Serviço de leitura em voz alta
// =====================================================================

class ReaderService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val A_LOAD = "com.novelvoz.LOAD"
        const val A_TOGGLE = "com.novelvoz.TOGGLE"
        const val A_NEXT = "com.novelvoz.NEXT"
        const val A_PREV = "com.novelvoz.PREV"
        const val A_STOP = "com.novelvoz.STOP"
        const val A_APPLY = "com.novelvoz.APPLY"
        const val A_TIMER = "com.novelvoz.TIMER"
        const val A_SEEK = "com.novelvoz.SEEK"
        private const val CHANNEL = "reader"
        private const val NID = 7
        private const val MSG_TRANSLATING = "Traduzindo…"
        private const val MSG_DOWNLOADING = "Baixando o idioma (só na primeira vez)…"
        private const val MSG_OPENING = "Abrindo o próximo capítulo…"
        private const val MSG_END = "Fim do capítulo."
    }

    private class Pending(val ch: Chapter, val url: String, val startAt: Int)

    private lateinit var tts: TextToSpeech
    private lateinit var session: MediaSessionCompat
    private lateinit var audio: AudioManager
    private val main = Handler(Looper.getMainLooper())

    private var ttsReady = false
    private var raw: List<String> = emptyList()
    private var shown: Array<String> = emptyArray()
    private var ready: BooleanArray = BooleanArray(0)
    private var waitingFor = -1
    private var chapterId = 0
    private var idx = 0
    private var gen = 0
    private var playing = false
    private var title = ""
    private var nextUrl: String? = null
    private var curUrl = ""
    private var speakLang = ""
    private var lastSaved = -1
    private var pending: Pending? = null
    private var failUrl: String? = null
    private var pausedByFocus = false
    private var focusReq: AudioFocusRequest? = null
    private var timerRunnable: Runnable? = null
    private var timerEnd = 0L
    private var wake: PowerManager.WakeLock? = null
    private var message: String? = null
    private var notice: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Leitura em voz alta", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        session = MediaSessionCompat(this, "NovelVoz").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { if (!playing) togglePlay() }
                override fun onPause() { if (playing) togglePlay() }
                override fun onSkipToNext() { skip(1) }
                override fun onSkipToPrevious() { skip(-1) }
                override fun onStop() { stopAll() }
            })
            isActive = true
        }
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Bridge.publish("O mecanismo de voz do Android não está disponível.", false, false)
            return
        }
        ttsReady = true
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { segmentDone(utteranceId) } }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) { main.post { segmentDone(utteranceId) } }
            override fun onError(utteranceId: String?, errorCode: Int) { main.post { segmentDone(utteranceId) } }
        })
        pending?.let {
            pending = null
            begin(it.ch, it.url, it.startAt)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        val action = intent?.action
        if (action != A_LOAD && raw.isEmpty() && pending == null) {
            stopAll()
            return START_NOT_STICKY
        }
        when (action) {
            A_LOAD -> load()
            A_TOGGLE -> togglePlay()
            A_NEXT -> skip(1)
            A_PREV -> skip(-1)
            A_STOP -> stopAll()
            A_APPLY -> if (ttsReady) {
                applyVoice()
                if (playing) speakCurrent()
            }
            A_TIMER -> setTimer(intent?.getIntExtra("min", 0) ?: 0)
            A_SEEK -> seek(intent?.getIntExtra("idx", 0) ?: 0)
        }
        return START_NOT_STICKY
    }

    // ---------- carregar capítulo ----------

    private fun load() {
        val html = Bridge.html
        val url = Bridge.url
        val start = Bridge.startSeg
        Bridge.startSeg = 0
        Thread {
            val ch = Extractor.parse(html, url)
            main.post {
                if (ch == null) {
                    Bridge.publish("Não encontrei o texto do capítulo nesta página.", playing, raw.isNotEmpty())
                    if (raw.isEmpty()) stopAll()
                } else {
                    failUrl = null
                    begin(ch, url, start)
                }
            }
        }.start()
    }

    private fun begin(ch: Chapter, url: String, startAt: Int) {
        if (!ttsReady) {
            pending = Pending(ch, url, startAt)
            Bridge.publish("Preparando a voz…", false, true)
            return
        }
        chapterId++
        val myId = chapterId
        curUrl = url
        Bridge.url = url
        title = ch.title
        nextUrl = ch.nextUrl
        message = null
        notice = null

        val list = ArrayList<String>()
        if (title.isNotBlank()) list.add(title)
        for (p in ch.paras) list.addAll(Speech.splitLong(p))
        raw = list
        shown = list.toTypedArray()
        ready = BooleanArray(list.size) { true }
        Bridge.shown = shown
        Bridge.version++
        idx = startAt.coerceIn(0, list.size - 1)
        lastSaved = -1
        waitingFor = -1

        val pageLang = ch.lang.take(2).lowercase()
        speakLang = pageLang
        val target = Prefs.target(this)
        if (target.isNotEmpty()) {
            ready = BooleanArray(list.size)
            speakLang = target
            startTranslation(myId, target, pageLang, list, shown, ready, idx)
        }

        applyVoice()
        Library.addHistory(this, url, title)
        saveProgress(true)
        requestFocus()
        setPlaying(true)
        speakCurrent()
        updateUi()
    }

    // ---------- tradução (no aparelho, com o ML Kit do Google) ----------

    private fun startTranslation(
        myId: Int, target: String, pageLang: String,
        rawL: List<String>, shownL: Array<String>, readyL: BooleanArray, startL: Int
    ) {
        Thread {
            var tr: Translator? = null
            try {
                val sample = rawL.take(8).joinToString(" ").take(600)
                var tag = "und"
                val li = LanguageIdentification.getClient()
                try {
                    tag = Tasks.await(li.identifyLanguage(sample))
                } catch (e: Exception) {
                } finally {
                    li.close()
                }
                val src = TranslateLanguage.fromLanguageTag(if (tag == "und") pageLang else tag)
                if (src == null || src == target) {
                    main.post {
                        if (myId == chapterId) {
                            readyL.fill(true)
                            onTranslated()
                        }
                    }
                    return@Thread
                }
                val t = Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(target).build()
                )
                tr = t
                main.post {
                    if (myId == chapterId) {
                        message = MSG_DOWNLOADING
                        updateUi()
                    }
                }
                Tasks.await(t.downloadModelIfNeeded(DownloadConditions.Builder().build()))
                main.post {
                    if (myId == chapterId && message == MSG_DOWNLOADING) {
                        message = null
                        updateUi()
                    }
                }
                val n = rawL.size
                for (k in 0 until n) {
                    if (myId != chapterId) break
                    val i = (startL + k) % n
                    val out = translateText(t, rawL[i])
                    main.post {
                        if (myId == chapterId) {
                            shownL[i] = out
                            readyL[i] = true
                            if (i == waitingFor) onTranslated()
                        }
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (myId == chapterId) {
                        notice = "falha na tradução, lendo o original"
                        if (message == MSG_DOWNLOADING) message = null
                        readyL.fill(true)
                        onTranslated()
                    }
                }
            } finally {
                try { tr?.close() } catch (e: Exception) {}
            }
        }.start()
    }

    private fun translateText(t: Translator, s: String): String {
        if (s.length <= 1500) return Tasks.await(t.translate(s))
        return Speech.splitLong(s, 1500).joinToString(" ") { Tasks.await(t.translate(it)) }
    }

    private fun onTranslated() {
        if (playing && waitingFor >= 0 && waitingFor == idx) speakCurrent() else updateUi()
    }

    private fun localeFor(lang: String): Locale =
        if (lang == "pt") Locale("pt", "BR") else Locale.forLanguageTag(lang)

    private fun applyVoice() {
        tts.setSpeechRate(Prefs.rate(this))
        tts.setPitch(Prefs.pitch(this))
        val wanted = if (speakLang.isNotEmpty()) Prefs.voiceFor(this, speakLang) else ""
        val voice = if (wanted.isNotEmpty()) tts.voices?.firstOrNull { it.name == wanted } else null
        if (voice != null) {
            tts.voice = voice
        } else if (speakLang.isNotEmpty()) {
            val loc = localeFor(speakLang)
            if (tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) tts.language = loc
        }
    }

    // ---------- fala ----------

    private fun speakCurrent() {
        if (!ttsReady || raw.isEmpty()) return
        val dict = Prefs.dict(this)
        var text = ""
        while (idx < raw.size) {
            if (!ready[idx]) {
                waitingFor = idx
                if (message == null) message = MSG_TRANSLATING
                updateUi()
                return
            }
            text = Speech.prepare(shown[idx], dict)
            if (text.isNotBlank()) break
            idx++
        }
        if (idx >= raw.size) {
            chapterEnded()
            return
        }
        waitingFor = -1
        if (message == MSG_TRANSLATING) message = null
        gen++
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "$gen:$idx")
        updateUi()
    }

    private fun segmentDone(id: String?) {
        if (id == null || !playing) return
        val g = id.substringBefore(':').toIntOrNull() ?: return
        if (g != gen) return
        idx++
        if (idx < raw.size) speakCurrent() else chapterEnded()
    }

    private fun chapterEnded() {
        val n = nextUrl
        if (Prefs.autoNext(this) && n != null) {
            message = MSG_OPENING
            updateUi()
            Thread {
                val html = fetch(n)
                val ch = html?.let { Extractor.parse(it, n) }
                main.post {
                    if (ch != null) {
                        message = null
                        if (playing) {
                            begin(ch, n, 0)
                        } else {
                            pending = Pending(ch, n, 0)
                            updateUi()
                        }
                    } else {
                        failUrl = n
                        message = "Não consegui abrir o próximo capítulo. Toque aqui para abrir no app."
                        setPlaying(false)
                        updateUi()
                    }
                }
            }.start()
        } else {
            Library.progress(this, curUrl, title, 0)
            message = MSG_END
            setPlaying(false)
            idx = raw.size - 1
            updateUi()
        }
    }

    private fun fetch(url: String): String? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 25000
            c.instanceFollowRedirects = true
            if (Bridge.userAgent.isNotEmpty()) c.setRequestProperty("User-Agent", Bridge.userAgent)
            c.setRequestProperty("Referer", Bridge.url)
            CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
            if (c.responseCode !in 200..299) return null
            val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(c.contentType ?: "")?.groupValues?.get(1) ?: "UTF-8"
            val body = c.inputStream.bufferedReader(charset(cs)).readText()
            if (body.contains("Just a moment...") || body.contains("cf-browser-verification")) null else body
        } catch (e: Exception) {
            null
        }
    }

    // ---------- controles ----------

    private fun togglePlay() {
        if (playing) {
            tts.stop()
            gen++
            setPlaying(false)
            saveProgress(true)
            updateUi()
            return
        }
        val pend = pending
        if (pend != null) {
            pending = null
            begin(pend.ch, pend.url, pend.startAt)
            return
        }
        if (raw.isEmpty()) return
        if (message == MSG_OPENING) return
        if (message == MSG_END) idx = 0
        if (message == MSG_END || message == MSG_OPENING) message = null
        requestFocus()
        setPlaying(true)
        speakCurrent()
        updateUi()
    }

    private fun skip(d: Int) {
        if (raw.isEmpty()) return
        seek(idx + d)
    }

    private fun seek(to: Int) {
        if (raw.isEmpty()) return
        idx = to.coerceIn(0, raw.size - 1)
        if (message == MSG_END) message = null
        if (playing) speakCurrent() else updateUi()
    }

    private fun setPlaying(p: Boolean) {
        playing = p
        val w = wake ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "novelvoz:reader").also { wake = it }
        if (p && !w.isHeld) w.acquire(6 * 60 * 60 * 1000L)
        if (!p && w.isHeld) w.release()
    }

    private fun setTimer(min: Int) {
        timerRunnable?.let { main.removeCallbacks(it) }
        timerRunnable = null
        timerEnd = 0
        if (min > 0) {
            val r = Runnable { stopAll() }
            timerRunnable = r
            timerEnd = System.currentTimeMillis() + min * 60_000L
            main.postDelayed(r, min * 60_000L)
        }
        updateUi()
    }

    private fun saveProgress(force: Boolean) {
        if (raw.isEmpty() || curUrl.isEmpty()) return
        if (!force && kotlin.math.abs(idx - lastSaved) < 3) return
        lastSaved = idx
        Library.progress(this, curUrl, title, idx)
    }

    private fun stopAll() {
        saveProgress(true)
        chapterId++
        tts.stop()
        gen++
        setPlaying(false)
        timerRunnable?.let { main.removeCallbacks(it) }
        focusReq?.let { audio.abandonAudioFocusRequest(it) }
        raw = emptyList()
        shown = emptyArray()
        ready = BooleanArray(0)
        pending = null
        Bridge.shown = shown
        Bridge.idx = 0
        Bridge.count = 0
        Bridge.version++
        Bridge.publish("Leitura parada.", false, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestFocus() {
        val req = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change -> main.post { onFocus(change) } }
            .build().also { focusReq = it }
        audio.requestAudioFocus(req)
    }

    private fun onFocus(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (playing) {
                    pausedByFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    togglePlay()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocus && !playing) {
                    pausedByFocus = false
                    togglePlay()
                }
            }
        }
    }

    // ---------- notificação / interface ----------

    private fun statusText(): String {
        message?.let { return it }
        val pos = if (raw.isEmpty()) "" else " (${idx + 1}/${raw.size})"
        val t = if (timerEnd > System.currentTimeMillis())
            "  ⏲ ${((timerEnd - System.currentTimeMillis()) / 60000) + 1} min" else ""
        val n = notice?.let { " · $it" } ?: ""
        return title + pos + n + t
    }

    private fun updateUi() {
        saveProgress(false)
        Bridge.idx = idx
        Bridge.count = raw.size
        Bridge.publish(statusText(), playing, raw.isNotEmpty() || pending != null)
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    idx.toLong(), if (playing) 1f else 0f
                )
                .build()
        )
        getSystemService(NotificationManager::class.java).notify(NID, buildNotification())
    }

    private fun startFg() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        fun act(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, ReaderService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        failUrl?.let { open.putExtra("url", it).putExtra("autoread", true) }
        val content = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("NovelVoz")
            .setContentText(statusText().ifBlank { "Pronto" })
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", act(A_PREV, 1))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pausar" else "Ler", act(A_TOGGLE, 2)
            )
            .addAction(android.R.drawable.ic_media_next, "Próximo", act(A_NEXT, 3))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", act(A_STOP, 4))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        chapterId++
        try { tts.shutdown() } catch (_: Exception) {}
        session.release()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
