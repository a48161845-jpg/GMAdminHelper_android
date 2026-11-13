package ru.gmadmin

/** Минимальный уровень для команд из админ-чата, на которые показываем запрос (без служебных). */
object Levels {
    /** Команды, которые считаются выданным наказанием (для журнала). */
    val punRe = Regex("^/(mute|jail|rmute|ban|sban|warn|offmute|offjail|offwarn|offban|kick|skick)\\s+\\S+\\s+\\S", RegexOption.IGNORE_CASE)

    val map: Map<String, Int> = mapOf(
        "sp" to 1, "hp" to 1, "alic" to 1, "fixcar" to 1, "flip" to 1, "getinfo" to 1, "spawn" to 2, "freeze" to 2,
        "tp" to 2, "slap" to 2, "kick" to 2, "mute" to 2, "unmute" to 2, "offmute" to 2, "offunmute" to 2, "jail" to 2,
        "unjail" to 2, "offjail" to 2, "offunjail" to 2, "rmute" to 2, "runmute" to 2, "weap" to 2, "stats" to 2, "goto" to 2,
        "gotocar" to 2, "getcar" to 2, "skills" to 2, "pay" to 2, "unfreeze" to 2, "ban" to 3, "offwarn" to 3, "offunwarn" to 3,
        "skick" to 3, "veh_find" to 3, "warn" to 3, "unwarn" to 3, "gethere" to 3, "hpall" to 3, "weapall" to 3, "uncuff" to 3,
        "untie" to 3, "acceptgnews" to 3, "find" to 3, "spcar" to 3, "spcars" to 3, "sban" to 4, "unban" to 4, "offban" to 4,
        "house" to 4, "biz" to 4, "okay" to 4, "no" to 4, "setskin" to 4, "setfuel" to 4, "getname" to 4, "history" to 4,
        "rgun" to 4, "lip" to 4, "arm" to 4
    )
}
