package com.cozyting.app.cast

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import com.cozyting.app.R

/**
 * 协议徽标：enum → 原创 drawable；本机无协议标。
 * 未实现协议的设备若出现在列表，徽标仍可显示，但 [CastProtocol.castImplemented]=false
 * 时路由层不得当作已投送成功。
 */
object ProtocolBadge {
    fun drawableRes(protocol: CastProtocol): Int = when (protocol) {
        CastProtocol.MIPLAY -> R.drawable.ic_protocol_miplay
        CastProtocol.DLNA -> R.drawable.ic_protocol_dlna
        CastProtocol.AIRPLAY -> R.drawable.ic_protocol_airplay
        CastProtocol.GOOGLE_CAST -> R.drawable.ic_protocol_gcast
    }

    fun contentDescription(protocol: CastProtocol): String =
        "协议 ${protocol.labelZh}" + if (protocol.castImplemented) "" else "（本 App 尚未实现投送）"

    fun contentDescription(context: Context, protocol: CastProtocol): String =
        if (protocol.castImplemented) {
            context.getString(R.string.protocol_desc_supported, protocol.labelZh)
        } else {
            context.getString(R.string.protocol_desc_unsupported, protocol.labelZh)
        }

    /**
     * 真实设备行应显示的协议：仅真实所属；本机返回 null。
     * 当前发现栈一律 MiPlay；禁止给 MiPlay 设备标成其它协议。
     */
    fun forDiscoveredDevice(kind: CastRouteSelection.Kind, discoveredViaMiPlayStack: Boolean): CastProtocol? {
        if (kind == CastRouteSelection.Kind.THIS_DEVICE) return null
        return if (discoveredViaMiPlayStack) CastProtocol.MIPLAY else null
    }

    fun tintForRow(selected: Boolean): Int =
        if (selected) Color.rgb(40, 60, 90) else Color.argb(220, 230, 235, 240)

    fun bind(image: ImageView, protocol: CastProtocol?, selected: Boolean) {
        bindWithTint(image, protocol, tintForRow(selected))
    }

    fun bindWithTint(image: ImageView, protocol: CastProtocol?, tintColor: Int) {
        if (protocol == null) {
            image.visibility = View.GONE
            image.setImageDrawable(null)
            image.contentDescription = null
            return
        }
        image.visibility = View.VISIBLE
        image.setImageResource(drawableRes(protocol))
        image.imageTintList = ColorStateList.valueOf(tintColor)
        image.contentDescription = contentDescription(image.context, protocol)
    }

    fun createLegendView(context: Context, protocol: CastProtocol, selectedPreview: Boolean = false): ImageView {
        return ImageView(context).apply {
            setImageResource(drawableRes(protocol))
            imageTintList = ColorStateList.valueOf(tintForRow(selectedPreview))
            contentDescription = contentDescription(context, protocol)
        }
    }
}
