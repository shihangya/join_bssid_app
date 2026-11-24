package com.dev.bssidconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast

/**
 * v14 前台服务常驻守护（功能1）+ 目标消失告警（功能2）。
 *
 * 设计背景：watchdog/nudge 原本跑在 Activity 里，用户退后台/锁屏后进程随时可能被
 * 厂商 ROM 杀掉（vivo/ColorOS 激进省电），锁定就中断——BootReceiver 又被 ColorOS
 * SKIPPED（remote app），恢复链路是断的。前台服务持有 CPU + 进程优先级，
 * 守护可以 7×24 运行；常驻通知即状态载体（锁定中/漂移/目标消失）。
 *
 * 状态所有权：GuardState 单例持有全部守护状态（target/password/relock 计数等），
 * Activity 与 Service 共享同一份；Activity 只负责 UI 展示与用户发起，服务负责执行。
 * 服务被系统重启（START_STICKY）或 Activity 重建时，状态不丢。
 */
class GuardService : Service() {

    companion object {
        const val CHANNEL_GUARD = "guard"
        const val CHANNEL_ALERT = "alert"
        const val NOTIF_GUARD_ID = 1001
        const val NOTIF_ALERT_ID = 1002
        const val ACTION_STOP = "com.dev.bssidconnect.STOP_GUARD"

        /** 便捷启动/停止入口 */
        fun start(ctx: Context) {
            val i = Intent(ctx, GuardService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GuardService::class.java))
        }

        /** diag.txt 大小上限（256KB）：超过即截断保留尾部一半，防止无限增长 */
        const val DIAG_MAX_BYTES = 256L * 1024

        /**
         * v14c：带轮转的 diag 追加写。超过 DIAG_MAX_BYTES 时只保留后半段
         * （守护每 2s 写一条 WD，长时间运行会无限增长；截断保留最近日志足够排障）
         */
        fun appendDiagRotated(f: java.io.File, line: String) {
            try {
                if (f.length() > DIAG_MAX_BYTES) {
                    val all = f.readText()
                    val keep = all.substring(all.length / 2, all.length)
                    f.writeText("…[rotated，丢弃较早一半日志]…\n$keep")
                }
                f.appendText(line)
            } catch (_: Exception) {
            }
        }
    }

    private lateinit var wm: WifiManager
    private lateinit var cm: ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun service(): GuardService = this@GuardService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ==== 守护参数（与 v6/v10 语义一致） ====
    private val watchdogIntervalMs = 2000L
    private val maxRelock = 5
    private val stableResetMs = 10000L
    private val relockTimeoutMs = 30000L
    // 功能2：目标消失判定 = 连续 missMinutes 分钟 (1) 不在目标上 且 (2) 扫描列表里也找不到目标 BSSID
    private val missMinutes = 3L
    private val missThresholdMs = missMinutes * 60_000L

    // ==== v15 功能4：断流检测（探活守护）参数 ====
    /** 探活间隔（watchdog 心跳里做，等效 2s 一次太密；独立计时器 5s 一次） */
    private val probeIntervalMs = 5000L
    /** 连续失败 N 次判定断流（=25s 无回包） */
    private val stallFailLimit = 5
    /** 恢复后连续 N 次成功判定恢复（防抖） */
    private val stallOkLimit = 2

    private var relockCount = 0
    private var relocking = false
    private var relockStartElapsed = 0L
    private var driftStrikes = 0
    private var matchSinceElapsed = 0L
    private var lastGoodBssid: String? = null
    private var lastSeenMissingSince = 0L    // 连续不在目标上的起点（0=在目标上）
    private var alertFired = false           // 消失告警只发一次，恢复后重置
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val nudgeHandler = Handler(Looper.getMainLooper())
    private val nudgeSteps = longArrayOf(12000L, 24000L, 36000L)

    // ==== v15 功能4：断流检测状态 ====
    private lateinit var probeHandler: Handler
    private var stallFails = 0
    private var stallOks = 0
    private var stallAlerted = false
    /** 最近一次探活延迟 ms（null=未知/不在目标上） */
    @Volatile var lastRttMs: Long? = null
    private var probeRunning = false
    private var probeTargetBssid: String? = null
    private var probeThread: HandlerThread? = null

    private fun diag(msg: String) {
        android.util.Log.w("BSC", "SVC|$msg")
        try {
            appendDiagRotated(java.io.File(filesDir, "diag.txt"),
                "${SystemClock.elapsedRealtime()}|SVC|$msg\n")
        } catch (_: Exception) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_GUARD, "锁定守护", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "目标告警", NotificationManager.IMPORTANCE_HIGH)
        )
        // v15 功能4：探活后台线程
        probeThread = HandlerThread("bsc-probe").also { it.start() }
        probeHandler = Handler(probeThread!!.looper)
        diag("SVC-CREATED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // v17：AdbCommandReceiver 经 startForegroundService 下发的远程锁定会话
        if (intent?.action == AdbCommandReceiver.ACTION_SESSION) {
            startForeground(NOTIF_GUARD_ID, buildGuardNotification("启动中…"))
            val ssid = intent.getStringExtra("ssid") ?: ""
            val bssid = intent.getStringExtra("bssid") ?: ""
            val password = intent.getStringExtra("password")
            val band = intent.getStringExtra("band") ?: ""
            val secure = intent.getStringExtra("secure") ?: "OPEN"
            if (bssid.isNotBlank() && ssid.isNotBlank()) {
                val ap = ApItem(
                    bssid = bssid, ssid = ssid, level = 0, frequency = 0,
                    channel = 0, band = band, secure = secure, isWifi6Plus = false
                )
                beginGuardSession(ap, password)
                diag("ADB-SESSION via intent bssid=$bssid mode=${if (GuardState.useSuggestion) "sug" else "spec"}")
            }
            return START_STICKY
        }
        startForeground(NOTIF_GUARD_ID, buildGuardNotification("启动中…"))
        // START_STICKY：进程被杀后系统尝试重建服务，配合 GuardState 持久状态自动续守护
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, watchdogIntervalMs)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        nudgeHandler.removeCallbacksAndMessages(null)
        stopProbe()
        probeThread?.quitSafely()
        probeThread = null
        releaseCallback()
        diag("SVC-DESTROYED relock=$relockCount")
        super.onDestroy()
    }

    // ==== 守护循环（从 MainActivity 迁移，判定逻辑不变） ====
    private val watchdog = object : Runnable {
        override fun run() {
            val t = GuardState.target ?: run {
                diag("WD-IDLE stop (target=null)")
                updateGuardNotification("空闲（无锁定目标）")
                return
            }
            val cur = currentBssid()
            diag("WD target=${t.bssid} cur=$cur strikes=$driftStrikes relock=$relockCount relocking=$relocking")

            // 功能2：目标消失检测（relocking 期间不算，等超时路径处理）
            if (!relocking) {
                if (cur != null && cur.equals(t.bssid, true)) {
                    lastSeenMissingSince = 0L
                    if (alertFired) {
                        alertFired = false
                        diag("ALERT-RECOVER back on target")
                        notifyGuardBack(t)
                    }
                } else {
                    if (lastSeenMissingSince == 0L) lastSeenMissingSince = SystemClock.elapsedRealtime()
                    val missingMs = SystemClock.elapsedRealtime() - lastSeenMissingSince
                    if (!alertFired && missingMs >= missThresholdMs && !targetVisibleInScan(t)) {
                        alertFired = true
                        diag("ALERT-FIRE missingMs=$missingMs scan=absent")
                        notifyTargetGone(t)
                    }
                }
            }

            if (relocking) {
                if (relockElapsedMs() > relockTimeoutMs) {
                    diag("RELOCK-TIMEOUT elapsed=${relockElapsedMs()}ms cur=$cur")
                    relocking = false
                    onDriftDetected(cur ?: "unknown")
                }
            } else {
                when {
                    cur == null -> {
                        driftStrikes = 0; matchSinceElapsed = 0L
                        // 连接丢失：清连接态，UI 按钮"已连接"→"连接"
                        if (GuardState.connectedBssid != null) {
                            GuardState.connectedBssid = null
                            GuardState.uiMsg = "连接已断开，守护等待重连 ${t.bssid}…"
                        }
                    }
                    cur.equals(t.bssid, true) -> {
                        lastGoodBssid = cur
                        // 连接成功后刷新 UI：connectedBssid 变化会被 MainActivity.uiSync
                        // 轮询捕获并重绑列表（按钮 连接→已连接），状态行同步刷新为"已连接"
                        if (!GuardState.connectedBssid.equals(t.bssid, true)) {
                            GuardState.connectedBssid = t.bssid
                            diag("SVC-CONNECTED cur==target relock=$relockCount rssi=${currentRssi()}")
                        }
                        // v14c：RSSI/IP 实时刷新——每个心跳都重写 uiMsg（MainActivity uiSync 2s 轮询渲染）
                        // v15：+ 链路速率 + 探活延迟
                        GuardState.uiMsg = "已连接 ${t.bssid}（${t.band}${rssiSuffix()}${linkSpeedSuffix()}${rttSuffix()}，防漂移守护中）${ipSuffix()}"
                        if (matchSinceElapsed == 0L) matchSinceElapsed = SystemClock.elapsedRealtime()
                        if (relockCount > 0 &&
                            SystemClock.elapsedRealtime() - matchSinceElapsed >= stableResetMs
                        ) {
                            diag("STABLE-RESET stableMs=${SystemClock.elapsedRealtime() - matchSinceElapsed} relock=$relockCount->0")
                            relockCount = 0
                        }
                        driftStrikes = 0
                    }
                    else -> {
                        matchSinceElapsed = 0L
                        driftStrikes++
                        // 漂移：清连接态（按钮还原"连接"），进入重锁流程
                        if (!GuardState.connectedBssid.equals(cur, true) && GuardState.connectedBssid != null) {
                            GuardState.connectedBssid = null
                            GuardState.uiMsg = "漂移检测：当前 $cur，正在重新锁定 ${t.bssid}…"
                        }
                        if (driftStrikes >= 2) onDriftDetected(cur)
                    }
                }
            }
            updateGuardNotification(
                if (relocking) "正在重新锁定 ${t.bssid}…"
                else if (cur != null && cur.equals(t.bssid, true)) "已锁定 ${t.bssid}（${t.band}${rssiSuffix()}${linkSpeedSuffix()}${rttSuffix()}）${ipSuffix()}"
                else "漂移检测中：当前 $cur，目标 ${t.bssid}"
            )
            // v15 功能4：探活只在锁定成功时跑（onTarget==true）；漂移/无连接时停
            if (cur != null && cur.equals(t.bssid, true)) ensureProbeRunning(t.bssid)
            else stopProbe()
            handler.postDelayed(this, watchdogIntervalMs)
        }
    }

    private fun relockElapsedMs(): Long = SystemClock.elapsedRealtime() - relockStartElapsed

    private fun onDriftDetected(actualBssid: String) {
        val t = GuardState.target ?: return
        diag("DRIFT actual=$actualBssid target=${t.bssid} relock=$relockCount mode=${if (GuardState.useSuggestion) "sug" else "spec"}")
        driftStrikes = 0
        stopNudge()
        if (relockCount >= maxRelock) {
            // 放弃：清状态、停服务（通知移除由系统完成）
            diag("GIVE-UP relock=$relockCount")
            GuardState.target = null
            GuardState.connectedBssid = null   // UI 按钮"已连接"还原为"连接"
            if (GuardState.useSuggestion) removeAllSuggestions()
            LockPlanStorage.clear(this)
            stopProbe()
            GuardState.uiMsg = "放弃锁定：系统反复把连接迁至 $actualBssid（已重锁 $maxRelock 次）"
            stopSelf()
            return
        }
        relockCount++
        relocking = false
        GuardState.uiMsg = "检测到漫游漂移（漂至 $actualBssid）→ 重新锁定 ${t.bssid}（第 $relockCount/$maxRelock 次）"
        if (GuardState.useSuggestion) {
            removeAllSuggestions()
            handler.postDelayed({ submitSuggestion(t, GuardState.password) }, 800L)
        } else {
            releaseCallback()
            requestLockedNetwork(t, GuardState.password)
        }
    }

    // ==== 连接发起（specifier / suggestion） ====
    /** 由 Activity 或本服务内部触发一次完整的锁定会话（重置计数、发连接、启守护） */
    fun beginGuardSession(ap: ApItem, password: String?) {
        GuardState.target = ap
        GuardState.password = password
        GuardState.connectedBssid = null   // 新会话发起：旧连接态清除（按钮"已连接"→"连接"）
        relockCount = 0
        relocking = false
        driftStrikes = 0
        matchSinceElapsed = 0L
        lastSeenMissingSince = 0L
        alertFired = false
        stopProbe()   // v15：新会话先停旧探活（连接成功后 watchdog 会重新拉起）
        // 功能3：方案落盘
        LockPlanStorage.save(
            this,
            LockPlan(
                ssid = ap.ssid, bssid = ap.bssid, band = ap.band, secure = ap.secure,
                useSuggestion = GuardState.useSuggestion
            ),
            password
        )
        diag("SVC-SESSION bssid=${ap.bssid} mode=${if (GuardState.useSuggestion) "sug" else "spec"}")
        if (GuardState.useSuggestion) {
            releaseCallback()
            removeAllSuggestions()
            handler.postDelayed({ submitSuggestion(ap, password) }, 800L)
            GuardState.uiMsg = "正在提交建议网络 ${ap.bssid}…"
        } else {
            releaseCallback()
            requestLockedNetwork(ap, password)
        }
        // 确保服务在前台运行（Activity 已 start 过则幂等）
        start(this)
    }

    private fun requestLockedNetwork(ap: ApItem, password: String?) {
        relocking = true
        relockStartElapsed = SystemClock.elapsedRealtime()
        val sb = WifiNetworkSpecifier.Builder()
            .setBssid(MacAddress.fromString(ap.bssid))
        if (ap.ssid.isNotBlank() && ap.ssid != "(隐藏网络)") sb.setSsid(ap.ssid)
        // setBand 在 vivo 上 13ms 同步拒绝（A/B 实测），禁用（见 MainActivity 同注释）
        if (password != null) {
            if (ap.secure == "WPA3") sb.setWpa3Passphrase(password)
            else sb.setWpa2Passphrase(password)
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(sb.build())
            .build()
        diag("SVC-REQ relock=$relockCount bssid=${ap.bssid}")
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                diag("SVC-onAvailable net=$network relock=$relockCount")
                relocking = false
                stopNudge()
                GuardState.connectedBssid = ap.bssid
                GuardState.uiMsg = "已连接 ${ap.bssid}（${ap.band}${rssiSuffix()}，防漂移守护中）${ipSuffix()}"
            }

            override fun onUnavailable() {
                diag("SVC-onUnavailable relock=$relockCount")
                // 服务内会话失败：清目标停守护（Activity 在前台时收到 uiMsg 更新界面）
                if (GuardState.target != null) {
                    relocking = false
                    stopNudge()
                    GuardState.target = null
                    GuardState.connectedBssid = null
                    LockPlanStorage.clear(this@GuardService)
                    GuardState.uiMsg =
                        if (relockCount > 0) "重新锁定失败（被取消/超时），已停止守护"
                        else "连接失败：用户取消 / 密码错误 / 超时"
                    stopSelf()
                }
            }
        }
        networkCallback = cb
        cm.requestNetwork(request, cb)
    }

    private fun submitSuggestion(ap: ApItem, password: String?) {
        diag("SVC-SUG-REQ bssid=${ap.bssid} pw=${password != null}")
        val b = WifiNetworkSuggestion.Builder()
            .setSsid(ap.ssid)
            .setBssid(MacAddress.fromString(ap.bssid))
        if (Build.VERSION.SDK_INT >= 31) b.setIsInitialAutojoinEnabled(true)
        if (password != null) {
            if (ap.secure == "WPA3") b.setWpa3Passphrase(password)
            else b.setWpa2Passphrase(password)
        }
        val code = wm.addNetworkSuggestions(listOf(b.build()))
        diag("SVC-SUG-RESULT code=$code")
        if (code == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            GuardState.uiMsg = "建议已提交：${ap.bssid}（等待系统自动连入，守护中）${ipSuffix()}"
            startNudge()
        } else {
            GuardState.uiMsg = "建议网络提交失败：code=$code"
            stopSelf()
        }
    }

    private fun removeAllSuggestions() {
        try {
            wm.removeNetworkSuggestions(emptyList())
            diag("SVC-SUG-CLEARED")
        } catch (e: Exception) {
            diag("SVC-SUG-CLEAR-FAIL ${e.message}")
        }
    }

    // ==== 功能1：nudge 阶梯（唤醒选网器） ====
    private fun startNudge() {
        stopNudge()
        if (!GuardState.useSuggestion) return
        for ((i, delay) in nudgeSteps.withIndex()) {
            nudgeHandler.postDelayed({
                val t = GuardState.target ?: return@postDelayed
                if (currentBssid()?.equals(t.bssid, true) == true) return@postDelayed
                diag("SVC-NUDGE step=$i target=${t.bssid}")
                if (i == 1) {
                    try { wm.startScan() } catch (_: Exception) {}
                } else {
                    removeAllSuggestions()
                    handler.postDelayed({
                        GuardState.target?.let { submitSuggestion(it, GuardState.password) }
                    }, 500L)
                }
            }, delay)
        }
        diag("SVC-NUDGE-ARMED")
    }

    private fun stopNudge() {
        nudgeHandler.removeCallbacksAndMessages(null)
    }

    // ==== 环境读取 ====
    private fun currentBssid(): String? = try {
        val b = wm.connectionInfo?.bssid
        if (b != null && b != "02:00:00:00:00:00") b else null
    } catch (_: Exception) {
        null
    }

    /** v14c：当前连接 RSSI（dBm，拿不到返回 null）——供状态行/通知实时显示 */
    private fun currentRssi(): Int? = try {
        wm.connectionInfo?.rssi?.takeIf { it in -100..0 }
    } catch (_: Exception) {
        null
    }

    private fun ipSuffix(): String = try {
        var found: String? = null
        for (network in cm.allNetworks) {
            val ni = cm.getNetworkCapabilities(network) ?: continue
            if (!ni.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val ip = cm.getLinkProperties(network)?.linkAddresses
                ?.firstOrNull { it.address is java.net.Inet4Address }
                ?.address?.hostAddress
            if (ip != null) { found = ip; break }
        }
        found?.let { "，IP：$it" } ?: ""
    } catch (_: Exception) {
        ""
    }

    /** v14c：RSSI 后缀（"，-28 dBm"，前导逗号拼在 band 后；拿不到返回空，避免双逗号） */
    private fun rssiSuffix(): String = currentRssi()?.let { "，$it dBm" } ?: ""

    /**
     * v15 功能5：链路速率后缀（"，154 Mbps"）——WifiInfo.linkSpeed（单位 Mbps），
     * 拿不到/为 -1 时返回空串避免双逗号。
     */
    private fun linkSpeedSuffix(): String = try {
        val mbps: Int = wm.connectionInfo?.linkSpeed ?: -1
        if (mbps > 0) "，$mbps Mbps" else ""
    } catch (_: Exception) {
        ""
    }

    /** v15 功能4：状态行的延迟后缀（"，延迟 3ms"；无数据返回空） */
    private fun rttSuffix(): String = lastRttMs?.let { "，延迟 ${it}ms" } ?: ""

    /** 功能2：目标 BSSID 是否还出现在扫描结果里（消失判定的第二条件） */
    private fun targetVisibleInScan(t: ApItem): Boolean = try {
        // v14b：结果带时间戳过滤——超过 90s 的陈旧缓存不算"可见"（wifi 关闭/系统停扫时缓存不刷新，
        // 会导致目标消失告警永不触发）
        val now = SystemClock.elapsedRealtimeNanos()
        wm.scanResults.any {
            it.BSSID.equals(t.bssid, true) &&
                now - it.timestamp < 90_000_000_000L  // 90s，纳秒
        }
    } catch (_: Exception) {
        // 拿不到扫描结果时保守起见视为可见，避免误报
        true
    }

    private fun releaseCallback() {
        networkCallback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
    }

    // ==== 通知 ====
    private fun buildGuardNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_GUARD) else Notification.Builder(this)
        return b
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BssidConnect 守护中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateGuardNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIF_GUARD_ID, buildGuardNotification(text))
        } catch (_: Exception) {
        }
    }

    /** 功能2：目标消失告警（高优先级通道，横幅+通知栏） */
    private fun notifyTargetGone(t: ApItem) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ALERT) else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("目标 AP 已离开环境")
            .setContentText("${t.ssid} ${t.bssid} 连续 $missMinutes 分钟不可达且扫描不到，守护继续等待其回归")
            .setAutoCancel(true)
            .setContentIntent(pi)
        try {
            nm.notify(NOTIF_ALERT_ID, b.build())
            diag("ALERT-NOTIFIED ${t.bssid}")
        } catch (e: Exception) {
            diag("ALERT-FAIL ${e.message}")
        }
    }

    private fun notifyGuardBack(t: ApItem) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.cancel(NOTIF_ALERT_ID)
            updateGuardNotification("已恢复锁定 ${t.bssid}")
        } catch (_: Exception) {
        }
    }

    // ==== v15 功能4：断流检测（探活守护） ====
    // 盲区补齐：守护原本只看"BSSID 在不在"——连着但 ping 不通（AP 假活/DHCP 掉了）不告警。
    // 探活线程每 5s ping 一次网关；连续 stallFailLimit 次失败 → 高优告警"连接存在但无数据"，
    // 恢复 stallOkLimit 次连续成功 → 解除。断流判定只在锁定目标上时进行（漂移中交给 DRIFT 逻辑）。

    /** 找当前 Wi-Fi 网络的网关 IP：routes IPv4 gateway（跳过 0.0.0.0）→ DHCP 服务器 → 本机 IP 推 .1 */
    private fun currentGateway(): java.net.InetAddress? {
        var found: java.net.InetAddress? = null
        try {
            for (network in cm.allNetworks) {
                val ni = cm.getNetworkCapabilities(network) ?: continue
                if (!ni.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val lp = cm.getLinkProperties(network) ?: continue
                for (r in lp.routes) {
                    val gw = r.gateway ?: continue
                    if (gw is java.net.Inet4Address && !gw.isAnyLocalAddress && gw.hostAddress != "0.0.0.0") { found = gw; break }
                }
                if (found != null) break
            }
            if (found == null) {
                for (network in cm.allNetworks) {
                    val ni = cm.getNetworkCapabilities(network) ?: continue
                    if (!ni.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                    val lp = cm.getLinkProperties(network) ?: continue
                    val d = lp.dhcpServerAddress
                    if (d != null && d is java.net.Inet4Address && !d.isAnyLocalAddress) { found = d; break }
                }
            }
            if (found == null) {
                val ip = currentIp()
                if (ip != null) {
                    val dot = ip.lastIndexOf(46.toChar())
                    if (dot > 0) found = java.net.InetAddress.getByName(ip.substring(0, dot) + ".1")
                }
            }
        } catch (_: Exception) {}
        return found
    }

    /** 当前 Wi-Fi 的 IPv4 地址（网关回退推 .1 用） */
    private fun currentIp(): String? = try {
        var found: String? = null
        for (network in cm.allNetworks) {
            val ni = cm.getNetworkCapabilities(network) ?: continue
            if (!ni.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val lp = cm.getLinkProperties(network) ?: continue
            for (la in lp.linkAddresses) {
                val a = la.address
                if (a is java.net.Inet4Address) { found = a.hostAddress; break }
            }
            if (found != null) break
        }
        found
    } catch (_: Exception) { null }

    /** 当前 Wi-Fi 网络（探活绑定用；无则 null） */
    private fun currentWifiNetwork(): Network? = try {
        cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    } catch (_: Exception) {
        null
    }

    /** 探活后台线程体：ping 配置目标（默认网关回退链）（绑定网络）→ 回主线程判定 */
    private fun probeLoop(targetBssid: String) {
        val cfg = ProbeConfig.target(this)
        val gw = if (cfg.isNotEmpty()) try { java.net.InetAddress.getByName(cfg) } catch (_: Exception) { currentGateway() } else currentGateway()
        val net = currentWifiNetwork()
        val t0 = SystemClock.elapsedRealtime()
        var ok = false
        if (gw != null && net != null) {
            try {
                // icmp 用 InetAddress.isReachable（内部走 ICMP/回显，需要 NAT 时回退 TCP 7 端口）
                ok = gw.isReachable(1500)
            } catch (_: Exception) {
                ok = false
            }
            // ICMP 被系统限制时（Android 上常见），回退 TCP 80 connect 探测
            if (!ok) {
                var sock: java.net.Socket? = null
                try {
                    sock = java.net.Socket()
                    net.bindSocket(sock)
                    sock.connect(java.net.InetSocketAddress(gw, 80), 1500)
                    ok = true
                } catch (_: Exception) {
                    ok = false
                } finally {
                    try { sock?.close() } catch (_: Exception) {}
                }
            }
        }
        val rtt = if (ok) SystemClock.elapsedRealtime() - t0 else null
        handler.post { onProbeResult(ok, rtt, targetBssid) }
    }

    /** 主线程判定：连续失败→告警；恢复→解除；更新 lastRttMs */
    private fun onProbeResult(ok: Boolean, rttMs: Long?, targetBssid: String) {
        val t = GuardState.target ?: return
        if (!GuardState.connectedBssid.equals(targetBssid, true)) return  // 已漂移/断开：探活结果不判
        // v16：趋势采样（RSSI + RTT，最多 360 点）
        run {
            GuardState.samples.addLast(ProbeSample(System.currentTimeMillis(), currentRssi(), rttMs))
            while (GuardState.samples.size > 360) GuardState.samples.removeFirst()
        }
        if (ok) {
            lastRttMs = rttMs
            GuardState.linkOk = true
            stallFails = 0
            stallOks++
            if (stallAlerted && stallOks >= stallOkLimit) {
                stallAlerted = false
                diag("STALL-RECOVER rtt=${rttMs}ms")
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                try { nm.cancel(NOTIF_ALERT_ID) } catch (_: Exception) {}
                GuardState.uiMsg = "网络已恢复（${rttMs}ms）${t.bssid}${rssiSuffix()}${linkSpeedSuffix()}${rttSuffix()}${ipSuffix()}"
            }
        } else {
            lastRttMs = null
            GuardState.linkOk = false
            stallOks = 0
            stallFails++
            if (!stallAlerted && stallFails >= stallFailLimit) {
                stallAlerted = true
                diag("STALL-FIRE fails=$stallFails gw_unreachable")
                notifyStall(t)
            }
        }
    }

    /** 断流告警（高优通道，横幅） */
    private fun notifyStall(t: ApItem) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ALERT) else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("连接存在但无数据")
            .setContentText("${t.ssid} ${t.bssid} 网关连续 $stallFailLimit 次探测失败（约 ${stallFailLimit * 5}s），网络可能断流")
            .setAutoCancel(true)
            .setContentIntent(pi)
        try {
            nm.notify(NOTIF_ALERT_ID, b.build())
            diag("ALERT-NOTIFIED stall ${t.bssid}")
        } catch (e: Exception) {
            diag("ALERT-FAIL stall ${e.message}")
        }
    }

    /** 确保探活循环在跑（目标切换时重启线程绑定新网关） */
    private fun ensureProbeRunning(targetBssid: String) {
        if (probeRunning && probeTargetBssid == targetBssid) return
        stopProbe()
        probeTargetBssid = targetBssid
        probeRunning = true
        stallFails = 0
        stallOks = 0
        stallAlerted = false
        probeHandler.post(object : Runnable {
            override fun run() {
                if (!probeRunning) return
                val tb = probeTargetBssid ?: return
                probeLoop(tb)
                probeHandler.postDelayed(this, probeIntervalMs)
            }
        })
        diag("PROBE-START target=$targetBssid interval=${probeIntervalMs}ms")
    }

    private fun stopProbe() {
        probeRunning = false
        probeTargetBssid = null
        lastRttMs = null
        GuardState.linkOk = null
        stallFails = 0
        stallOks = 0
        if (stallAlerted) {
            stallAlerted = false
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF_ALERT_ID)
            } catch (_: Exception) {}
        }
    }
}
