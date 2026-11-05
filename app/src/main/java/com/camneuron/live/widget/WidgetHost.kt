package com.camneuron.live.widget

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.camneuron.core.overlay.OverlayItem
import com.camneuron.core.overlay.OverlaySettings
import com.camneuron.core.overlay.OverlayStore
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 치지직 채팅창·후원 알림 웹 위젯을 화면 밖에서 띄우고, 바뀔 때마다 그림(Bitmap)으로 떠서 알린다.
 *
 * 위젯은 앱만 쓰는 가상 화면(VirtualDisplay) 위의 [Presentation] 창에 둔다. 액티비티 창이 아니라서 앱을 내려도,
 * 화면을 꺼도 방송 중에는 계속 돈다. 권한도 필요 없다 (앱이 만든 비공개 가상 화면).
 * 그 화면의 출력은 쓰지 않고, 위젯 창마다 소프트웨어로 다시 그려 투명도까지 담은 그림을 얻는다.
 * 페이지가 다시 그려졌을 때만, 많아야 초당 [MAX_FPS]번 뜬다.
 *
 * 쓰는 쪽(화면, 방송 세션)이 [acquire]/[release]로 붙고 떨어진다. 아무도 없으면 웹 페이지를 닫는다.
 * 모든 함수와 리스너는 메인 스레드에서 돈다.
 */
object WidgetHost {
    /** [index] 0 = 채팅창, 1 = 후원 알림. */
    const val CHAT = 0
    const val ALERT = 1
    const val COUNT = 2

    class Frame(val bitmap: Bitmap, val item: OverlayItem)

    fun interface Listener {
        /** [frame]이 null이면 그 위젯이 꺼졌다 (주소 없음). [frame.bitmap]은 읽기만 한다. */
        fun onWidgetFrame(index: Int, frame: Frame?)
    }

    private const val TAG = "WidgetHost"
    private const val MAX_FPS = 10
    private const val FRAME_MS = 1000L / MAX_FPS

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private var users = 0
    private var context: Context? = null

    private var readerThread: HandlerThread? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var root: FrameLayout? = null
    private val slots = arrayOfNulls<Slot>(COUNT)
    private val latest = arrayOfNulls<Frame>(COUNT)
    private var dirty = false
    private var lastCaptureMs = 0L
    private var captureScheduled = false

    private val settingsListener: (OverlaySettings) -> Unit = { main.post { applySettings(it) } }
    private val drawListener = ViewTreeObserver.OnDrawListener { markDirty() }

    /** 지금 그림 (없으면 null). */
    fun latest(index: Int): Frame? = latest[index]

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun acquire(context: Context) {
        checkMain()
        users++
        if (users == 1) start(context.applicationContext)
    }

    fun release() {
        checkMain()
        if (users == 0) return
        users--
        if (users == 0) stop()
    }

    /** 채팅창이 안 바뀌어도 다시 뜬다 (예: 방송 세션이 새 인코더에 처음 얹을 때는 [latest]를 쓰면 된다). */
    fun refresh() {
        checkMain()
        markDirty()
    }

    private fun start(app: Context) {
        context = app
        OverlayStore.init(app)
        try {
            val thread = HandlerThread("widget-display").apply { start() }
            readerThread = thread
            // 가상 화면의 출력은 쓰지 않는다. 버퍼가 차서 그리기가 막히지 않게 바로 버린다.
            val imageReader = ImageReader.newInstance(WidgetCanvas.WIDTH, WidgetCanvas.HEIGHT, PixelFormat.RGBA_8888, 2)
            imageReader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, Handler(thread.looper))
            reader = imageReader
            val manager = app.getSystemService(DisplayManager::class.java)
            val virtual = manager.createVirtualDisplay(
                "camneuron-widgets", WidgetCanvas.WIDTH, WidgetCanvas.HEIGHT, WidgetCanvas.DENSITY_DPI,
                imageReader.surface, 0,
            )
            display = virtual
            val window = Presentation(app, virtual.display)
            window.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val content = FrameLayout(window.context)
            window.setContentView(content, ViewGroup.LayoutParams(WidgetCanvas.WIDTH, WidgetCanvas.HEIGHT))
            window.show()
            content.viewTreeObserver.addOnDrawListener(drawListener)
            presentation = window
            root = content
            Log.i(TAG, "widget display ready")
        } catch (e: Exception) {
            Log.w(TAG, "widget display unavailable", e)
            stop()
            return
        }
        OverlayStore.addListener(settingsListener)
        applySettings(OverlayStore.current)
    }

    private fun stop() {
        OverlayStore.removeListener(settingsListener)
        main.removeCallbacks(captureRunnable)
        captureScheduled = false
        for (i in 0 until COUNT) {
            slots[i]?.destroy()
            slots[i] = null
            if (latest[i] != null) {
                latest[i] = null
                listeners.forEach { it.onWidgetFrame(i, null) }
            }
        }
        root?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnDrawListener(drawListener)
        root = null
        runCatching { presentation?.dismiss() }
        presentation = null
        display?.release()
        display = null
        reader?.close()
        reader = null
        readerThread?.quitSafely()
        readerThread = null
        context = null
    }

    private fun applySettings(settings: OverlaySettings) {
        val content = root ?: return
        listOf(settings.chat, settings.alert).forEachIndexed { index, item ->
            var slot = slots[index]
            if (!item.active) {
                if (slot != null) {
                    slot.destroy()
                    content.removeView(slot.frame)
                    slots[index] = null
                }
                if (latest[index] != null) {
                    latest[index] = null
                    listeners.forEach { it.onWidgetFrame(index, null) }
                }
                return@forEachIndexed
            }
            if (slot == null) {
                slot = Slot(content.context)
                content.addView(slot.frame)
                slots[index] = slot
            }
            slot.bind(item)
        }
        markDirty()
    }

    private fun markDirty() {
        dirty = true
        if (captureScheduled) return
        captureScheduled = true
        val wait = (lastCaptureMs + FRAME_MS - SystemClock.uptimeMillis()).coerceAtLeast(0)
        main.postDelayed(captureRunnable, wait)
    }

    private val captureRunnable = Runnable {
        captureScheduled = false
        if (!dirty) return@Runnable
        dirty = false
        lastCaptureMs = SystemClock.uptimeMillis()
        for (i in 0 until COUNT) {
            val slot = slots[i] ?: continue
            val bitmap = slot.capture() ?: continue
            val frame = Frame(bitmap, slot.item ?: continue)
            latest[i] = frame
            listeners.forEach { it.onWidgetFrame(i, frame) }
        }
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "WidgetHost: main thread only" }

    /** 위젯 하나: 자리 잡은 틀과 그 안의 웹 페이지. */
    private class Slot(context: Context) {
        @SuppressLint("SetJavaScriptEnabled")
        val webView = WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 후원 알림은 소리·영상을 스스로 재생한다 (OBS 브라우저 소스처럼).
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) = applyZoom()
            }
            webChromeClient = WebChromeClient()
        }
        val frame = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        var item: OverlayItem? = null
            private set
        private var loadedUrl: String? = null

        fun bind(next: OverlayItem) {
            val rect = WidgetCanvas.pixelRect(next)
            frame.layoutParams = FrameLayout.LayoutParams(rect.width, rect.height).apply {
                leftMargin = rect.left
                topMargin = rect.top
            }
            val zoomChanged = item?.zoom != next.zoom
            item = next
            if (next.url != loadedUrl) {
                loadedUrl = next.url
                webView.loadUrl(next.url)
            } else if (zoomChanged) {
                applyZoom()
            }
        }

        private fun applyZoom() {
            val zoom = item?.zoom ?: 1f
            webView.evaluateJavascript("document.documentElement.style.zoom='$zoom'", null)
        }

        /** 지금 모습을 투명도까지 담아 뜬다. 아직 자리를 잡지 않았으면 null. */
        fun capture(): Bitmap? {
            val w = frame.width
            val h = frame.height
            if (w <= 0 || h <= 0) return null
            return try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { frame.draw(Canvas(it)) }
            } catch (e: Exception) {
                Log.w(TAG, "widget capture failed", e)
                null
            }
        }

        fun destroy() {
            webView.stopLoading()
            webView.destroy()
        }
    }
}
