package com.dev.bssidconnect

import android.net.wifi.ScanResult
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build

/** 扫描结果数据模型：SSID 分组 + 组内 BSSID 明细 */
data class SsidGroup(
    val ssid: String,
    val bssids: MutableList<ApItem> = mutableListOf()
)

data class ApItem(
    val bssid: String,
    val ssid: String,
    val level: Int,          // dBm
    val frequency: Int,      // MHz
    val channel: Int,
    val band: String,        // 2.4G / 5G / 6G
    val secure: String,      // OPEN / WEP / WPA / WPA2 / WPA3
    val isWifi6Plus: Boolean
)

/** ScanResult → ApItem */
fun ScanResult.toApItem(): ApItem {
    val freq = this.frequency
    val band = when {
        freq in 2401..2495 -> "2.4G"
        freq in 5160..5885 -> "5G"
        Build.VERSION.SDK_INT >= 30 && freq >= 5955 -> "6G"
        else -> "${freq}MHz"
    }
    val channel = when {
        freq in 2401..2495 -> (freq - 2407) / 5
        freq in 5160..5885 -> (freq - 5000) / 5
        freq >= 5955 -> (freq - 5950) / 5
        else -> 0
    }
    val caps = this.capabilities ?: ""
    val secure = when {
        caps.contains("WPA3", true) -> "WPA3"
        caps.contains("SAE") -> "WPA3"
        caps.contains("WPA2", true) || caps.contains("RSN") -> "WPA2"
        caps.contains("WPA", true) -> "WPA"
        caps.contains("WEP") -> "WEP"
        else -> "开放"
    }
    val wifi6 = Build.VERSION.SDK_INT >= 30 &&
        (this.wifiStandard == ScanResult.WIFI_STANDARD_11AX ||
         this.wifiStandard == ScanResult.WIFI_STANDARD_11BE)
    return ApItem(
        bssid = this.BSSID,
        ssid = this.SSID,
        level = this.level,
        frequency = freq,
        channel = channel,
        band = band,
        secure = secure,
        isWifi6Plus = wifi6
    )
}

/** 按 SSID 分组（隐藏 SSID 单独归到"(隐藏网络)"），信号取组内最强，倒序排 */
fun groupBySsid(results: List<ScanResult>): List<SsidGroup> {
    val map = LinkedHashMap<String, SsidGroup>()
    for (r in results) {
        val ssid = r.SSID.ifBlank { "(隐藏网络)" }
        map.getOrPut(ssid) { SsidGroup(ssid) }.bssids.add(r.toApItem())
    }
    val groups = map.values.toList()
    groups.forEach { g -> g.bssids.sortByDescending { it.level } }
    return groups.sortedByDescending { g -> g.bssids.maxOf { it.level } }
}

// ==== 功能3：锁定方案（持久化 + 自动重锁） ====

/** 一次 BSSID 锁定任务的持久化描述 */
data class LockPlan(
    val ssid: String,
    val bssid: String,
    val band: String,
    val secure: String,
    val useSuggestion: Boolean,
    val ts: Long = System.currentTimeMillis()
)

/** 无 UI 上下文（BootReceiver / 自动重锁）共用的锁定引擎 */
object LockEngine {
    const val TAG = "BSC"

    private fun log(ctx: android.content.Context, msg: String) {
        android.util.Log.w(TAG, msg)
        try {
            java.io.File(ctx.filesDir, "diag.txt")
                .appendText("${android.os.SystemClock.elapsedRealtime()}|$msg\n")
        } catch (_: Exception) {
        }
    }

    /** BootReceiver 专用落盘日志（带 BOOT 前缀，elapsedRealtime 开机后从 0 计） */
    fun logBoot(ctx: android.content.Context, msg: String = "BOOT-RECEIVED") {
        log(ctx, "BOOT|$msg")
    }

    /** Suggestion 通道：提交建议网络（供 BootReceiver 复用，无 UI） */
    fun submitSuggestion(wm: android.net.wifi.WifiManager, plan: LockPlan, password: String?, ctx: android.content.Context? = null) {
        val b = WifiNetworkSuggestion.Builder()
            .setSsid(plan.ssid)
            .setBssid(android.net.MacAddress.fromString(plan.bssid))
        // setIsInitialAutojoinEnabled 是 API 31+；低版本默认启用
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setIsInitialAutojoinEnabled(true)
        if (password != null) {
            if (plan.secure == "WPA3") b.setWpa3Passphrase(password)
            else b.setWpa2Passphrase(password)
        }
        val code = wm.addNetworkSuggestions(listOf(b.build()))
        android.util.Log.w(TAG, "ENGINE-SUG code=$code bssid=${plan.bssid}")
        ctx?.let { log(it, "ENGINE-SUG code=$code bssid=${plan.bssid}") }
    }

    /** Specifier 通道：发起带 BSSID 约束的请求（供 BootReceiver 复用，无 UI） */
    fun requestLockedNetwork(cm: android.net.ConnectivityManager, plan: LockPlan, password: String?, ctx: android.content.Context? = null) {
        val sb = WifiNetworkSpecifier.Builder()
            .setBssid(android.net.MacAddress.fromString(plan.bssid))
        if (plan.ssid.isNotBlank() && plan.ssid != "(隐藏网络)") sb.setSsid(plan.ssid)
        if (password != null) {
            if (plan.secure == "WPA3") sb.setWpa3Passphrase(password)
            else sb.setWpa2Passphrase(password)
        }
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(sb.build())
            .build()
        cm.requestNetwork(request, object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                android.util.Log.w(TAG, "ENGINE-AVAILABLE net=$network bssid=${plan.bssid}")
                ctx?.let { log(it, "ENGINE-AVAILABLE net=$network bssid=${plan.bssid}") }
            }

            override fun onUnavailable() {
                android.util.Log.w(TAG, "ENGINE-UNAVAILABLE bssid=${plan.bssid}")
                ctx?.let { log(it, "ENGINE-UNAVAILABLE bssid=${plan.bssid}") }
            }
        })
    }
}

/**
 * v15 功能3：SharedPreferences-backed 可变 Set——给 NetworkAdapter 的展开状态持久化用。
 * 内存操作实时回写 prefs（apply 异步落盘），App 重启后展开状态保持。
 */
class PrefsStringSet(
    ctx: android.content.Context,
    prefsName: String,
    key: String
) : AbstractMutableSet<String>() {

    private val sp = ctx.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)
    private val key = key
    private val inner = LinkedHashSet<String>(sp.getStringSet(key, emptySet()) ?: emptySet())

    override val size: Int get() = inner.size

    override fun add(element: String): Boolean {
        val changed = inner.add(element)
        if (changed) flush()
        return changed
    }

    override fun remove(element: String): Boolean {
        val changed = inner.remove(element)
        if (changed) flush()
        return changed
    }

    override fun iterator(): MutableIterator<String> = object : MutableIterator<String> {
        private val it = inner.iterator()
        private var last: String? = null
        override fun hasNext() = it.hasNext()
        override fun next() = it.next().also { last = it }
        override fun remove() {
            it.remove()
            flush()
        }
    }

    private fun flush() {
        sp.edit().putStringSet(key, LinkedHashSet(inner)).apply()
    }
}

/** SSID→密码 记忆库：连接时记住密码，同 SSID 再次弹窗自动填入（与锁定方案的 lock_pw 独立） */object PasswordStore {
    private const val PREFS = "pw_store"

    fun save(ctx: android.content.Context, ssid: String, password: String) {
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putString(ssid, password).apply()
    }

    fun load(ctx: android.content.Context, ssid: String): String? =
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(ssid, null)

    fun forget(ctx: android.content.Context, ssid: String) {
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .remove(ssid).apply()
    }

    fun clearAll(ctx: android.content.Context) {
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/**
 * v14：Activity 与 GuardService 共享的守护状态单例。
 * Activity 前台时把 UI 消息从这里取走渲染；Activity 被杀/重建不影响服务侧守护。
 */
object GuardState {
    @Volatile var target: ApItem? = null
    @Volatile var password: String? = null
    @Volatile var useSuggestion: Boolean = false
    /** 服务写给 Activity 的最新状态文案（Activity onResume/轮询时渲染到 tvStatus） */
    @Volatile var uiMsg: String = ""
    /** 服务侧确认的当前连接 BSSID（列表按钮状态同步用） */
    @Volatile var connectedBssid: String? = null
}

/** LockPlan 的 SharedPreferences 持久化（密码单独存，JSON 用 org.json 手拼避免引依赖） */
object LockPlanStorage {
    private const val PREFS = "bsc_prefs"

    fun save(ctx: android.content.Context, plan: LockPlan, password: String?) {
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putString("lock_plan", toJson(plan))
            .putString("lock_pw", password)
            .apply()
    }

    fun load(ctx: android.content.Context): LockPlan? {
        val raw = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString("lock_plan", null) ?: return null
        return fromJson(raw)
    }

    fun loadPassword(ctx: android.content.Context): String? =
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString("lock_pw", null)

    fun clear(ctx: android.content.Context) {
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .remove("lock_plan").remove("lock_pw").apply()
    }

    private fun toJson(p: LockPlan): String = org.json.JSONObject().apply {
        put("ssid", p.ssid); put("bssid", p.bssid); put("band", p.band)
        put("secure", p.secure); put("useSuggestion", p.useSuggestion); put("ts", p.ts)
    }.toString()

    private fun fromJson(raw: String): LockPlan? = try {
        val o = org.json.JSONObject(raw)
        LockPlan(
            ssid = o.optString("ssid"), bssid = o.getString("bssid"),
            band = o.optString("band"), secure = o.optString("secure"),
            useSuggestion = o.optBoolean("useSuggestion", false), ts = o.optLong("ts", 0L)
        )
    } catch (_: Exception) {
        null
    }
}
