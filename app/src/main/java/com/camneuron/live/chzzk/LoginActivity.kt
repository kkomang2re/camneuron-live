package com.camneuron.live.chzzk

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.camneuron.live.LivePrefs
import com.camneuron.live.R
import kotlin.math.roundToInt

/**
 * 치지직 계정 연결: 앱 안 화면에서 네이버 로그인과 권한 동의를 하고, 등록한 리디렉션 주소로 돌아가려는 순간 가로채
 * code를 토큰으로 바꾼다. 그 주소는 실제로 열지 않는다. 성공하면 채널 이름과 스트림 키도 받아 둔다.
 */
class LoginActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var message: TextView
    private lateinit var redirectUri: String
    private lateinit var state: String
    private var handled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clientId = ChzzkPrefs.clientId(this)
        redirectUri = ChzzkPrefs.redirectUri(this)
        if (clientId.isBlank()) {
            finishWith(getString(R.string.chzzk_need_app), Activity.RESULT_CANCELED)
            return
        }
        state = savedInstanceState?.getString(KEY_STATE) ?: ChzzkAuth.newState()

        message = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            text = getString(R.string.chzzk_login_title)
        }
        val cancel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            text = getString(R.string.btn_cancel)
            setOnClickListener { finishWith(null, Activity.RESULT_CANCELED) }
        }
        val bar = LinearLayout(this).apply {
            setBackgroundColor(0xFF161618.toInt())
            gravity = Gravity.CENTER_VERTICAL
            addView(message, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(cancel)
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    intercept(request.url.toString())

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    // 일부 리디렉션은 shouldOverrideUrlLoading을 거치지 않는다.
                    if (intercept(url)) view.stopLoading()
                }
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(bar)
            addView(webView, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        webView.loadUrl(ChzzkAuth.authorizeUrl(clientId, redirectUri, state))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::state.isInitialized) outState.putString(KEY_STATE, state)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else finishWith(null, Activity.RESULT_CANCELED)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    /** 리디렉션 주소면 가로채 처리하고 true. */
    private fun intercept(url: String): Boolean {
        if (!ChzzkAuth.isRedirect(url, redirectUri)) return false
        if (handled) return true
        handled = true
        webView.visibility = View.INVISIBLE
        when (val result = ChzzkAuth.parseRedirect(url, state)) {
            is ChzzkAuth.Redirect.Failed -> finishWith(getString(R.string.chzzk_login_failed, result.reason), Activity.RESULT_CANCELED)
            is ChzzkAuth.Redirect.Code -> {
                message.text = getString(R.string.chzzk_login_finishing)
                exchange(result)
            }
        }
        return true
    }

    private fun exchange(result: ChzzkAuth.Redirect.Code) {
        ChzzkAccount.run(this, { api ->
            api.exchangeCode(result.code, result.state)
            // 채널 이름과 스트림 키는 권한이 없으면 건너뛴다 (로그인 자체는 성공).
            val channel = runCatching { api.me() }.getOrNull()
            val key = runCatching { api.streamKey() }.getOrNull()
            channel to key
        }) { outcome ->
            outcome.onSuccess { (channel, key) ->
                ChzzkPrefs.setChannelName(this, channel?.channelName)
                if (!key.isNullOrBlank()) LivePrefs.save(this, LivePrefs.serverUrl(this), key)
                val name = channel?.channelName?.ifBlank { null } ?: getString(R.string.chzzk_unknown_channel)
                val note = if (key.isNullOrBlank()) getString(R.string.chzzk_login_ok_no_key, name) else getString(R.string.chzzk_login_ok, name)
                finishWith(note, Activity.RESULT_OK)
            }.onFailure { finishWith(getString(R.string.chzzk_login_failed, it.message ?: ""), Activity.RESULT_CANCELED) }
        }
    }

    private fun finishWith(toast: String?, result: Int) {
        if (toast != null) Toast.makeText(this, toast, Toast.LENGTH_LONG).show()
        setResult(result)
        finish()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val KEY_STATE = "state"
    }
}
