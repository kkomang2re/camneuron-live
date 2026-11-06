package com.camneuron.live

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.inputmethod.EditorInfo
import android.widget.ScrollView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.window.OnBackInvokedDispatcher
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.camneuron.core.camera.CameraCaps
import com.camneuron.core.camera.CameraControls
import com.camneuron.core.camera.CameraEngine
import com.camneuron.core.camera.FocusMode
import com.camneuron.core.camera.NormPoint
import com.camneuron.core.camera.WhiteBalance
import com.camneuron.core.encode.BeautySettings
import com.camneuron.core.encode.BeautyStore
import com.camneuron.core.device.DeviceStatus
import com.camneuron.core.overlay.OverlayItem
import com.camneuron.core.overlay.OverlaySettings
import com.camneuron.core.overlay.OverlayStore
import com.camneuron.live.chzzk.ChzzkAccount
import com.camneuron.live.chzzk.ChzzkApi
import com.camneuron.live.chzzk.ChzzkCategory
import com.camneuron.live.chzzk.ChzzkChat
import com.camneuron.live.chzzk.ChzzkChatSession
import com.camneuron.live.chzzk.ChzzkChatSettings
import com.camneuron.live.chzzk.ChzzkCredentials
import com.camneuron.live.chzzk.ChzzkDiagnose
import com.camneuron.live.chzzk.ChzzkEvent
import com.camneuron.live.chzzk.ChzzkLiveSetting
import com.camneuron.live.chzzk.ChzzkPrefs
import com.camneuron.live.chzzk.ChzzkTags
import com.camneuron.live.chzzk.LoginActivity
import com.camneuron.live.rtmp.RtmpUrl
import com.camneuron.live.widget.BrowserSources
import com.camneuron.live.widget.WidgetCanvas
import com.camneuron.live.widget.WidgetLayout
import com.camneuron.live.widget.WidgetFrame
import com.camneuron.live.widget.WidgetHost
import com.camneuron.live.widget.WidgetUrls
import kotlin.math.min

/**
 * 한 화면: 카메라 미리보기(방송 화면 16:9를 왼쪽에 붙인다), 그 오른쪽 빈 띠에 버튼 한 줄과 "더보기",
 * 왼쪽 위에 방송 준비 카드(방송 전) 또는 상태 글자(방송 중), 아래에 줌 단계.
 * 미리보기를 누르면 그곳에 초점·노출을 맞추고, 길게 누르면 자동 초점으로 돌아가며, 두 손가락으로 줌.
 * 방송은 [LiveService]가 하므로 이 화면을 닫아도 계속된다.
 */
class MainActivity : Activity() {
    private lateinit var engine: CameraEngine
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var liveButton: TextView
    private lateinit var settingsButton: TextView
    private lateinit var settingsPanel: View
    private lateinit var serverInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var micButton: TextView
    private lateinit var coverButton: TextView
    private lateinit var torchButton: TextView
    private lateinit var stabilizationButton: TextView
    private lateinit var zoomBar: LinearLayout
    private lateinit var focusRing: View
    private lateinit var coverBanner: View
    private lateinit var presetRow: LinearLayout
    private lateinit var bitrateLabel: TextView
    private lateinit var bitrateSeek: SeekBar
    private lateinit var keyFrame1: TextView
    private lateinit var keyFrame2: TextView
    private lateinit var widgetLayer: FrameLayout
    private lateinit var widgetFrames: List<WidgetFrame>
    private lateinit var widgetDone: View
    private lateinit var widgetAutoButton: TextView

    // 오른쪽 버튼 줄과 "더보기"
    private lateinit var controlsView: View
    private lateinit var rail: LinearLayout
    private lateinit var morePanel: LinearLayout
    private lateinit var moreButton: TextView
    private lateinit var flipButton: TextView
    private lateinit var widgetsButton: TextView

    /** 버튼이 지금 방송 중 자리에 놓였는지. null이면 아직 놓지 않았다 ([arrangeButtons]). */
    private var buttonsLive: Boolean? = null

    /** 영상 오른쪽 빈 띠에 버튼 줄이 들어가는지. 안 들어가는 화면비면 줄이 영상 위에 겹친다 (위젯 조정 중에는 숨긴다). */
    private var railFits = true

    /** 시스템 바·카메라 구멍 (px). 영상과 버튼을 그 안쪽에 둔다. */
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0

    // 방송 준비 카드 (방송 전, 왼쪽 위)
    private class PrepRow(val view: View) {
        val label: TextView = view.findViewById(R.id.prep_label)
        val value: TextView = view.findViewById(R.id.prep_value)
        val arrow: View = view.findViewById(R.id.prep_arrow)
    }
    private lateinit var prepCard: View
    private lateinit var prepHeaderTitle: TextView
    private lateinit var prepHeaderToggle: TextView
    private lateinit var prepRows: View
    private lateinit var prepTitle: PrepRow
    private lateinit var prepCategory: PrepRow
    private lateinit var prepQuality: PrepRow
    private lateinit var prepWidgets: PrepRow
    private lateinit var prepCheck: PrepRow
    private var prepCollapsed = false

    /** 카드에 보이는 치지직 방송 제목·카테고리. 로그인했을 때 불러온다 ([refreshPrepLiveInfo]). */
    private var prepLiveSetting: ChzzkLiveSetting? = null
    private var prepLiveLoading = false
    private var prepLiveFailed = false

    /** "자동 배치" 직전의 위젯 설정. 있으면 그 버튼이 "되돌리기"가 된다. 손으로 옮기거나 조정을 끝내면 지운다. */
    private var beforeAutoLayout: OverlaySettings? = null
    private lateinit var chatUrlInput: EditText
    private lateinit var alertUrlInput: EditText
    private lateinit var chatStreamChip: TextView
    private lateinit var alertStreamChip: TextView
    private lateinit var sourcesList: LinearLayout
    private lateinit var addSourceButton: TextView

    /** 설정 화면에서 고치는 중인 브라우저 소스 한 줄. [base]는 저장된 것 (자리·배율을 지킨다), 새로 더한 줄이면 null. */
    private class SourceRow(val view: View, val base: OverlayItem?, var stream: Boolean) {
        val name: EditText = view.findViewById(R.id.input_source_name)
        val url: EditText = view.findViewById(R.id.input_source_url)
        val streamChip: TextView = view.findViewById(R.id.chip_source_stream)
    }
    private val sourceRows = mutableListOf<SourceRow>()

    /** 설정 화면에서 고치는 중인 "방송 화면에 넣기" (채팅창, 후원 알림). */
    private var draftChatStream = true
    private var draftAlertStream = true
    private var editingWidgets = false

    // 치지직 계정·방송 정보 (설정 창)
    private lateinit var chzzkStatus: TextView
    private lateinit var clientIdInput: EditText
    private lateinit var clientSecretInput: EditText
    private lateinit var redirectInput: EditText
    private lateinit var showSecretButton: TextView
    private lateinit var secretSaved: TextView
    private var secretVisible = false
    private lateinit var liveInfoSection: View
    private lateinit var titleInput: EditText
    private lateinit var categoryCurrent: TextView
    private lateinit var categorySearchInput: EditText
    private lateinit var categoryResults: LinearLayout
    private lateinit var tagsInput: EditText

    /** 고른 카테고리. null이면 바꾸지 않는다, [ChzzkApi.NO_CATEGORY]면 지운다. */
    private var draftCategory: ChzzkCategory? = null

    // 채팅 창
    private lateinit var chatButton: TextView
    private lateinit var chatPanel: View
    private lateinit var chatStatus: TextView
    private lateinit var chatLines: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatInput: EditText
    private lateinit var slowChip: TextView
    private lateinit var followerChip: TextView
    private lateinit var readAloudChip: TextView
    private var chatSettings: ChzzkChatSettings? = null
    private var shownTotal = -1L
    private val chatListener = ChzzkChat.Listener { renderChat() }

    // 보정 창
    private lateinit var adjustButton: TextView
    private lateinit var adjustPanel: View
    private lateinit var smoothRow: LinearLayout
    private lateinit var brightRow: LinearLayout
    private lateinit var evLabel: TextView
    private lateinit var evSeek: SeekBar
    private lateinit var wbChips: LinearLayout
    private lateinit var lockChip: TextView

    private val main = Handler(Looper.getMainLooper())
    private var permissionsRequested = false

    /** 설정 화면에서 고치는 중인 화질. 저장하면 [LivePrefs]에 쓴다. */
    private var draftQuality = LiveQuality()
    private var zoomSteps: List<Float> = emptyList()
    private var pinchZoom = 1f

    private val stateListener: (LiveState.Status) -> Unit = { render(it) }
    private val hideFocusRing = Runnable { focusRing.visibility = View.GONE }

    private val widgetListener = WidgetHost.Listener { index, frame -> widgetFrames[index].setBitmap(frame?.bitmap) }
    private val overlayListener: (OverlaySettings) -> Unit = { runOnUiThread { renderWidgets() } }

    private val engineListener = object : CameraEngine.Listener {
        override fun onPreviewSizeChanged(size: Size) = runOnUiThread { applyPreviewTransform() }

        override fun onCapsChanged(caps: CameraCaps) = runOnUiThread {
            rebuildZoomBar(caps)
            renderCameraControls()
            rebuildWhiteBalanceChips(caps)
            renderAdjust()
        }

        override fun onControlsChanged(controls: CameraControls) = runOnUiThread {
            renderCameraControls()
            renderAdjust()
        }

        override fun onError(message: String?) = runOnUiThread {
            Toast.makeText(this@MainActivity, message ?: "camera error", Toast.LENGTH_SHORT).show()
        }
    }

    /** 방송 시간과 배터리를 1초마다 다시 그린다. */
    private val ticker = object : Runnable {
        override fun run() {
            render(LiveState.current)
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = CameraEngine.get(this)

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        liveButton = findViewById(R.id.btn_live)
        settingsButton = findViewById(R.id.btn_settings)
        settingsPanel = findViewById(R.id.settings_panel)
        serverInput = findViewById(R.id.input_server)
        keyInput = findViewById(R.id.input_key)
        micButton = findViewById(R.id.btn_mic)
        coverButton = findViewById(R.id.btn_cover)
        torchButton = findViewById(R.id.btn_torch)
        stabilizationButton = findViewById(R.id.btn_stabilization)
        zoomBar = findViewById(R.id.zoom_bar)
        focusRing = findViewById(R.id.focus_ring)
        coverBanner = findViewById(R.id.cover_banner)
        presetRow = findViewById(R.id.quality_presets)
        bitrateLabel = findViewById(R.id.label_bitrate)
        bitrateSeek = findViewById(R.id.seek_bitrate)
        keyFrame1 = findViewById(R.id.key_frame_1)
        keyFrame2 = findViewById(R.id.key_frame_2)
        widgetLayer = findViewById(R.id.widget_layer)
        widgetDone = findViewById(R.id.widget_edit_bar)
        widgetAutoButton = findViewById(R.id.btn_widget_auto)
        chatUrlInput = findViewById(R.id.input_chat_url)
        alertUrlInput = findViewById(R.id.input_alert_url)
        chatStreamChip = findViewById(R.id.chip_chat_stream)
        alertStreamChip = findViewById(R.id.chip_alert_stream)
        sourcesList = findViewById(R.id.sources_list)
        addSourceButton = findViewById(R.id.btn_add_source)
        OverlayStore.init(this)
        chzzkStatus = findViewById(R.id.chzzk_status)
        clientIdInput = findViewById(R.id.input_client_id)
        clientSecretInput = findViewById(R.id.input_client_secret)
        redirectInput = findViewById(R.id.input_redirect)
        showSecretButton = findViewById(R.id.btn_show_secret)
        secretSaved = findViewById(R.id.secret_saved)
        liveInfoSection = findViewById(R.id.live_info_section)
        titleInput = findViewById(R.id.input_title)
        categoryCurrent = findViewById(R.id.category_current)
        categorySearchInput = findViewById(R.id.input_category_search)
        categoryResults = findViewById(R.id.category_results)
        tagsInput = findViewById(R.id.input_tags)
        chatButton = findViewById(R.id.btn_chat)
        chatPanel = findViewById(R.id.chat_panel)
        chatStatus = findViewById(R.id.chat_status)
        chatLines = findViewById(R.id.chat_lines)
        chatScroll = findViewById(R.id.chat_scroll)
        chatInput = findViewById(R.id.chat_input)
        slowChip = findViewById(R.id.chip_slow)
        followerChip = findViewById(R.id.chip_follower)
        readAloudChip = findViewById(R.id.chip_read_aloud)
        adjustButton = findViewById(R.id.btn_adjust)
        adjustPanel = findViewById(R.id.adjust_panel)
        smoothRow = findViewById(R.id.row_beauty_smooth)
        brightRow = findViewById(R.id.row_beauty_bright)
        evLabel = findViewById(R.id.label_ev)
        evSeek = findViewById(R.id.seek_ev)
        wbChips = findViewById(R.id.wb_chips)
        lockChip = findViewById(R.id.chip_lock)
        controlsView = findViewById(R.id.controls)
        rail = findViewById(R.id.rail)
        morePanel = findViewById(R.id.more_panel)
        moreButton = findViewById(R.id.btn_more)
        flipButton = findViewById(R.id.btn_flip)
        widgetsButton = findViewById(R.id.btn_widgets)
        prepCard = findViewById(R.id.prep_card)
        prepHeaderTitle = findViewById(R.id.prep_header_title)
        prepHeaderToggle = findViewById(R.id.prep_header_toggle)
        prepRows = findViewById(R.id.prep_rows)
        prepTitle = PrepRow(findViewById(R.id.prep_row_title))
        prepCategory = PrepRow(findViewById(R.id.prep_row_category))
        prepQuality = PrepRow(findViewById(R.id.prep_row_quality))
        prepWidgets = PrepRow(findViewById(R.id.prep_row_widgets))
        prepCheck = PrepRow(findViewById(R.id.prep_row_check))
        BeautyStore.init(this)

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                if (hasCameraPermission()) engine.setPreviewTexture(texture)
                applyPreviewTransform()
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) =
                applyPreviewTransform()

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                // 카메라가 이 SurfaceTexture를 놓은 뒤에 해제한다.
                engine.setPreviewTexture(null) { texture.release() }
                return false
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }

        liveButton.setOnClickListener { toggleLive() }
        settingsButton.setOnClickListener { showSettings(true) }
        flipButton.setOnClickListener {
            showMore(false)
            engine.requestFacing(!engine.controls.front)
        }
        moreButton.setOnClickListener { showMore(morePanel.visibility != View.VISIBLE) }
        findViewById<View>(R.id.btn_save).setOnClickListener { saveSettings() }
        findViewById<View>(R.id.btn_cancel).setOnClickListener { showSettings(false) }
        setUpQuickButtons()
        setUpPrepCard()
        setUpQualityControls()
        setUpWidgets()
        setUpChzzk()
        setUpChat()
        setUpAdjust()
        setUpGestures()
        avoidSystemBars()
        // Android 13부터는 뒤로 가기가 이 콜백으로 온다 (이 앱은 API 36이 대상이라 onBackPressed가 불리지 않는다).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBack()
    }

    // ---------------------------------------------------------------------------------------------
    // 뒤로 가기, 종료

    /** 열린 창이 있으면 그것부터 닫고, 없으면 종료할지 묻는다. */
    private fun handleBack() {
        when {
            morePanel.visibility == View.VISIBLE -> showMore(false)
            settingsPanel.visibility == View.VISIBLE -> showSettings(false)
            editingWidgets -> setEditingWidgets(false)
            chatPanel.visibility == View.VISIBLE || adjustPanel.visibility == View.VISIBLE -> {
                chatPanel.visibility = View.GONE
                adjustPanel.visibility = View.GONE
                render(LiveState.current)
            }
            else -> confirmExit()
        }
    }

    private fun confirmExit() {
        val live = LiveState.current.phase != LiveState.Phase.IDLE
        val dialog = android.app.AlertDialog.Builder(this)
        if (live) {
            dialog.setTitle(R.string.exit_live_title)
                .setMessage(R.string.exit_live_message)
                .setPositiveButton(R.string.exit_live_stop) { _, _ -> exitApp() }
                .setNeutralButton(R.string.exit_background) { _, _ -> moveTaskToBack(true) }
                .setNegativeButton(R.string.btn_cancel, null)
        } else {
            dialog.setTitle(R.string.exit_title)
                .setMessage(R.string.exit_message)
                .setPositiveButton(R.string.exit_confirm) { _, _ -> exitApp() }
                .setNegativeButton(R.string.btn_cancel, null)
        }
        dialog.show()
    }

    /**
     * 완전히 끈다: 방송 중이면 서버에 방송 종료를 알리고, 화면을 닫고 최근 앱 목록에서도 빼고, 앱 프로세스를 끝낸다
     * (카메라·채팅·위젯 연결이 남지 않게). 방송 세션이 끝날 때까지(많아야 [EXIT_WAIT_MS]) 기다린 뒤 프로세스를 끝낸다.
     */
    private fun exitApp() {
        if (LiveState.current.phase != LiveState.Phase.IDLE) LiveService.stop(this)
        LiveControls.setCovered(false)
        finishAndRemoveTask()
        val deadline = SystemClock.uptimeMillis() + EXIT_WAIT_MS
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (LiveState.current.phase == LiveState.Phase.IDLE || SystemClock.uptimeMillis() > deadline) {
                    android.os.Process.killProcess(android.os.Process.myPid())
                } else {
                    handler.postDelayed(this, 200)
                }
            }
        }, EXIT_SETTLE_MS)
    }

    /**
     * Android 15부터 앱이 화면 끝까지 그려져 가로 화면에서 내비게이션 바가 버튼을 가린다.
     * 미리보기 뷰는 끝까지 두고, 영상은 카메라 구멍 안쪽부터 그리며([displayedImageRect]),
     * 버튼·줌 단계·상태 글자·준비 카드는 시스템 바·카메라 구멍을 피해 안쪽으로 민다.
     */
    private fun avoidSystemBars() {
        val statusMargins = status.layoutParams as ViewGroup.MarginLayoutParams
        val baseStart = statusMargins.leftMargin
        val baseTop = statusMargins.topMargin
        val cardMargins = prepCard.layoutParams as ViewGroup.MarginLayoutParams
        val baseCardStart = cardMargins.leftMargin
        val baseCardTop = cardMargins.topMargin
        val zoomMargins = zoomBar.layoutParams as ViewGroup.MarginLayoutParams
        val baseZoomBottom = zoomMargins.bottomMargin
        val bannerMargins = coverBanner.layoutParams as ViewGroup.MarginLayoutParams
        val baseBannerBottom = bannerMargins.bottomMargin
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                left = bars.left
                top = bars.top
                right = bars.right
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                left = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                right = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            insetLeft = left
            insetTop = top
            insetRight = right
            insetBottom = bottom
            statusMargins.leftMargin = baseStart + left
            statusMargins.topMargin = baseTop + top
            status.layoutParams = statusMargins
            cardMargins.leftMargin = baseCardStart + left
            cardMargins.topMargin = baseCardTop + top
            prepCard.layoutParams = cardMargins
            zoomMargins.bottomMargin = baseZoomBottom + bottom
            zoomBar.layoutParams = zoomMargins
            bannerMargins.bottomMargin = baseBannerBottom + bottom
            coverBanner.layoutParams = bannerMargins
            applyPreviewTransform()
            layoutAroundVideo()
            view.onApplyWindowInsets(insets)
        }
    }

    /**
     * 영상 자리에 맞춰 나머지를 놓는다. 버튼 줄은 영상 오른쪽 빈 띠의 가운데에 (띠가 좁으면 오른쪽 끝에 겹쳐),
     * 줌 단계·가림 알림·위젯 조정 막대는 영상 가운데 아래에.
     */
    private fun layoutAroundVideo() {
        val railWidth = resources.getDimensionPixelSize(R.dimen.rail_width)
        val image = displayedImageRect()
        val screenWidth = preview.width.toFloat()
        val strip = if (image != null) screenWidth - image.right - insetRight else 0f
        val side = ((strip - railWidth) / 2).toInt()
        railFits = image != null && side >= 0
        // 들어가면 띠 가운데 (여백이 몇 px뿐이어도), 안 들어가면 오른쪽 끝에서 조금 띄운다.
        controlsView.setPadding(controlsView.paddingLeft, insetTop, insetRight + if (railFits) side else dp(12), insetBottom)
        val dx = if (image != null) image.centerX() - screenWidth / 2 else 0f
        zoomBar.translationX = dx
        coverBanner.translationX = dx
        widgetDone.translationX = dx
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        LiveState.addListener(stateListener)
        engine.addListener(engineListener)
        main.post(ticker)
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && !permissionsRequested) {
            permissionsRequested = true
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
        if (preview.isAvailable && hasCameraPermission()) preview.surfaceTexture?.let { engine.setPreviewTexture(it) }
        engine.caps?.let { rebuildZoomBar(it) }
        renderCameraControls()
        WidgetHost.acquire(this)
        WidgetHost.addListener(widgetListener)
        OverlayStore.addListener(overlayListener)
        for (i in 0 until WidgetHost.COUNT) widgetFrames[i].setBitmap(WidgetHost.latest(i)?.bitmap)
        renderWidgets()
        ChzzkChat.acquire(this)
        ChzzkChat.addListener(chatListener)
        shownTotal = -1 // 채팅 줄을 처음부터 다시 그린다
        renderChat()
        if (LiveState.current.phase == LiveState.Phase.IDLE) refreshPrepLiveInfo()
        // 이미 열려 있으면 다시 채우지 않는다 (다른 앱에서 값을 복사해 오는 동안 입력한 것이 지워지지 않게).
        if (LivePrefs.streamKey(this).isEmpty() && LiveState.current.phase == LiveState.Phase.IDLE &&
            settingsPanel.visibility != View.VISIBLE
        ) {
            showSettings(true)
        }
    }

    override fun onStop() {
        // 방송하지 않을 때는 카메라를 닫는다. 방송 중이면 엔진이 인코더에 계속 그린다.
        engine.setPreviewTexture(null)
        engine.removeListener(engineListener)
        LiveState.removeListener(stateListener)
        OverlayStore.removeListener(overlayListener)
        WidgetHost.removeListener(widgetListener)
        WidgetHost.release()
        ChzzkChat.removeListener(chatListener)
        ChzzkChat.release()
        setEditingWidgets(false)
        main.removeCallbacks(ticker)
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_LOGIN) return
        if (resultCode == RESULT_OK) {
            ChzzkChat.restart()
            // 로그인하면서 받은 스트림 키를 칸에 보인다.
            if (settingsPanel.visibility == View.VISIBLE) keyInput.setText(LivePrefs.streamKey(this))
            refreshPrepLiveInfo()
        }
        renderChzzkSettings()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (hasCameraPermission() && preview.isAvailable) preview.surfaceTexture?.let { engine.setPreviewTexture(it) }
    }

    private fun toggleLive() {
        if (LiveState.current.phase != LiveState.Phase.IDLE) {
            LiveService.stop(this)
            return
        }
        if (!hasCameraPermission()) {
            Toast.makeText(this, R.string.error_camera_permission, Toast.LENGTH_SHORT).show()
            requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS)
            return
        }
        if (RtmpUrl.parse(LivePrefs.serverUrl(this)) == null || LivePrefs.streamKey(this).isEmpty()) {
            showSettings(true)
            return
        }
        LiveService.start(this)
    }

    // ---------------------------------------------------------------------------------------------
    // 방송 중 조작

    private fun setUpQuickButtons() {
        micButton.setOnClickListener {
            LiveControls.setMicMuted(!LiveControls.micMuted)
            render(LiveState.current)
        }
        coverButton.setOnClickListener {
            LiveControls.setCovered(!LiveControls.covered)
            render(LiveState.current)
        }
        torchButton.setOnClickListener { engine.update { it.copy(torch = !it.torch) } }
        stabilizationButton.setOnClickListener { engine.update { it.copy(stabilization = !it.stabilization) } }
    }

    private fun renderCameraControls() {
        val caps = engine.caps
        val controls = engine.controls
        torchButton.isEnabled = caps?.torch == true
        torchButton.isActivated = controls.torch
        stabilizationButton.isEnabled = caps?.stabilization == true
        stabilizationButton.isActivated = controls.stabilization
        val selected = ZoomSteps.selected(zoomSteps, controls.zoom)
        for (i in 0 until zoomBar.childCount) {
            zoomBar.getChildAt(i).isActivated = zoomSteps.getOrNull(i) == selected
        }
    }

    private fun rebuildZoomBar(caps: CameraCaps) {
        val steps = ZoomSteps.steps(caps.zoomMin, caps.zoomMax)
        if (steps == zoomSteps && zoomBar.childCount == steps.size) return
        zoomSteps = steps
        zoomBar.removeAllViews()
        for (step in steps) {
            zoomBar.addView(chip(zoomBar, ZoomSteps.label(step)) { engine.update { it.copy(zoom = step) } })
        }
    }

    /** 고르는 칩 하나 (줌 단계, 화질 프리셋). */
    private fun chip(parent: ViewGroup, text: String, layout: Int = R.layout.chip, onClick: () -> Unit): TextView =
        (layoutInflater.inflate(layout, parent, false) as TextView).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchZoom = engine.controls.zoom
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val caps = engine.caps ?: return false
                pinchZoom = (pinchZoom * detector.scaleFactor).coerceIn(caps.zoomMin, caps.zoomMax)
                val zoom = pinchZoom
                engine.update { it.copy(zoom = zoom) }
                return true
            }
        })
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // 더보기가 열려 있으면 밖을 누른 것으로 보고 닫기만 한다.
                if (morePanel.visibility == View.VISIBLE) {
                    showMore(false)
                    return true
                }
                if (settingsPanel.visibility == View.VISIBLE || editingWidgets) return true
                focusAtView(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                engine.update { it.copy(focusMode = FocusMode.CONTINUOUS, focusPoint = null, aeLock = false, awbLock = false) }
                Toast.makeText(this@MainActivity, R.string.toast_focus_auto, Toast.LENGTH_SHORT).show()
            }
        })
        preview.setOnTouchListener { _, event ->
            scale.onTouchEvent(event)
            if (!scale.isInProgress) gestures.onTouchEvent(event)
            true
        }
    }

    /** 미리보기에서 누른 점을 송출 영상 좌표(0~1)로 바꿔 초점·노출을 맞춘다. */
    private fun focusAtView(x: Float, y: Float) {
        val image = displayedImageRect() ?: return
        if (!image.contains(x, y)) return
        var nx = (x - image.left) / image.width()
        val ny = (y - image.top) / image.height()
        // 전면 카메라 미리보기는 거울처럼 좌우가 뒤집혀 보이지만 송출 영상은 뒤집히지 않는다.
        if (engine.controls.front) nx = 1f - nx
        engine.focusAt(NormPoint(nx, ny))

        val size = focusRing.layoutParams.width
        focusRing.translationX = x - size / 2f
        focusRing.translationY = y - size / 2f
        focusRing.visibility = View.VISIBLE
        main.removeCallbacks(hideFocusRing)
        main.postDelayed(hideFocusRing, FOCUS_RING_MS)
    }

    /**
     * 미리보기 안에서 영상이 그려진 곳 ([applyPreviewTransform]과 같은 자리). 화면비를 지키며, 폰 화면이 영상보다
     * 길면 왼쪽(카메라 구멍 안쪽)에 붙여 오른쪽에 버튼 줄이 들어갈 빈 띠를 남긴다. 위아래가 남으면 가운데.
     */
    private fun displayedImageRect(): RectF? {
        val size = engine.previewSize ?: return null
        val viewWidth = preview.width.toFloat()
        val viewHeight = preview.height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f) return null
        val aspect = maxOf(size.width, size.height).toFloat() / minOf(size.width, size.height)
        val w: Float
        val h: Float
        if (viewWidth / viewHeight > aspect) {
            h = viewHeight
            w = viewHeight * aspect
        } else {
            w = viewWidth
            h = viewWidth / aspect
        }
        val left = min(insetLeft.toFloat(), viewWidth - w).coerceAtLeast(0f)
        val top = (viewHeight - h) / 2
        return RectF(left, top, left + w, top + h)
    }

    // ---------------------------------------------------------------------------------------------
    // 위젯 (채팅창, 후원 알림, 브라우저 소스)

    private fun setUpWidgets() {
        // 칸 번호 순서 (WidgetHost.CHAT, ALERT, 그 뒤 브라우저 소스). 뒤 칸이 위에 보인다 (방송 화면과 같은 순서).
        widgetFrames = List(WidgetHost.COUNT) { index ->
            WidgetFrame(
                this, widgetTitle(index, null),
                onMoved = { x, y, w, h ->
                    OverlayStore.update { BrowserSources.update(it, index) { item -> item.copy(x = x, y = y, w = w, h = h) } }
                    setBeforeAutoLayout(null)
                },
                onZoom = { zoom ->
                    OverlayStore.update { BrowserSources.update(it, index) { item -> item.copy(zoom = zoom) } }
                    setBeforeAutoLayout(null)
                },
            )
        }
        widgetFrames.forEach { widgetLayer.addView(it) }
        addSourceButton.setOnClickListener {
            if (sourceRows.size >= BrowserSources.MAX) {
                Toast.makeText(this, getString(R.string.toast_sources_full, BrowserSources.MAX), Toast.LENGTH_SHORT).show()
            } else {
                addSourceRow(null).url.requestFocus()
            }
        }
        chatStreamChip.setOnClickListener {
            draftChatStream = !draftChatStream
            renderWidgetDraft()
        }
        alertStreamChip.setOnClickListener {
            draftAlertStream = !draftAlertStream
            renderWidgetDraft()
        }
        findViewById<View>(R.id.btn_widget_edit).setOnClickListener {
            if (saveSettings()) setEditingWidgets(true)
        }
        findViewById<View>(R.id.btn_widget_done).setOnClickListener { setEditingWidgets(false) }
        widgetAutoButton.setOnClickListener { autoLayoutWidgets() }
        // 방송 중에도 된다. 옮기거나 배율을 바꾸면 바로 저장되고, 방송 세션이 같은 설정을 보고 송출 화면에도 바로 반영한다.
        widgetsButton.setOnClickListener {
            showMore(false)
            when {
                editingWidgets -> setEditingWidgets(false)
                BrowserSources.slots(OverlayStore.current).none { it?.active == true } ->
                    Toast.makeText(this, R.string.toast_no_widgets, Toast.LENGTH_LONG).show()
                else -> setEditingWidgets(true)
            }
        }
    }

    /** "자동 배치" 또는 (바로 전에 했으면) "되돌리기". */
    private fun autoLayoutWidgets() {
        val saved = beforeAutoLayout
        if (saved != null) {
            OverlayStore.update { WidgetLayout.restore(it, saved) }
            setBeforeAutoLayout(null)
            return
        }
        val current = OverlayStore.current
        val arranged = WidgetLayout.arrange(current)
        if (arranged == current) {
            Toast.makeText(this, R.string.toast_widget_auto_same, Toast.LENGTH_SHORT).show()
            return
        }
        OverlayStore.update { WidgetLayout.arrange(it) }
        setBeforeAutoLayout(current)
        Toast.makeText(this, R.string.toast_widget_auto, Toast.LENGTH_LONG).show()
    }

    private fun setBeforeAutoLayout(settings: OverlaySettings?) {
        beforeAutoLayout = settings
        widgetAutoButton.setText(if (settings != null) R.string.btn_widget_undo else R.string.btn_widget_auto)
    }

    private fun setEditingWidgets(editing: Boolean) {
        if (editing != editingWidgets) {
            // 조정하는 동안에는 줌 단계를 숨긴다 (그 밑에 놓인 위젯도 잡을 수 있게). 버튼 줄은 영상 밖 빈 띠에 있으면
            // 그대로 두어 방송 중에도 마이크·가리기를 누를 수 있게 하고, 영상 위에 겹치는 화면에서만 숨긴다.
            // (바뀔 때만: 방송 설정 창이 열리며 숨긴 것을 여기서 다시 보이게 하지 않도록)
            controlsView.visibility = if (editing && !railFits) View.INVISIBLE else View.VISIBLE
            zoomBar.visibility = if (editing) View.INVISIBLE else View.VISIBLE
        }
        editingWidgets = editing
        setBeforeAutoLayout(null)
        widgetFrames.forEach { it.editable = editing }
        widgetDone.visibility = if (editing) View.VISIBLE else View.GONE
        widgetsButton.isActivated = editing
        if (editing) {
            // 채팅·보정 창이 왼쪽 위젯(채팅창 기본 자리)을 가리지 않게 닫는다.
            chatPanel.visibility = View.GONE
            adjustPanel.visibility = View.GONE
            chatButton.isActivated = false
            adjustButton.isActivated = false
            showMore(false)
            Toast.makeText(this, R.string.toast_widget_edit, Toast.LENGTH_LONG).show()
        }
        render(LiveState.current)
    }

    /** 위젯 자리를 미리보기 속 영상 위치에 맞춘다. */
    private fun renderWidgets() {
        val image = displayedImageRect() ?: RectF()
        BrowserSources.slots(OverlayStore.current).forEachIndexed { i, slot ->
            val item = slot ?: EMPTY_WIDGET
            val label = getString(if (WidgetCanvas.streamed(item)) R.string.widget_streamed else R.string.widget_phone_only)
            widgetFrames[i].bind(item, image, label, widgetTitle(i, slot))
        }
    }

    private fun widgetTitle(index: Int, item: OverlayItem?): String = when (index) {
        WidgetHost.CHAT -> getString(R.string.widget_chat)
        WidgetHost.ALERT -> getString(R.string.widget_alert)
        else -> item?.name?.takeIf { it.isNotBlank() }
            ?: getString(R.string.source_default_name, index - WidgetHost.FIRST_SOURCE + 1)
    }

    /** 설정 화면을 열 때 저장된 브라우저 소스로 줄을 다시 만든다. */
    private fun renderSourceRows(sources: List<OverlayItem>) {
        sourceRows.clear()
        sourcesList.removeAllViews()
        sources.forEach { addSourceRow(it) }
        renderSourceButtons()
    }

    private fun addSourceRow(base: OverlayItem?): SourceRow {
        val view = layoutInflater.inflate(R.layout.row_browser_source, sourcesList, false)
        val row = SourceRow(view, base, stream = base?.stream ?: true)
        row.name.setText(base?.name.orEmpty())
        row.url.setText(base?.url.orEmpty())
        row.streamChip.isActivated = row.stream
        row.streamChip.setOnClickListener {
            row.stream = !row.stream
            row.streamChip.isActivated = row.stream
        }
        view.findViewById<View>(R.id.btn_source_remove).setOnClickListener {
            sourceRows.remove(row)
            sourcesList.removeView(view)
            renderSourceButtons()
        }
        sourceRows.add(row)
        sourcesList.addView(view)
        renderSourceButtons()
        return row
    }

    private fun renderSourceButtons() {
        addSourceButton.alpha = if (sourceRows.size < BrowserSources.MAX) 1f else 0.4f
    }

    private fun renderWidgetDraft() {
        chatStreamChip.isActivated = draftChatStream
        alertStreamChip.isActivated = draftAlertStream
    }

    // ---------------------------------------------------------------------------------------------
    // 치지직 계정 연결, 방송 정보

    private fun setUpChzzk() {
        showSecretButton.setOnClickListener { setSecretVisible(!secretVisible) }
        findViewById<View>(R.id.btn_chzzk_login).setOnClickListener {
            if (clientIdInput.text.isBlank() || clientSecretInput.text.isBlank()) {
                Toast.makeText(this, R.string.chzzk_need_app, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveChzzkApp()
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, LoginActivity::class.java), REQUEST_LOGIN)
        }
        findViewById<View>(R.id.btn_chzzk_logout).setOnClickListener {
            ChzzkAccount.logout(this) {
                Toast.makeText(this, R.string.chzzk_logged_out, Toast.LENGTH_SHORT).show()
                ChzzkChat.restart()
                renderChzzkSettings()
                refreshPrepLiveInfo()
            }
        }
        // 로그인 없이 Client ID/Secret만으로 부르는 API(카테고리 검색)로 앱 정보가 맞는지 따로 확인한다.
        findViewById<View>(R.id.btn_chzzk_check).setOnClickListener {
            if (clientIdInput.text.isBlank() || clientSecretInput.text.isBlank()) {
                Toast.makeText(this, R.string.chzzk_need_app, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveChzzkApp()
            val id = ChzzkPrefs.clientId(this)
            val secret = ChzzkPrefs.clientSecret(this)
            // 같은 ID·Secret으로 요청 모양만 바꿔 보낸다. 결과는 대화상자와 로그(ChzzkDiagnose)에 남는다.
            ChzzkAccount.run(this, { ChzzkDiagnose.run(id, secret) }) { result ->
                val lines = result.getOrElse { listOf(it.message ?: "오류") }
                android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.btn_chzzk_check)
                    .setMessage(lines.joinToString("\n"))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
        findViewById<View>(R.id.btn_live_info_load).setOnClickListener { loadLiveInfo() }
        findViewById<View>(R.id.btn_live_info_save).setOnClickListener { saveLiveInfo() }
        findViewById<View>(R.id.btn_category_search).setOnClickListener { searchCategories() }
        categorySearchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) searchCategories()
            action == EditorInfo.IME_ACTION_SEARCH
        }
    }

    /** Client ID/Secret/리디렉션 주소 저장. 바뀌었으면 이전 로그인은 지워진다 ([ChzzkPrefs.saveApp]). */
    private fun saveChzzkApp() {
        val id = clientIdInput.text.toString().trim()
        val secret = clientSecretInput.text.toString().trim()
        val redirect = redirectInput.text.toString().trim()
        val before = ChzzkPrefs.loggedIn(this)
        ChzzkPrefs.saveApp(this, id, secret, redirect)
        if (before && !ChzzkPrefs.loggedIn(this)) ChzzkChat.restart()
        clientIdInput.setText(ChzzkPrefs.clientId(this))
        clientSecretInput.setText(ChzzkPrefs.clientSecret(this))
        renderChzzkSettings()
    }

    /** Secret 칸을 글자로 보이거나 점으로 가린다. */
    private fun setSecretVisible(visible: Boolean) {
        secretVisible = visible
        val selection = clientSecretInput.selectionEnd
        clientSecretInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            if (visible) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        clientSecretInput.setSelection(selection.coerceIn(0, clientSecretInput.text.length))
        showSecretButton.setText(if (visible) R.string.btn_hide_secret else R.string.btn_show_secret)
    }

    private fun renderChzzkSettings() {
        val saved = ChzzkCredentials.summary(ChzzkPrefs.clientSecret(this))
        secretSaved.text = saved?.let { getString(R.string.secret_saved, it) } ?: getString(R.string.secret_saved_none)
        val loggedIn = ChzzkPrefs.loggedIn(this)
        val name = ChzzkPrefs.channelName(this).ifBlank { getString(R.string.chzzk_unknown_channel) }
        chzzkStatus.text = if (loggedIn) getString(R.string.chzzk_status_on, name) else getString(R.string.chzzk_status_off)
        liveInfoSection.visibility = if (loggedIn) View.VISIBLE else View.GONE
        renderCategory()
    }

    private fun renderCategory() {
        val label = when (val c = draftCategory) {
            null -> getString(R.string.category_unchanged)
            ChzzkApi.NO_CATEGORY -> getString(R.string.category_none)
            else -> c.name
        }
        categoryCurrent.text = getString(R.string.category_current, label)
    }

    private fun loadLiveInfo() {
        ChzzkAccount.run(this, { it.liveSetting() }) { result ->
            result.onSuccess { setting ->
                prepLiveSetting = setting
                prepLiveFailed = false
                titleInput.setText(setting.title)
                tagsInput.setText(ChzzkTags.format(setting.tags))
                draftCategory = setting.category ?: ChzzkApi.NO_CATEGORY
                renderCategory()
                Toast.makeText(this, R.string.toast_live_info_loaded, Toast.LENGTH_SHORT).show()
            }.onFailure { showError(it) }
        }
    }

    private fun searchCategories() {
        val query = categorySearchInput.text.toString().trim()
        if (query.isEmpty()) return
        ChzzkAccount.run(this, { it.searchCategories(query, MAX_CATEGORY_RESULTS) }) { result ->
            result.onSuccess { found ->
                categoryResults.removeAllViews()
                categoryResults.addView(chip(categoryResults, getString(R.string.chip_no_category)) {
                    draftCategory = ChzzkApi.NO_CATEGORY
                    renderCategory()
                })
                found.forEach { category ->
                    categoryResults.addView(chip(categoryResults, category.name) {
                        draftCategory = category
                        renderCategory()
                    })
                }
                if (found.isEmpty()) Toast.makeText(this, R.string.toast_no_categories, Toast.LENGTH_SHORT).show()
            }.onFailure { showError(it) }
        }
    }

    private fun saveLiveInfo() {
        val title = titleInput.text.toString().trim()
        if (title.isEmpty()) {
            titleInput.error = getString(R.string.error_title_empty)
            return
        }
        val category = draftCategory
        val tags = ChzzkTags.parse(tagsInput.text.toString())
        tagsInput.setText(ChzzkTags.format(tags))
        ChzzkAccount.run(this, { it.updateLiveSetting(title, category, tags) }) { result ->
            result.onSuccess {
                Toast.makeText(this, R.string.toast_live_info_saved, Toast.LENGTH_SHORT).show()
                refreshPrepLiveInfo()
            }.onFailure { showError(it) }
        }
    }

    private fun showError(error: Throwable) {
        Toast.makeText(this, error.message ?: error.javaClass.simpleName, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------------------------------------
    // 채팅 창

    // ---------------------------------------------------------------------------------------------
    // 보정 (뷰티·노출·화이트밸런스). 방송 중에도 바로 바뀐다.

    private fun setUpAdjust() {
        adjustButton.setOnClickListener {
            showMore(false)
            val show = adjustPanel.visibility != View.VISIBLE
            if (show && editingWidgets) setEditingWidgets(false)
            adjustPanel.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                chatPanel.visibility = View.GONE
                engine.caps?.let { rebuildWhiteBalanceChips(it) }
                renderAdjust()
            }
            render(LiveState.current)
        }
        // 캠뉴런과 같은 칩과 단계. 값은 core의 BeautyStore에 저장되고 방송 중이면 바로 적용된다.
        for ((labelRes, level) in BEAUTY_LEVELS) {
            smoothRow.addView(chip(smoothRow, getString(labelRes), R.layout.chip_small) {
                BeautyStore.update { it.copy(smooth = level) }
                renderAdjust()
            }.apply { tag = level })
            brightRow.addView(chip(brightRow, getString(labelRes), R.layout.chip_small) {
                BeautyStore.update { it.copy(bright = level) }
                renderAdjust()
            }.apply { tag = level })
        }
        evSeek.setOnSeekBarChangeListener(seekListener { progress ->
            val caps = engine.caps ?: return@seekListener
            val ev = caps.evMin + progress * caps.evStep
            engine.update { it.copy(ev = ev) }
        })
        lockChip.setOnClickListener {
            val lock = !engine.controls.aeLock
            engine.update { it.copy(aeLock = lock, awbLock = lock) }
        }
        findViewById<View>(R.id.btn_adjust_reset).setOnClickListener {
            BeautyStore.update { BeautySettings.OFF }
            engine.update { it.copy(ev = 0f, whiteBalance = WhiteBalance.AUTO, aeLock = false, awbLock = false) }
            renderAdjust()
        }
    }

    /** 손으로 움직일 때만 알린다 (그리기 때문에 바뀐 것은 무시). */
    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    }

    private fun rebuildWhiteBalanceChips(caps: CameraCaps) {
        wbChips.removeAllViews()
        for (wb in caps.whiteBalances) {
            wbChips.addView(chip(wbChips, wb.label) { engine.update { it.copy(whiteBalance = wb) } }.apply { tag = wb })
        }
    }

    private fun renderAdjust() {
        val beauty = BeautyStore.current
        for (i in 0 until smoothRow.childCount) smoothRow.getChildAt(i).let { it.isActivated = it.tag == nearestBeautyLevel(beauty.smooth) }
        for (i in 0 until brightRow.childCount) brightRow.getChildAt(i).let { it.isActivated = it.tag == nearestBeautyLevel(beauty.bright) }
        val controls = engine.controls
        val caps = engine.caps
        if (caps != null && caps.evStep > 0f && caps.evMax > caps.evMin) {
            evSeek.isEnabled = true
            evSeek.max = Math.round((caps.evMax - caps.evMin) / caps.evStep)
            evSeek.progress = Math.round((controls.ev - caps.evMin) / caps.evStep)
        } else {
            evSeek.isEnabled = false
        }
        evLabel.text = getString(R.string.label_ev, controls.ev)
        for (i in 0 until wbChips.childCount) {
            val view = wbChips.getChildAt(i)
            view.isActivated = view.tag == controls.whiteBalance
        }
        lockChip.isActivated = controls.aeLock
    }

    private fun setUpChat() {
        chatButton.setOnClickListener {
            showMore(false)
            val show = chatPanel.visibility != View.VISIBLE
            if (show && editingWidgets) setEditingWidgets(false)
            chatPanel.visibility = if (show) View.VISIBLE else View.GONE
            if (show) adjustPanel.visibility = View.GONE
            if (show) {
                loadChatSettings()
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            } else {
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(chatInput.windowToken, 0)
            }
            render(LiveState.current)
        }
        findViewById<View>(R.id.chat_send).setOnClickListener { sendChat(notice = false) }
        findViewById<View>(R.id.chat_notice).setOnClickListener { sendChat(notice = true) }
        chatInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) sendChat(notice = false)
            action == EditorInfo.IME_ACTION_SEND
        }
        readAloudChip.setOnClickListener {
            ChzzkPrefs.setReadAloud(this, !ChzzkPrefs.readAloud(this))
            renderChatChips()
        }
        slowChip.setOnClickListener {
            val current = chatSettings ?: return@setOnClickListener
            updateChatSettings { it.updateChatSettings(current, slowModeSec = if (current.slowModeSec > 0) 0 else SLOW_MODE_SEC) }
        }
        followerChip.setOnClickListener {
            val current = chatSettings ?: return@setOnClickListener
            updateChatSettings { it.updateChatSettings(current, followerOnly = !current.followerOnly) }
        }
    }

    private fun loadChatSettings() {
        if (!ChzzkPrefs.loggedIn(this)) {
            chatSettings = null
            renderChatChips()
            return
        }
        ChzzkAccount.run(this, { it.chatSettings() }) { result ->
            chatSettings = result.getOrNull()
            result.exceptionOrNull()?.let { showError(it) }
            renderChatChips()
        }
    }

    private fun updateChatSettings(change: (ChzzkApi) -> Unit) {
        ChzzkAccount.run(this, { api ->
            change(api)
            api.chatSettings()
        }) { result ->
            result.onSuccess { chatSettings = it }.onFailure { showError(it) }
            renderChatChips()
        }
    }

    private fun sendChat(notice: Boolean) {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty()) return
        chatInput.setText("")
        ChzzkAccount.run(this, { if (notice) it.sendNotice(text) else it.sendChat(text) }) { result ->
            result.onSuccess { if (notice) Toast.makeText(this, R.string.toast_notice_sent, Toast.LENGTH_SHORT).show() }
                .onFailure {
                    chatInput.setText(text) // 못 보낸 글은 되돌려 둔다
                    showError(it)
                }
        }
    }

    private fun renderChatChips() {
        val settings = chatSettings
        slowChip.isEnabled = settings != null
        followerChip.isEnabled = settings != null
        slowChip.isActivated = (settings?.slowModeSec ?: 0) > 0
        followerChip.isActivated = settings?.followerOnly == true
        readAloudChip.isActivated = ChzzkPrefs.readAloud(this)
    }

    /** 새로 온 줄만 덧붙이고, 100줄이 넘으면 오래된 줄을 뺀다. */
    private fun renderChat() {
        val status = ChzzkChat.status
        chatStatus.text = when (status.state) {
            ChzzkChatSession.State.CONNECTING -> getString(R.string.chat_status_connecting)
            ChzzkChatSession.State.CONNECTED -> status.message?.let { getString(R.string.chat_status_connected_note, it) }
                ?: getString(R.string.chat_status_connected)
            ChzzkChatSession.State.RETRYING -> getString(R.string.chat_status_retrying, status.message ?: "")
            ChzzkChatSession.State.NEEDS_LOGIN -> getString(R.string.chat_status_login)
            ChzzkChatSession.State.STOPPED -> getString(R.string.chat_status_stopped)
        }
        renderChatChips()
        val lines = ChzzkChat.lines
        val total = ChzzkChat.total
        val fresh = if (shownTotal < 0 || total < shownTotal) {
            chatLines.removeAllViews()
            lines.size
        } else {
            (total - shownTotal).coerceAtMost(lines.size.toLong()).toInt()
        }
        val atBottom = !chatScroll.canScrollVertically(1)
        for (event in lines.takeLast(fresh)) chatLines.addView(chatLine(event))
        shownTotal = total
        while (chatLines.childCount > MAX_CHAT_VIEWS) chatLines.removeViewAt(0)
        if (atBottom) chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun chatLine(event: ChzzkEvent): TextView {
        val text = SpannableStringBuilder()
        var highlight = false
        when (event) {
            is ChzzkEvent.Chat -> {
                text.append(event.nickname, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(ForegroundColorSpan(nickColor(event.nickname)), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.append("  ").append(event.content)
            }
            is ChzzkEvent.Donation -> {
                highlight = true
                text.append(getString(R.string.chat_line_donation, event.nickname, event.amount, event.text))
            }
            is ChzzkEvent.Subscription -> {
                highlight = true
                text.append(getString(R.string.chat_line_subscription, event.nickname, event.month))
            }
            is ChzzkEvent.System -> text.append(event.type)
        }
        return TextView(this).apply {
            this.text = text
            setTextColor(if (highlight) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(6, 3, 6, 3)
            if (highlight) setBackgroundColor(0xE6FFD54F.toInt())
        }
    }

    private fun nickColor(name: String): Int = NICK_COLORS[(name.hashCode() and 0x7FFFFFFF) % NICK_COLORS.size]

    // ---------------------------------------------------------------------------------------------
    // 방송 설정 (서버 주소, 스트림 키, 화질)

    private fun setUpQualityControls() {
        for (height in LiveQuality.HEIGHTS) {
            for (fps in LiveQuality.FPS_VALUES) {
                val view = chip(presetRow, getString(R.string.quality_preset, height, fps)) {
                    draftQuality = draftQuality.withPreset(height, fps)
                    renderQuality()
                }
                view.tag = height to fps
                presetRow.addView(view)
            }
        }
        bitrateSeek.max = (LiveQuality.MAX_KBPS - LiveQuality.MIN_KBPS) / LiveQuality.STEP_KBPS
        bitrateSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                draftQuality = draftQuality.copy(bitrateKbps = LiveQuality.MIN_KBPS + progress * LiveQuality.STEP_KBPS)
                renderQuality()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        keyFrame1.setOnClickListener {
            draftQuality = draftQuality.copy(keyFrameS = 1)
            renderQuality()
        }
        keyFrame2.setOnClickListener {
            draftQuality = draftQuality.copy(keyFrameS = 2)
            renderQuality()
        }
    }

    private fun renderQuality() {
        val q = draftQuality
        for (i in 0 until presetRow.childCount) {
            val view = presetRow.getChildAt(i)
            view.isActivated = view.tag == (q.height to q.fps)
        }
        bitrateSeek.progress = (q.bitrateKbps - LiveQuality.MIN_KBPS) / LiveQuality.STEP_KBPS
        bitrateLabel.text = getString(R.string.label_bitrate, q.bitrateKbps / 1000f)
        keyFrame1.isActivated = q.keyFrameS == 1
        keyFrame2.isActivated = q.keyFrameS == 2
    }

    /** 설정 창을 열면 그 뒤의 버튼·줌·위젯을 숨긴다 (가로 화면에서 겹쳐 보이지 않게). */
    private fun showSettings(show: Boolean) {
        val behind = if (show) View.INVISIBLE else View.VISIBLE
        controlsView.visibility = behind
        zoomBar.visibility = behind
        widgetLayer.visibility = behind
        if (show) {
            showMore(false)
            chatPanel.visibility = View.GONE
            adjustPanel.visibility = View.GONE
        }
        if (show) {
            serverInput.setText(LivePrefs.serverUrl(this))
            keyInput.setText(LivePrefs.streamKey(this))
            draftQuality = LivePrefs.quality(this)
            renderQuality()
            val overlays = OverlayStore.current
            clientIdInput.setText(ChzzkPrefs.clientId(this))
            clientSecretInput.setText(ChzzkPrefs.clientSecret(this))
            redirectInput.setText(ChzzkPrefs.redirectUri(this))
            setSecretVisible(false)
            renderChzzkSettings()
            chatUrlInput.setText(overlays.chat.url)
            alertUrlInput.setText(overlays.alert.url)
            renderSourceRows(overlays.sources)
            // 주소가 아직 없으면 "방송 화면에 넣기"를 켠 채로 시작한다.
            draftChatStream = overlays.chat.stream || !overlays.chat.active
            draftAlertStream = overlays.alert.stream || !overlays.alert.active
            renderWidgetDraft()
            setEditingWidgets(false)
            settingsPanel.visibility = View.VISIBLE
        } else {
            settingsPanel.visibility = View.GONE
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(settingsPanel.windowToken, 0)
        }
        render(LiveState.current)
    }

    /** 방송 설정을 열고 [target] (그 부분의 이름 줄)이 위에 오게 내린다. 준비 카드의 줄을 눌렀을 때. */
    private fun openSettingsAt(target: View) {
        showSettings(true)
        val scroll = settingsPanel as ScrollView
        // 막 보이게 한 창은 아직 자리가 없으니, 자리가 잡힌 뒤(그리기 직전)에 내린다.
        scroll.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                scroll.viewTreeObserver.removeOnPreDrawListener(this)
                var y = 0
                var view: View = target
                while (view !== scroll) {
                    y += view.top
                    view = view.parent as? View ?: return true
                }
                scroll.scrollTo(0, (y - dp(8)).coerceAtLeast(0))
                return true
            }
        })
    }

    /** 저장하고 닫는다. 잘못된 값이 있으면 그 칸에 표시하고 false. */
    private fun saveSettings(): Boolean {
        val server = serverInput.text.toString().trim()
        val key = keyInput.text.toString().trim()
        val chatUrl = chatUrlInput.text.toString().trim()
        val alertUrl = alertUrlInput.text.toString().trim()
        // 이름도 주소도 없는 줄은 버린다. 이름만 있으면 주소를 넣으라고 알린다.
        val rows = sourceRows.filter { it.name.text.isNotBlank() || it.url.text.isNotBlank() }
        val badSource = rows.firstOrNull { row ->
            val url = row.url.text.toString().trim()
            url.isEmpty() || !WidgetUrls.valid(url)
        }
        when {
            RtmpUrl.parse(server) == null -> serverInput.error = getString(R.string.error_bad_server)
            key.isEmpty() -> keyInput.error = getString(R.string.error_no_key)
            !WidgetUrls.valid(chatUrl) -> chatUrlInput.error = getString(R.string.error_bad_widget_url)
            !WidgetUrls.valid(alertUrl) -> alertUrlInput.error = getString(R.string.error_bad_widget_url)
            badSource != null -> badSource.url.apply {
                error = getString(if (text.isBlank()) R.string.error_source_no_url else R.string.error_bad_widget_url)
                requestFocus()
            }
            else -> {
                // 새로 넣은 위젯은 캠 방송에 알맞은 자리에 저절로 놓는다 (사람이 옮겨 둔 것은 그대로).
                val newSlots = rows.indices.filter { rows[it].base == null }.map { WidgetHost.FIRST_SOURCE + it }.toSet()
                val sources = rows.map { row ->
                    (row.base ?: OverlaySettings.DEFAULT_SOURCE).copy(
                        name = row.name.text.toString().trim(),
                        url = row.url.text.toString().trim(),
                        stream = row.stream,
                        visible = true,
                    )
                }
                LivePrefs.save(this, server, key)
                LivePrefs.saveQuality(this, draftQuality)
                saveChzzkApp()
                OverlayStore.update { old ->
                    val next = old.copy(
                        chat = old.chat.copy(url = chatUrl, stream = draftChatStream, visible = true),
                        alert = old.alert.copy(url = alertUrl, stream = draftAlertStream, visible = true),
                        sources = sources,
                    )
                    WidgetLayout.placeNew(old, next, newSlots)
                }
                showSettings(false)
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------------------------------------
    // 상태

    private fun render(s: LiveState.Status) {
        val idle = s.phase == LiveState.Phase.IDLE
        arrangeButtons(live = !idle)
        liveButton.isActivated = !idle
        liveButton.setText(if (idle) R.string.btn_start else R.string.btn_stop_short)
        settingsButton.isEnabled = idle
        settingsButton.alpha = if (idle) 1f else 0.4f

        val lines = mutableListOf<String>()
        lines += when (s.phase) {
            LiveState.Phase.IDLE -> s.message?.let { getString(R.string.status_failed, it) } ?: getString(R.string.status_idle)
            LiveState.Phase.CONNECTING -> getString(R.string.status_connecting)
            LiveState.Phase.LIVE -> getString(R.string.status_live, elapsed(s.startedAtMs), s.sendKbps / 1000f)
            LiveState.Phase.RECONNECTING -> getString(R.string.status_reconnecting, s.message ?: "")
        }
        if (!idle) {
            s.format?.let { lines += getString(R.string.status_format, "${it.height}p${it.fps}", s.targetKbps / 1000f) }
            val network = NetworkLabel.current(this)
            lines += if (s.droppedFrames > 0) {
                getString(R.string.status_network_drops, network, s.droppedFrames)
            } else {
                getString(R.string.status_network, network)
            }
            if (s.heatLevel > 0) lines += getString(R.string.status_heat)
            if (s.netLevel > 0) lines += getString(R.string.status_net)
            s.micError?.let { lines += getString(R.string.status_no_mic, it) }
            if (s.coverUnavailable) lines += getString(R.string.status_cover_unavailable)
        }
        if (LiveControls.micMuted) lines += getString(R.string.status_mic_muted)
        if (LiveControls.covered) lines += getString(R.string.status_covered)
        val device = DeviceStatus.read(this)
        if (device.batteryPercent >= 0) lines += getString(R.string.status_battery, device.batteryPercent, device.temperatureC)
        status.text = lines.joinToString("\n")
        // 방송 전에는 준비 카드가 같은 자리에서 같은 것(실패 이유, 네트워크, 배터리)을 보인다.
        status.visibility = if (idle) View.GONE else View.VISIBLE

        val muted = LiveControls.micMuted
        micButton.isActivated = muted
        micButton.setText(if (muted) R.string.rail_mic_muted else R.string.rail_mic)
        setRailIcon(micButton, if (muted) R.drawable.ic_rail_mic_off else R.drawable.ic_rail_mic)
        coverButton.isActivated = LiveControls.covered
        coverButton.setText(if (LiveControls.covered) R.string.rail_covered else R.string.rail_cover)
        coverBanner.visibility = if (LiveControls.covered) View.VISIBLE else View.GONE
        chatButton.isActivated = chatPanel.visibility == View.VISIBLE
        adjustButton.isActivated = adjustPanel.visibility == View.VISIBLE
        renderPrepCard(s)
    }

    /** 버튼 줄 아이콘을 바꾼다 (같으면 그대로: 1초마다 불린다). 색은 버튼의 drawableTint를 따른다. */
    private fun setRailIcon(button: TextView, icon: Int) {
        if (button.getTag(R.id.btn_mic) == icon) return
        button.setTag(R.id.btn_mic, icon)
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, icon, 0, 0)
    }

    // ---------------------------------------------------------------------------------------------
    // 오른쪽 버튼 줄과 "더보기"

    /**
     * 방송 전에는 준비에 쓰는 버튼(설정·전환·위젯·보정)을, 방송 중에는 자주 누르는 버튼(마이크·가리기·채팅)을 줄에 두고
     * 나머지는 "더보기" 안에 넣는다. 같은 버튼 뷰를 옮기므로 누를 때 하는 일과 켜짐 표시는 한 곳에서 관리된다.
     * 방송 설정은 방송 중에 바꿀 수 없어 방송 중에는 어디에도 두지 않는다.
     */
    private fun arrangeButtons(live: Boolean) {
        if (buttonsLive == live) return
        buttonsLive = live
        val inRail = if (live) {
            listOf(micButton, coverButton, chatButton)
        } else {
            listOf(settingsButton, flipButton, widgetsButton, adjustButton)
        }
        val inMore = if (live) {
            listOf(flipButton, widgetsButton, adjustButton, torchButton, stabilizationButton)
        } else {
            listOf(chatButton, micButton, coverButton, torchButton, stabilizationButton)
        }
        val all = listOf(
            settingsButton, flipButton, widgetsButton, adjustButton, micButton, coverButton, chatButton,
            torchButton, stabilizationButton, moreButton, liveButton,
        )
        all.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        inRail.forEach { rail.addView(it) }
        rail.addView(moreButton)
        rail.addView(liveButton)
        inMore.forEach { morePanel.addView(it) }
        showMore(false)
    }

    private fun showMore(show: Boolean) {
        morePanel.visibility = if (show) View.VISIBLE else View.GONE
        moreButton.isActivated = show
    }

    // ---------------------------------------------------------------------------------------------
    // 방송 준비 카드

    private fun setUpPrepCard() {
        prepTitle.label.setText(R.string.prep_label_title)
        prepCategory.label.setText(R.string.prep_label_category)
        prepQuality.label.setText(R.string.prep_label_quality)
        prepWidgets.label.setText(R.string.prep_label_widgets)
        prepCheck.label.setText(R.string.prep_label_check)
        findViewById<View>(R.id.prep_header).setOnClickListener {
            prepCollapsed = !prepCollapsed
            render(LiveState.current)
        }
        val openLiveInfo = View.OnClickListener {
            if (ChzzkPrefs.loggedIn(this)) {
                openSettingsAt(liveInfoSection)
                loadLiveInfo()
            } else {
                openSettingsAt(findViewById(R.id.section_chzzk))
            }
        }
        prepTitle.view.setOnClickListener(openLiveInfo)
        prepCategory.view.setOnClickListener(openLiveInfo)
        prepQuality.view.setOnClickListener { openSettingsAt(findViewById(R.id.section_quality)) }
        prepWidgets.view.setOnClickListener { openSettingsAt(findViewById(R.id.section_widgets)) }
        prepCheck.view.setOnClickListener {
            if (!streamTargetSet()) openSettingsAt(findViewById(R.id.section_server))
        }
    }

    private fun streamTargetSet(): Boolean =
        RtmpUrl.parse(LivePrefs.serverUrl(this)) != null && LivePrefs.streamKey(this).isNotEmpty()

    /** 치지직에 로그인했으면 방송 제목·카테고리를 불러와 카드에 보인다. 로그인하지 않았으면 지운다. */
    private fun refreshPrepLiveInfo() {
        if (!ChzzkPrefs.loggedIn(this)) {
            prepLiveSetting = null
            prepLiveFailed = false
            render(LiveState.current)
            return
        }
        if (prepLiveLoading) return
        prepLiveLoading = true
        ChzzkAccount.run(this, { it.liveSetting() }) { result ->
            prepLiveLoading = false
            result.onSuccess {
                prepLiveSetting = it
                prepLiveFailed = false
            }.onFailure { prepLiveFailed = true }
            render(LiveState.current)
        }
    }

    /**
     * 방송 전에 한눈에 볼 것: 치지직 제목·카테고리, 화질, 켜진 위젯 수, 점검(네트워크·배터리·온도·스트림 키).
     * 문제가 있으면 머리에 "확인 필요"를 붙이고 점검 줄에 노랗게 적는다. 1초마다 [render]에서 불린다.
     */
    private fun renderPrepCard(s: LiveState.Status) {
        val show = s.phase == LiveState.Phase.IDLE && settingsPanel.visibility != View.VISIBLE && !editingWidgets &&
            chatPanel.visibility != View.VISIBLE && adjustPanel.visibility != View.VISIBLE
        prepCard.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        val network = NetworkLabel.current(this)
        val device = DeviceStatus.read(this)
        val targetSet = streamTargetSet()
        val problems = mutableListOf<String>()
        if (!targetSet) problems += getString(R.string.prep_warn_no_key)
        s.message?.let { problems += getString(R.string.status_failed, it) }
        if (network == getString(R.string.net_none)) problems += network
        if (device.batteryPercent in 0 until LOW_BATTERY_PERCENT && !device.charging) {
            problems += getString(R.string.prep_warn_battery, device.batteryPercent)
        }
        if (device.temperatureC >= HOT_C || device.thermalStatus >= THERMAL_WARN) {
            problems += getString(R.string.prep_warn_hot, device.temperatureC)
        }
        if (LiveControls.micMuted) problems += getString(R.string.status_mic_muted)
        if (LiveControls.covered) problems += getString(R.string.status_covered)

        prepHeaderTitle.setText(if (problems.isEmpty()) R.string.prep_title else R.string.prep_title_warn)
        prepHeaderTitle.setTextColor(if (problems.isEmpty()) COLOR_TEXT else COLOR_WARN)
        prepHeaderToggle.setText(if (prepCollapsed) R.string.prep_expand else R.string.prep_collapse)
        prepRows.visibility = if (prepCollapsed) View.GONE else View.VISIBLE
        if (prepCollapsed) return

        if (ChzzkPrefs.loggedIn(this)) {
            val setting = prepLiveSetting
            prepTitle.label.setText(R.string.prep_label_title)
            prepTitle.value.text = when {
                setting != null -> setting.title.ifBlank { getString(R.string.prep_no_title) }
                prepLiveFailed -> getString(R.string.prep_load_failed)
                else -> getString(R.string.prep_loading)
            }
            prepCategory.view.visibility = View.VISIBLE
            prepCategory.value.text = when {
                setting != null -> setting.category?.name ?: getString(R.string.category_none)
                prepLiveFailed -> "-"
                else -> getString(R.string.prep_loading)
            }
        } else {
            prepTitle.label.setText(R.string.prep_label_chzzk)
            prepTitle.value.setText(R.string.prep_chzzk_off)
            prepCategory.view.visibility = View.GONE
        }

        val quality = LivePrefs.quality(this)
        prepQuality.value.text = getString(R.string.prep_quality, quality.label, quality.bitrateKbps / 1000f)

        val widgets = BrowserSources.slots(OverlayStore.current).count { it?.active == true }
        prepWidgets.value.text = if (widgets == 0) {
            getString(R.string.prep_widgets_none)
        } else {
            getString(R.string.prep_widgets_count, widgets)
        }

        prepCheck.value.text = when {
            problems.isNotEmpty() -> problems.joinToString(" · ")
            device.batteryPercent >= 0 -> getString(R.string.prep_check_ok, network, device.batteryPercent, device.temperatureC)
            else -> network
        }
        prepCheck.value.setTextColor(if (problems.isEmpty()) COLOR_OK else COLOR_WARN)
        // 스트림 키가 없을 때만 누르면 할 일이 있다 (방송 설정의 서버 주소·스트림 키).
        prepCheck.arrow.visibility = if (targetSet) View.INVISIBLE else View.VISIBLE
    }

    private fun elapsed(startedAtMs: Long): String {
        val seconds = ((SystemClock.elapsedRealtime() - startedAtMs) / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    private fun hasCameraPermission() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    /** 미리보기를 화면비를 지키며 가운데에 맞춘다 (캠뉴런 MainActivity와 같은 변환). */
    private fun applyPreviewTransform() {
        val size = engine.previewSize ?: return
        val viewWidth = preview.width.toFloat()
        val viewHeight = preview.height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f) return

        @Suppress("DEPRECATION")
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: 0 else windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val centerX = viewWidth / 2
        val centerY = viewHeight / 2
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val viewRect = RectF(0f, 0f, viewWidth, viewHeight)
            val bufferRect = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = min(viewHeight / size.height, viewWidth / size.width)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }
        // 위 변환은 영상을 가운데에 둔다. [displayedImageRect] 자리(왼쪽에 붙임)로 옮긴다.
        displayedImageRect()?.let { image ->
            matrix.postTranslate(image.left - (viewWidth - image.width()) / 2, image.top - (viewHeight - image.height()) / 2)
        }
        preview.setTransform(matrix)
        renderWidgets()
        layoutAroundVideo()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val FOCUS_RING_MS = 1_200L
        private const val REQUEST_LOGIN = 2

        /** 비어 있는 위젯 칸 (안 보인다). */
        private val EMPTY_WIDGET = OverlayItem(x = 0f, y = 0f, w = 0f, h = 0f)

        /** 캠뉴런과 같은 뷰티 보정 단계 (0~100). */
        val BEAUTY_LEVELS = listOf(
            R.string.beauty_off to 0,
            R.string.beauty_weak to 35,
            R.string.beauty_medium to 65,
            R.string.beauty_strong to 100,
        )

        fun nearestBeautyLevel(value: Int): Int = BEAUTY_LEVELS.minBy { kotlin.math.abs(it.second - value) }.second
        private const val EXIT_WAIT_MS = 6_000L
        private const val EXIT_SETTLE_MS = 600L
        private const val SLOW_MODE_SEC = 5
        private const val MAX_CATEGORY_RESULTS = 10
        private const val MAX_CHAT_VIEWS = 100

        // 방송 준비 카드의 점검 기준. 배터리 온도는 40°C를 넘으면 방송 중 발열로 화질이 내려가기 쉽다.
        private const val LOW_BATTERY_PERCENT = 20
        private const val HOT_C = 40f
        private const val THERMAL_WARN = 2 // PowerManager.THERMAL_STATUS_MODERATE
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_OK = 0xFFA5D6A7.toInt()
        private const val COLOR_WARN = 0xFFFFD54F.toInt()
        private val NICK_COLORS = intArrayOf(0xFF7CFC00.toInt(), 0xFFFFD54F.toInt(), 0xFF80D8FF.toInt(), 0xFFFF8A80.toInt(), 0xFFB388FF.toInt(), 0xFFA7FFEB.toInt())
    }
}
