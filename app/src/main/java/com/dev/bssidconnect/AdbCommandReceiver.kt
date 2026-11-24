package com.dev.bssidconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * v17：ADB 意图接口（远程控制入口）。
 *
 * 设计目标：电脑端 adb 一条命令即可让手机锁定/解锁指定 BSSID，无需触屏操作——
 * 批量测试自动化（脚本循环"重置→锁定→测流量→下一台"）的基础设施。
 *
 * 用法（adb shell 以 shell uid 发送广播，exported receiver 可接收）：
 *   锁定：adb shell am broadcast -a com.dev.bssidconnect.LOCK \
 *           --es ssid g10 --es bssid 6C:87:20:31:CE:40 [--es password xxx] [--es mode sug|spec]
 *   解锁：adb shell am broadcast -a com.dev.bssidconnect.UNLOCK
 *   状态：adb shell am broadcast -a com.dev.bssidconnect.STATUS
 *         （执行后发 ACK 广播，diagnostics 落 diag.txt；结果也可通过
 *          `adb shell run-as com.dev.bssidconnect sh -c "cat files/diag.txt"` 读取 ADB-ACK 行）
 *
 * 安全考量：action 前缀固定 + 广播来源不校验（测试工具定位，接 adb 即信任）。
 * receiver 里不做重活：拉起 GuardService 前台服务，由 beginGuardSession 完成全部锁定逻辑
 * （BootReceiver 同款路径，已验证无需 Activity 栈）。
 */
class AdbCommandReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_LOCK = "com.dev.bssidconnect.LOCK"
        const val ACTION_UNLOCK = "com.dev.bssidconnect.UNLOCK"
        const val ACTION_STATUS = "com.dev.bssidconnect.STATUS"
        const val ACTION_ACK = "com.dev.bssidconnect.ACK"

        /** receiver → GuardService 的会话下发 action（经 startForegroundService intent 传递参数） */
        const val ACTION_SESSION = "com.dev.bssidconnect.SESSION"

        /** STATUS/LOCK/UNLOCK 统一走此回执：setResult 供有序广播读取 + 发 ACK 广播 */
        const val EXTRA_ACK = "ack"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            ACTION_LOCK -> handleLock(context, intent)
            ACTION_UNLOCK -> handleUnlock(context)
            ACTION_STATUS -> handleStatus(context)
            else -> return
        }
    }

    /** LOCK：解析参数 → 拉起 GuardService → beginGuardSession（BootReceiver 同链路） */
    private fun handleLock(context: Context, intent: Intent) {
        val bssid = intent.getStringExtra("bssid")?.trim()?.uppercase()
        val ssid = intent.getStringExtra("ssid")?.trim() ?: ""
        val password = intent.getStringExtra("password")  // null/缺省 = 开放网络
        val mode = intent.getStringExtra("mode")?.trim()?.lowercase()  // sug / spec，缺省=按当前厂商默认

        if (bssid.isNullOrBlank() || !bssid.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))) {
            ack(context, "LOCK-REJECT reason=bad_bssid bssid=${bssid ?: "null"}")
            return
        }
        if (ssid.isBlank()) {
            ack(context, "LOCK-REJECT reason=missing_ssid")
            return
        }

        // mode 显式指定时切模式（并持久化，与手动切换同语义）；缺省用当前/厂商默认
        if (mode == "sug" || mode == "spec") {
            val sug = (mode == "sug")
            if (sug != GuardState.useSuggestion) {
                GuardState.useSuggestion = sug
                context.getSharedPreferences("bsc_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("mode_suggestion", sug).apply()
            }
        } else if (!context.getSharedPreferences("bsc_prefs", Context.MODE_PRIVATE)
                .contains("mode_suggestion")
        ) {
            // 从未手动设置过 → 按厂商默认（与 MainActivity.loadModePref 同逻辑）
            GuardState.useSuggestion = isBbkFamily()
        }

        val secure = if (password == null) "OPEN" else "WPA2"
        val ap = ApItem(
            bssid = bssid,
            ssid = ssid,
            level = 0,
            frequency = 0,
            channel = 0,
            band = if (intent.getStringExtra("band").isNullOrBlank()) "" else intent.getStringExtra("band")!!,
            secure = secure,
            isWifi6Plus = false
        )

        // 拉起前台服务并下发会话。
        // 注意：BroadcastReceiver 里不能 bindService（ReceiverCallNotAllowedException），
        // 也不能依赖 binder——直接 GuardService.start 拉起前台服务，服务 onStartCommand
        // 收到 ACTION_SESSION intent 后自行 beginGuardSession（服务是唯一执行者，无竞态）。
        val sessionIntent = Intent(context, GuardService::class.java)
            .setAction(ACTION_SESSION)
            .putExtra("ssid", ssid)
            .putExtra("bssid", bssid)
            .putExtra("password", password)
            .putExtra("band", ap.band)
            .putExtra("secure", secure)
        context.startForegroundService(sessionIntent)
        ack(context, "LOCK-ACCEPT bssid=$bssid ssid=$ssid mode=${if (GuardState.useSuggestion) "sug" else "spec"} pw=${password != null}")
    }

    /** UNLOCK：清方案/停服务/清建议+请求（与 UI 解锁按钮同语义） */
    private fun handleUnlock(context: Context) {
        try {
            LockPlanStorage.clear(context)
            // 清建议网络（sug 模式的连接依附于建议）
            try {
                val wm = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                wm.removeNetworkSuggestions(emptyList())
            } catch (_: Exception) {
            }
            GuardState.target = null
            GuardState.password = null
            GuardState.connectedBssid = null
            GuardState.uiMsg = "已通过 ADB 解锁"
            GuardService.stop(context)
            ack(context, "UNLOCK-OK")
        } catch (e: Exception) {
            ack(context, "UNLOCK-ERROR ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** STATUS：回执当前状态（有序广播可同步读；普通广播读 diag/ACK） */
    private fun handleStatus(context: Context) {
        val t = GuardState.target
        val msg = if (t == null) {
            "STATUS idle (no target)"
        } else {
            "STATUS target=${t.bssid} ssid=${t.ssid} mode=${if (GuardState.useSuggestion) "sug" else "spec"} " +
                "connected=${GuardState.connectedBssid ?: "null"} linkOk=${GuardState.linkOk} " +
                "ui=${GuardState.uiMsg ?: "-"}"
        }
        ack(context, msg)
    }

    /** vivo/OPPO 系厂商判定（与 MainActivity.loadModePref 一致） */
    private fun isBbkFamily(): Boolean {
        val m = android.os.Build.MANUFACTURER ?: return false
        return listOf("vivo", "bbk", "oppo", "oplus", "oneplus", "realme", "iqoo").any { m.contains(it, true) }
    }

    /**
     * 统一回执：① setResult（`am broadcast` 配合 --receiver-permission 或有序广播可见）
     * ② 落 diag.txt（ADB-ACK 前缀，可 run-as 读取）③ 发 ACK 广播（监听方可再收一条）
     * ④ Log.w（logcat 可见）
     */
    private fun ack(context: Context, msg: String) {
        android.util.Log.w("BSC-ADB", msg)
        try {
            java.io.File(context.filesDir, "diag.txt")
                .appendText("${android.os.SystemClock.elapsedRealtime()}|ADB|$msg\n")
        } catch (_: Exception) {
        }
        try {
            val i = Intent(ACTION_ACK).setPackage(context.packageName).putExtra(EXTRA_ACK, msg)
            context.sendBroadcast(i)
        } catch (_: Exception) {
        }
        resultCode = android.app.Activity.RESULT_OK
        resultData = msg
    }
}
