package com.camneuron.live.chzzk

/**
 * 채팅을 소리 내어 읽을 글자로 바꾼다 ("채팅 읽기"). 야외에서 걸으며 방송하면 화면을 못 보므로 이어폰·스피커로 듣는다.
 * 이모티콘 코드({:d_47:})와 주소는 빼고, 같은 글자가 길게 이어지면(ㅋㅋㅋㅋㅋㅋ) 줄이고, 너무 긴 글은 앞부분만 읽는다.
 */
object ChatSpeech {
    const val MAX_CHARS = 60

    /** 방송하는 사람이 앱에서 직접 쓴 채팅은 읽지 않는다. */
    private const val ROLE_STREAMER = "streamer"

    private val EMOTE = Regex("\\{:[^}]*:\\}")
    private val LINK = Regex("https?://\\S+")
    private val SPACES = Regex("\\s+")

    /** 읽을 것이 없으면 null (이모티콘만 있는 채팅 등). */
    fun forChat(event: ChzzkEvent.Chat): String? {
        if (event.role == ROLE_STREAMER) return null
        val text = squeeze(event.content.replace(EMOTE, " ").replace(LINK, " ").replace(SPACES, " ").trim())
        if (text.isEmpty()) return null
        return "${event.nickname}. ${text.take(MAX_CHARS)}"
    }

    /** 같은 글자가 3번 넘게 이어지면 3번으로 줄인다. */
    private fun squeeze(text: String): String {
        val out = StringBuilder(text.length)
        var run = 0
        for ((i, c) in text.withIndex()) {
            run = if (i > 0 && c == text[i - 1]) run + 1 else 1
            if (run <= 3) out.append(c)
        }
        return out.toString()
    }
}
