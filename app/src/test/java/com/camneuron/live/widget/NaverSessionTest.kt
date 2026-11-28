package com.camneuron.live.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaverSessionTest {
    @Test
    fun loggedInNeedsBothLoginCookies() {
        assertTrue(NaverSession.loggedIn("NNB=abc; NID_AUT=xxxx; NID_SES=yyyy; nid_inf=1"))
        assertTrue(NaverSession.loggedIn("NID_SES=yyyy;NID_AUT=xxxx"))
        assertFalse(NaverSession.loggedIn("NNB=abc; NID_AUT=xxxx"))
        assertFalse(NaverSession.loggedIn("NNB=abc; NID_SES=yyyy"))
    }

    @Test
    fun notLoggedInWithoutCookies() {
        assertFalse(NaverSession.loggedIn(null))
        assertFalse(NaverSession.loggedIn(""))
        assertFalse(NaverSession.loggedIn("NNB=abc; BUC=def"))
    }

    @Test
    fun emptyOrLookAlikeCookiesDoNotCount() {
        // 로그아웃하면 값이 빈 쿠키가 남기도 한다.
        assertFalse(NaverSession.loggedIn("NID_AUT=; NID_SES="))
        assertFalse(NaverSession.loggedIn("NID_AUT=xxxx; NID_SES="))
        // 이름이 비슷할 뿐인 쿠키.
        assertFalse(NaverSession.loggedIn("XNID_AUT=a; NID_SES_OLD=b"))
        assertFalse(NaverSession.loggedIn("other=NID_AUT=a; another=NID_SES=b"))
    }
}
