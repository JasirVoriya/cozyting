package com.cozyting.app.compat

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.TileService
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.cozyting.app.CastTileService
import com.cozyting.app.R
import java.util.concurrent.Executor

/**
 * 隔离快捷设置磁贴（API 33 `requestAddTileService`、API 34 `startActivityAndCollapse(PendingIntent)`）
 * 及悬浮弹窗背景模糊（API 31 `blurBehindRadius`）的高版本 API，确保 `minSdk = 29` 与 Lint `NewApi` 零告警。
 */
object QuickSettingsTileCompat {
    const val ADD_RESULT_ADDED = 2
    const val ADD_RESULT_ALREADY_ADDED = 1
    const val ADD_RESULT_DENIED = 0
    const val ADD_RESULT_UNSUPPORTED = -1

    fun canRequestAddTile(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU

    fun startActivityAndCollapse(service: TileService, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Api34.startActivityAndCollapse(service, intent)
        } else {
            Api29.startActivityAndCollapse(service, intent)
        }
    }

    fun requestAddTile(
        context: Context,
        label: CharSequence,
        executor: Executor,
        onResult: (Int) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33.requestAddTile(context, label, executor, onResult)
        } else {
            onResult(ADD_RESULT_UNSUPPORTED)
        }
    }

    fun requestListeningState(context: Context) {
        runCatching {
            val appCtx = context.applicationContext
            TileService.requestListeningState(
                appCtx,
                ComponentName(appCtx, CastTileService::class.java),
            )
        }
    }

    fun supportsWindowBlurBehind(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

    fun applyWindowBlurBehind(window: Window, blurRadiusPx: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurRadiusPx > 0) {
            Api31.applyBlurBehind(window, blurRadiusPx)
        }
    }

    /**
     * 禁用系统默认的 Activity 进出场切换动画，交由悬浮卡片自身执行细腻的渐显/缩放/位移缓动，
     * 避免系统默认 Dialog 动画生硬跳切。
     */
    fun disableActivityTransition(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Api34.disableTransition(activity)
        } else {
            Api29.disableTransition(activity)
        }
    }

    private object Api29 {
        @SuppressLint("StartActivityAndCollapseDeprecated")
        @Suppress("DEPRECATION")
        fun startActivityAndCollapse(service: TileService, intent: Intent) {
            service.startActivityAndCollapse(intent)
        }

        @Suppress("DEPRECATION")
        fun disableTransition(activity: Activity) {
            activity.overridePendingTransition(0, 0)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31 {
        fun applyBlurBehind(window: Window, blurRadiusPx: Int) {
            runCatching {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply {
                    blurBehindRadius = blurRadiusPx
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33 {
        fun requestAddTile(
            context: Context,
            label: CharSequence,
            executor: Executor,
            onResult: (Int) -> Unit,
        ) {
            val sbm = context.getSystemService(StatusBarManager::class.java)
            if (sbm == null) {
                onResult(ADD_RESULT_UNSUPPORTED)
                return
            }
            val cn = ComponentName(context.applicationContext, CastTileService::class.java)
            val icon = Icon.createWithResource(context, R.drawable.ic_stat_miplay)
            runCatching {
                sbm.requestAddTileService(cn, label, icon, executor) { result ->
                    val mapped = when (result) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> ADD_RESULT_ADDED
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> ADD_RESULT_ALREADY_ADDED
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> ADD_RESULT_DENIED
                        else -> ADD_RESULT_UNSUPPORTED
                    }
                    onResult(mapped)
                }
            }.onFailure {
                onResult(ADD_RESULT_UNSUPPORTED)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34 {
        fun startActivityAndCollapse(service: TileService, intent: Intent) {
            val pi = PendingIntent.getActivity(
                service,
                401,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            service.startActivityAndCollapse(pi)
        }

        fun disableTransition(activity: Activity) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }
}
