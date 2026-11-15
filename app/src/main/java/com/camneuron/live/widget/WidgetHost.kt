package com.camneuron.live.widget

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.camneuron.core.overlay.OverlayItem
import com.camneuron.core.overlay.OverlaySettings
import com.camneuron.core.overlay.OverlayStore
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 치지직 채팅창·후원 알림·브라우저 소스(투네이션 후원·영상 후원 등) 웹 위젯을 화면 밖에서 띄우고, 바뀔 때마다
 * 그림(Bitmap)으로 떠서 알린다.
 *
 * 위젯은 앱만 쓰는 가상 화면(VirtualDisplay) 위의 [Presentation] 창에 둔다. 액티비티 창이 아니라서 앱을 내려도,
 * 화면을 꺼도 방송 중에는 계속 돈다. 권한도 필요 없다 (앱이 만든 비공개 가상 화면).
 *
 * 그림은 그 창에 실제로 그려진 것을 위젯 자리만큼 복사해 얻는다 ([PixelCopy]: 투명도도, 영상 후원의 영상도 담긴다).
 * 웹 페이지를 소프트웨어로 다시 그리면 영상(video)은 빈 칸으로 나오기 때문이다. 복사가 안 되는 폰에서는 소프트웨어로
 * 다시 그리는 옛 방식으로 돌아간다 (영상만 안 보인다).
 * 위젯마다 따로, 그 페이지가 다시 그려졌을 때만 뜬다. 보통은 많아야 초당 10번, 움직임이 많은 후원 알림·영상 후원은 20번.
 * 가상 화면 전체의 출력(ImageReader)은 투명도가 없어 쓰지 않는다.
 *
 * 창을 복사하므로 두 위젯의 자리가 겹치면 겹친 곳에는 서로의 그림이 함께 담긴다 (반투명한 곳이 조금 진해질 뿐이다).
 *
 * 쓰는 쪽(화면, 방송 세션)이 [acquire]/[release]로 붙고 떨어진다. 아무도 없으면 웹 페이지를 닫는다.
 * 모든 함수와 리스너는 메인 스레드에서 돈다.
 */
object WidgetHost {
    /** [index] 0 = 채팅창, 1 = 후원 알림, 2부터 브라우저 소스 ([BrowserSources]). */
    const val CHAT = 0
    const val ALERT = 1
    const val FIRST_SOURCE = 2
    const val COUNT = FIRST_SOURCE + BrowserSources.MAX

    class Frame(val bitmap: Bitmap, val item: OverlayItem)

    fun interface Listener {
        /** [frame]이 null이면 그 위젯이 꺼졌다 (주소 없음). [frame.bitmap]은 읽기만 한다. */
        fun onWidgetFrame(index: Int, frame: Frame?)
    }

    private const val TAG = "WidgetHost"

    /** 그림을 뜨는 간격: 보통 위젯, 그리고 움직임이 많은 위젯(후원 알림, 영상 후원). */
    private const val FRAME_MS = 100L
    private const val MOTION_FRAME_MS = 50L

    /** 마지막으로 바뀐 뒤 이만큼 지나 한 번 더 뜬다 (복사가 한 장 늦었어도 끝 모습이 남게). */
    private const val SETTLE_MS = 250L
    private const val MAX_COPY_FAILURES = 3

    /** 영상이 뜨기 전에 보일 그림: 투명한 점 하나. */
    private val EMPTY_POSTER: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

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

    /** 창에 그려진 것을 복사해 뜨는지. 이 폰에서 거듭 실패하면 false가 되어 소프트웨어로 다시 그린다. */
    private var windowCapture = true
    private var copyFailures = 0

    private val settingsListener: (OverlaySettings) -> Unit = { main.post { applySettings(it) } }

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

    /** 위젯이 안 바뀌어도 다시 뜬다 (방송 세션이 새 인코더에 처음 얹을 때는 [latest]를 쓰면 된다). */
    fun refresh() {
        checkMain()
        slots.forEach { it?.markDirty() }
    }

    private fun start(app: Context) {
        context = app
        windowCapture = true
        copyFailures = 0
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
            // 창 자체가 투명해야 복사한 그림에 투명도가 담긴다.
            window.window?.setFormat(PixelFormat.TRANSLUCENT)
            window.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val content = FrameLayout(window.context)
            window.setContentView(content, ViewGroup.LayoutParams(WidgetCanvas.WIDTH, WidgetCanvas.HEIGHT))
            window.show()
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
        for (i in 0 until COUNT) {
            slots[i]?.destroy()
            slots[i] = null
            if (latest[i] != null) {
                latest[i] = null
                listeners.forEach { it.onWidgetFrame(i, null) }
            }
        }
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
        BrowserSources.slots(settings).forEachIndexed { index, item ->
            var slot = slots[index]
            if (item == null || !item.active) {
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
                slot = Slot(content.context, index)
                content.addView(slot.frame)
                slots[index] = slot
            }
            slot.bind(item)
        }
    }

    private fun deliver(index: Int, bitmap: Bitmap, item: OverlayItem) {
        val frame = Frame(bitmap, item)
        latest[index] = frame
        listeners.forEach { it.onWidgetFrame(index, frame) }
    }

    private fun onCopyFailed(reason: String) {
        copyFailures++
        Log.w(TAG, "widget window copy failed ($reason), $copyFailures in a row")
        if (copyFailures >= MAX_COPY_FAILURES && windowCapture) {
            windowCapture = false
            Log.w(TAG, "falling back to software drawing (video in widgets will not show)")
        }
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "WidgetHost: main thread only" }

    /** 위젯 하나: 자리 잡은 틀과 그 안의 웹 페이지, 그리고 그 그림을 뜨는 일. */
    private class Slot(context: Context, private val index: Int) {
        private var destroyed = false

        /** 마지막으로 뜬 뒤에 페이지가 다시 그려졌는지. */
        private var dirty = false
        private var scheduled = false

        /** 창 복사를 맡기고 결과를 기다리는 중 (한 번에 하나만). */
        private var copying = false

        /** 바뀜이 멈춘 뒤의 마무리 뜨기를 이미 했거나 맡겼는지. */
        private var settled = true
        private var lastCaptureMs = 0L

        /** 소프트웨어로 뜨느라 우리가 그리는 중 (이때의 그리기는 "페이지가 바뀜"이 아니다). */
        private var drawingForCapture = false

        @SuppressLint("SetJavaScriptEnabled")
        val webView: WebView = object : WebView(context) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                // 페이지가 다시 그려질 때마다 (영상이면 프레임마다) 불린다.
                if (!drawingForCapture) markDirty()
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 후원 알림은 소리·영상을 스스로 재생한다 (OBS 브라우저 소스처럼).
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) = applyZoom()
            }
            webChromeClient = object : WebChromeClient() {
                // 영상이 뜨기 전에 보이는 회색 재생 그림 대신 아무것도 안 보이게 한다 (방송 화면에 회색 칸이 뜨지 않게).
                override fun getDefaultVideoPoster(): Bitmap = EMPTY_POSTER
            }
            // 영상(video)이 복사한 그림에 담기게 한다. 그냥 두면 WebView가 영상을 창 뒤의 별도 층에 올리고 창에는 구멍만
            // 뚫어서, 창을 복사하면 영상 자리가 투명하게 나온다 (Galaxy S25 Ultra에서 확인). 웹 페이지를 자기 층에 그리게
            // 하면 WebView가 그 방식을 못 써서 영상을 페이지와 함께 창 안에 그린다.
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        val frame = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        var item: OverlayItem? = null
            private set
        private var loadedUrl: String? = null

        init {
            // 영상 후원은 위젯 페이지 안에 유튜브 같은 다른 사이트의 플레이어를 넣는다. 그 플레이어가 쿠키를 못 쓰면
            // 재생을 거부하는 경우가 있어 허용한다 (OBS 브라우저 소스와 같게). 위젯 페이지에만 해당한다.
            runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true) }
        }

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
            markDirty()
        }

        private fun applyZoom() {
            val zoom = item?.zoom ?: 1f
            webView.evaluateJavascript("document.documentElement.style.zoom='$zoom'", null)
        }

        fun markDirty() {
            if (destroyed) return
            dirty = true
            settled = false
            schedule()
        }

        private fun schedule() {
            if (scheduled || copying || destroyed) return
            scheduled = true
            val wait = (lastCaptureMs + frameMs() - SystemClock.uptimeMillis()).coerceAtLeast(0)
            main.postDelayed(captureRunnable, wait)
        }

        private fun frameMs(): Long {
            val current = item ?: return FRAME_MS
            return when (WidgetLayout.kind(index, current)) {
                WidgetLayout.Kind.ALERT, WidgetLayout.Kind.VIDEO -> MOTION_FRAME_MS
                else -> FRAME_MS
            }
        }

        private val captureRunnable = Runnable {
            scheduled = false
            if (destroyed || !dirty) return@Runnable
            val current = item ?: return@Runnable
            val w = frame.width
            val h = frame.height
            if (w <= 0 || h <= 0) return@Runnable // 아직 자리를 잡지 않음: 자리를 잡으면 다시 그려져 여기로 온다
            dirty = false
            lastCaptureMs = SystemClock.uptimeMillis()
            val window = presentation?.window
            if (!windowCapture || window == null) {
                captureSoftware(w, h)?.let { deliver(index, it, current) }
                afterCapture()
                return@Runnable
            }
            val location = IntArray(2)
            frame.getLocationInWindow(location)
            val source = Rect(location[0], location[1], location[0] + w, location[1] + h)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            copying = true
            try {
                PixelCopy.request(window, source, bitmap, { result ->
                    copying = false
                    if (destroyed) return@request
                    if (result == PixelCopy.SUCCESS) {
                        copyFailures = 0
                        deliver(index, bitmap, current)
                    } else {
                        onCopyFailed("result $result")
                        captureSoftware(w, h)?.let { deliver(index, it, current) }
                    }
                    afterCapture()
                }, main)
            } catch (e: IllegalArgumentException) {
                // 창이 아직 그려지지 않아 복사할 것이 없다.
                copying = false
                onCopyFailed(e.message ?: "no surface")
                captureSoftware(w, h)?.let { deliver(index, it, current) }
                afterCapture()
            }
        }

        /** 뜨는 사이에 또 바뀌었으면 다시 뜨고, 아니면 조금 뒤에 마무리로 한 번 더 뜬다. */
        private fun afterCapture() {
            if (destroyed) return
            if (dirty) {
                schedule()
            } else if (!settled) {
                settled = true
                main.postDelayed(settleRunnable, SETTLE_MS)
            }
        }

        private val settleRunnable = Runnable {
            if (destroyed || dirty || scheduled || copying) return@Runnable
            dirty = true // settled는 그대로 둔다: 이 뜨기 뒤에는 다시 마무리하지 않는다
            schedule()
        }

        /** 옛 방식: 웹 페이지를 소프트웨어로 다시 그린다 (투명도는 담기지만 영상은 빈 칸). */
        private fun captureSoftware(w: Int, h: Int): Bitmap? {
            drawingForCapture = true
            return try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { frame.draw(Canvas(it)) }
            } catch (e: Exception) {
                Log.w(TAG, "widget capture failed", e)
                null
            } finally {
                drawingForCapture = false
            }
        }

        fun destroy() {
            destroyed = true
            main.removeCallbacks(captureRunnable)
            main.removeCallbacks(settleRunnable)
            webView.stopLoading()
            webView.destroy()
        }
    }
}
