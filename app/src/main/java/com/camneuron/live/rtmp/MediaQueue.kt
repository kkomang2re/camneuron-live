package com.camneuron.live.rtmp

/**
 * 인코더와 송신 스레드 사이의 대기열. 바이트 수([capacityBytes])와 기다린 시간([maxDelayMs])으로 제한한다
 * (모바일 데이터가 느려지면 쌓인다).
 *
 * 가득 차거나 맨 앞 항목이 [maxDelayMs]보다 오래 기다렸으면 쌓인 영상 프레임을 모두 버리고 다음 키프레임부터 다시 보낸다
 * (중간 프레임만 버리면 화면이 깨진다). 그때 [onNeedKeyFrame]을 불러 인코더에 키프레임을 바로 요청하게 한다.
 * 시퀀스 헤더·메타데이터는 버리지 않고, 소리는 영상을 버리고도 자리가 없을 때만 버린다 (소리가 끊기는 게 더 거슬린다).
 *
 * 시간 제한이 없으면 비트레이트를 낮췄을 때 같은 바이트가 더 긴 시간이 된다 (35%로 낮추면 2초 분량이 6초 가까이).
 * 그만큼 방송이 늦어지고, 늦어진 채로 되돌아오지 않는다.
 */
class MediaQueue(
    private val capacityBytes: Int,
    private val maxDelayMs: Long = Long.MAX_VALUE,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onNeedKeyFrame: () -> Unit = {},
) {
    enum class Kind { ESSENTIAL, KEY_FRAME, FRAME, AUDIO }

    class Item(val kind: Kind, val chunkStreamId: Int, val type: Int, val timestampMs: Long, val payload: ByteArray) {
        /** 대기열에 들어간 시각 ([clockMs]). */
        internal var queuedAtMs = 0L
    }

    private val lock = Object()
    private val items = ArrayDeque<Item>()
    private var bytes = 0
    private var waitingForKeyFrame = false
    private var closed = false

    // 통계 (마지막 [takeStats] 뒤로)
    private var dropEvents = 0
    private var peakBytes = 0
    private var peakDelayMs = 0L
    private var droppedAudio = 0

    /** 지금까지 버린 영상 프레임 수. */
    var droppedFrames = 0
        private set

    /** 넣었으면 true, 버렸으면 false. */
    fun offer(item: Item): Boolean {
        var needKeyFrame = false
        val queued = synchronized(lock) {
            if (closed) return false
            if (item.kind == Kind.FRAME && waitingForKeyFrame) {
                droppedFrames++
                return false
            }
            val size = item.payload.size
            val now = clockMs()
            val delay = delayMs(now)
            peakDelayMs = maxOf(peakDelayMs, delay)
            var accept = true
            if (item.kind != Kind.ESSENTIAL && (bytes + size > capacityBytes || delay > maxDelayMs)) {
                val dropped = dropQueuedVideo()
                droppedFrames += dropped
                when (item.kind) {
                    Kind.FRAME -> {
                        droppedFrames++
                        accept = false
                        needKeyFrame = true
                    }
                    // 키프레임은 자리가 모자라도 넣는다 (넣지 않으면 영영 회복하지 못한다).
                    Kind.KEY_FRAME -> Unit
                    else -> {
                        if (dropped > 0) needKeyFrame = true
                        if (bytes + size > capacityBytes) {
                            droppedAudio++
                            accept = false
                        }
                    }
                }
                if (dropped > 0 || item.kind == Kind.FRAME) dropEvents++
            }
            if (needKeyFrame) waitingForKeyFrame = true
            if (accept) {
                if (item.kind == Kind.KEY_FRAME) waitingForKeyFrame = false
                item.queuedAtMs = now
                items.addLast(item)
                bytes += size
                peakBytes = maxOf(peakBytes, bytes)
                lock.notifyAll()
            }
            accept
        }
        if (needKeyFrame) onNeedKeyFrame()
        return queued
    }

    /** 다음 항목. [timeoutMs] 동안 없거나 닫혔으면 null. */
    fun take(timeoutMs: Long): Item? = synchronized(lock) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (items.isEmpty() && !closed) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            lock.wait(left)
        }
        if (closed) return null
        val item = items.removeFirst()
        bytes -= item.payload.size
        peakDelayMs = maxOf(peakDelayMs, clockMs() - item.queuedAtMs)
        item
    }

    fun queuedBytes(): Int = synchronized(lock) { bytes }

    /**
     * 지난번 이후 버린 횟수와 가장 찼을 때의 비율 (NetGovernor에 넘긴다). 비율은 바이트와 기다린 시간 중 더 찬 쪽이다
     * (1이면 버리기 직전).
     */
    fun takeStats(): Stats = synchronized(lock) {
        val delay = delayMs(clockMs())
        val peakDelay = maxOf(peakDelayMs, delay)
        val byBytes = peakBytes.toFloat() / capacityBytes
        val byDelay = if (maxDelayMs == Long.MAX_VALUE) 0f else peakDelay.toFloat() / maxDelayMs
        val stats = Stats(dropEvents, maxOf(byBytes, byDelay), droppedAudio, peakDelay)
        dropEvents = 0
        droppedAudio = 0
        peakBytes = bytes
        peakDelayMs = 0L // 맨 앞 항목이 계속 기다리면 다음 번에 그 시간이 다시 잡힌다
        stats
    }

    /** [delayMs]는 그동안 맨 앞 항목이 가장 오래 기다린 시간 (폰 안에서 방송이 밀린 정도). */
    data class Stats(val drops: Int, val fill: Float, val droppedAudio: Int, val delayMs: Long = 0L)

    /** 맨 앞 항목이 기다린 시간. 비어 있으면 0. lock 안에서. */
    private fun delayMs(now: Long): Long = items.firstOrNull()?.let { (now - it.queuedAtMs).coerceAtLeast(0) } ?: 0L

    fun close() = synchronized(lock) {
        closed = true
        items.clear()
        bytes = 0
        lock.notifyAll()
    }

    private fun dropQueuedVideo(): Int {
        var dropped = 0
        val iterator = items.iterator()
        while (iterator.hasNext()) {
            val queued = iterator.next()
            if (queued.kind == Kind.FRAME || queued.kind == Kind.KEY_FRAME) {
                iterator.remove()
                bytes -= queued.payload.size
                dropped++
            }
        }
        return dropped
    }
}
