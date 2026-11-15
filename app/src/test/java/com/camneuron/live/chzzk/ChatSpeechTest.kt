package com.camneuron.live.chzzk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatSpeechTest {
    private fun chat(content: String, nickname: String = "산책중", role: String = "common_user") =
        ChzzkEvent.Chat(nickname, content, role, 0L)

    @Test
    fun readsNicknameThenContent() {
        assertEquals("산책중. 안녕하세요", ChatSpeech.forChat(chat("안녕하세요")))
    }

    @Test
    fun dropsEmoteCodesAndLinks() {
        assertEquals("산책중. 여기 어디예요", ChatSpeech.forChat(chat("{:d_47:} 여기 어디예요 {:d_1:}")))
        assertEquals("산책중. 이거 보세요", ChatSpeech.forChat(chat("이거 보세요 https://example.com/a?b=1")))
        assertNull(ChatSpeech.forChat(chat("{:d_47:}{:d_48:}")))
        assertNull(ChatSpeech.forChat(chat("   ")))
    }

    @Test
    fun shortensLongRunsAndLongMessages() {
        assertEquals("산책중. ㅋㅋㅋ 웃기다", ChatSpeech.forChat(chat("ㅋㅋㅋㅋㅋㅋㅋㅋㅋㅋ 웃기다")))
        val long = "가나다라마바사아자차".repeat(10)
        val spoken = ChatSpeech.forChat(chat(long))!!
        assertEquals("산책중. ".length + ChatSpeech.MAX_CHARS, spoken.length)
    }

    @Test
    fun skipsTheStreamersOwnChat() {
        assertNull(ChatSpeech.forChat(chat("공지입니다", role = "streamer")))
        assertEquals("매니저. 공지입니다", ChatSpeech.forChat(chat("공지입니다", nickname = "매니저", role = "streaming_channel_manager")))
    }
}
