package com.camneuron.live.chzzk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import java.util.concurrent.Executors

/** 치지직 API를 백그라운드 스레드에서 부르고 결과를 메인 스레드로 돌려준다. */
object ChzzkAccount {
    private const val TAG = "ChzzkAccount"
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "chzzk-api") }
    private val main = Handler(Looper.getMainLooper())

    /** 앱 정보(Client ID/Secret)가 없으면 null. 로그인 전이어도 돌려준다 (카테고리 검색은 로그인 없이 된다). */
    fun api(context: Context): ChzzkApi? {
        val app = context.applicationContext
        if (!ChzzkPrefs.hasApp(app)) return null
        return ChzzkApi(
            UrlConnectionTransport(), ChzzkPrefs.clientId(app), ChzzkPrefs.clientSecret(app), ChzzkPrefs.TokenStore(app),
            log = { Log.w(TAG, it) },
        )
    }

    /** [work]를 백그라운드에서 돌리고 [done]을 메인 스레드에서 부른다. 앱 정보가 없으면 바로 실패. */
    fun <T> run(context: Context, work: (ChzzkApi) -> T, done: (Result<T>) -> Unit) {
        val api = api(context)
        if (api == null) {
            done(Result.failure(ChzzkApiException(0, "NO_APP", "방송 설정에서 치지직 Client ID와 Secret을 넣으세요")))
            return
        }
        executor.execute {
            val result = runCatching { work(api) }
            result.exceptionOrNull()?.let { Log.w(TAG, "chzzk call failed: ${it.message}") }
            main.post { done(result) }
        }
    }

    /** 토큰을 폐기하고 앱 안 로그인 화면의 네이버 쿠키도 지운다 (다른 계정으로 로그인할 수 있게). */
    fun logout(context: Context, done: () -> Unit) {
        val app = context.applicationContext
        executor.execute {
            api(app)?.revoke() ?: ChzzkPrefs.TokenStore(app).also { it.tokens = null }
            ChzzkPrefs.setChannelName(app, null)
            main.post {
                CookieManager.getInstance().removeAllCookies(null)
                done()
            }
        }
    }
}
