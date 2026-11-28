package com.camneuron.live.rtmp

/**
 * 인코더와 송신 스레드 사이의 대기열. 바이트 수([capacityBytes])와 기다린 시간([maxDelayMs])으로 제한한다
 * (모바일 데이터가 느려지면 쌓인다).
 *
 * 가득 차거나 가장 오래된 것이 [maxDelayMs]보다 오래 기다렸으면 **쌓인 영상과 소리를 함께** 모두 버리고 다음 키프레임부터
 * 다시 보낸다 ("건너뛰기". 중간 프레임만 버리면 화면이 깨진다). 그때 [onNeedKeyFrame]을 불러 인코더에 키프레임을 바로
 * 요청하게 한다. 키프레임이 올 때까지는 소리도 받지 않는다. 시퀀스 헤더·메타데이터는 버리지 않는다.
 * 건너뛴 만큼 그 뒤의 시각을 당겨 ([Item.sendTimestampMs]) 서버에는 끊긴 데 없이 이어진 것으로 보이게 한다
 * (시각이 건너뛰면 멈추는 플레이어가 있다). 영상과 소리를 똑같이 당기므로 둘은 어긋나지 않는다.
 *
 * 영상과 소리를 함께 버리는 까닭: 서버는 영상과 소리를 한 조각으로 묶어 내보내고, 조각은 영상이 있어야 끝난다.
 * 영상만 버리고 소리는 계속 보내면 그동안 시청자에게는 아무것도 나가지 않다가, 영상이 다시 갈 때 그동안의 소리까지
 * 한꺼번에 나간다. 따라잡지 않는 플레이어(치지직 모바일)는 그만큼 뒤처진 채로 남는다: 영상을 쉬고 소리만 보낸 시간이
 * 고스란히 시청자 지연이 된다 (0.0.11~0.0.13은 느린 망에서 영상을 20초까지 쉬었고, 지연이 28초까지 늘었다).
 * 함께 버리고 시각을 이어 붙이면 망이 나쁜 동안 방송이 멈췄다가, 좋아지면 지금 장면에서 바로 이어진다.
 *
 * 시간 제한이 없으면 비트레이트를 낮췄을 때 같은 바이트가 더 긴 시간이 된다 (35%로 낮추면 2초 분량이 6초 가까이).
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

        /** 서버에 보낼 시각: [timestampMs]에서 지금까지 건너뛴 만큼을 뺀 값. 대기열에 들어갈 때 정해진다. */
        var sendTimestampMs = timestampMs
            internal set
    }

    private val lock = Object()
    private val items = ArrayDeque<Item>()
    private var bytes = 0
    private var closed = false

    /** 건너뛴 뒤 키프레임을 기다리는 중. 그동안에는 영상도 소리도 받지 않는다. */
    private var waitingForKeyFrame = false

    /** 지금까지 건너뛴 시간. 들어오는 항목의 시각에서 뺀다. */
    private var shiftMs = 0L

    /** 송신 스레드가 꺼내 간 것의 가장 늦은 [Item.sendTimestampMs]. 아직 없으면 -1. */
    private var lastTakenMs = -1L

    /** 메시지 종류(영상, 소리)마다: 꺼내 간 것과 대기열에 넣은 것의 가장 늦은 시각. 종류마다 시각이 거꾸로 가지 않게 한다. */
    private val takenByType = HashMap<Int, Long>()
    private val queuedByType = HashMap<Int, Long>()

    // 통계 (마지막 [takeStats] 뒤로)
    private var dropEvents = 0
    private var peakBytes = 0
    private var peakDelayMs = 0L
    private var droppedAudio = 0
    private var skippedMs = 0L

    /** 지금까지 버린 영상 프레임 수. */
    var droppedFrames = 0
        private set

    /** 넣었으면 true, 버렸으면 false. */
    fun offer(item: Item): Boolean {
        var requestKeyFrame = false
        val queued = synchronized(lock) {
            if (closed) return false
            val size = item.payload.size
            val now = clockMs()
            peakDelayMs = maxOf(peakDelayMs, delayMs(now))
            var accept = true
            if (item.kind != Kind.ESSENTIAL) {
                if (!waitingForKeyFrame && (bytes + size > capacityBytes || mediaDelayMs(now) > maxDelayMs)) {
                    // 업로드가 못 따라간다: 쌓인 것을 모두 버리고 키프레임부터 다시.
                    dropQueued()
                    waitingForKeyFrame = true
                    dropEvents++
                    requestKeyFrame = item.kind != Kind.KEY_FRAME
                }
                if (waitingForKeyFrame) {
                    if (item.kind == Kind.KEY_FRAME) {
                        resumeWith(item)
                    } else {
                        if (item.kind == Kind.AUDIO) droppedAudio++ else droppedFrames++
                        accept = false
                    }
                }
            }
            if (accept) {
                item.queuedAtMs = now
                // 건너뛴 만큼 당기되, 같은 종류에서 앞서 넣은 것보다 이르지는 않게 한다.
                var stamp = item.timestampMs - shiftMs
                // 키프레임을 기다리는 동안 온 시퀀스 헤더는 방금 보낸 것 바로 뒤에 둔다 (건너뛸 자리 너머로 가지 않게).
                if (waitingForKeyFrame && lastTakenMs >= 0) stamp = minOf(stamp, lastTakenMs + RESUME_GAP_MS)
                stamp = maxOf(stamp, queuedByType[item.type] ?: 0L, 0L)
                item.sendTimestampMs = stamp
                queuedByType[item.type] = stamp
                items.addLast(item)
                bytes += size
                peakBytes = maxOf(peakBytes, bytes)
                lock.notifyAll()
            }
            accept
        }
        if (requestKeyFrame) onNeedKeyFrame()
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
        lastTakenMs = maxOf(lastTakenMs, item.sendTimestampMs)
        takenByType[item.type] = maxOf(takenByType[item.type] ?: 0L, item.sendTimestampMs)
        item
    }

    fun queuedBytes(): Int = synchronized(lock) { bytes }

    /** 지금 맨 앞 항목이 기다린 시간 (ms). 비어 있으면 0. 통계([takeStats])는 건드리지 않는다. */
    fun currentDelayMs(): Long = synchronized(lock) { delayMs(clockMs()) }

    /**
     * 지난번 이후 건너뛴 횟수와 가장 찼을 때의 비율 (비트레이트 조절에 쓴다). 비율은 바이트와 기다린 시간 중 더 찬 쪽이다
     * (1이면 건너뛰기 직전).
     */
    fun takeStats(): Stats = synchronized(lock) {
        val delay = delayMs(clockMs())
        val peakDelay = maxOf(peakDelayMs, delay)
        val byBytes = peakBytes.toFloat() / capacityBytes
        val byDelay = if (maxDelayMs == Long.MAX_VALUE) 0f else peakDelay.toFloat() / maxDelayMs
        val stats = Stats(dropEvents, maxOf(byBytes, byDelay), droppedAudio, peakDelay, skippedMs)
        dropEvents = 0
        droppedAudio = 0
        skippedMs = 0
        peakBytes = bytes
        peakDelayMs = 0L // 맨 앞 항목이 계속 기다리면 다음 번에 그 시간이 다시 잡힌다
        stats
    }

    /**
     * [drops]는 건너뛴 횟수, [droppedAudio]는 버린 소리 조각 수, [delayMs]는 그동안 맨 앞 항목이 가장 오래 기다린 시간
     * (폰 안에서 방송이 밀린 정도), [skippedMs]는 건너뛰어 방송에서 빠진 시간.
     */
    data class Stats(val drops: Int, val fill: Float, val droppedAudio: Int, val delayMs: Long = 0L, val skippedMs: Long = 0L)

    /** 맨 앞 항목이 기다린 시간. 비어 있으면 0. lock 안에서. */
    private fun delayMs(now: Long): Long = items.firstOrNull()?.let { (now - it.queuedAtMs).coerceAtLeast(0) } ?: 0L

    /** 영상·소리 가운데 가장 오래 기다린 시간 (버리지 않는 시퀀스 헤더·메타데이터는 빼고). 없으면 0. lock 안에서. */
    private fun mediaDelayMs(now: Long): Long =
        items.firstOrNull { it.kind != Kind.ESSENTIAL }?.let { (now - it.queuedAtMs).coerceAtLeast(0) } ?: 0L

    fun close() = synchronized(lock) {
        closed = true
        items.clear()
        bytes = 0
        lock.notifyAll()
    }

    /** 시퀀스 헤더·메타데이터만 남기고 쌓인 영상·소리를 모두 버린다. lock 안에서. */
    private fun dropQueued() {
        val iterator = items.iterator()
        while (iterator.hasNext()) {
            val queued = iterator.next()
            if (queued.kind == Kind.ESSENTIAL) continue
            iterator.remove()
            bytes -= queued.payload.size
            if (queued.kind == Kind.AUDIO) droppedAudio++ else droppedFrames++
        }
        // 버린 것들의 시각은 잊는다: 남은 헤더는 방금 보낸 것 바로 뒤로 당기고, 그 뒤로만 이어 간다.
        if (lastTakenMs >= 0) {
            for (queued in items) queued.sendTimestampMs = minOf(queued.sendTimestampMs, lastTakenMs + RESUME_GAP_MS)
        }
        queuedByType.clear()
        queuedByType.putAll(takenByType)
        for (queued in items) queuedByType[queued.type] = maxOf(queuedByType[queued.type] ?: 0L, queued.sendTimestampMs)
    }

    /**
     * 키프레임 [key]부터 다시 보낸다. 방금 보낸 것 바로 뒤에 이어지도록, 그 사이에 건너뛴 시간만큼 이것과 그 뒤의 시각을
     * 당긴다. lock 안에서.
     */
    private fun resumeWith(key: Item) {
        waitingForKeyFrame = false
        if (lastTakenMs < 0) return // 아직 아무것도 보내지 않았다: 이어 붙일 자리가 없다
        var last = lastTakenMs
        for (queued in items) last = maxOf(last, queued.sendTimestampMs)
        val resume = last + RESUME_GAP_MS
        val stamp = key.timestampMs - shiftMs
        if (stamp > resume) {
            skippedMs += stamp - resume
            shiftMs += stamp - resume
        }
    }

    private companion object {
        /** 건너뛴 뒤 다음 것을 방금 보낸 것에서 이만큼 뒤에 잇는다 (소리 한 조각 길이쯤). */
        const val RESUME_GAP_MS = 20L
    }
}
