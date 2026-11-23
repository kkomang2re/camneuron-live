package com.camneuron.live.rtmp

/**
 * 인코더와 송신 스레드 사이의 대기열. 바이트 수([capacityBytes])와 기다린 시간([maxDelayMs], [maxAudioDelayMs])으로 제한한다
 * (모바일 데이터가 느려지면 쌓인다).
 *
 * 가득 차거나 맨 앞 항목이 [maxDelayMs]보다 오래 기다렸으면 쌓인 영상 프레임을 모두 버리고 다음 키프레임부터 다시 보낸다
 * (중간 프레임만 버리면 화면이 깨진다). 그때 [onNeedKeyFrame]을 불러 인코더에 키프레임을 바로 요청하게 한다.
 * 시퀀스 헤더·메타데이터는 버리지 않는다.
 *
 * 소리는 끊기는 것이 더 거슬려서 영상보다 오래 둔다. 하지만 업로드가 소리조차 못 따라가면 (소리만 남은 대기열의 맨 앞이
 * [maxAudioDelayMs]보다 오래 기다렸으면) 쌓인 소리도 모두 버리고 건너뛴다. 그러지 않으면 대기열이 찰 때까지 (소리만으로
 * 1분 가까이) 방송이 늦어지고, 망이 좋아져도 시청자 화면은 늦은 채로 남는다.
 * 건너뛴 만큼 그 뒤의 시각을 당겨 ([Item.sendTimestampMs]) 서버에는 끊긴 데 없이 이어진 것으로 보이게 한다
 * (시각이 건너뛰면 멈추는 플레이어가 있다). 영상과 소리를 똑같이 당기므로 둘은 어긋나지 않는다.
 *
 * 소리가 밀려 있는 동안에는 키프레임도 넣지 않는다 (큰 키프레임 하나가 느린 망을 몇 초씩 차지해 소리가 더 밀린다).
 * 대기열이 비면 그때 키프레임을 다시 달라고 한다.
 *
 * 시간 제한이 없으면 비트레이트를 낮췄을 때 같은 바이트가 더 긴 시간이 된다 (35%로 낮추면 2초 분량이 6초 가까이).
 */
class MediaQueue(
    private val capacityBytes: Int,
    private val maxDelayMs: Long = Long.MAX_VALUE,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxAudioDelayMs: Long = Long.MAX_VALUE,
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
    private var waitingForKeyFrame = false
    private var closed = false

    /** 밀린 것이 다 나가면 키프레임을 달라고 할 것 (소리가 밀려 있어 영상을 바로 다시 시작하지 못했을 때). */
    private var keyFrameWhenClear = false

    /** 지금까지 건너뛴 시간. 들어오는 항목의 시각에서 뺀다. */
    private var shiftMs = 0L

    /** 송신 스레드가 꺼내 간 것의 가장 늦은 [Item.sendTimestampMs]. 아직 없으면 -1. */
    private var lastTakenMs = -1L

    /** 메시지 종류(영상, 소리)마다: 꺼내 간 것과 대기열에 넣은 것의 가장 늦은 시각. 종류마다 시각이 거꾸로 가지 않게 한다. */
    private val takenByType = HashMap<Int, Long>()
    private val queuedByType = HashMap<Int, Long>()

    // 영상을 다시 시작하자마자 또 버려야 했으면 (망이 영상을 못 싣는다) 다음에는 덜 쌓였을 때 버린다: 소리가 덜 밀린다.
    // 소리까지 건너뛰어야 했으면 다음에는 더 기다렸다 시작한다.
    private var lastRestartMs = NONE
    private var struggling = false
    private var restartFailed = false
    private var holdMs = 0L
    private var videoHoldUntilMs = 0L

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
            if (item.kind == Kind.FRAME && waitingForKeyFrame) {
                droppedFrames++
                return false
            }
            val size = item.payload.size
            val now = clockMs()
            peakDelayMs = maxOf(peakDelayMs, delayMs(now))
            var accept = true
            var overloaded = false
            var restartVideo = false // 영상을 버렸다: 키프레임부터 다시 보내야 한다
            if (item.kind != Kind.ESSENTIAL) {
                val restartedJustNow = lastRestartMs != NONE && now - lastRestartMs <= RESTART_FAIL_MS
                val videoDelayMs = if (struggling && restartedJustNow) maxDelayMs / 2 else maxDelayMs
                if (bytes + size > capacityBytes || delayMs(now) > videoDelayMs) {
                    overloaded = true
                    val dropped = dropQueuedVideo()
                    droppedFrames += dropped
                    when (item.kind) {
                        Kind.FRAME -> {
                            droppedFrames++
                            accept = false
                            restartVideo = true
                        }
                        // 키프레임은 자리가 모자라도 넣는다 (아래에서 소리가 밀려 있지 않다면).
                        Kind.KEY_FRAME -> Unit
                        else -> {
                            if (dropped > 0) restartVideo = true
                            if (bytes + size > capacityBytes) {
                                droppedAudio++
                                accept = false
                            }
                        }
                    }
                    if (dropped > 0 || item.kind == Kind.FRAME) {
                        dropEvents++
                        struggling = restartedJustNow
                    }
                }
                // 영상을 버리고도 소리가 너무 오래 기다렸다: 업로드가 소리조차 못 따라간다. 쌓인 것을 모두 버리고 건너뛴다.
                if (mediaDelayMs(now) > maxAudioDelayMs) {
                    skipAhead(item, now)
                    dropEvents++
                    restartVideo = true
                    if (item.kind == Kind.FRAME && accept) {
                        droppedFrames++
                        accept = false
                    }
                }
                // 소리가 밀려 있는 동안에는 영상을 다시 시작하지 않는다. 밀린 것이 다 나가면 키프레임을 달라고 한다 ([take]).
                val backlogged = delayMs(now) > maxDelayMs / 2 || now < videoHoldUntilMs
                if (item.kind == Kind.KEY_FRAME && accept && backlogged && (waitingForKeyFrame || overloaded || restartVideo)) {
                    droppedFrames++
                    accept = false
                    restartVideo = true
                }
                if (restartVideo && !(accept && item.kind == Kind.KEY_FRAME)) {
                    waitingForKeyFrame = true
                    keyFrameWhenClear = backlogged
                    requestKeyFrame = !backlogged
                }
            }
            if (accept) {
                if (item.kind == Kind.KEY_FRAME) {
                    if (waitingForKeyFrame || overloaded || restartVideo) {
                        // 영상을 다시 시작한다. 지난번에 다시 시작한 것이 버텼으면 다음부터는 기다리지 않아도 된다.
                        if (!restartFailed) holdMs = 0
                        restartFailed = false
                        lastRestartMs = now
                    }
                    waitingForKeyFrame = false
                    keyFrameWhenClear = false
                }
                item.queuedAtMs = now
                // 건너뛴 만큼 당기되, 같은 종류에서 앞서 넣은 것보다 이르지는 않게 한다.
                val stamp = maxOf(item.timestampMs - shiftMs, queuedByType[item.type] ?: 0L, 0L)
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
    fun take(timeoutMs: Long): Item? {
        var requestKeyFrame = false
        val taken = synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (items.isEmpty() && !closed) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                lock.wait(left)
            }
            if (closed) return null
            val item = items.removeFirst()
            bytes -= item.payload.size
            val now = clockMs()
            peakDelayMs = maxOf(peakDelayMs, now - item.queuedAtMs)
            lastTakenMs = maxOf(lastTakenMs, item.sendTimestampMs)
            takenByType[item.type] = maxOf(takenByType[item.type] ?: 0L, item.sendTimestampMs)
            // 밀린 것이 다 나갔다: 미뤄 둔 키프레임을 달라고 한다.
            if (keyFrameWhenClear && now >= videoHoldUntilMs && delayMs(now) <= maxDelayMs / 4) {
                keyFrameWhenClear = false
                requestKeyFrame = true
            }
            item
        }
        if (requestKeyFrame) onNeedKeyFrame()
        return taken
    }

    fun queuedBytes(): Int = synchronized(lock) { bytes }

    /**
     * 지난번 이후 버린 횟수와 가장 찼을 때의 비율 (NetGovernor에 넘긴다). 비율은 바이트와 기다린 시간 중 더 찬 쪽이다
     * (1이면 버리기 직전). 영상을 쉬고 있는 동안에는 대기열이 비어 있어도 [HELD_FILL] 아래로 내려가지 않는다:
     * 소리만 보내서 비어 있는 것이지 망이 좋아진 것이 아니므로, 그사이에 비트레이트를 도로 올리지 않게 한다.
     */
    fun takeStats(): Stats = synchronized(lock) {
        val now = clockMs()
        val delay = delayMs(now)
        val peakDelay = maxOf(peakDelayMs, delay)
        val byBytes = peakBytes.toFloat() / capacityBytes
        val byDelay = if (maxDelayMs == Long.MAX_VALUE) 0f else peakDelay.toFloat() / maxDelayMs
        val held = if (now < videoHoldUntilMs) HELD_FILL else 0f
        val stats = Stats(dropEvents, maxOf(byBytes, byDelay, held), droppedAudio, peakDelay, skippedMs)
        dropEvents = 0
        droppedAudio = 0
        skippedMs = 0
        peakBytes = bytes
        peakDelayMs = 0L // 맨 앞 항목이 계속 기다리면 다음 번에 그 시간이 다시 잡힌다
        stats
    }

    /**
     * [delayMs]는 그동안 맨 앞 항목이 가장 오래 기다린 시간 (폰 안에서 방송이 밀린 정도),
     * [skippedMs]는 소리까지 버리고 건너뛴 시간.
     */
    data class Stats(val drops: Int, val fill: Float, val droppedAudio: Int, val delayMs: Long = 0L, val skippedMs: Long = 0L)

    /** 맨 앞 항목이 기다린 시간. 비어 있으면 0. lock 안에서. */
    private fun delayMs(now: Long): Long = items.firstOrNull()?.let { (now - it.queuedAtMs).coerceAtLeast(0) } ?: 0L

    fun close() = synchronized(lock) {
        closed = true
        items.clear()
        bytes = 0
        lock.notifyAll()
    }

    /** 시퀀스 헤더·메타데이터가 아닌 것 가운데 가장 오래 기다린 시간. 없으면 0. lock 안에서. */
    private fun mediaDelayMs(now: Long): Long =
        items.firstOrNull { it.kind != Kind.ESSENTIAL }?.let { (now - it.queuedAtMs).coerceAtLeast(0) } ?: 0L

    /**
     * 시퀀스 헤더·메타데이터만 남기고 쌓인 것을 모두 버린다. 그리고 [next]부터는 방금 보낸 것 바로 뒤에 이어지도록
     * 건너뛴 시간만큼 시각을 당긴다. lock 안에서.
     */
    private fun skipAhead(next: Item, now: Long) {
        val iterator = items.iterator()
        while (iterator.hasNext()) {
            val queued = iterator.next()
            if (queued.kind == Kind.ESSENTIAL) continue
            iterator.remove()
            bytes -= queued.payload.size
            if (queued.kind == Kind.AUDIO) droppedAudio++ else droppedFrames++
        }
        if (lastTakenMs >= 0) {
            val resume = lastTakenMs + RESUME_GAP_MS
            val stamp = next.timestampMs - shiftMs
            if (stamp > resume) {
                skippedMs += stamp - resume
                shiftMs += stamp - resume
            }
            // 남아 있는 시퀀스 헤더도 그 자리로 당긴다. 버린 것들의 시각은 잊고, 보낸 것 뒤로만 이어 간다.
            for (queued in items) queued.sendTimestampMs = minOf(queued.sendTimestampMs, resume)
            queuedByType.clear()
            queuedByType.putAll(takenByType)
            for (queued in items) queuedByType[queued.type] = maxOf(queuedByType[queued.type] ?: 0L, queued.sendTimestampMs)
        }
        waitingForKeyFrame = true
        // 영상을 다시 시작한 직후라면 그 영상이 망을 막은 것이다. 다음에는 더 기다렸다 시작한다 (그동안은 소리만 보낸다).
        if (lastRestartMs != NONE && now - lastRestartMs <= RESTART_FAIL_MS) {
            restartFailed = true
            lastRestartMs = NONE
            holdMs = (holdMs * 2).coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
            videoHoldUntilMs = now + holdMs
        }
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

    private companion object {
        const val NONE = Long.MIN_VALUE

        /** 건너뛴 뒤 다음 것을 방금 보낸 것에서 이만큼 뒤에 잇는다 (소리 한 조각 길이쯤). */
        const val RESUME_GAP_MS = 20L

        /** 영상을 다시 시작하고 이 안에 또 버리거나 소리까지 건너뛰었으면, 망이 영상을 싣지 못하는 것으로 본다. */
        const val RESTART_FAIL_MS = 6_000L

        /** 그때 영상을 쉬는 시간: 처음에는 5초, 되풀이되면 두 배씩 20초까지. */
        const val MIN_HOLD_MS = 5_000L
        const val MAX_HOLD_MS = 20_000L

        /** 영상을 쉬는 동안 알리는 대기열 비율: 화질을 더 낮추지도(0.5 이상), 도로 올리지도(0.2 미만) 않는 값. */
        const val HELD_FILL = 0.3f
    }
}
