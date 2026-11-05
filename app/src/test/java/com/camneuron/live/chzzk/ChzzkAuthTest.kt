package com.camneuron.live.chzzk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChzzkAuthTest {
    private val redirect = "https://localhost/camneuron-live/login"

    @Test
    fun authorizeUrlEncodesParameters() {
        assertEquals(
            "https://chzzk.naver.com/account-interlock?clientId=abc+1&redirectUri=https%3A%2F%2Flocalhost%2Fcamneuron-live%2Flogin&state=s1",
            ChzzkAuth.authorizeUrl("abc 1", redirect, "s1"),
        )
    }

    @Test
    fun recognisesTheRedirect() {
        assertTrue(ChzzkAuth.isRedirect("https://localhost/camneuron-live/login?code=x&state=y", redirect))
        assertTrue(ChzzkAuth.isRedirect("https://LOCALHOST:443/camneuron-live/login/?code=x", redirect))
        assertFalse(ChzzkAuth.isRedirect("https://nid.naver.com/nidlogin.login?url=https://localhost/camneuron-live/login", redirect))
        assertFalse(ChzzkAuth.isRedirect("http://localhost/camneuron-live/login?code=x", redirect))
        assertFalse(ChzzkAuth.isRedirect("https://localhost/other?code=x", redirect))
    }

    @Test
    fun parsesCodeAndChecksState() {
        assertEquals(
            ChzzkAuth.Redirect.Code("c%d", "s1"),
            ChzzkAuth.parseRedirect("$redirect?code=c%25d&state=s1", "s1"),
        )
        assertTrue(ChzzkAuth.parseRedirect("$redirect?code=c&state=other", "s1") is ChzzkAuth.Redirect.Failed)
        assertTrue(ChzzkAuth.parseRedirect("$redirect?state=s1", "s1") is ChzzkAuth.Redirect.Failed)
        assertEquals(
            ChzzkAuth.Redirect.Failed("denied"),
            ChzzkAuth.parseRedirect("$redirect?error=access_denied&error_description=denied", "s1"),
        )
    }

    @Test
    fun statesAreRandomHex() {
        val a = ChzzkAuth.newState()
        assertEquals(32, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertFalse(a == ChzzkAuth.newState())
    }
}
