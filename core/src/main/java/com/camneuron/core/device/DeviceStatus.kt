package com.camneuron.core.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

/** 배터리와 발열 상태. 오래 송출하면 폰이 뜨거워지므로 화면과 PC에 함께 보여 준다. */
object DeviceStatus {
    data class Reading(
        val batteryPercent: Int,
        val temperatureC: Float,
        val charging: Boolean,
        /** PowerManager.THERMAL_STATUS_* (0 = 정상, 3 이상 = 심함) */
        val thermalStatus: Int,
    )

    /**
     * 0 이상이면 실제 발열 단계 대신 이 값을 쓴다. 디버그 빌드에서 발열 대응을 시험할 때만 쓴다:
     * `adb shell am start --activity-single-top -n com.camneuron.app/.MainActivity --ei simulate_thermal 3` (-1이면 해제)
     */
    @Volatile
    var simulatedThermal = -1

    /** Wi-Fi(wlan*)의 사설 IPv4 주소. USB 테더링 등 다른 연결도 있으면 PC가 이 주소를 고르게 "폰 찾기" 답에 넣는다. */
    fun wifiIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /** Wi-Fi 신호 세기(dBm). Wi-Fi에 연결돼 있지 않거나 모르면 null. 위치 권한 없이도 세기는 알려 준다. */
    @Suppress("DEPRECATION") // connectionInfo: 세기만 쓰므로 NetworkCallback 없이 1초마다 읽는다
    fun wifiRssi(context: Context): Int? = runCatching {
        val info = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        info?.rssi?.takeIf { info.networkId != -1 && it > -127 && it < 0 }
    }.getOrNull()

    /** 사용자가 정한 기기 이름 (설정 → 휴대전화 정보, 예: "Galaxy S25 Ultra"). 없으면 모델명. PC 화면에 보인다. */
    fun name(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    fun read(context: Context): Reading {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temperature = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val thermal = if (simulatedThermal >= 0) {
            simulatedThermal
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java).currentThermalStatus
        } else {
            0
        }
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return Reading(percent, temperature, plugged, thermal)
    }
}
