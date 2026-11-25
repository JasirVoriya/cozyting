package com.cozyting.app.capture.shell

/**
 * 无感采集 Helper 心跳容错与断连自动重建限频策略（纯 Kotlin，便于 JVM 单测）。
 *
 * 行为约定：
 * 1. 单次控制通道读超时（如切到重度 App 瞬间触发的短暂调度抖动）不立即判死，
 *    允许连续 [maxConsecutiveTimeouts] 次超时内继续重试，收到任意正常响应即清零；
 * 2. 明确 EOF / Socket 断开或连续超时耗尽时，在滑动时间窗 [respawnWindowMs] 内最多允许
 *    [maxRespawnAttempts] 次自动静默重建，防止异常环境下的无限拉起风暴。
 */
internal class HelperHeartbeatPolicy(
    private val maxConsecutiveTimeouts: Int = ShellCaptureProtocol.HEARTBEAT_MAX_CONSECUTIVE_TIMEOUTS,
    private val respawnWindowMs: Long = ShellCaptureProtocol.AUTO_RESPAWN_WINDOW_MS,
    private val maxRespawnAttempts: Int = ShellCaptureProtocol.AUTO_RESPAWN_MAX_ATTEMPTS,
) {
    private var consecutiveTimeouts = 0
    private val respawnTimestamps = ArrayDeque<Long>()

    @Synchronized
    fun onHeartbeatSuccess() {
        consecutiveTimeouts = 0
    }

    /**
     * 记录一次单次心跳超时；返回 `true` 表示仍在容忍窗口内应继续重试，`false` 表示已耗尽容忍次数需触发断连处理。
     */
    @Synchronized
    fun onHeartbeatTimeout(): Boolean {
        consecutiveTimeouts += 1
        return consecutiveTimeouts <= maxConsecutiveTimeouts
    }

    @Synchronized
    fun consecutiveTimeoutCount(): Int = consecutiveTimeouts

    @Synchronized
    fun resetTimeouts() {
        consecutiveTimeouts = 0
    }

    /**
     * 在当前时间戳 [nowMs] 申请一次自动静默重建配额；若在滑动窗口内未超限则记录并返回 `true`。
     */
    @Synchronized
    fun tryAcquireRespawnBudget(nowMs: Long): Boolean {
        while (respawnTimestamps.isNotEmpty() && nowMs - respawnTimestamps.first() > respawnWindowMs) {
            respawnTimestamps.removeFirst()
        }
        if (respawnTimestamps.size >= maxRespawnAttempts) {
            return false
        }
        respawnTimestamps.addLast(nowMs)
        return true
    }

    @Synchronized
    fun resetAll() {
        consecutiveTimeouts = 0
        respawnTimestamps.clear()
    }
}
