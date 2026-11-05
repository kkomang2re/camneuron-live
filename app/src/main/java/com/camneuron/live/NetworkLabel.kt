package com.camneuron.live

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** 지금 쓰는 네트워크 종류를 화면에 보일 글자로. LTE와 5G 구분은 전화 권한이 있어야 해 하지 않는다. */
object NetworkLabel {
    fun current(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            ?: return context.getString(R.string.net_none)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> context.getString(R.string.net_wifi)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> context.getString(R.string.net_cellular)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> context.getString(R.string.net_ethernet)
            else -> context.getString(R.string.net_other)
        }
    }
}
