package com.camneuron.live.widget

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.camneuron.live.R
import kotlin.math.roundToInt

/**
 * 위젯용 네이버 로그인: 앱 안 화면에서 네이버에 로그인해 둔다 ([NaverSession]). 위젯도 같은 쿠키를 쓰므로, 여기서 로그인하면
 * 치지직 영상 후원 위젯이 19세 제한 클립을 틀 수 있다. 비밀번호는 네이버 페이지에만 넣는다 (앱은 보지 않는다).
 * "완료"를 누르면 쿠키를 저장하고 돌아간다: 로그인되어 있으면 RESULT_OK.
 */
class WidgetLoginActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var message: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        message = barText().apply { text = getString(R.string.widget_login_title) }
        val done = barText().apply {
            text = getString(R.string.btn_done)
            setOnClickListener { finishLogin() }
        }
        val bar = LinearLayout(this).apply {
            setBackgroundColor(0xFF161618.toInt())
            gravity = Gravity.CENTER_VERTICAL
            addView(message, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(done)
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) = renderState()
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
        webView.loadUrl(NaverSession.LOGIN_URL)
    }

    /** 위쪽 줄에 지금 로그인되어 있는지를 보인다. */
    private fun renderState() {
        message.setText(if (NaverSession.loggedIn()) R.string.widget_login_done_hint else R.string.widget_login_title)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else finishLogin()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    private fun finishLogin() {
        // 방금 받은 쿠키가 디스크에 남게 한다 ("로그인 상태 유지"를 켠 경우에 앱을 다시 켜도 남는다).
        runCatching { CookieManager.getInstance().flush() }
        setResult(if (NaverSession.loggedIn()) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    private fun barText() = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
