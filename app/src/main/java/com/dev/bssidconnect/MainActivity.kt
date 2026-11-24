package com.dev.bssidconnect

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.EditText

class MainActivity : AppCompatActivity() {

    companion object {
        // 诊断日志 tag：vivo 裁剪了 Wi-Fi 框架日志（logcat 里看不到 WifiService/WifiClientModeImpl），
        // 只能靠 App 自身日志观察 specifier 请求生命周期与实际 BSSID 变化。
        private const val TAG = "BSC"
    }

    /**
     * 开机自启：恢复锁定方案（功能3）。
     * 应用层直接持有 WifiManager/ConnectivityManager 即可执行锁定逻辑，无需 UI；
     * Toast 仅用于可观测性（BootReceiver 里弹 Toast 需 BACKGROUND 特权，Android 12+ 可能不显示，
     * 连接动作本身不受影响）。
     */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
            // goAsync 保活：onReceive 返回后进程不会被立刻杀，直到 finish()
            val pending = goAsync()
            try {
                LockEngine.logBoot(context)
                val plan = LockPlanStorage.load(context)
                if (plan == null) {
                    LockEngine.logBoot(context, "BOOT-NO-PLAN")
                    return
                }
                val appCtx = context.applicationContext
                val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val pw = LockPlanStorage.loadPassword(context)
                try {
                    Toast.makeText(appCtx, "BssidConnect：恢复锁定 ${plan.bssid}", Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                }
                // v14：恢复动作交给前台服务承载（守护+告警持续运行），无 UI
                GuardState.useSuggestion = plan.useSuggestion
                GuardState.target = ApItem(
                    bssid = plan.bssid, ssid = plan.ssid, level = 0,
                    frequency = 0, channel = 0, band = plan.band, secure = plan.secure,
                    isWifi6Plus = false
                )
                GuardState.password = pw
                val svcIntent = Intent(appCtx, GuardService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 26) appCtx.startForegroundService(svcIntent)
                else appCtx.startService(svcIntent)
                // 服务 onStartCommand 后由 beginGuardSession 发起连接（等 bind 或直接再发一次）
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val wm2 = appCtx.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                        val cm2 = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                        // 服务可能尚未 bind——直接用 LockEngine 一次性提交兜底，服务接管后自动覆盖
                        if (plan.useSuggestion) {
                            LockEngine.submitSuggestion(wm2, plan, pw, appCtx)
                        } else {
                            LockEngine.requestLockedNetwork(cm2, plan, pw, appCtx)
                        }
                    } catch (t: Throwable) {
                        LockEngine.logBoot(context, "BOOT-FALLBACK-ERR ${t.message}")
                    }
                }, 1500L)
                LockEngine.logBoot(context, "BOOT-RESTORE-DONE bssid=${plan.bssid} mode=${if (plan.useSuggestion) "sug" else "spec"}")
            } catch (t: Throwable) {
                LockEngine.logBoot(context, "BOOT-ERROR ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    // 双写诊断：vivo 会丢弃本 App 的 logcat 输出（REQ 已执行但 logcat 零记录），
    // 因此同时落盘到私有文件，用 run-as 读取。
    private fun diag(msg: String) {
        Log.w(TAG, msg)
        try {
            GuardService.appendDiagRotated(java.io.File(filesDir, "diag.txt"),
                "${SystemClock.elapsedRealtime()}|$msg\n")
        } catch (_: Exception) {
        }
    }

    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var adapter: NetworkAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var tvStatus: TextView
    private lateinit var tvPerm: TextView
    private lateinit var progressScan: ProgressBar
    private lateinit var btnMode: TextView

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastScanAt = 0L
    /** v14c：系统扫描限流窗口（Android 4 次/2min = 240s），用于限流提示计算剩余等待 */
    private val SCAN_THROTTLE_MS = 240_000L
    /** v14c：本地提示（限流等）在状态行的保持截止时间——期间服务心跳/扫描刷新不覆盖 */
    private var localMsgHoldUntil = 0L

    // ==== v15 功能1：AP 列表搜索/过滤 ====
    private lateinit var etSearch: EditText
    /** 当前过滤关键字（小写；空=不过滤） */
    private var filterText: String = ""
    /** 最近一次完整扫描结果（过滤在此副本上做，避免反复读系统缓存） */
    private var lastResults: List<ScanResult> = emptyList()

    // ==== 功能2：厂商默认模式 + 模式持久化 ====
    // SharedPreferences("bsc_prefs") 键 mode_suggestion；无存储值时按厂商推断默认值。
    private fun loadModePref(): Boolean {
        val sp = getSharedPreferences("bsc_prefs", Context.MODE_PRIVATE)
        if (sp.contains("mode_suggestion")) return sp.getBoolean("mode_suggestion", false)
        val mfr = Build.MANUFACTURER ?: ""
        // vivo/OPPO 系（含一加 realme 等）：Specifier 的 BSSID 约束被无视（实测），
        // Suggestion 实测可锁 → 默认 Suggestion；
        // 其余（AOSP/华为/小米等）默认 Specifier（即时反馈 + 弹窗确认）。
        val isBbkFamily = mfr.contains("vivo", true) || mfr.contains("oppo", true) ||
            mfr.contains("oplus", true) || mfr.contains("realme", true) ||
            mfr.contains("oneplus", true)
        return isBbkFamily
    }

    // ==== BSSID 锁定 + 防漂移守护 ====
    // 背景：部分厂商 ROM（如 vivo 的 SET_5GROAMINGRATETH 5G 漫游优化）会在连接建立后
    // 按信号强度在同 SSID 内自动漫游，无视 WifiNetworkSpecifier 的 BSSID 约束，
    // 导致"指定 2.4G BSSID 却被迁到 5G AP"。对策：连接后周期校验实际 BSSID，
    // 检测到漂移就断开并重新发起带 BSSID 约束的 specifier 请求（次数封顶防死循环）。
    private var lockedTarget: ApItem? = null      // 用户选定的目标 AP
    private var lockedPassword: String? = null
    private var relockCount = 0                   // 已自动重锁次数
    private var relocking = false                 // 正在(重)建立连接，期间不做漂移判定
    private var driftStrikes = 0                  // 连续检出漂移次数（≥2 才认定，防瞬时误判）
    private val watchdogIntervalMs = 2000L
    private val maxRelock = 5
    // v6 预算语义：relockCount 只统计"连续失败"。目标 BSSID 稳定保持 stableResetMs
    // 后预算清零——vivo 平台初始连接可精确锁定目标（Suggestion 模式已实证），但系统
    // 周期性漫游（~16-60s 一次）会把连接迁走，守护每次都能拉回；若预算是终身制则
    // ~3 分钟即耗尽放弃，改为连续失败制后可长期维持锁定。
    private val stableResetMs = 10000L
    private var matchSinceElapsed = 0L
    /** 最近一次确认的当前 BSSID（connectionInfo 在部分 ROM 上闪烁返回 null/02:00…，用于状态行显示兜底） */
    private var lastGoodBssid: String? = null
    private var relockStartElapsed = 0L          // relocking 开始时间戳（SystemClock.elapsedRealtime）
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            val target = lockedTarget
            if (target == null) {
                // 无锁定任务（已放弃或尚未开始）：终止自调度循环
                diag("WD-IDLE stop (target=null)")
                return
            }
            val cur = currentBssid()
            diag("WD target=${target?.bssid} cur=$cur strikes=$driftStrikes relock=$relockCount relocking=$relocking")
            if (target != null) {
                if (relocking) {
                    // 请求挂起中：只做超时判定（如 vivo 选网器无视 BSSID 连了别的 AP，
                    // specifier 模式永不匹配，onAvailable 永远不来，靠超时触发重锁）
                    if (relockElapsedMs() > 30000L) {
                        diag("RELOCK-TIMEOUT elapsed=${relockElapsedMs()}ms cur=$cur")
                        relocking = false
                        onDriftDetected(cur ?: "unknown")
                    }
                } else {
                    when {
                        cur == null -> { driftStrikes = 0; matchSinceElapsed = 0L }
                        cur.equals(target.bssid, ignoreCase = true) -> {
                            lastGoodBssid = cur
                            if (matchSinceElapsed == 0L) matchSinceElapsed = SystemClock.elapsedRealtime()
                            if (relockCount > 0 &&
                                SystemClock.elapsedRealtime() - matchSinceElapsed >= stableResetMs
                            ) {
                                diag("STABLE-RESET stableMs=${SystemClock.elapsedRealtime() - matchSinceElapsed} relock=$relockCount->0")
                                relockCount = 0
                            }
                            if (driftStrikes > 0) {
                                setStatusRedIp("已锁定 ${target.bssid}（${target.band}，防漂移守护中）${ipSuffix()}", target.bssid, ipSuffix().removePrefix("，"))
                            }
                            driftStrikes = 0
                        }
                        else -> {
                            matchSinceElapsed = 0L
                            driftStrikes++
                            if (driftStrikes >= 2) onDriftDetected(cur)
                        }
                    }
                }
            }
            watchdogHandler.postDelayed(this, watchdogIntervalMs)
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) {
                tvPerm.visibility = View.GONE
                startScan()
            } else {
                tvPerm.visibility = View.VISIBLE
                Toast.makeText(this, "未授权，无法扫描 Wi-Fi", Toast.LENGTH_LONG).show()
            }
        }

    // v14：通知权限（Android 13+ 需运行时请求；消失告警与常驻守护通知依赖它）
    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            diag("NOTIF-PERM granted=$granted")
        }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)
                renderResults()
                progressScan.visibility = View.GONE
                if (!updated) {
                    // v14c：限流提示带剩余等待秒数（Android 限流窗口 4 次/2min，节流后需等窗口）
                    val remain = ((SCAN_THROTTLE_MS - (SystemClock.elapsedRealtime() - lastScanAt)) / 1000L).coerceAtLeast(0)
                    tvStatus.text = "扫描被系统限流（约 ${remain}s 后可再试），以下为缓存结果"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        recycler = findViewById(R.id.recycler)
        tvStatus = findViewById(R.id.tvStatus)
        tvPerm = findViewById(R.id.tvPerm)
        progressScan = findViewById(R.id.progressScan)
        btnMode = findViewById(R.id.btnMode)
        btnUnlock = findViewById(R.id.btnUnlock)
        etSearch = findViewById(R.id.etSearch)
        // v15b: 状态行整行点击兜底打开 ping 详情（IP 红字有 ClickableSpan，这里保证任何点按都能进）
        tvStatus.setOnClickListener { showPingDialog() }

        // 功能2：首次启动按厂商推断默认模式；已有选择则恢复
        useSuggestion = loadModePref()
        btnMode.text = if (useSuggestion) "模式:Suggestion" else "模式:Specifier"
        diag("MODE-INIT -> ${if (useSuggestion) "Suggestion" else "Specifier"} (pref or OEM default)")

        // 功能3：恢复上次锁定方案（杀进程/重启后自动重锁）。
        // v14：恢复动作直接交给 GuardService 前台服务承载（守护不随 Activity 生命周期中断）
        val savedPlan = LockPlanStorage.load(this)
        if (savedPlan != null) {
            btnUnlock.visibility = View.VISIBLE
            setStatusRedIp("恢复锁定方案：${savedPlan.bssid}（${savedPlan.band}）${ipSuffix()}", savedPlan.bssid, ipSuffix().removePrefix("，"))
            diag("PLAN-RESTORE bssid=${savedPlan.bssid} mode=${if (savedPlan.useSuggestion) "sug" else "spec"}")
            val ap = ApItem(
                bssid = savedPlan.bssid, ssid = savedPlan.ssid, level = 0,
                frequency = 0, channel = 0, band = savedPlan.band, secure = savedPlan.secure,
                isWifi6Plus = false
            )
            // 等 UI 稳定 + 服务就绪后由服务发起会话
            GuardState.useSuggestion = useSuggestion
            GuardService.start(this)
            bindGuardService()
            watchdogHandler.postDelayed({
                val pw = LockPlanStorage.loadPassword(this)
                guardBinder?.service()?.beginGuardSession(ap, pw)
                    ?: run {
                        diag("SVC-RESTORE-BIND-FAIL fallback local")
                        localFallbackConnect(ap, pw)
                    }
            }, 800L)
        }

        btnUnlock.setOnClickListener {
            // v14：解锁要同时停服务侧守护与本 Activity 遗留会话
            stopWatchdog()
            stopNudge()
            lockedTarget = null
            releaseCallback()
            removeAllSuggestions()
            clearLockPlan()
            GuardState.target = null
            GuardState.uiMsg = ""
            GuardState.connectedBssid = null
            GuardService.stop(this)
            tvStatus.text = "已解除锁定，恢复系统自动管理"
            Toast.makeText(this, "已解锁：清除锁定方案并停止守护", Toast.LENGTH_SHORT).show()
        }

        // v15 功能3：展开状态持久化（SharedPreferences-backed StringSet）
        val expandedStore = PrefsStringSet(this, "ui_state", "expanded_ssids")
        adapter = NetworkAdapter(emptyList(), onConnect = { ap -> onConnectClicked(ap) }, expandedStore = expandedStore)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // v15 功能1：搜索框——输入即过滤 SSID/BSSID（不区分大小写）
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                filterText = s?.toString()?.trim() ?: ""
                renderResults()
            }
        })

        findViewById<TextView>(R.id.btnScan).setOnClickListener { startScan() }
        tvPerm.setOnClickListener { requestNeededPermissions() }
        btnMode.setOnClickListener {
            useSuggestion = !useSuggestion
            btnMode.text = if (useSuggestion) "模式:Suggestion" else "模式:Specifier"
            // 功能2：模式持久化
            getSharedPreferences("bsc_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("mode_suggestion", useSuggestion).apply()
            diag("MODE -> ${if (useSuggestion) "Suggestion" else "Specifier"} (saved)")
            Toast.makeText(
                this,
                if (useSuggestion) "已切换 Suggestion 模式：提交系统建议网络（厂商处理可能不同）"
                else "已切换 Specifier 模式：即时请求连接",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        if (hasPermissions()) {
            tvPerm.visibility = View.GONE
            startScan()
        } else {
            requestNeededPermissions()
        }
        requestNotifPermissionIfNeeded()
        adapter.connectedBssid = currentBssid()
        // v14：UI 恢复时从服务同步状态文案（Activity 重建/回前台场景）
        syncUiFromGuardState()
        // 服务可能在 Activity 不在时被 GuardService.beginGuardSession 启动；
        // 若有锁定目标但服务未运行（极端情况），重新拉起
        if (GuardState.target != null) GuardService.start(this)
    }

    /** 把 GuardState 的最新文案渲染到状态行（保留红色渲染逻辑） */
    private fun syncUiFromGuardState() {
        // v14c：本地提示保持期内不覆盖（限流提示等）
        if (SystemClock.elapsedRealtime() < localMsgHoldUntil) return
        val t = GuardState.target
        val msg = GuardState.uiMsg
        if (t != null && msg.isNotEmpty()) {
            // 状态文案里的 BSSID 与 IP 部分标红（与本地渲染规则一致）
            val cur = currentBssid() ?: lastGoodBssid
            tvStatus.text = msg
            if (cur != null && cur.equals(t.bssid, true)) {
                setStatusRedIp(msg, t.bssid, currentIp()?.let { "IP：$it" } ?: "")
            } else {
                setStatusRedIp(msg, t.bssid)
            }
        } else if (msg.isNotEmpty()) {
            tvStatus.text = msg
        }
        // v14c：锁定目标但服务尚未产出文案时（提交中/恢复中），本地补渲染"已连接"态
        // （服务侧每 2s 心跳会写 uiMsg，这里兜底避免短暂空白）
        if (t != null && msg.isEmpty() && (cur0() ?: lastGoodBssid).equals(t.bssid, true)) {
            setStatusRedIp("已连接 ${t.bssid}（${t.band}，防漂移守护中）${ipSuffix()}", t.bssid, ipSuffix().removePrefix("，"))
        }
    }

    /** 本地 currentBssid 别名（syncUiFromGuardState 内不遮蔽外部 cur 变量语义） */
    private fun cur0(): String? = currentBssid()

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(scanReceiver)
        } catch (_: Exception) {
        }
    }

    // v14：前台时轮询服务状态消息（2s，与守护节奏一致），UI 实时反映服务侧守护动态
    private val uiSyncHandler = Handler(Looper.getMainLooper())
    private var lastRenderedMsg = ""
    private var lastSyncedConnected: String? = null
    private val uiSync = object : Runnable {
        override fun run() {
            syncUiFromGuardState()
            // 连接状态（按钮 连接→已连接）：connectedBssid 有变化才整表重绑，避免频繁刷列表
            val cb = GuardState.connectedBssid ?: currentBssid()
            if (cb != lastSyncedConnected) {
                lastSyncedConnected = cb
            trendView?.invalidate()
                adapter.connectedBssid = cb
                adapter.connectingBssid = null
                adapter.notifyDataSetChanged()
            }
            uiSyncHandler.postDelayed(this, 2000L)
        }
    }

    override fun onStart() {
        super.onStart()
        bindGuardService()
        uiSyncHandler.postDelayed(uiSync, 2000L)
    }

    override fun onStop() {
        super.onStop()
        uiSyncHandler.removeCallbacks(uiSync)
        // 不解绑：服务连接保留（bindService 的连接随 Activity 销毁自动解除）
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWatchdog()
        stopNudge()
        // v14：守护移交前台服务——Activity 销毁（用户退出/系统回收）不清锁定状态，
        // 服务继续 7×24 守护；仅当无锁定目标时才顺带清建议释放资源
        lockedTarget = null
        releaseCallback()
        if (GuardState.target == null) removeAllSuggestions()
        serviceConn?.let { try { unbindService(it) } catch (_: Exception) {} }
        serviceConn = null
        guardBinder = null
    }

    private fun hasPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33) {
            val nearby = ContextCompat.checkSelfPermission(
                this, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            return nearby || fine
        }
        return fine
    }

    private fun requestNeededPermissions() {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        permLauncher.launch(list.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasPermissions()) {
            requestNeededPermissions()
            return
        }
        // v14c：限流预检——系统限流 4 次/2min（240s 窗口）。距上次成功发起扫描不足 30s 时
        // 直接提示等待并展示缓存，避免无效调用（点了也拿不到新结果）
        val sinceLast = SystemClock.elapsedRealtime() - lastScanAt
        if (lastScanAt > 0 && sinceLast < 30_000L) {
            val waitSec = ((30_000L - sinceLast) / 1000L) + 1
            tvStatus.text = "系统扫描限流中，约 ${waitSec}s 后可刷新（当前为缓存结果）"
            // v14c：提示保持 4s，期间服务心跳/扫描回调不覆盖状态行
            localMsgHoldUntil = SystemClock.elapsedRealtime() + 4000L
            diag("SCAN-THROTTLED since=${sinceLast}ms")
            return
        }
        progressScan.visibility = View.VISIBLE
        lastScanAt = SystemClock.elapsedRealtime()
        wifiManager.startScan()
        renderResults()
    }

    @SuppressLint("MissingPermission")
    private fun renderResults() {
        // v14c：本地提示保持期内不覆盖（限流提示等）
        if (SystemClock.elapsedRealtime() < localMsgHoldUntil) return
        val results = wifiManager.scanResults
        lastResults = results
        adapter.connectedBssid = currentBssid()
        if (results.isEmpty()) {
            adapter.update(emptyList())
            tvStatus.text = "暂无扫描结果，点右上角刷新"
            return
        }
        // v15 功能1：按关键字过滤 SSID/BSSID（不区分大小写；空关键字=全部）
        val kw = filterText.lowercase()
        val filtered = if (kw.isEmpty()) results else results.filter {
            (it.SSID ?: "").lowercase().contains(kw) || it.BSSID.lowercase().contains(kw)
        }
        if (filtered.isEmpty()) {
            adapter.update(emptyList())
            tvStatus.text = "无匹配 \"${filterText}\" 的接入点（共 ${results.size} 个）"
            return
        }
        adapter.update(groupBySsid(filtered))
        tvStatus.text = if (kw.isEmpty()) "共 ${results.size} 个接入点"
        else "匹配 ${filtered.size}/${results.size} 个接入点"
        // 已在守护目标上（锁定/已连接态）时，扫描刷新保留连接信息并追加 IP
        // v14：目标可能在服务侧（lockedTarget=null 但 GuardState.target 非空）
        val target = lockedTarget ?: GuardState.target
        val cur = currentBssid() ?: lastGoodBssid
        if (target != null && cur != null && cur.equals(target.bssid, ignoreCase = true)) {
            setStatusRedIp("已连接 ${target.bssid}（${target.band}，防漂移守护中）${ipSuffix()}", target.bssid, ipSuffix().removePrefix("，"))
            GuardState.uiMsg = "已连接 ${target.bssid}（${target.band}，防漂移守护中）${ipSuffix()}"
        }
    }

    private fun currentBssid(): String? = try {
        val b = wifiManager.connectionInfo?.bssid
        if (b != null && b != "02:00:00:00:00:00") b else null
    } catch (_: Exception) {
        null
    }

    /** 当前 Wi-Fi 网络的 IPv4（遍历所有网络，兼容 specifier 建立的 LocalOnly 网络不是默认网络的机型），无连接返回 null */
    private fun currentIp(): String? = try {
        var found: String? = null
        for (network in connectivityManager.allNetworks) {
            val ni = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!ni.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val ip = connectivityManager.getLinkProperties(network)?.linkAddresses
                ?.firstOrNull { it.address is java.net.Inet4Address }
                ?.address?.hostAddress
            if (ip != null) { found = ip; break }
        }
        found
    } catch (_: Exception) {
        null
    }

    /** 拼接到状态文案尾部的 IP 后缀（红色由状态行统一着色不需要，直接跟文本） */
    private fun ipSuffix(): String = currentIp()?.let { "，IP：$it" } ?: ""

    /** 手动 ping 详情弹窗状态（弹窗内实时刷新） */
    private val pingLog = java.util.ArrayDeque<String>()
    private var pingDialog: androidx.appcompat.app.AlertDialog? = null
    private var pingTv: android.widget.TextView? = null
    private var trendView: TrendView? = null
    @Volatile private var pingRunning = false

    /** 状态行部分文字标红（BSSID/IP），其余保持默认色；IP 入口版走 setStatusRedIp */
    private fun setStatusRed(base: String, vararg redParts: String) {
        setStatusRedImpl(base, false, *redParts)
    }

    private fun setStatusRedIp(base: String, vararg redParts: String) {
        setStatusRedImpl(base, true, *redParts)
    }

    private fun setStatusRedImpl(base: String, ipClickable: Boolean, vararg redParts: String) {
        val ssb = android.text.SpannableStringBuilder(base)
        val paint = tvStatus.paint
        for (part in redParts) {
            if (part.isNullOrEmpty()) continue
            var idx = ssb.indexOf(part)
            while (idx >= 0) {
                ssb.setSpan(
                    android.text.style.ForegroundColorSpan(0xFFD32F2F.toInt()),
                    idx, idx + part.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                idx = ssb.indexOf(part, idx + part.length)
            }
        }
        // v15b: IP 段叠加可点击入口（弹手动 ping 详情）；v15d: IP 颜色随链路探活（通=绿 / 断或未知=红）
        if (ipClickable) {
            val ip = currentIp()
            if (ip != null) {
                val ipLabel = "IP：$ip"
                val i = ssb.indexOf(ipLabel)
                if (i >= 0) {
                    val ipColor = if (GuardState.linkOk == true) 0xFF2E7D32.toInt() else 0xFFD32F2F.toInt()
                    ssb.setSpan(
                        object : android.text.style.ClickableSpan() {
                            override fun onClick(widget: android.view.View) { showPingDialog() }
                            override fun updateDrawState(ds: android.text.TextPaint) {
                                ds.color = ipColor
                                ds.isUnderlineText = true
                            }
                        },
                        i, i + ipLabel.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    tvStatus.movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }
            }
        }
        tvStatus.text = ssb
    }

    /** 手动 ping：网关 5 发连测（ICMP 失败回退 TCP:80），结果滚动进弹窗 */
    private fun runManualPing(targetOverride: String? = null) {
        if (pingRunning) return
        pingRunning = true
        appendPing("── 立即探测 " + android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()) + " ──")
        val gw = if (!targetOverride.isNullOrBlank()) try { java.net.InetAddress.getByName(targetOverride.trim()) } catch (_: Exception) { currentGateway() } else currentGateway()
        val net = currentWifiNetwork()
        appendPing("目标：" + (gw?.hostAddress ?: "未获取") + "（Wi-Fi 网络 " + (if (net != null) "已绑定" else "未找到") + "）")
        if (gw == null || net == null) {
            pingRunning = false
            appendPing("[错误] 未获取到 Wi-Fi 网络/网关（可能是刚启动尚未就绪，稍后重试）")
            return
        }
        val gwF: java.net.InetAddress = gw
        val netF: Network = net

        Thread {
            var okCnt = 0
            var rttSum = 0L
            for (seq in 1..5) {
                
                val t0 = SystemClock.elapsedRealtime()
                var ok = try { gwF.isReachable(1500) } catch (_: Exception) { false }
                var via = "ICMP"
                if (!ok) {
                    var sock: java.net.Socket? = null
                    try {
                        sock = java.net.Socket()
                        netF.bindSocket(sock)
                        sock.connect(java.net.InetSocketAddress(gwF, 80), 1500)
                        ok = true
                        via = "TCP:80"
                    } catch (_: Exception) { ok = false } finally { try { sock?.close() } catch (_: Exception) {} }
                }
                val rtt = if (ok) SystemClock.elapsedRealtime() - t0 else -1L
                if (ok) { okCnt++; rttSum += rtt }
                postPing("#" + seq + " " + (if (ok) "通 via " + via + " " + rtt + "ms" else "不通（ICMP+TCP:80 均 1500ms 超时）"))
                try { Thread.sleep(500) } catch (_: Exception) {}
            }
            val avg = if (okCnt > 0) "，平均 " + (rttSum / okCnt) + "ms" else "，网关不可达（断流守护会告警）"
            postPing("= 结果：" + okCnt + "/5 通" + avg)
            run { GuardState.samples.addLast(ProbeSample(System.currentTimeMillis(), wifiManager.connectionInfo?.rssi?.takeIf { it in -100..0 }, if (okCnt > 0) rttSum / okCnt else null)); while (GuardState.samples.size > 360) GuardState.samples.removeFirst() }
            // 对照诊断：不绑定网络直连一发（区分"网关真不可达"vs"绑定网络路径问题"）
            if (okCnt == 0) {
                var nobOk = false
                var nobErr = ""
                val t1 = SystemClock.elapsedRealtime()
                var s2: java.net.Socket? = null
                try {
                    s2 = java.net.Socket()
                    s2.connect(java.net.InetSocketAddress(gwF, 80), 2500)
                    nobOk = true
                } catch (e: Exception) {
                    nobErr = e.javaClass.simpleName + ":" + (e.message ?: "")
                } finally { try { s2?.close() } catch (_: Exception) {} }
                val ms = SystemClock.elapsedRealtime() - t1
                postPing(if (nobOk) "[对照] 不绑定直连 TCP:80 通（${ms}ms）→ 绑定路径问题，已自动改用直连" else "[对照] 不绑定直连也不通（${ms}ms）" + (if (nobErr.isNotEmpty()) " " + nobErr else ""))
                if (nobOk) postPing("[结论] 网关实际可达！绑定 socket 到 specifier 网络后发包被丢弃（厂商行为）。守护探活将自动加入直连模式。")
            }
            pingRunning = false
        }.start()
    }

    private fun currentGateway(): java.net.InetAddress? {
        // 1) routes 里的 IPv4 gateway（跳过 0.0.0.0 on-link 默认路由）
        try {
            for (network in connectivityManager.allNetworks) {
                val ni = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (!ni.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val lp = connectivityManager.getLinkProperties(network) ?: continue
                for (r in lp.routes) {
                    val gw = r.gateway ?: continue
                    if (gw is java.net.Inet4Address && !gw.isAnyLocalAddress && gw.hostAddress != "0.0.0.0") return gw
                }
            }
        } catch (_: Exception) {}
        // 2) DHCP 服务器地址
        try {
            for (network in connectivityManager.allNetworks) {
                val ni = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (!ni.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val lp = connectivityManager.getLinkProperties(network) ?: continue
                val d = lp.dhcpServerAddress
                if (d != null && d is java.net.Inet4Address && !d.isAnyLocalAddress) return d
            }
        } catch (_: Exception) {}
        // 3) 由本机 IP 推 .1（常见网关约定）
        try {
            val ip = currentIp() ?: return null
            val seg = ip.substringBeforeLast(".")
            return java.net.InetAddress.getByName("$seg.1")
        } catch (_: Exception) {
            // 全部回退失败
        }
        return null
    }

    private fun currentWifiNetwork(): Network? = try {
        connectivityManager.allNetworks.firstOrNull { n ->
            connectivityManager.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    } catch (_: Exception) { null }

    private fun appendPing(s: String) {
        pingLog.addLast(s)
        while (pingLog.size > 12) pingLog.removeFirst()   // v16b：一次探测记录容量（固定 8 行区显示）
        renderPing()
    }

    private fun postPing(s: String) = runOnUiThread { appendPing(s) }

    private fun renderPing() {
        pingTv?.text = pingLog.joinToString("\n")
    }

    /** ping 详情弹窗 v16：探测目标可配置 + 信号趋势曲线 + 日志 */
    private fun showPingDialog() {
        val gw = currentGateway()

        val box = android.widget.LinearLayout(this)
        box.orientation = android.widget.LinearLayout.VERTICAL
        box.setPadding(48, 24, 48, 24)

        // 探测目标输入行（空=网关）
        val targetRow = android.widget.LinearLayout(this)
        targetRow.orientation = android.widget.LinearLayout.HORIZONTAL
        val etTarget = EditText(this)
        etTarget.hint = "目标 IP/域名（空=网关）"
        etTarget.setText(ProbeConfig.target(this))
        etTarget.textSize = 13f
        etTarget.isSingleLine = true
        etTarget.inputType = android.text.InputType.TYPE_CLASS_PHONE  // 数字键盘+点号，避免中文 IME 打成「。」
        targetRow.addView(
            etTarget,
            android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        box.addView(targetRow)

        // v16：信号趋势曲线（RSSI/延迟 最近 30 分钟）
        val trend = TrendView(this)
        box.addView(
            trend,
            android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 320
            )
        )

        val tv = android.widget.TextView(this)
        tv.setPadding(0, 16, 0, 0)
        tv.textSize = 13f
        tv.setTextIsSelectable(true)
        tv.setLines(8)   // v16b：固定 8 行（一次探测记录：2 行头 + 5 发 + 结果），弹窗高度稳定不跳
        pingTv = tv
        renderPing()
        if (pingLog.isEmpty()) {
            appendPing("点『立即探测』开始；守护探活每 5s 自动跑，结论见状态行『延迟 Nms』")
            appendPing("网关：" + (gw?.hostAddress ?: "未获取"))
        }
        box.addView(tv)

        pingDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("网关探活详情")
            .setOnDismissListener { trendView = null }
            .setView(box)
            .setPositiveButton("关闭", null)   // 右侧=关闭，默认 dismiss，符合直觉（v16c 修"点两下才能关"）
            .setNeutralButton("立即探测", null)
            .setNegativeButton("保存目标", null)
            .show()
        // v16b：弹窗固定大小（宽 92%，高 = 标题+输入行+320px 趋势图+8 行日志+按钮行，一次探测记录刚好放下）
        pingDialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        // 按钮序：[保存目标][立即探测][关闭]——立即探测=NEUTRAL（接管不关窗），保存目标=NEGATIVE
        pingDialog?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            val t = etTarget.text?.toString()?.trim().orEmpty()
            ProbeConfig.setTarget(this, t)   // 顺手记住目标
            runManualPing(if (t.isEmpty()) null else t)
            // 探测中保持弹窗可见，实时刷新结果
            pingDialog?.let { d -> if (!d.isShowing) d.show() }
        }
        pingDialog?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            ProbeConfig.setTarget(this, etTarget.text?.toString()?.trim().orEmpty())
            Toast.makeText(this, "探活目标已保存：${ProbeConfig.target(this).ifEmpty { "网关(自动)" }}", Toast.LENGTH_SHORT).show()
            diag("PROBE-TARGET set=${ProbeConfig.target(this)}")
        }
    }

    /** v16：信号趋势自绘视图（RSSI dBm 绿线 + 延迟 ms 蓝线，双轴归一化，最近 360 采样点） */
    inner class TrendView(ctx: android.content.Context) : android.view.View(ctx) {
        private val pLine = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f }
        private val pText = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f }
        private val pGrid = android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 1f; color = 0x33000000.toInt() }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, h, pGrid)
            val s = GuardState.samples.toList()
            if (s.isEmpty()) {
                pText.color = 0xFF888888.toInt()
                canvas.drawText("趋势图：守护探活每 5s 采样 RSSI/延迟", 20f, h / 2, pText)
                return
            }
            val rssis = s.mapNotNull { it.rssi }
            val rtts = s.mapNotNull { it.rtt }
            val rssiMin = (rssis.minOrNull() ?: -100) - 2
            val rssiMax = (rssis.maxOrNull() ?: -40) + 2
            val rttMax = ((rtts.maxOrNull() ?: 100) * 1.2 + 10).toFloat()
            fun yRssi(v: Int) = h - ( (v - rssiMin).toFloat() / ((rssiMax - rssiMin).coerceAtLeast(1)) ) * (h - 40) - 20
            fun yRtt(v: Long) = h - ( v / rttMax ) * (h - 40) - 20
            // 网格时间刻度：最早点与最新点
            pText.color = 0xFF666666.toInt()
            val t0 = s.first().ts; val t1 = s.last().ts
            canvas.drawText(android.text.format.DateFormat.format("HH:mm", t0).toString(), 8f, h - 4f, pText)
            val lbl = android.text.format.DateFormat.format("HH:mm", t1).toString()
            canvas.drawText(lbl, w - pText.measureText(lbl) - 8f, h - 4f, pText)
            // RSSI 绿线
            pLine.color = 0xFF2E7D32.toInt()
            var prev: Pair<Float, Float>? = null
            for ((i, sp) in s.withIndex()) {
                val r = sp.rssi ?: continue
                val x = if (s.size == 1) w / 2 else (i.toFloat() / (s.size - 1)) * (w - 20) + 10
                val y = yRssi(r)
                prev?.let { canvas.drawLine(it.first, it.second, x, y, pLine) }
                prev = x to y
            }
            // 延迟蓝线
            pLine.color = 0xFF1565C0.toInt()
            prev = null
            for ((i, sp) in s.withIndex()) {
                val r = sp.rtt ?: continue
                val x = if (s.size == 1) w / 2 else (i.toFloat() / (s.size - 1)) * (w - 20) + 10
                val y = yRtt(r)
                prev?.let { canvas.drawLine(it.first, it.second, x, y, pLine) }
                prev = x to y
            }
            // 图例
            pText.color = 0xFF2E7D32.toInt()
            canvas.drawText("RSSI ${rssis.lastOrNull() ?: "-"}dBm", 8f, 30f, pText)
            pText.color = 0xFF1565C0.toInt()
            canvas.drawText("RTT ${rtts.lastOrNull() ?: "-"}ms", 260f, 30f, pText)
        }
    }


    private fun onConnectClicked(ap: ApItem) {
        if (ap.secure == "开放") {
            connectToBssid(ap, null)
        } else {
            showPasswordDialog(ap)
        }
    }

    private fun showPasswordDialog(ap: ApItem) {
        val view = layoutInflater.inflate(R.layout.dialog_password, null)
        val et = view.findViewById<EditText>(R.id.etPassword)
        val cbRemember = view.findViewById<android.widget.CheckBox>(R.id.cbRemember)
        val info = view.findViewById<TextView>(R.id.tvInfo)
        info.text = "SSID: ${ap.ssid}\nBSSID: ${ap.bssid}（${ap.band}，${ap.secure}）"
        // 密码记忆库：同 SSID 有存过密码则自动填入
        PasswordStore.load(this, ap.ssid)?.let { et.setText(it) }
        AlertDialog.Builder(this)
            .setTitle("输入 Wi-Fi 密码")
            .setView(view)
            .setPositiveButton("连接") { _, _ ->
                val pw = et.text?.toString().orEmpty()
                if (pw.length < 8) {
                    Toast.makeText(this, "密码至少 8 位", Toast.LENGTH_SHORT).show()
                } else {
                    if (cbRemember.isChecked) PasswordStore.save(this, ap.ssid, pw)
                    else PasswordStore.forget(this, ap.ssid)
                    connectToBssid(ap, pw)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 核心：按当前模式锁定 BSSID 发起连接，守护由前台服务 GuardService 承载（v14） */
    private fun connectToBssid(ap: ApItem, password: String?) {
        GuardState.useSuggestion = useSuggestion
        GuardService.start(this)
        // 会话发起交给服务（服务就绪后由 startId 之后的调用执行；直接调 binder 方法）
        guardBinder?.service()?.beginGuardSession(ap, password)
            // binder 未就绪（服务刚启动）时兜底：post 一帧再试
            ?: watchdogHandler.postDelayed({
                guardBinder?.service()?.beginGuardSession(ap, password)
                    ?: run {
                        diag("SVC-BIND-FAIL fallback local session")
                        localFallbackConnect(ap, password)
                    }
            }, 400L)
        persistLockPlanForService(ap, password)
    }

    private var guardBinder: GuardService.LocalBinder? = null

    private var serviceConn: android.content.ServiceConnection? = null

    private fun bindGuardService() {
        if (serviceConn != null) return
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                guardBinder = binder as? GuardService.LocalBinder
                diag("SVC-BOUND")
            }

            override fun onServiceDisconnected(name: android.content.ComponentName) {
                guardBinder = null
                diag("SVC-UNBOUND")
            }
        }
        serviceConn = conn
        bindService(Intent(this, GuardService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    /** 功能3：锁定方案落盘（服务侧也会存一份，这里保证 UI 按钮状态及时） */
    private fun persistLockPlanForService(ap: ApItem, password: String?) {
        LockPlanStorage.save(
            this,
            LockPlan(
                ssid = ap.ssid, bssid = ap.bssid, band = ap.band, secure = ap.secure,
                useSuggestion = useSuggestion
            ),
            password
        )
        btnUnlock.visibility = View.VISIBLE
        diag("PLAN-SAVED bssid=${ap.bssid} mode=${if (useSuggestion) "sug" else "spec"}")
    }

    /** 服务绑定失败的兜底：老路径本 Activity 会话（保留 v6 全部逻辑） */
    private fun localFallbackConnect(ap: ApItem, password: String?) {
        lockedTarget = ap
        lockedPassword = password
        relockCount = 0
        relocking = false
        if (useSuggestion) {
            releaseCallback()
            removeAllSuggestions()
            watchdogHandler.postDelayed({ submitSuggestion(ap, password) }, 800L)
            tvStatus.text = "正在提交建议网络 ${ap.bssid}…"
            return
        }
        releaseCallback()
        startWatchdog()
        requestLockedNetwork(ap, password)
    }

    /** 发起（或重新发起）带 BSSID 约束的 specifier 请求 */
    private fun requestLockedNetwork(ap: ApItem, password: String?) {
        relocking = true
        relockStartElapsed = SystemClock.elapsedRealtime()

        // 双重锁定：BSSID + 频段/信道约束。
        // 背景：Wi-Fi 7 MLO AP（双频合一）和厂商漫游（如 vivo SET_5GROAMINGRATETH）
        // 都会在连接建立后把链路迁到同 SSID 的 5G AP，单靠 setBssid 约束不住。
        // setBand + setPreferredChannelsFrequenciesMhz（API 34+）从网络选择器层面
        // 限定候选频段/信道，配合防漂移守护双保险。
        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setBssid(MacAddress.fromString(ap.bssid))
        if (ap.ssid.isNotBlank() && ap.ssid != "(隐藏网络)") {
            specifierBuilder.setSsid(ap.ssid)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val band = when (ap.band) {
                "2.4G" -> ScanResult.WIFI_BAND_24_GHZ
                "5G" -> ScanResult.WIFI_BAND_5_GHZ
                "6G" -> ScanResult.WIFI_BAND_6_GHZ
                else -> null
            }
            // A/B 实测（vivo Android16）：setBand 会让 specifier 请求 13ms 内被同步拒绝
            // （onUnavailable 立即触发、系统确认弹窗不出现），疑似 vivo band 过滤实现 bug，
            // 禁用 setBand，BSSID 约束 + 防漂移守护足够。
            // if (band != null) specifierBuilder.setBand(band)
            diag("setBand skipped (band=${ap.band}) due to OEM instant-reject")
        }
        if (password != null) {
            if (ap.secure == "WPA3") specifierBuilder.setWpa3Passphrase(password)
            else specifierBuilder.setWpa2Passphrase(password)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        if (relockCount == 0) {
            adapter.connectingBssid = ap.bssid
            adapter.notifyDataSetChanged()
            tvStatus.text = "正在连接 ${ap.bssid}…请在系统弹窗中点「连接」确认"
        }
        diag("REQ id=relock$relockCount bssid=${ap.bssid} ssid=${ap.ssid} band=${ap.band}")

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                diag("onAvailable net=$network relock=$relockCount")
                runOnUiThread {
                    relocking = false
                    stopNudge()   // 功能1：已连上，取消 nudge 阶梯
                    adapter.connectedBssid = ap.bssid
                    adapter.connectingBssid = null
                    adapter.notifyDataSetChanged()
                    setStatusRedIp("已连接 ${ap.bssid}（${ap.band}，防漂移守护中）${ipSuffix()}", ap.bssid, ipSuffix().removePrefix("，"))
                    startScan()   // 连接成功后触发一次列表刷新（信号强度/IP 状态同步）
                    Toast.makeText(
                        this@MainActivity,
                        if (relockCount == 0) "连接成功" else "已重新锁定到 ${ap.bssid}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onUnavailable() {
                diag("onUnavailable relock=$relockCount")
                runOnUiThread {
                    // 只处理仍属于当前 specifier 会话的回调：模式已切走/目标已变更时忽略
                    if (!useSuggestion && lockedTarget != null) {
                        relocking = false
                        stopNudge()
                        adapter.connectingBssid = null
                        adapter.notifyDataSetChanged()
                        stopWatchdog()
                        lockedTarget = null
                        clearLockPlan()
                        tvStatus.text =
                            if (relockCount > 0) "重新锁定失败（被取消/超时），已停止守护"
                            else "连接失败：用户取消 / 密码错误 / 超时"
                        Toast.makeText(this@MainActivity, tvStatus.text, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        networkCallback = cb
        connectivityManager.requestNetwork(request, cb)
    }

    /** 守护线程检出漂移：实际 BSSID 已不是用户选定目标（厂商漫游迁走） */
    private fun relockElapsedMs(): Long = SystemClock.elapsedRealtime() - relockStartElapsed

    private fun onDriftDetected(actualBssid: String) {
        diag("DRIFT actual=$actualBssid target=${lockedTarget?.bssid} relock=$relockCount mode=${if (useSuggestion) "sug" else "spec"}")
        val target = lockedTarget ?: return
        driftStrikes = 0
        stopNudge()   // 功能1：漂移处理会重新提交建议并重启 nudge，先停旧的
        if (relockCount >= maxRelock) {
            stopWatchdog()
            lockedTarget = null
            if (useSuggestion) removeAllSuggestions()
            clearLockPlan()   // 功能3：放弃锁定即清除持久化方案
            tvStatus.text = "放弃锁定：系统反复把连接迁至 $actualBssid（已重锁 $maxRelock 次）"
            Toast.makeText(this, "该系统漫游策略持续覆盖 BSSID 约束", Toast.LENGTH_LONG).show()
            return
        }
        relockCount++
        adapter.connectingBssid = target.bssid
        adapter.notifyDataSetChanged()
        tvStatus.text =
            "检测到漫游漂移（漂至 $actualBssid）→ 重新锁定 ${target.bssid}（第 $relockCount/$maxRelock 次）"
        if (useSuggestion) {
            // Suggestion 模式漂移：清除并重新提交建议（触发系统按新建议重选 AP）
            removeAllSuggestions()
            watchdogHandler.postDelayed({ submitSuggestion(target, lockedPassword) }, 800L)
        } else {
            // 断开当前连接并按 BSSID 约束重新关联
            releaseCallback()
            requestLockedNetwork(target, lockedPassword)
        }
    }

    private fun startWatchdog() {
        driftStrikes = 0
        matchSinceElapsed = 0L
        watchdogHandler.removeCallbacks(watchdog)
        watchdogHandler.postDelayed(watchdog, watchdogIntervalMs)
    }

    private fun stopWatchdog() {
        watchdogHandler.removeCallbacks(watchdog)
    }

    private fun releaseCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
    }

    // ==== V2: WifiNetworkSuggestion 通道 ====
    // 背景：vivo 平台 WifiNetworkSpecifier 的 setBssid 约束被厂商连接管理完全无视
    // （场景 A 挂起不动作 / 场景 B 连上但换 BSSID+频段）。Suggestion API 走的是
    // 完全不同的通路：提交为系统"建议网络"，由平台自动连接（用户批准一次后常驻），
    // 厂商选网器对 suggestion 的 BSSID 处理可能不同——本通道即验证此点。
    private var useSuggestion = false

    // ==== 功能1：nudge 阶梯（唤醒选网器）====
    // 背景：vivo 提交 suggestion 后选网器经常不评估（60s+ 无动作），svc wifi toggle 可立即唤醒。
    // App 无 shell 权限，改为阶梯：t1=12s 重提交 → t2=24s 触发扫描 → t3=36s 再重提交。
    // 连上目标（onAvailable）即取消。Specifier 模式 requestNetwork 立即评估，无需 nudge。
    private val nudgeHandler = Handler(Looper.getMainLooper())
    private val nudgeSteps = longArrayOf(12000L, 24000L, 36000L)
    private var nudgePending = 0

    private fun startNudge() {
        stopNudge()
        if (!useSuggestion) return
        nudgePending = nudgeSteps.size
        for ((i, delay) in nudgeSteps.withIndex()) {
            nudgeHandler.postDelayed({
                val t = lockedTarget ?: return@postDelayed
                if (currentBssid()?.equals(t.bssid, true) == true) return@postDelayed
                diag("NUDGE step=$i target=${t.bssid}")
                if (i == 1) {
                    try { wifiManager.startScan() } catch (_: Exception) {}
                } else {
                    removeAllSuggestions()
                    watchdogHandler.postDelayed({
                        lockedTarget?.let { submitSuggestion(it, lockedPassword) }
                    }, 500L)
                }
            }, delay)
        }
        diag("NUDGE-ARMED steps=${nudgeSteps.joinToString()}")
    }

    private fun stopNudge() {
        nudgeHandler.removeCallbacksAndMessages(null)
        nudgePending = 0
    }

    // ==== 功能3：锁定方案持久化 + 自动重锁 + 解锁按钮 ====
    private lateinit var btnUnlock: TextView

    /** 当前锁定状态序列化为 LockPlan 并落盘 */
    private fun persistLockPlan() {
        val t = lockedTarget ?: return
        LockPlanStorage.save(
            this,
            LockPlan(
                ssid = t.ssid, bssid = t.bssid, band = t.band, secure = t.secure,
                useSuggestion = useSuggestion
            ),
            lockedPassword
        )
        btnUnlock.visibility = View.VISIBLE
        diag("PLAN-SAVED bssid=${t.bssid} mode=${if (useSuggestion) "sug" else "spec"}")
    }

    private fun clearLockPlan() {
        LockPlanStorage.clear(this)
        btnUnlock.visibility = View.GONE
        diag("PLAN-CLEARED")
    }

    private fun submitSuggestion(ap: ApItem, password: String?) {
        diag("SUG-REQ bssid=${ap.bssid} ssid=${ap.ssid} band=${ap.band} pw=${password != null}")
        val b = WifiNetworkSuggestion.Builder()
            .setSsid(ap.ssid)
            .setBssid(MacAddress.fromString(ap.bssid))
        // setIsInitialAutojoinEnabled 是 API 31+；API 29/30（如 EMUI10）没有该方法，默认即启用
        if (Build.VERSION.SDK_INT >= 31) b.setIsInitialAutojoinEnabled(true)
        if (password != null) {
            if (ap.secure == "WPA3") b.setWpa3Passphrase(password)
            else b.setWpa2Passphrase(password)
        }
        val list = listOf(b.build())
        // API 36：addNetworkSuggestions(List) 同步返回状态码（旧 (List, IntentExecutor) 重载已移除）
        val code = wifiManager.addNetworkSuggestions(list)
        val reason = when (code) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> "成功"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL -> "内部错误"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED -> "App 被禁止提交建议"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> "重复建议"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP -> "超出建议数上限"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID -> "建议内容无效"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_NOT_ALLOWED -> "当前不允许添加"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_RESTRICTED_BY_ADMIN -> "被管理员限制"
            else -> "未知 code=$code"
        }
        diag("SUG-RESULT code=$code ($reason)")
        runOnUiThread {
            if (code == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                setStatusRedIp("建议已提交：${ap.bssid}（等待系统自动连入，守护中）${ipSuffix()}", ap.bssid, ipSuffix().removePrefix("，"))
                Toast.makeText(this@MainActivity, "建议网络已提交，等待系统自动连入目标 AP", Toast.LENGTH_LONG).show()
                startWatchdog()
                startNudge()   // 功能1：选网器迟迟不评估时按阶梯唤醒
            } else {
                tvStatus.text = "建议网络提交失败：$reason"
                Toast.makeText(this@MainActivity, tvStatus.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun removeAllSuggestions() {
        try {
            // 传空列表 = 移除本 App 全部建议
            wifiManager.removeNetworkSuggestions(emptyList())
            diag("SUG-CLEARED")
        } catch (e: Exception) {
            diag("SUG-CLEAR-FAIL ${e.message}")
        }
    }
}
