package ru.gmadmin

import java.util.concurrent.CopyOnWriteArrayList

object Store {
    @Volatile var lvl = 3
    @Volatile var lv: Map<String, Int> = emptyMap()
    @Volatile var lastOcr = ""
    @Volatile var ocrErr = ""
    @Volatile var ocrState = "не запущено"
    @Volatile var lang = "rus+eng"
    @Volatile var scan = 350
    @Volatile var autoSend = false
    @Volatile var autoReply = true
    @Volatile var cscale = 75            // качество записи экрана, %
    @Volatile var strict = false            // ловить только полные репорты «…[К-во репорта: N]» (без догадок по обрывкам)
    @Volatile var autoRec = true           // автозапись экрана при слежке по репорту с чужим ID и термином из правил
    @Volatile var ocrThreads = 2           // сколько строк чата распознаётся одновременно (1–2)
    @Volatile var loose = true             // принимать репорты с повреждённым хвостом (оранжевые строки, ник Имя_Фамилия, скобка в конце)
    @Volatile var wrapMs = 1100            // сколько ждать продолжение репорта, не поместившегося в одну строку
    @Volatile var maxId = 1500             // ID игрока выше этого — не репорт
    @Volatile var ttlMin = 90              // сколько минут живут репорты и формы в списке
    @Volatile var vibMs = 250              // вибрация при репорте, мс (0 — выкл.)
    @Volatile var autoRecMin = 20          // максимум автозаписи, мин
    @Volatile var autoRecAny = false       // автозапись при любом чужом ID, а не только с термином
    @Volatile var winDp = 320              // ширина окон репорта/слежки в портрете, dp
    @Volatile var gridPref = 0             // колонок кнопок в окнах (0 — авто)
    @Volatile var ansCols = 2              // колонок в окне ответов
    @Volatile var uiScale = 1f             // размер текста окон
    @Volatile var winAlpha = 100           // непрозрачность окон, %
    @Volatile var hideMin = 3              // авто-скрытие окна без касаний, мин (0 — никогда)
    @Volatile var nextAuto = true          // после ответа сразу показывать следующий репорт
    @Volatile var helpAns = "Ваш вопрос передан администрации, ожидайте."   // ответ игроку кнопкой «На помощь»
    @Volatile var catchForms = true        // ловить формы (запросы команд) из админ-чата
    @Volatile var oneAtATime = false        // пока окно репорта открыто, новые репорты не берём
    @Volatile var repPause = 0             // после пойманного репорта столько секунд новые не берём (0 — выкл.)
    @Volatile var windowOpen = false       // окно входящих сейчас на экране
    @Volatile var lastAccept = 0L
    @Volatile var fb = 8                  // страховочное чтение всей области, сек (0 — выкл.)
    @Volatile var gap = 500               // пауза между командами в цепочке, мс
    @Volatile var aTpl = "/a {who} нужна помощь: {text}"   // «позвать на помощь» в админ-чат: {who}=ник[ID], {nick}, {id}, {text}
    @Volatile var reqTpl = "/a {cmd}"     // форма запроса в админ-чат, когда не хватает прав на команду
    @Volatile var myNick = ""             // мой игровой ник: свои же запросы в админ-чат не показываются как формы
    @Volatile var logState = "не настроено"   // состояние чтения лога чата игры
    @Volatile var logSrc = ""             // путь или content:// лога чата
    @Volatile var logLines = 0
    val logTail = java.util.Collections.synchronizedList(ArrayList<String>())
    @Volatile var ocrRuns = 0
    @Volatile var ocrSkips = 0
    @Volatile var replies: Map<String, String> = emptyMap()
    @Volatile var accent = 0xFF14B8A6.toInt()   // цвет темы
    @Volatile var nextRep = ""                 // автор репорта, из окна которого сейчас начнётся слежка
    @Volatile var specRep = ""                 // автор репорта, которому отвечаем из окна слежки
    @Volatile var crop = floatArrayOf(0.19f, 0.0f, 0.67f, 0.25f)      // область чтения: левый, верхний, правый, нижний край — доли экрана

    /** Очередь текстов для чата игры: (текст, отправлять ли сразу). Вписывается по одному, когда поле открыто. */
    val out = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val answered = java.util.concurrent.CopyOnWriteArraySet<String>()   // репорты, на которые уже ответили (остаются в списке, но не считаются новыми)
    @Volatile var onRefresh: (() -> Unit)? = null   // входящие изменились не из окна (например, другой админ уже ответил)
    val queue = CopyOnWriteArrayList<String>()   // репорты
    val reqs = CopyOnWriteArrayList<String>()    // запросы из админ-чата: "ник\tкоманда"

    @Volatile var onReport: ((String) -> Unit)? = null
    @Volatile var onAdminCmd: ((String, String) -> Unit)? = null
    @Volatile var onOutbox: ((Boolean) -> Unit)? = null
    @Volatile var onQueue: (() -> Unit)? = null
    /** Слежка началась (цель) или закончилась (null) — оверлей показывает/прячет панель слежки. */
    @Volatile var onSpectate: ((String?) -> Unit)? = null
    @Volatile var hideOverlay: ((Boolean) -> Unit)? = null          // спрятать значок на время скриншота
    @Volatile var imeCommit: ((String, Boolean) -> Unit)? = null   // только для чужих полей (игра)
    @Volatile var admPrefix = ""                                    // команда админ-чата, которую клавиатура подставляет в начало сообщения ("" — выключено)
    @Volatile var imeAdm: ((Boolean) -> Unit)? = null
    @Volatile var imeRaw: ((String) -> Unit)? = null                // любое поле
    @Volatile var imeDelete: ((Int) -> Unit)? = null
    @Volatile var imeEnter: (() -> Unit)? = null
    @Volatile var imeFlush: (() -> Unit)? = null                    // вписать следующий текст из очереди
    @Volatile var imeClear: (() -> Unit)? = null                    // стереть всё в активном поле
    @Volatile var imeResize: ((Int) -> Unit)? = null                // высота панели, % экрана

    private val spRe = Regex("^/sp\\s+(\\S+)", RegexOption.IGNORE_CASE)
    private val spOffRe = Regex("^/spoff\\b", RegexOption.IGNORE_CASE)

    /** Вызывать из главного потока. true — поле чата открыто и текст вписан сразу. */
    fun enqueue(items: List<String>, send: Boolean): Boolean {
        for (t in items) {
            out.add(Pair(t, send))
            val m = spRe.find(t)
            if (m != null) { specRep = nextRep; nextRep = ""; onSpectate?.invoke(m.groupValues[1]) } else if (spOffRe.containsMatchIn(t)) { specRep = ""; onSpectate?.invoke(null) }
        }
        val f = imeFlush
        if (f != null) { f(); return true }
        onOutbox?.invoke(true)
        return false
    }
}
