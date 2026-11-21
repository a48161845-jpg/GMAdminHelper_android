package ru.gmadmin

/** Минимальный уровень для команд из админ-чата, на которые показываем запрос (без служебных). */
object Levels {
    /** Команды, которые считаются выданным наказанием (для журнала). */
    val punRe = Regex("^/(mute|jail|rmute|ban|sban|warn|offmute|offjail|offwarn|offban|kick|skick)\\s+\\S+\\s+\\S", RegexOption.IGNORE_CASE)

    /** Уровни команд берутся только из раздела «Команды» приложения (Store.lv). Встроенного списка нет. */
    val map: Map<String, Int> = emptyMap()
}
