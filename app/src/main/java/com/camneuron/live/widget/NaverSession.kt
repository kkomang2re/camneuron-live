package com.camneuron.live.widget

import android.webkit.CookieManager

/**
 * 위젯을 그리는 앱 안 브라우저(WebView)가 네이버에 로그인되어 있는지.
 *
 * 치지직 영상 후원 위젯은 19세 제한이 걸린 클립을 로그인한(성인 인증된) 계정으로만 틀어 준다: 로그인되어 있지 않으면 위젯에
 * "로그인하세요"가 뜨고 영상이 나오지 않는다 (OBS의 브라우저 소스도 그 안에서 따로 로그인해야 하는 것과 같다).
 *
 * 앱 안의 웹 화면은 모두 쿠키를 함께 쓴다. 그래서 "치지직 로그인" 화면이나 [WidgetLoginActivity]에서 네이버에 로그인하면
 * 위젯도 로그인된 것으로 보인다. 다만 네이버 로그인 화면에서 "로그인 상태 유지"를 켜지 않으면 그 쿠키는 앱을 완전히 끌 때
 * 사라진다 (WebView는 기한 없는 쿠키를 저장해 두지 않는다). API용 토큰은 따로 저장되므로 채팅·스트림 키는 계속 되는데
 * 위젯만 로그아웃된 상태가 된다.
 *
 * 쿠키의 이름만 본다. 값은 읽어 두지도, 로그에 남기지도 않는다.
 */
object NaverSession {
    /** 네이버 로그인 화면. 이미 로그인되어 있으면 네이버 첫 화면으로 넘어간다. */
    const val LOGIN_URL = "https://nid.naver.com/nidlogin.login"

    /** 치지직 위젯 페이지가 보내는 쿠키를 여기로 본다 (.naver.com 쿠키가 함께 간다). */
    private const val CHECK_URL = "https://chzzk.naver.com/"

    /** 네이버가 로그인한 브라우저에 주는 쿠키. 둘 다 있어야 로그인된 것이다. */
    private val LOGIN_COOKIES = listOf("NID_AUT", "NID_SES")

    /** 지금 앱 안 브라우저가 네이버에 로그인되어 있는지. */
    fun loggedIn(): Boolean = runCatching { loggedIn(CookieManager.getInstance().getCookie(CHECK_URL)) }.getOrDefault(false)

    /** [cookieHeader]("이름=값; 이름=값" 꼴)에 로그인 쿠키가 값과 함께 모두 들어 있는지. */
    fun loggedIn(cookieHeader: String?): Boolean {
        if (cookieHeader.isNullOrBlank()) return false
        val present = cookieHeader.split(';').mapNotNull { part ->
            val name = part.substringBefore('=', "").trim()
            val value = part.substringAfter('=', "").trim()
            name.takeIf { it.isNotEmpty() && value.isNotEmpty() }
        }.toSet()
        return present.containsAll(LOGIN_COOKIES)
    }
}
