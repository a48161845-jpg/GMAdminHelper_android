p='OverlayService.kt'
s=open(p,encoding='utf-8').read()
def rep(a,b):
    global s
    assert s.count(a)==1,a[:70]
    s=s.replace(a,b)

# 1. no heads-up notification
rep("vibrate(); refreshBubble(); headsUp(title, text)","vibrate(); refreshBubble()")

# helpers
rep("    private fun repKey(rep: String, line: String): String {","""    private val nickOf = HashMap<String, String>()          // ID автора репорта → ник (для окна слежки)
    private var popPos: IntArray? = null                    // где пользователь оставил окно репорта (x, y)
    private var specPos: IntArray? = null

    /** Окно двигается за «ручку» (шапку): после первого сдвига переходит на абсолютные координаты и запоминает место. */
    private fun makeDraggable(handle: View, win: View, lp: WindowManager.LayoutParams, save: (IntArray) -> Unit) {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    val loc = IntArray(2); win.getLocationOnScreen(loc)
                    sx = loc[0]; sy = loc[1]; tx = e.rawX; ty = e.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (!moved && abs(dx) + abs(dy) < dp(6)) return@setOnTouchListener true
                    if (!moved) { moved = true; lp.gravity = Gravity.TOP or Gravity.START }
                    val dm = resources.displayMetrics
                    lp.x = (sx + dx).coerceIn(0, max(0, dm.widthPixels - win.width))
                    lp.y = (sy + dy).coerceIn(0, max(0, dm.heightPixels - win.height))
                    try { wm.updateViewLayout(win, lp) } catch (x: Exception) {}
                    lastAct = System.currentTimeMillis(); true
                }
                MotionEvent.ACTION_UP -> { if (moved) save(intArrayOf(lp.x, lp.y)); true }
                else -> true
            }
        }
    }

    private fun copyText(label: String, v: String) {
        try { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, v)) } catch (e: Exception) {}
        Toast.makeText(this, "Скопировано: $v", Toast.LENGTH_SHORT).show()
    }

    /** Ряд «Копировать»: каждое значение (ник, ID автора, ID нарушителя…) копируется касанием. */
    private fun addCopyRow(parent: LinearLayout, items: List<Pair<String, String>>) {
        val vals = items.filter { it.second.isNotEmpty() }
        if (vals.isEmpty()) return
        val sc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        row.addView(txt("Копировать:", 11f, 0x99FFFFFF.toInt()).apply { setPadding(0, 0, dp(6), 0) })
        for ((l, v) in vals) row.addView(pill("$l $v", CHIP) { copyText(l, v) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        sc.addView(row)
        parent.addView(sc, LinearLayout.LayoutParams(-1, -2))
    }

    private fun repKey(rep: String, line: String): String {""")

# 2. inbox window: position + drag
rep("""        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.y = dp(20)
        try { wm.addView(box, lp); popup = box }""","""        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.y = dp(20)
        popPos?.let { lp.gravity = Gravity.TOP or Gravity.START; lp.x = it[0]; lp.y = it[1] }
        makeDraggable(head, box, lp) { popPos = it }
        try { wm.addView(box, lp); popup = box }""")
# setFocusable keeps position (uses same lp object from view) - fine

# 3. spectate: position + drag + copy
rep("""        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.y = dp(12)
        try { wm.addView(box, lp); spec = box }""","""        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.y = dp(12)
        specPos?.let { lp.gravity = Gravity.TOP or Gravity.START; lp.x = it[0]; lp.y = it[1] }
        makeDraggable(head, box, lp) { specPos = it }
        try { wm.addView(box, lp); spec = box }""")
rep("""        // ответ автору репорта прямо из слежки — без клавиатуры
        if (rep.isNotEmpty()) {""","""        addCopyRow(body, listOf("Ник" to "", "ID" to target, "Автор" to (nickOf[rep] ?: ""), "ID автора" to rep))
        // ответ автору репорта прямо из слежки — без клавиатуры
        if (rep.isNotEmpty()) {""")

# 4. report: copy row, nick map
rep("""        titleView.text = "$name [$rep]"
""","""        titleView.text = "$name [$rep]"
        if (rep.isNotEmpty() && name != "?") nickOf[rep] = name
""")
rep("""        fun done() {
            kept.add(line)""","""        addCopyRow(box, listOf("Ник" to name.takeIf { it != "?" }.orEmpty(), "ID" to rep) + (if (t != rep) listOf("Нарушитель" to t) else emptyList()) +
            ids.filter { it != t }.take(3).map { "ID" to it })

        fun done() {
            kept.add(line)""")
open(p,'w',encoding='utf-8').write(s)
