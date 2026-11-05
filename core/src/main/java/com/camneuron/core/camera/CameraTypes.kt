package com.camneuron.core.camera

import android.hardware.camera2.CameraMetadata

/** 이름(wire)은 protocol/PROTOCOL.md의 JSON 값과 같다. */
enum class FocusMode(val wire: String) {
    CONTINUOUS("continuous"),
    AUTO("auto"),
    MANUAL("manual");

    companion object {
        fun fromWire(value: String): FocusMode? = entries.firstOrNull { it.wire == value }
    }
}

enum class WhiteBalance(val wire: String, val awbMode: Int, val label: String) {
    AUTO("auto", CameraMetadata.CONTROL_AWB_MODE_AUTO, "자동"),
    INCANDESCENT("incandescent", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, "백열등"),
    FLUORESCENT("fluorescent", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, "형광등"),
    DAYLIGHT("daylight", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, "맑음"),
    CLOUDY("cloudy", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, "흐림");

    companion object {
        fun fromWire(value: String): WhiteBalance? = entries.firstOrNull { it.wire == value }
    }
}

/** 0~1 정규화 좌표. 송출되는 영상 기준으로 왼쪽 위가 (0, 0). */
data class NormPoint(val x: Float, val y: Float)

/** 사용자가 조절하는 카메라 값. 폰 화면과 OBS 어느 쪽에서 바꿔도 이 값 하나로 모인다. */
data class CameraControls(
    val front: Boolean = false,
    val zoom: Float = 1f,
    val focusMode: FocusMode = FocusMode.CONTINUOUS,
    val focusDistance: Float = 0f,
    val focusPoint: NormPoint? = null,
    val ev: Float = 0f,
    val aeLock: Boolean = false,
    val awbLock: Boolean = false,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val torch: Boolean = false,
    val stabilization: Boolean = false,
) {
    /** 카메라가 지원하지 않는 값은 지원 범위로 맞춘다 (예: 전면 카메라로 바꾸면 손전등 끔). */
    fun clampedTo(caps: CameraCaps): CameraControls = copy(
        zoom = zoom.coerceIn(caps.zoomMin, caps.zoomMax),
        focusMode = if (focusMode == FocusMode.MANUAL && !caps.manualFocus) FocusMode.CONTINUOUS else focusMode,
        ev = ev.coerceIn(caps.evMin, caps.evMax),
        whiteBalance = if (whiteBalance in caps.whiteBalances) whiteBalance else WhiteBalance.AUTO,
        torch = torch && caps.torch,
        stabilization = stabilization && caps.stabilization,
    )
}

/** 지금 열린 카메라가 지원하는 범위. */
data class CameraCaps(
    val front: Boolean,
    val hasFront: Boolean,
    val hasBack: Boolean,
    val zoomMin: Float,
    val zoomMax: Float,
    val evMin: Float,
    val evMax: Float,
    val evStep: Float,
    val manualFocus: Boolean,
    val torch: Boolean,
    val stabilization: Boolean,
    val whiteBalances: List<WhiteBalance>,
)

data class StreamFormat(val width: Int, val height: Int, val fps: Int)
