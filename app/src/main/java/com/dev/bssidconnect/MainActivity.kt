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
import com.google.android.material.textfield.TextInputEditText

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
                // 同步执行（不再 postDelayed：BOOT_COMPLETED 到达时 Wi-Fi 服务已就绪；
                // Suggestion 提交后由系统持久持有，无需本进程存活）
                if (plan.useSuggestion) {
                    LockEngine.submitSuggestion(wm, plan, pw, appCtx)
                } else {
                    LockEngine.requestLockedNetwork(cm, plan, pw, appCtx)
                }
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
            java.io.File(filesDir, "diag.txt").appendText(
                "${SystemClock.elapsedRealtime()}|$msg\n"
            )
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
    private lateinit var tvMac: TextView
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastScanAt = 0L

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
                                tvStatus.text = "已锁定 ${target.bssid}（${target.band}，防漂移守护中）${ipSuffix()}"
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

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)
                renderResults()
                progressScan.visibility = View.GONE
                if (!updated) tvStatus.text = "扫描被系统限流，以下为缓存结果"
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
        tvMac = findViewById(R.id.tvMac)
        tvMac.setOnClickListener { showMacHelpDialog() }

        showMacInfo()

        // 功能2：首次启动按厂商推断默认模式；已有选择则恢复
        useSuggestion = loadModePref()
        btnMode.text = if (useSuggestion) "模式:Suggestion" else "模式:Specifier"
        diag("MODE-INIT -> ${if (useSuggestion) "Suggestion" else "Specifier"} (pref or OEM default)")

        // 功能3：恢复上次锁定方案（杀进程/重启后自动重锁）
        val savedPlan = LockPlanStorage.load(this)
        if (savedPlan != null) {
            btnUnlock.visibility = View.VISIBLE
            tvStatus.text = "恢复锁定方案：${savedPlan.bssid}（${savedPlan.band}）${ipSuffix()}"
            diag("PLAN-RESTORE bssid=${savedPlan.bssid} mode=${if (savedPlan.useSuggestion) "sug" else "spec"}")
            val ap = ApItem(
                bssid = savedPlan.bssid, ssid = savedPlan.ssid, level = 0,
                frequency = 0, channel = 0, band = savedPlan.band, secure = savedPlan.secure,
                isWifi6Plus = false
            )
            // 等 UI 稳定 + 系统服务就绪后再发起
            watchdogHandler.postDelayed({ connectToBssid(ap, LockPlanStorage.loadPassword(this)) }, 800L)
        }

        btnUnlock.setOnClickListener {
            stopWatchdog()
            stopNudge()
            lockedTarget = null
            releaseCallback()
            removeAllSuggestions()
            clearLockPlan()
            tvStatus.text = "已解除锁定，恢复系统自动管理"
            Toast.makeText(this, "已解锁：清除锁定方案并停止守护", Toast.LENGTH_SHORT).show()
        }

        adapter = NetworkAdapter(emptyList(), onConnect = { ap -> onConnectClicked(ap) })
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

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
        adapter.connectedBssid = currentBssid()
        showMacInfo()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(scanReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWatchdog()
        stopNudge()
        lockedTarget = null
        releaseCallback()
        removeAllSuggestions()
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
        progressScan.visibility = View.VISIBLE
        wifiManager.startScan()
        renderResults()
    }

    @SuppressLint("MissingPermission")
    private fun renderResults() {
        val results = wifiManager.scanResults
        adapter.connectedBssid = currentBssid()
        if (results.isEmpty()) {
            adapter.update(emptyList())
            tvStatus.text = "暂无扫描结果，点右上角刷新"
            return
        }
        adapter.update(groupBySsid(results))
        tvStatus.text = "共 ${results.size} 个接入点"
        // 已在守护目标上（锁定/已连接态）时，扫描刷新保留连接信息并追加 IP
        val target = lockedTarget
        val cur = currentBssid() ?: lastGoodBssid
        if (target != null && cur != null && cur.equals(target.bssid, ignoreCase = true)) {
            tvStatus.text = "已连接 ${target.bssid}（${target.band}，防漂移守护中）${ipSuffix()}"
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

    /**
     * 读取芯片出厂硬件 MAC（factory MAC）。
     * Android 11+ 应用层拿不到 WifiInfo.getMacAddress()（返回 02:00:00:00:00:00），
     * 依次尝试：反射 mWifiManager.factory MAC 相关隐藏字段/方法 → sysfs 节点 → 失败返回 null。
     */
    private fun readFactoryMac(): String? {
        // 1) 反射 WifiManager 内部的 factory MAC（厂商/平台差异大，宽松遍历）
        try {
            val wm = wifiManager.javaClass
            // 1a. getFactoryMac() 类似方法
            for (m in wm.methods) {
                if (m.parameterTypes.isEmpty() &&
                    m.name.endsWith("actoryMac", ignoreCase = true) &&
                    (m.returnType == String::class.java)
                ) {
                    val v = m.invoke(wifiManager) as? String
                    if (!v.isNullOrEmpty() && v != "02:00:00:00:00:00") return v
                }
            }
            // 1b. 常见隐藏字段（mFactoryMac / factoryMac / mWifiFactoryMac …）
            var c: Class<*>? = wm
            var depth = 0
            while (c != null && c != Any::class.java && depth < 4) {
                for (f in c.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    val n = f.name.lowercase()
                    if ((n.contains("factory") || n.contains("persistent")) && n.contains("mac")) {
                        f.isAccessible = true
                        val v = f.get(wifiManager) as? String
                        if (!v.isNullOrEmpty() && v != "02:00:00:00:00:00") return v
                    }
                }
                c = c.superclass
                depth++
            }
            // 1c. WifiInfo 里也可能有（部分平台）
            val info = wifiManager.connectionInfo
            if (info != null) {
                for (f in info.javaClass.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    val n = f.name.lowercase()
                    if (n.contains("factory") && n.contains("mac")) {
                        f.isAccessible = true
                        val v = f.get(info) as? String
                        if (!v.isNullOrEmpty() && v != "02:00:00:00:00:00") return v
                    }
                }
            }
        } catch (_: Throwable) {
        }
        // 2) sysfs /proc 节点（root/部分平台可读，普通应用一般被 SELinux 拦，读不到就算了）
        for (p in arrayOf(
            "/sys/class/net/wlan0/address",
            "/proc/net/arp/wlan0_address",
            "/sys/class/net/wlan1/address"
        )) {
            try {
                val v = java.io.File(p).readText().trim()
                if (v.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}"))) return v
            } catch (_: Throwable) {
            }
        }
        // 3) Settings.Global 个别平台把出厂 MAC 存在这里（非标准，试试无妨）
        try {
            for (k in arrayOf("wifi_sta_factory_mac_address", "wifi_factory_mac", "factory_mac")) {
                val v = android.provider.Settings.Global.getString(contentResolver, k)
                if (!v.isNullOrEmpty() && v.matches(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}"))) return v
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /** wlan0 网卡当前 MAC（开启随机化时是随机 MAC，非出厂 MAC）。 */
    private fun readWlanInterfaceMac(): String? = try {
        val nis = java.net.NetworkInterface.getNetworkInterfaces()
        var result: String? = null
        while (nis?.hasMoreElements() == true && result == null) {
            val ni = nis.nextElement()
            if (ni.name?.startsWith("wlan") == true) {
                val mac = ni.hardwareAddress
                if (mac != null && mac.size == 6) {
                    val v = mac.joinToString(":") { String.format("%02x", it) }
                    if (v != "02:00:00:00:00:00") result = v
                }
            }
        }
        result
    } catch (_: Throwable) {
        null
    }

    private fun showMacInfo() {
        val factory = readFactoryMac()
        val iface = readWlanInterfaceMac()
        val curMac = try {
            wifiManager.connectionInfo?.macAddress
        } catch (_: Exception) {
            null
        }
        val info = buildString {
            append("硬件MAC：")
            append(factory ?: "应用层不可读（系统限制，点击查看ADB查询方法）")
            if (iface != null) append("　网卡MAC：").append(iface).append("（当前，随机化后）")
        }
        tvMac.text = info
        diag("MAC-INFO factory=$factory iface=$iface connectionInfo=$curMac")
    }

    private fun showMacHelpDialog() {
        val msg = buildString {
            appendLine("Android 11 起普通应用无法读取 Wi-Fi 硬件 MAC（出厂 MAC）。")
            appendLine("当前能读到的所有应用层路径均已尝试并被系统拦截。")
            appendLine()
            appendLine("可通过 ADB 在电脑上查询（手机连接电脑后执行）：")
            appendLine()
            appendLine("adb shell dumpsys wifi | findstr /i factory")
            appendLine()
            appendLine("输出的 wifi_sta_factory_mac_address 即为出厂硬件 MAC。")
            appendLine()
            appendLine("说明：当前连接使用的随机 MAC 每个保存网络固定不变")
            appendLine("（系统默认 Non-Persistent 随机化按 SSID 生成并持久保存）。")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("关于硬件 MAC")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
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
        val et = view.findViewById<TextInputEditText>(R.id.etPassword)
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

    /** 核心：按当前模式锁定 BSSID 发起连接，并启动防漂移守护 */
    private fun connectToBssid(ap: ApItem, password: String?) {
        lockedTarget = ap
        lockedPassword = password
        relockCount = 0
        // 切换目标/模式时清除上一会话（可能是 Specifier）遗留的挂起状态：
        // 否则旧会话的 relocking=true 会污染新会话（30s 假超时 → 假 DRIFT）
        relocking = false
        // 功能3：锁定方案落盘（重启/杀进程后可自动恢复）
        persistLockPlan()
        if (useSuggestion) {
            // Suggestion 模式：清旧建议→提交新建议（含 BSSID），系统自动连入
            releaseCallback()
            removeAllSuggestions()
            // 给系统一点时间消化移除，避免同 SSID 新旧建议冲突
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
                    tvStatus.text = "已连接 ${ap.bssid}（${ap.band}，防漂移守护中）${ipSuffix()}"
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
                tvStatus.text = "建议已提交：${ap.bssid}（等待系统自动连入，守护中）${ipSuffix()}"
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
