package com.camneuron.live.chzzk

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * 치지직 계정 연결(OAuth). 로그인 화면 주소를 만들고, 로그인 뒤 돌아오는 주소(redirect)에서 code를 꺼낸다.
 * redirect 주소는 개발자 센터에 등록한 "로그인 리디렉션 URL"과 같아야 한다. 앱 안 로그인 화면이 그 주소로 가려는 순간
 * 가로채므로, 그 주소에 실제 서버가 없어도 된다.
 */
object ChzzkAuth {
    const val AUTHORIZE_URL = "https://chzzk.naver.com/account-interlock"

    /** 개발자 센터에 등록하라고 안내하는 기본 리디렉션 주소. 실제로 열리지 않는다 (가로챈다). */
    const val DEFAULT_REDIRECT_URI = "https://localhost/camneuron-live/login"

    sealed class Redirect {
        data class Code(val code: String, val state: String) : Redirect()
        data class Failed(val reason: String) : Redirect()
    }

    fun authorizeUrl(clientId: String, redirectUri: String, state: String): String =
        "$AUTHORIZE_URL?clientId=${enc(clientId)}&redirectUri=${enc(redirectUri)}&state=${enc(state)}"

    fun newState(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** [url]이 [redirectUri]로 가는 주소인지 (쿼리는 무시하고 scheme·host·port·path를 비교). */
    fun isRedirect(url: String, redirectUri: String): Boolean {
        val a = runCatching { URI(url) }.getOrNull() ?: return false
        val b = runCatching { URI(redirectUri) }.getOrNull() ?: return false
        return a.scheme.equals(b.scheme, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            port(a) == port(b) &&
            a.path.orEmpty().trimEnd('/') == b.path.orEmpty().trimEnd('/')
    }

    /** 돌아온 주소에서 code와 state를 꺼내고 [expectedState]와 맞는지 확인한다. */
    fun parseRedirect(url: String, expectedState: String): Redirect {
        val query = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
        val params = query.split('&').filter { it.isNotEmpty() }.associate { part ->
            val i = part.indexOf('=')
            if (i < 0) dec(part) to "" else dec(part.substring(0, i)) to dec(part.substring(i + 1))
        }
        params["error"]?.let { return Redirect.Failed(params["error_description"] ?: it) }
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: return Redirect.Failed("로그인 결과에 code가 없음")
        val state = params["state"].orEmpty()
        if (state != expectedState) return Redirect.Failed("로그인 확인 값(state)이 맞지 않음 · 다시 시도하세요")
        return Redirect.Code(code, state)
    }

    private fun port(u: URI): Int = when {
        u.port >= 0 -> u.port
        u.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
}
