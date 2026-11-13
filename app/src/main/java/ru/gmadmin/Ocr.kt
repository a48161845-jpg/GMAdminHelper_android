package ru.gmadmin

/**
 * Пост-обработка распознанного текста. Ники и ID НЕ исправляются автоматически: ник берётся ровно так,
 * как его прочитал англ. проход Tesseract (он для латиницы точнее), ID — ровно как цифры из того же прохода.
 * Единственное, что чиним, — лишние пробелы внутри «[1 34]» и цифры в хвосте «[К-во репорта: N]».
 */
object Ocr {
    private val strictTok = Regex("([A-Za-z][A-Za-z0-9_]{2,31})\\s*[\\[({]\\s*(\\d(?:\\s?\\d){0,3})\\s*[\\])}]")
    private val anyTok = Regex("([^\\s\\[\\](){}:;<>|\"'«»]{2,32})\\s*[\\[({]\\s*([0-9][0-9 ]{0,5})\\s*[\\])}]")
    private val spacedId = Regex("([\\[({])\\s*(\\d[\\d ]{1,6}?)\\s*([\\])}])")
    private val splitNick = Regex("(^|[>)\\]]\\s*|ответил\\s+)([A-Za-z][A-Za-z0-9]{1,15})[ _]\\s*([A-Za-z][A-Za-z0-9_]{1,15})(?=\\s*[\\[({]\\s*\\d)")
    private val tailNum = Regex("([:;.]\\s*)([0-9OoIl|]{1,3})(\\s*[\\])}]\\s*)$")

    class Result(val text: String, val ruRaw: String, val enRaw: String, val note: String)

    /**
     * Строка прочитана дважды (рус+англ и англ). Текст берём из первой, «Ник[ID]» — из второй как есть,
     * если вторая прочитала его по строгому шаблону (ник — латиница/цифры/_, ID — цифры).
     * Сопоставление идёт по порядку слева направо внутри одной строки изображения.
     */
    fun merge(ru: String, en: String): Result {
        val a = anyTok.findAll(ru).toList()
        val b = strictTok.findAll(en).toList()
        if (b.isEmpty()) return Result(clean(ru), ru, en, if (a.isEmpty()) "ник не найден" else "ник из рус+англ")
        val sb = StringBuilder(ru)
        var note = "ник из англ"
        if (a.isEmpty()) {
            // русский проход ник не нашёл — подставляем начало строки английского результата
            return Result(clean(en), ru, en, "рус не нашёл ник, взят англ")
        }
        val n = minOf(a.size, b.size)
        for (k in 1..n) {                           // с конца: так сдвиги от лишних слов не мешают
            val ma = a[a.size - k]; val mb = b[b.size - k]
            sb.replace(ma.range.first, ma.range.last + 1, mb.groupValues[1] + "[" + mb.groupValues[2].replace(" ", "") + "]")
        }
        return Result(clean(sb.toString()), ru, en, note)
    }

    /** Без подмены букв: убираем пробелы в ID и приводим цифры хвоста. */
    fun clean(text: String): String {
        val sb = StringBuilder()
        for (raw in text.split("\n")) {
            val l1 = spacedId.replace(raw) { m -> m.groupValues[1] + m.groupValues[2].replace(" ", "") + m.groupValues[3] }
            val l1b = splitNick.replace(l1) { m -> m.groupValues[1] + m.groupValues[2] + "_" + m.groupValues[3] }     // «Ivan Rockafellner[7]» → «Ivan_Rockafellner[7]»: подчёркивание Tesseract читает как пробел
            val l2 = tailNum.replace(l1b) { m -> m.groupValues[1] + m.groupValues[2].map { digit(it) }.joinToString("") + m.groupValues[3] }
            if (l2.isNotBlank()) sb.append(l2.trim()).append("\n")
        }
        return sb.toString().trimEnd()
    }

    private fun digit(ch: Char): Char = when (ch) { 'O', 'o' -> '0'; 'I', 'l', '|' -> '1'; else -> ch }

    private val latNick = Regex("[A-Za-z][A-Za-z0-9_]*")

    /** Нужен ли второй (англ.) проход: ник в «Ник[ID]» прочитан не латиницей или не найден. */
    fun needEn(ru: String): Boolean {
        val a = anyTok.findAll(ru).toList()
        return a.any { !latNick.matches(it.groupValues[1]) }
    }

    fun normalize(text: String): String = clean(text)
}
