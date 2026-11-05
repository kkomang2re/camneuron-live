package com.camneuron.live

import android.content.Context

/**
 * 폰에만 저장하는 방송 설정. 스트림 키는 앱 전용 저장소에 두고 백업하지 않는다 (manifest의 allowBackup=false).
 * 로그에도 남기지 않는다.
 */
object LivePrefs {
    /**
     * 치지직 스튜디오의 "스트림 URL" 기본값. 공식 문서로 확인하지 못한 값이라 화면에서 고칠 수 있게 둔다.
     * TODO: 치지직 스튜디오 → 방송 설정에 보이는 주소와 맞는지 확인.
     */
    const val DEFAULT_SERVER = "rtmp://global-rtmp.lip2.navercorp.com:8080/relay"

    private const val FILE = "live"
    private const val KEY_SERVER = "server_url"
    private const val KEY_STREAM_KEY = "stream_key"
    private const val KEY_HEIGHT = "quality_height"
    private const val KEY_FPS = "quality_fps"
    private const val KEY_KBPS = "quality_kbps"
    private const val KEY_KEY_FRAME = "quality_key_frame_s"

    fun serverUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER

    fun streamKey(context: Context): String = prefs(context).getString(KEY_STREAM_KEY, null).orEmpty()

    fun save(context: Context, serverUrl: String, streamKey: String) {
        prefs(context).edit()
            .putString(KEY_SERVER, serverUrl.trim())
            .putString(KEY_STREAM_KEY, streamKey.trim())
            .apply()
    }

    fun quality(context: Context): LiveQuality {
        val p = prefs(context)
        val default = LiveQuality()
        return LiveQuality(
            height = p.getInt(KEY_HEIGHT, default.height),
            fps = p.getInt(KEY_FPS, default.fps),
            bitrateKbps = p.getInt(KEY_KBPS, default.bitrateKbps),
            keyFrameS = p.getInt(KEY_KEY_FRAME, default.keyFrameS),
        ).sanitized()
    }

    fun saveQuality(context: Context, quality: LiveQuality) {
        val q = quality.sanitized()
        prefs(context).edit()
            .putInt(KEY_HEIGHT, q.height)
            .putInt(KEY_FPS, q.fps)
            .putInt(KEY_KBPS, q.bitrateKbps)
            .putInt(KEY_KEY_FRAME, q.keyFrameS)
            .apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
