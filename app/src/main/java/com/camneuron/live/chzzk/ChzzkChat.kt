package com.camneuron.live.chzzk

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import com.camneuron.live.R
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 방송하는 사람이 폰에서 보는 치지직 채팅. 화면이나 방송 세션이 [acquire]하는 동안 세션을 붙여 둔다.
 * 후원·구독이 오면 진동하고, 켜 두었으면 소리 내어 읽는다 (이어폰을 끼면 걸으면서도 듣는다).
 * 모든 함수와 리스너는 메인 스레드에서 돈다.
 */
object ChzzkChat {
    private const val TAG = "ChzzkChat"
    private const val MAX_LINES = 100

    fun interface Listener {
        fun onChatChanged()
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val buffer = ArrayDeque<ChzzkEvent>()
    private var users = 0
    private var app: Context? = null
    private var session: ChzzkChatSession? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** 최근 채팅·후원·구독 (오래된 것부터, 많아야 100개). */
    val lines: List<ChzzkEvent> get() = buffer.toList()

    /** 지금까지 받은 개수. 화면이 새로 온 것만 덧붙이는 데 쓴다. */
    var total = 0L
        private set

    var status: ChzzkChatSession.Status = ChzzkChatSession.Status(ChzzkChatSession.State.STOPPED)
        private set

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun acquire(context: Context) {
        users++
        if (users == 1) {
            app = context.applicationContext
            startSession()
        }
    }

    fun release() {
        if (users == 0) return
        users--
        if (users == 0) {
            stopSession()
            tts?.shutdown()
            tts = null
            ttsReady = false
        }
    }

    /** 로그인·로그아웃 뒤에 부른다: 쓰는 쪽이 있으면 새 계정으로 다시 붙는다. */
    fun restart() {
        stopSession()
        if (users > 0) startSession()
    }

    private fun startSession() {
        val context = app ?: return
        if (!ChzzkPrefs.hasApp(context) || !ChzzkPrefs.loggedIn(context)) {
            setStatus(ChzzkChatSession.Status(ChzzkChatSession.State.NEEDS_LOGIN))
            return
        }
        // 멈춘 세션이 늦게 보낸 알림은 버린다: 리스너가 자기 세션이 지금 세션인지 확인한다.
        var created: ChzzkChatSession? = null
        val next = ChzzkChatSession(
            api = { ChzzkAccount.api(context)?.takeIf { ChzzkPrefs.loggedIn(context) } },
            connector = WebSocketClient,
            listener = object : ChzzkChatSession.Listener {
                override fun onStatus(status: ChzzkChatSession.Status) {
                    main.post { if (session === created) setStatus(status) }
                }

                override fun onEvent(event: ChzzkEvent) {
                    main.post { if (session === created) add(event) }
                }
            },
            log = { Log.i(TAG, it) },
        )
        created = next
        session = next
        next.start()
    }

    private fun stopSession() {
        val old = session ?: return
        session = null
        Thread({ old.stop() }, "chzzk-chat-stop").start()
        setStatus(ChzzkChatSession.Status(ChzzkChatSession.State.STOPPED))
    }

    private fun setStatus(next: ChzzkChatSession.Status) {
        status = next
        listeners.forEach { it.onChatChanged() }
    }

    private fun add(event: ChzzkEvent) {
        buffer.addLast(event)
        total++
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        when (event) {
            is ChzzkEvent.Donation -> alert(app?.getString(R.string.chzzk_say_donation, event.nickname, event.amount, event.text))
            is ChzzkEvent.Subscription -> alert(app?.getString(R.string.chzzk_say_subscription, event.nickname, event.month))
            else -> Unit
        }
        listeners.forEach { it.onChatChanged() }
    }

    private fun alert(speech: String?) {
        val context = app ?: return
        vibrate(context)
        if (speech == null || !ChzzkPrefs.readAloud(context)) return
        val engine = tts
        if (engine == null) {
            tts = TextToSpeech(context) { code ->
                ttsReady = code == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.KOREAN
                    speak(speech)
                }
            }
        } else if (ttsReady) {
            speak(speech)
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "chzzk-${System.nanoTime()}")
    }

    private fun vibrate(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1)) }
    }
}
