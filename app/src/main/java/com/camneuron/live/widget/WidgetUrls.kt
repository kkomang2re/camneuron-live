package com.camneuron.live.widget

/** 위젯 주소 검사. 빈 칸은 "끔"이라 허용한다. */
object WidgetUrls {
    fun valid(url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return true
        val lower = u.lowercase()
        val rest = when {
            lower.startsWith("https://") -> u.substring(8)
            lower.startsWith("http://") -> u.substring(7)
            else -> return false
        }
        return rest.isNotEmpty() && !rest.startsWith("/") && rest.none { it.isWhitespace() }
    }
}
