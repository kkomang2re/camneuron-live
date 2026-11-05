package com.camneuron.live.chzzk

import android.content.Context
import android.content.SharedPreferences

/**
 * 치지직 계정 연결 설정: 개발자 센터에서 받은 Client ID/Secret, 등록한 리디렉션 주소, 로그인 토큰, 채널 이름.
 * 모두 앱 전용 저장소에만 두고 백업하지 않는다 (allowBackup=false). 로그에도 남기지 않는다.
 */
object ChzzkPrefs {
    private const val FILE = "chzzk"
    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_CLIENT_SECRET = "client_secret"
    private const val KEY_REDIRECT = "redirect_uri"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES = "expires_at"
    private const val KEY_CHANNEL = "channel_name"
    private const val KEY_READ_ALOUD = "read_aloud"

    fun clientId(context: Context): String = prefs(context).getString(KEY_CLIENT_ID, null).orEmpty()

    fun clientSecret(context: Context): String = prefs(context).getString(KEY_CLIENT_SECRET, null).orEmpty()

    fun redirectUri(context: Context): String =
        prefs(context).getString(KEY_REDIRECT, null)?.takeIf { it.isNotBlank() } ?: ChzzkAuth.DEFAULT_REDIRECT_URI

    fun hasApp(context: Context): Boolean = clientId(context).isNotBlank() && clientSecret(context).isNotBlank()

    /** 앱 정보를 바꾸면 이전 로그인은 다른 앱의 것이라 지운다. */
    fun saveApp(context: Context, clientId: String, clientSecret: String, redirectUri: String) {
        val p = prefs(context)
        val id = ChzzkCredentials.clean(clientId)
        val secret = ChzzkCredentials.clean(clientSecret)
        val changed = p.getString(KEY_CLIENT_ID, "") != id || p.getString(KEY_CLIENT_SECRET, "") != secret
        val edit = p.edit()
            .putString(KEY_CLIENT_ID, id)
            .putString(KEY_CLIENT_SECRET, secret)
            .putString(KEY_REDIRECT, redirectUri.trim())
        if (changed) edit.remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES).remove(KEY_CHANNEL)
        edit.apply()
    }

    fun channelName(context: Context): String = prefs(context).getString(KEY_CHANNEL, null).orEmpty()

    fun setChannelName(context: Context, name: String?) {
        prefs(context).edit().putString(KEY_CHANNEL, name.orEmpty()).apply()
    }

    /** 후원·구독을 소리 내어 읽어 줄지. 기본은 켬. */
    fun readAloud(context: Context): Boolean = prefs(context).getBoolean(KEY_READ_ALOUD, true)

    fun setReadAloud(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_READ_ALOUD, on).apply()
    }

    fun loggedIn(context: Context): Boolean = TokenStore(context).tokens != null

    /** 토큰 보관. 여러 스레드(API, 채팅)에서 쓰므로 묶어서 읽고 쓴다. */
    class TokenStore(context: Context) : ChzzkTokenStore {
        private val p = prefs(context)

        override var tokens: ChzzkTokens?
            get() {
                synchronized(LOCK) {
                    val access = p.getString(KEY_ACCESS, null) ?: return null
                    val refresh = p.getString(KEY_REFRESH, null) ?: return null
                    return ChzzkTokens(access, refresh, p.getLong(KEY_EXPIRES, 0L))
                }
            }
            set(value) {
                synchronized(LOCK) { write(value) }
            }

        private fun write(value: ChzzkTokens?) {
            val edit = p.edit()
            if (value == null) {
                edit.remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES).remove(KEY_CHANNEL)
            } else {
                edit.putString(KEY_ACCESS, value.accessToken)
                    .putString(KEY_REFRESH, value.refreshToken)
                    .putLong(KEY_EXPIRES, value.expiresAtMs)
            }
            // Refresh Token은 한 번만 쓸 수 있어 바로 디스크에 쓴다 (앱이 죽어도 새 토큰이 남게).
            edit.commit()
        }
    }

    private val LOCK = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
