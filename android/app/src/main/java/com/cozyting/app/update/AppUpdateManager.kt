package com.cozyting.app.update

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.cozyting.app.BuildConfig
import com.cozyting.app.NativeBridge
import com.cozyting.app.R
import com.cozyting.app.ui.AppLocaleManager
import com.cozyting.app.ui.PrefsCastSettingsStore
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程级 App 更新检测协调器：
 * - 单飞（single-flight）去重，避免主界面与设置页并发重复发起网络请求；
 * - 优先请求公开仓静态清单 `update.json`（支持 ETag 304），不可达时自动回退至公开仓 Releases API；
 * - 全部版本号比较、通道过滤与跨版本区间日志聚合均委托给 L1 Rust `NativeBridge.evaluateUpdateJson`；
 * - 严格遵守「自动检测 24h 节流」「验收包禁止自动弹窗」「零遥测」与「不打扰投送」边界。
 */
object AppUpdateManager {

    fun interface Transport {
        fun getText(url: String, etag: String?): UpdateHttpClient.FetchResult
    }

    fun interface Evaluator {
        fun evaluate(
            currentVersion: String,
            channel: String,
            platform: String,
            abi: String,
            osVersion: String,
            ignoredTag: String,
            rawResponseJson: String,
        ): String
    }

    private val defaultTransport = Transport { url, etag ->
        UpdateHttpClient.getText(url = url, etag = etag)
    }

    private val defaultEvaluator = Evaluator { cur, ch, pl, ab, os, ig, raw ->
        NativeBridge.evaluateUpdateJson(cur, ch, pl, ab, os, ig, raw)
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cozyting-update").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlight = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<(UpdateCheckState) -> Unit>()

    @Volatile
    var state: UpdateCheckState = UpdateCheckState.Idle
        private set

    /** 用户已手动点 × 关掉主界面顶部轻提示条的版本 tag（与本地持久化保持同步）。 */
    @Volatile
    private var bannerDismissedTag: String = ""

    @Volatile
    internal var transport: Transport = defaultTransport

    @Volatile
    internal var evaluator: Evaluator = defaultEvaluator

    @Volatile
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    @Volatile
    internal var currentVersionProvider: () -> String = { BuildConfig.VERSION_NAME }

    @Volatile
    internal var isAcceptanceProvider: () -> Boolean = { BuildConfig.IS_ACCEPTANCE }

    @Volatile
    internal var sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }

    fun addListener(listener: (UpdateCheckState) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (UpdateCheckState) -> Unit) {
        listeners.remove(listener)
    }

    fun currentDisplayVersion(): String =
        UpdateChannel.stripBuildSuffixes(currentVersionProvider())

    /** 从本地偏好恢复已点 × 关闭的更新提示横幅版本 tag。 */
    fun hydrateDismissedBanner(context: Context) {
        val persisted = PrefsCastSettingsStore(context.applicationContext).dismissedUpdateBannerTag
        if (persisted.isNotEmpty() && bannerDismissedTag.isEmpty()) {
            bannerDismissedTag = persisted
        }
    }

    fun dismissBannerForTag(tag: String) {
        bannerDismissedTag = tag.trim()
        notifyListeners(state)
    }

    /** 关闭主界面更新提示横幅并持久化该版本 tag，重启后同版本不再反复弹出。 */
    fun dismissBannerForTag(context: Context, tag: String) {
        val clean = tag.trim()
        PrefsCastSettingsStore(context.applicationContext).dismissedUpdateBannerTag = clean
        bannerDismissedTag = clean
        notifyListeners(state)
    }

    fun isBannerDismissed(tag: String): Boolean =
        bannerDismissedTag.isNotEmpty() && bannerDismissedTag.equals(tag.trim(), ignoreCase = true)

    /** 判断当前是否应在主界面顶栏设置图标上展示更新小圆点。 */
    fun shouldShowUpdateBadge(): Boolean {
        if (isAcceptanceProvider()) return false
        val ready = state as? UpdateCheckState.Ready ?: return false
        return ready.decision.status == UpdateDecisionStatus.UPDATE_AVAILABLE
    }

    /**
     * 判断当前是否应在主界面展示非阻塞轻提示条：
     * - 仅在检测到未跳过的新版本、非验收包且本次会话未手动点 × 关闭时展示；
     * - 投送中照常展示，仅在用户点击「跳过此版本」后彻底隐藏该版本提醒。
     */
    fun activeBannerDecision(@Suppress("UNUSED_PARAMETER") isCastingNow: Boolean = false): UpdateDecision? {
        if (isAcceptanceProvider()) return null
        val ready = state as? UpdateCheckState.Ready ?: return null
        val d = ready.decision
        if (d.status != UpdateDecisionStatus.UPDATE_AVAILABLE) return null
        if (isBannerDismissed(d.latestTag)) return null
        return d
    }

    /** 跳过此版本：持久化记录并立即刷新内存态。 */
    fun ignoreVersion(context: Context, tag: String) {
        val settings = PrefsCastSettingsStore(context)
        settings.ignoredUpdateTag = tag
        reevaluateFromCache(context, manual = false)
    }

    /** 切换更新通道后，若本地已有缓存清单则立即用 L1 重新裁决，无需等待网络。 */
    fun reevaluateFromCache(context: Context, manual: Boolean = false) {
        hydrateDismissedBanner(context)
        val settings = PrefsCastSettingsStore(context)
        val cached = settings.cachedUpdateManifestJson
        if (cached.isBlank()) return
        val decision = runCatching {
            evaluateRaw(
                settings = settings,
                rawJson = cached,
                respectIgnored = !manual,
            )
        }.getOrNull() ?: return
        if (decision.status != UpdateDecisionStatus.ERROR) {
            publishState(UpdateCheckState.Ready(decision, settings.lastUpdateCheckMs, fromCache = true))
        }
    }

    /**
     * 冷启动静默检查入口：
     * - 默认常开（受 24 小时节流与验收包保护约束，零遥测、绝不冷启动强弹窗）；
     * - 若在 24 小时冷却期内但存在本地缓存清单，直接基于本地缓存无网复算状态（零流量）。
     */
    fun maybeAutoCheck(context: Context) {
        val appCtx = context.applicationContext
        hydrateDismissedBanner(appCtx)
        val settings = PrefsCastSettingsStore(appCtx)
        if (isAcceptanceProvider()) {
            return
        }
        val now = clockMs()
        val elapsed = now - settings.lastUpdateCheckMs
        if (settings.lastUpdateCheckMs > 0L && elapsed in 0 until UpdateConfig.AUTO_CHECK_COOLDOWN_MS) {
            if (state is UpdateCheckState.Idle && settings.cachedUpdateManifestJson.isNotBlank()) {
                reevaluateFromCache(appCtx, manual = false)
            }
            return
        }
        checkAsync(appCtx, manual = false)
    }

    /** 手动触发更新检查（忽略 24h 节流与“跳过此版本”屏蔽，随时可见最新版本）。 */
    fun checkManually(context: Context) {
        checkAsync(context.applicationContext, manual = true)
    }

    fun checkAsync(context: Context, manual: Boolean) {
        val appCtx = context.applicationContext
        if (!inFlight.compareAndSet(false, true)) return
        publishState(UpdateCheckState.Checking(manual))
        executor.execute {
            try {
                val result = checkBlocking(appCtx, manual)
                publishState(result)
            } finally {
                inFlight.set(false)
            }
        }
    }

    /** 同步执行一次检测（便于单元测试与后台工作线程复用）。 */
    internal fun checkBlocking(context: Context, manual: Boolean): UpdateCheckState {
        hydrateDismissedBanner(context)
        val settings = PrefsCastSettingsStore(context)
        val now = clockMs()
        var primaryError: String? = null

        // 1. 首选：公开分发仓静态清单 update.json（支持 ETag 304）
        val cachedJson = settings.cachedUpdateManifestJson
        val sendEtag = if (cachedJson.isNotBlank()) settings.updateEtag.takeIf { it.isNotBlank() } else null
        try {
            when (val res = transport.getText(UpdateConfig.MANIFEST_PRIMARY_URL, sendEtag)) {
                is UpdateHttpClient.FetchResult.NotModified -> {
                    if (cachedJson.isNotBlank()) {
                        val decision = evaluateRaw(settings, cachedJson, respectIgnored = !manual)
                        if (decision.status != UpdateDecisionStatus.ERROR) {
                            settings.lastUpdateCheckMs = now
                            return UpdateCheckState.Ready(decision, now, fromCache = true)
                        }
                    }
                }
                is UpdateHttpClient.FetchResult.Ok -> {
                    val decision = evaluateRaw(settings, res.body, respectIgnored = !manual)
                    if (decision.status != UpdateDecisionStatus.ERROR) {
                        settings.cachedUpdateManifestJson = res.body
                        settings.updateEtag = res.etag.orEmpty()
                        settings.lastUpdateCheckMs = now
                        return UpdateCheckState.Ready(decision, now, fromCache = false)
                    } else {
                        primaryError = decision.error
                    }
                }
                is UpdateHttpClient.FetchResult.HttpError -> {
                    primaryError = res.message
                }
            }
        } catch (e: Exception) {
            primaryError = e.message ?: AppLocaleManager.localizedContext(context).getString(R.string.update_err_network)
        }

        // 2. 兜底：公开分发仓 GitHub Releases REST API（最近 30 条，同样支持跨版本区间日志）
        val locCtx = AppLocaleManager.localizedContext(context)
        try {
            when (val res = transport.getText(UpdateConfig.RELEASES_FALLBACK_API_URL, null)) {
                is UpdateHttpClient.FetchResult.Ok -> {
                    val decision = evaluateRaw(settings, res.body, respectIgnored = !manual)
                    if (decision.status != UpdateDecisionStatus.ERROR) {
                        settings.cachedUpdateManifestJson = res.body
                        settings.updateEtag = ""
                        settings.lastUpdateCheckMs = now
                        return UpdateCheckState.Ready(decision, now, fromCache = false)
                    } else {
                        return UpdateCheckState.Failed(decision.localizedError(locCtx), now)
                    }
                }
                is UpdateHttpClient.FetchResult.HttpError -> {
                    val detail = if (res.code == 403) {
                        locCtx.getString(R.string.update_err_github_rate_limit)
                    } else {
                        locCtx.getString(R.string.update_err_http_unavailable, res.code)
                    }
                    return UpdateCheckState.Failed(detail, now)
                }
                UpdateHttpClient.FetchResult.NotModified -> {
                    // 未传 ETag 不会返回 304
                }
            }
        } catch (e: Exception) {
            val detail = e.message ?: primaryError ?: locCtx.getString(R.string.update_err_network_connect)
            return UpdateCheckState.Failed(locCtx.getString(R.string.update_err_check_failed, detail), now)
        }

        return UpdateCheckState.Failed(
            locCtx.getString(
                R.string.update_err_check_failed,
                primaryError ?: locCtx.getString(R.string.update_err_feed_unreachable),
            ),
            now,
        )
    }

    private fun evaluateRaw(
        settings: PrefsCastSettingsStore,
        rawJson: String,
        respectIgnored: Boolean,
    ): UpdateDecision {
        val currentVersion = currentVersionProvider()
        val channel = settings.resolvedUpdateChannel(currentVersion).id
        val ignored = if (respectIgnored) settings.ignoredUpdateTag else ""
        val outJson = evaluator.evaluate(
            currentVersion = currentVersion,
            channel = channel,
            platform = "android",
            abi = primaryAbi(),
            osVersion = sdkIntProvider().toString(),
            ignoredTag = ignored,
            rawResponseJson = rawJson,
        )
        return UpdateDecision.fromJson(outJson)
    }

    private fun primaryAbi(): String =
        Build.SUPPORTED_ABIS?.firstOrNull { it.isNotBlank() } ?: "arm64-v8a"

    private fun publishState(newState: UpdateCheckState) {
        state = newState
        notifyListeners(newState)
    }

    private fun notifyListeners(s: UpdateCheckState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { it(s) }
        } else {
            mainHandler.post { listeners.forEach { it(s) } }
        }
    }

    /** 仅用于单测重置全局依赖与内存态。 */
    internal fun resetForTest() {
        transport = defaultTransport
        evaluator = defaultEvaluator
        clockMs = { System.currentTimeMillis() }
        currentVersionProvider = { BuildConfig.VERSION_NAME }
        isAcceptanceProvider = { BuildConfig.IS_ACCEPTANCE }
        sdkIntProvider = { Build.VERSION.SDK_INT }
        inFlight.set(false)
        bannerDismissedTag = ""
        state = UpdateCheckState.Idle
        listeners.clear()
    }
}
