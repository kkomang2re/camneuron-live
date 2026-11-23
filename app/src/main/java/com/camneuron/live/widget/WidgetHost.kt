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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
 * 폰이 뜨거우면 [slowdown]만큼 더 드물게 뜬다.
 * 가상 화면 전체의 출력(ImageReader)은 투명도가 없어 쓰지 않는다.
 *
 * 창을 복사하므로 두 위젯의 자리가 겹치면 겹친 곳에는 서로의 그림이 함께 담긴다 (반투명한 곳이 조금 진해질 뿐이다).
 *
 * 위젯 페이지가 말썽일 때: [skipDonation]은 후원 위젯의 시계를 빨리 감아 지금 나오는 후원을 넘기고 (밀린 다음 후원은 남는다),
 * [reloadAll]은 페이지를 다시 부른다. 페이지를 불러오지 못했으면 조금 뒤에 스스로 다시 부르고, 웹 페이지를 그리는
 * 프로세스가 죽으면 (메모리 부족 등) 웹 뷰를 새로 만든다. 그 일을 처리하지 않으면 앱까지 함께 죽는다.
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

    /** 페이지를 불러오지 못했을 때 다시 부르기까지 기다리는 시간. */
    private const val RETRY_LOAD_MS = 5_000L

    /** 위젯 페이지에 넣는 스크립트 (assets의 widget_time.js와 widget_skip.js). 처음 쓸 때 읽는다. */
    private var pageScript: String? = null

    /**
     * 후원을 넘길 수 있게 페이지에 넣는 스크립트. 페이지의 타이머와 시계를 감싸 두었다가 넘기라고 하면 페이지의 시간만 빨리
     * 감는다. 여러 번 넣어도 한 번만 걸린다. 읽지 못하면 null.
     */
    private fun pageScript(): String? = pageScript ?: runCatching {
        val assets = context?.assets ?: return@runCatching null
        listOf("widget_time.js", "widget_skip.js").joinToString("\n") { name ->
            assets.open(name).bufferedReader().use { it.readText() }
        }
    }.onFailure { Log.w(TAG, "widget page script unavailable", it) }.getOrNull()?.also { pageScript = it }

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

    /**
     * 그림을 뜨는 간격에 곱하는 값 (1 이상). 폰이 뜨거우면 방송 세션이 늘린다 ([com.camneuron.live.LiveHeat]).
     * 위젯을 뜨는 일(웹 페이지 그리기, 창 복사, 인코더에 올리기)도 GPU를 써서 폰을 데운다.
     */
    var slowdown = 1f
        set(value) {
            checkMain()
            field = value.coerceAtLeast(1f)
        }

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

    /** 모든 위젯 페이지를 다시 부른다 (멈추거나 이상해졌을 때). 페이지가 쌓아 둔 후원은 사라질 수 있다. 다시 부른 위젯 수를 돌려준다. */
    fun reloadAll(): Int {
        checkMain()
        var count = 0
        slots.forEach { if (it != null && it.reload()) count++ }
        return count
    }

    /**
     * 후원 위젯에서 지금 나오는 후원을 넘긴다. 누르는 즉시 그 위젯을 가리고 소리를 끈 뒤 (방송 화면에서 바로 사라진다),
     * 후원이 바뀌면 다시 보인다. 페이지는 그대로라서 밀려 있던 다음 후원이 이어서 나온다.
     * - 후원마다 정해진 시간이 지나야 넘어가는 위젯은 페이지의 시계를 빨리 감아 바로 넘어가게 한다 (1~2초).
     * - 재생 시간을 서버가 재는 위젯([WidgetLayout.isServerTimed], 치지직 영상 후원)은 앞당길 수 없다. 가린 채로 서버가
     *   다음 후원을 보내기를 기다린다.
     * [onResult]에 대상 위젯 수와 넘기기 시작한 위젯 수를 알린다 (메인 스레드). 나오는 후원이 없는 위젯은 시작하지 않는다.
     *
     * 대상은 후원 알림·영상 후원 위젯이고, 이름으로 알아볼 수 없으면 채팅창이 아닌 모든 위젯이다 (그때는 가리지 않는다).
     */
    fun skipDonation(onResult: (widgets: Int, started: Int) -> Unit) {
        checkMain()
        val active = slots.filterNotNull()
        val donations = active.filter { it.isDonation() }
        val targets = donations.ifEmpty { active.filter { !it.isChat() } }
        val hide = donations.isNotEmpty()
        val script = pageScript()
        if (targets.isEmpty() || script == null) {
            onResult(targets.size, 0)
            return
        }
        var waiting = targets.size
        var started = 0
        for (slot in targets) {
            // 스크립트를 함께 넣는다: 페이지를 불러올 때 넣지 못했어도 (이때부터 거는 타이머는) 넘길 수 있다.
            slot.evaluate("$script\nwindow.__camneuronTime.skip($hide, ${slot.isServerTimed()})") { result ->
                if (result?.contains("started") == true || result?.contains("busy") == true) started++
                if (--waiting == 0) onResult(targets.size, started)
            }
        }
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
    private class Slot(private val context: Context, private val index: Int) {
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

        private var webView: WebView = newWebView()

        @SuppressLint("SetJavaScriptEnabled")
        private fun newWebView(): WebView = object : WebView(context) {
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
                override fun onPageFinished(view: WebView, url: String?) {
                    applyZoom()
                    injectPageScript()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    // 페이지 자체를 불러오지 못했을 때만 (네트워크가 잠깐 끊긴 경우). 그대로 두면 빈 위젯으로 남는다.
                    if (request.isForMainFrame) {
                        Log.w(TAG, "widget $index failed to load (${error.errorCode}), retrying in ${RETRY_LOAD_MS}ms")
                        main.removeCallbacks(retryRunnable)
                        main.postDelayed(retryRunnable, RETRY_LOAD_MS)
                    }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    // 웹 페이지를 그리는 프로세스가 죽었다. true를 돌려주지 않으면 앱도 함께 죽는다. 이 웹 뷰는 더 쓸 수 없어 새로 만든다.
                    Log.w(TAG, "widget $index renderer gone (crashed ${detail.didCrash()}), recreating the web view")
                    main.post { recreate() }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                // 영상이 뜨기 전에 보이는 회색 재생 그림 대신 아무것도 안 보이게 한다 (방송 화면에 회색 칸이 뜨지 않게).
                override fun getDefaultVideoPoster(): Bitmap = EMPTY_POSTER

                // 페이지를 불러오는 동안에도 넣어 둔다. 일찍 넣을수록 페이지가 거는 타이머를 더 많이 감쌀 수 있다
                // (다 불러온 뒤에 넣어도 그 뒤에 오는 후원은 넘길 수 있다).
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (newProgress in 10..99) injectPageScript()
                }
            }
            // 영상(video)이 복사한 그림에 담기게 한다. 그냥 두면 WebView가 영상을 창 뒤의 별도 층에 올리고 창에는 구멍만
            // 뚫어서, 창을 복사하면 영상 자리가 투명하게 나온다 (Galaxy S25 Ultra에서 확인). 웹 페이지를 자기 층에 그리게
            // 하면 WebView가 그 방식을 못 써서 영상을 페이지와 함께 창 안에 그린다.
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            // 영상 후원은 위젯 페이지 안에 유튜브 같은 다른 사이트의 플레이어를 넣는다. 그 플레이어가 쿠키를 못 쓰면
            // 재생을 거부하는 경우가 있어 허용한다 (OBS 브라우저 소스와 같게). 위젯 페이지에만 해당한다.
            runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(this, true) }
        }

        val frame = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        var item: OverlayItem? = null
            private set
        private var loadedUrl: String? = null

        /** 불러오지 못한 페이지를 다시 부른다. */
        private val retryRunnable = Runnable { if (!destroyed) loadedUrl?.let { webView.loadUrl(it) } }

        /** 후원 알림이나 영상 후원인지 ([WidgetHost.skipDonation]의 대상). */
        fun isDonation(): Boolean {
            val current = item ?: return false
            return WidgetLayout.isDonation(WidgetLayout.kind(index, current))
        }

        fun isServerTimed(): Boolean = WidgetLayout.isServerTimed(loadedUrl.orEmpty())

        fun isChat(): Boolean {
            val current = item ?: return false
            return WidgetLayout.kind(index, current) == WidgetLayout.Kind.CHAT
        }

        /** 후원을 넘길 수 있게 하는 스크립트를 페이지에 넣는다 ([pageScript]). 채팅창에는 넣지 않는다. */
        private fun injectPageScript() {
            if (destroyed || isChat()) return
            val script = pageScript() ?: return
            webView.evaluateJavascript(script, null)
        }

        /** 페이지를 처음부터 다시 부른다. 주소가 없으면 false. */
        fun reload(): Boolean {
            val url = loadedUrl
            if (destroyed || url == null) return false
            main.removeCallbacks(retryRunnable)
            webView.loadUrl(url)
            markDirty()
            return true
        }

        /** 페이지에서 [script]를 돌리고 결과(JSON 글자)를 [onResult]에 넘긴다. */
        fun evaluate(script: String, onResult: (String?) -> Unit) {
            if (destroyed) onResult(null) else webView.evaluateJavascript(script) { onResult(it) }
        }

        /** 웹 뷰를 새로 만들어 같은 주소를 다시 부른다 (그리는 프로세스가 죽은 웹 뷰는 다시 쓸 수 없다). */
        private fun recreate() {
            if (destroyed) return
            val old = webView
            frame.removeView(old)
            runCatching { old.destroy() }
            webView = newWebView()
            frame.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            loadedUrl?.let { webView.loadUrl(it) }
            markDirty()
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
            val current = item
            val base = when (current?.let { WidgetLayout.kind(index, it) }) {
                WidgetLayout.Kind.ALERT, WidgetLayout.Kind.VIDEO -> MOTION_FRAME_MS
                else -> FRAME_MS
            }
            return (base * slowdown).toLong()
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
            main.removeCallbacks(retryRunnable)
            webView.stopLoading()
            webView.destroy()
        }
    }
}
