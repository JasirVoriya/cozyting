package com.miaocast.app.capture.shell

import com.miaocast.app.capture.shell.WirelessPairingScreenParser.NodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessPairingScreenParserTest {

    @Test
    fun extractsViaAospViewIdsRegardlessOfLanguage() {
        val nodes = listOf(
            NodeSnapshot(viewIdResourceName = "com.android.settings:id/alertTitle", text = "デバイスとペア設定"),
            NodeSnapshot(viewIdResourceName = "com.android.settings:id/pairing_code", text = "482910"),
            NodeSnapshot(viewIdResourceName = "com.android.settings:id/ip_addr", text = "192.0.2.10:41493"),
        )
        val res = WirelessPairingScreenParser.extract(nodes)
        assertNotNull(res)
        assertEquals("482910", res!!.pinCode)
        assertEquals(41493, res.pairingPort)
        assertTrue(res.matchedByViewId)
    }

    @Test
    fun extractsFromObfuscatedEnglishDialogWithoutViewIdsAndDistinguishesDialogPortFromMainPagePort() {
        // 底层「无线调试」主页面（windowId=1, layer=1）包含主连接端口 37597；
        // 顶层弹窗（windowId=2, layer=2）包含 6 位配对码 061683 与配对端口 41493，且无任何 ViewID。
        val nodes = listOf(
            NodeSnapshot(text = "Wireless debugging", windowId = 1, windowLayer = 1),
            NodeSnapshot(text = "192.0.2.10:37597", windowId = 1, windowLayer = 1),
            NodeSnapshot(text = "Pair with device", windowId = 2, windowLayer = 2),
            NodeSnapshot(text = "Wi-Fi pairing code", windowId = 2, windowLayer = 2),
            NodeSnapshot(text = "061683", windowId = 2, windowLayer = 2),
            NodeSnapshot(text = "IP address & Port", windowId = 2, windowLayer = 2),
            NodeSnapshot(text = "192.0.2.10:41493", windowId = 2, windowLayer = 2),
            NodeSnapshot(text = "Cancel", windowId = 2, windowLayer = 2),
        )
        val res = WirelessPairingScreenParser.extract(nodes)
        assertNotNull(res)
        assertEquals("061683", res!!.pinCode)
        assertEquals(41493, res.pairingPort)
        assertFalse(res.matchedByViewId)
    }

    @Test
    fun extractsSpacedSixDigitCodeAndFullWidthColonInChineseRom() {
        val nodes = listOf(
            NodeSnapshot(text = "使用配对码配对设备"),
            NodeSnapshot(text = "WLAN 配对码"),
            NodeSnapshot(text = "061 683"),
            NodeSnapshot(text = "IP 地址和端口"),
            NodeSnapshot(text = "198.51.100.25：43210"),
        )
        val res = WirelessPairingScreenParser.extract(nodes)
        assertNotNull(res)
        assertEquals("061683", res!!.pinCode)
        assertEquals(43210, res.pairingPort)
    }

    @Test
    fun extractsCombinedMultiLineNodeText() {
        val nodes = listOf(
            NodeSnapshot(text = "WLAN 配对码：739104\nIP 地址：203.0.113.8:39876"),
        )
        val res = WirelessPairingScreenParser.extract(nodes)
        assertNotNull(res)
        assertEquals("739104", res!!.pinCode)
        assertEquals(39876, res.pairingPort)
    }

    @Test
    fun fallsBackToMdnsPortWhenOnlyPinVisibleInDialog() {
        val nodes = listOf(
            NodeSnapshot(text = "918273"),
        )
        // 无 IP:Port 且无 mDNS 端口时不误触发
        assertNull(WirelessPairingScreenParser.extract(nodes, mdnsPairingPort = 0))

        // 当后台 mDNS 已确认配对端口活跃时允许匹配
        val res = WirelessPairingScreenParser.extract(nodes, mdnsPairingPort = 40123)
        assertNotNull(res)
        assertEquals("918273", res!!.pinCode)
        assertEquals(40123, res.pairingPort)
    }

    @Test
    fun ignoresUnrelatedSixDigitNumbersWithoutPortCoOccurrence() {
        val nodes = listOf(
            NodeSnapshot(text = "关于手机"),
            NodeSnapshot(text = "内部版本号 261123"),
            NodeSnapshot(text = "安全补丁 202605"),
        )
        assertNull(WirelessPairingScreenParser.extract(nodes, mdnsPairingPort = 0))
    }

    @Test
    fun picksNearestPortWhenMainPageAndDialogShareSameWindowId() {
        val nodes = listOf(
            NodeSnapshot(text = "无线调试"),
            NodeSnapshot(text = "192.0.2.10:37597"),
            NodeSnapshot(text = "配对设备"),
            NodeSnapshot(text = "配对码"),
            NodeSnapshot(text = "550192"),
            NodeSnapshot(text = "192.0.2.10:42901"),
        )
        val res = WirelessPairingScreenParser.extract(nodes)
        assertNotNull(res)
        assertEquals("550192", res!!.pinCode)
        assertEquals(42901, res.pairingPort)
    }
}
