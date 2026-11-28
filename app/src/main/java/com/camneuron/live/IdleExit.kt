package com.camneuron.live

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 방송하지 않는 채로 화면에서 벗어난 지 [IDLE_EXIT_MS]가 지나면 앱을 스스로 끈다 (최근 앱 목록에서도 빼고 프로세스를 끝낸다).
 * 방송 중에는 끄지 않는다. 방송이 뒤에서 끝나면 그때부터 센다. 화면으로 돌아오면 취소한다.
 *
 * 시간은 알람으로 잰다. 뒤에 있는 앱은 시스템이 멈춰 두기도 해서 앱 안의 타이머는 제때 돌지 않을 수 있다.
 * 정확한 알람이 아니라 몇 분 늦을 수 있고, 폰이 잠들어 있으면 깨어날 때 울린다.
 */
object IdleExit {
    const val IDLE_EXIT_MS = 10 * 60_000L

    private const val TAG = "IdleExit"

    /** 최근 앱 목록에서 빠진 뒤 프로세스를 끝내기까지 두는 틈. */
    private const val KILL_DELAY_MS = 500L

    private var app: Application? = null
    private var startedActivities = 0
    private var scheduled = false

    /** 방송하지 않고 화면에도 없을 때만 끈다. */
    fun shouldExit(startedActivities: Int, phase: LiveState.Phase): Boolean =
        startedActivities <= 0 && phase == LiveState.Phase.IDLE

    /** 앱이 켜질 때 한 번 (여러 번 불러도 된다). 메인 스레드에서. */
    fun init(application: Application) {
        if (app != null) return
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                update()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                update()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        LiveState.addListener { update() }
    }

    /** 지금 상태에 맞게 알람을 걸거나 푼다. 이미 걸려 있으면 그대로 둔다 (처음 벗어난 때부터 센다). */
    private fun update() {
        val context = app ?: return
        val wanted = shouldExit(startedActivities, LiveState.current.phase)
        if (wanted == scheduled) return
        scheduled = wanted
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        if (wanted) {
            alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + IDLE_EXIT_MS, pending(context))
        } else {
            alarms.cancel(pending(context))
        }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, IdleExitReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 알람이 울렸다. 그 사이 화면으로 돌아왔거나 방송을 시작했으면 아무것도 하지 않는다. */
    internal fun onAlarm(context: Context) {
        scheduled = false
        if (!shouldExit(startedActivities, LiveState.current.phase)) return
        Log.i(TAG, "idle in the background for ${IDLE_EXIT_MS / 60_000} min, exiting")
        context.getSystemService(ActivityManager::class.java)?.appTasks?.forEach { task ->
            runCatching { task.finishAndRemoveTask() }
        }
        Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, KILL_DELAY_MS)
    }
}

/** [IdleExit]의 알람을 받는다. */
class IdleExitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = IdleExit.onAlarm(context.applicationContext)
}
