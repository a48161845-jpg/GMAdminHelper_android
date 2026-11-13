p='OverlayService.kt'
s=open(p,encoding='utf-8').read()
def rep(a,b):
    global s
    assert s.count(a)==1,a[:70]
    s=s.replace(a,b)
rep("""        addCopyRow(box, listOf("Ник" to name.takeIf { it != "?" }.orEmpty(), "ID" to rep) + (if (t != rep) listOf("Нарушитель" to t) else emptyList()) +
            ids.filter { it != t }.take(3).map { "ID" to it })
""","""        // ники в тексте репорта (скорее всего нарушитель); если он один и ID один — запоминаем для окна слежки
        val cands = Regex("[A-Za-z][A-Za-z0-9_]{2,24}").findAll(body).map { it.value }.filter { !it.equals(name, true) }.distinct().take(2).toList()
        if (cands.size == 1 && ids.size == 1) vioNick[ids[0]] = cands[0]
        addCopyRow(box, listOf("Автор" to name.takeIf { it != "?" }.orEmpty(), "ID автора" to rep) +
            (if (t != rep) listOf("ID нарушителя" to t) else emptyList()) + cands.map { "Ник" to it } + ids.filter { it != t }.take(2).map { "ID" to it })
""")
rep("    private val nickOf = HashMap<String, String>()","    private val vioNick = HashMap<String, String>()\n    private val nickOf = HashMap<String, String>()")
rep('listOf("Ник" to "", "ID" to target, "Автор"','listOf("Ник" to (vioNick[target] ?: ""), "ID" to target, "Автор"')
open(p,'w',encoding='utf-8').write(s)
