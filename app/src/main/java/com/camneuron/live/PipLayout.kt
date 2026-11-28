package com.camneuron.live

/**
 * 작은 창(PiP)에 방송 화면을 놓는 계산.
 *
 * 방송 화면은 폰을 가로로 든 방향에 맞춰 그린다. 방송 중에 홈 화면으로 나가면 홈 화면이 세로라서 폰의 화면 방향도
 * 세로로 돈다. 그때 방송 화면의 틀을 그만큼 거꾸로 돌려, 폰을 든 채로 보면 방송 화면이 그대로 가로로 보이게 한다.
 * 화면 방향은 Surface.ROTATION_0~270 (0~3)이고, 1 늘 때마다 그림이 시계 방향으로 90도 돈다.
 */
object PipLayout {
    /**
     * [frameRotation] 방향에 맞춰 그린 틀을, 지금 화면 방향이 [displayRotation]일 때 시계 방향으로 90도씩 몇 번 돌려야 하는지.
     */
    fun quarterTurns(frameRotation: Int, displayRotation: Int): Int = ((frameRotation - displayRotation) % 4 + 4) % 4

    /** 틀을 [quarterTurns]번 돌렸을 때 가로와 세로가 바뀌는지. */
    fun swapsSides(quarterTurns: Int): Boolean = quarterTurns % 2 != 0

    /**
     * 작은 창의 화면비 (가로, 세로). 방송 화면은 16:9이고, 틀을 90도 돌려 놓을 때는 창이 화면 좌표로는 9:16이어야
     * 방송 화면이 창을 가득 채운다 (폰을 가로로 든 채로 보면 16:9로 보인다).
     */
    fun aspect(quarterTurns: Int): Pair<Int, Int> = if (swapsSides(quarterTurns)) 9 to 16 else 16 to 9
}
