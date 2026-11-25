package com.cozyting.app

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.cozyting.app.compat.AcceptanceAudioDefaults
import com.cozyting.app.compat.QuickSettingsTileCompat
import com.cozyting.app.ui.AppLocaleManager
import com.cozyting.app.ui.PrefsCastSettingsStore

/**
 * 系统控制中心快捷设置磁贴（Quick Settings Tile）：
 * - 空闲时暗显；投送中亮起并同步副标题（如 `2 台 · 45%` 或 `2 台 · 已暂停`）；
 * - 点击后收起系统栏并在当前应用上方弹出轻量悬浮控制卡 [QuickCastPanelActivity]。
 */
class CastTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val panelIntent = QuickCastPanelActivity.createLaunchIntent(this)
        val launch = {
            QuickSettingsTileCompat.startActivityAndCollapse(this, panelIntent)
        }
        if (isLocked) {
            unlockAndRun(launch)
        } else {
            launch()
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val locCtx = AppLocaleManager.localizedContext(this)
        val casting = CastService.running.get()
        val paused = CastService.paused
        val activeCount = CastService.activeReceiverIps().size
        val defaultVol = AcceptanceAudioDefaults.speakerPremutePercent(BuildConfig.IS_ACCEPTANCE)
        val masterVol = PrefsCastSettingsStore(this).loadMasterVolume(defaultVol)

        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_miplay)
        tile.label = locCtx.getString(R.string.qs_tile_label)
        if (casting) {
            tile.state = Tile.STATE_ACTIVE
            val count = activeCount.coerceAtLeast(1)
            tile.subtitle = if (paused) {
                locCtx.getString(R.string.qs_tile_sub_paused_fmt, count)
            } else {
                locCtx.getString(R.string.qs_tile_sub_casting_fmt, count, masterVol)
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.subtitle = locCtx.getString(R.string.qs_tile_sub_idle)
        }
        tile.updateTile()
    }
}
