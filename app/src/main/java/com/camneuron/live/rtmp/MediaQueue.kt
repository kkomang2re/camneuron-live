package com.camneuron.live.rtmp

/**
 * 인코더와 송신 스레드 사이의 대기열. 바이트 수로 크기를 제한한다 (모바일 데이터가 느려지면 쌓인다).
 *
 * 가득 차면 쌓인 영상 프레임을 모두 버리고 다음 키프레임부터 다시 보낸다 (중간 프레임만 버리면 화면이 깨진다).
 * 그때 [onNeedKeyFrame]을 불러 인코더에 키프레임을 바로 요청하게 한다.
 * 시퀀스 헤더·메타데이터는 버리지 않고, 소리는 영상을 버리고도 자리가 없을 때만 버린다 (소리가 끊기는 게 더 거슬린다).
 */
class MediaQueue(private val capacityBytes: Int, private val onNeedKeyFrame: () -> Unit = {}) {
    enum class Kind { ESSENTIAL, KEY_FRAME, FRAME, AUDIO }

    class Item(val kind: Kind, val chunkStreamId: Int, val type: Int, val timestampMs: Long, val payload: ByteArray)

    private val lock = Object()
    private val items = ArrayDeque<Item>()
    private var bytes = 0
    private var waitingForKeyFrame = false
    private var closed = false

    // 통계 (마지막 [takeStats] 뒤로)
    private var dropEvents = 0
    private var peakBytes = 0
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
            var accept = true
            if (item.kind != Kind.ESSENTIAL && bytes + size > capacityBytes) {
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
        item
    }

    fun queuedBytes(): Int = synchronized(lock) { bytes }

    /** 지난번 이후 버린 횟수와 가장 찼을 때의 비율 (NetGovernor에 넘긴다). */
    fun takeStats(): Stats = synchronized(lock) {
        val stats = Stats(dropEvents, peakBytes.toFloat() / capacityBytes, droppedAudio)
        dropEvents = 0
        droppedAudio = 0
        peakBytes = bytes
        stats
    }

    data class Stats(val drops: Int, val fill: Float, val droppedAudio: Int)

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
