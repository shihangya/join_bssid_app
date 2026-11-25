package com.dev.bssidconnect

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 二级列表：外层 SSID 分组（可展开），内层 BSSID 明细（每项带"连接"按钮）。
 * 简单实现：展开状态存在 group 层，不跨 ViewModel。
 *
 * @param expandedStore v15 功能3：展开状态的外部持久化集合（Activity 提供，
 *                      底层为 SharedPreferences-backed Set；null 则退化为内存态）
 */
class NetworkAdapter(
    private var groups: List<SsidGroup>,
    private val onConnect: (ApItem) -> Unit,
    private val expandedStore: MutableSet<String>? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val expanded: MutableSet<String> = expandedStore ?: mutableSetOf<String>()

    fun update(newGroups: List<SsidGroup>) {
        groups = newGroups
        notifyDataSetChanged()
    }

    fun isConnecting(item: ApItem): Boolean = item.bssid == connectingBssid
    fun isBssidConnected(item: ApItem): Boolean = item.bssid == connectedBssid

    var connectingBssid: String? = null
    var connectedBssid: String? = null

    class GroupVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvSsid: TextView = v.findViewById(R.id.tvSsid)
        val tvApCount: TextView = v.findViewById(R.id.tvApCount)
        val container: LinearLayout = v.findViewById(R.id.bssidContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_network, parent, false)
        return GroupVH(v)
    }

    override fun getItemCount(): Int = groups.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        holder as GroupVH
        val group = groups[position]
        holder.tvSsid.text = group.ssid
        val best = group.bssids.firstOrNull()
        holder.tvApCount.text = buildString {
            append("${group.bssids.size} 个接入点")
            if (best != null) append(" · 最强 ${best.level}dBm")
        }

        holder.container.removeAllViews()
        val open = expanded.contains(group.ssid)
        if (open) {
            for ((idx, ap) in group.bssids.withIndex()) {
                val row = LayoutInflater.from(holder.container.context)
                    .inflate(R.layout.item_bssid, holder.container, false)
                bindApRow(row, ap, idx == group.bssids.size - 1)
                holder.container.addView(row)
            }
        }

        holder.itemView.setOnClickListener {
            if (open) expanded.remove(group.ssid) else expanded.add(group.ssid)
            notifyItemChanged(position)
        }
    }

    private fun bindApRow(row: View, ap: ApItem, last: Boolean) {
        val tvBssid = row.findViewById<TextView>(R.id.tvBssid)
        val tvBand = row.findViewById<TextView>(R.id.tvBand)
        val tvSecure = row.findViewById<TextView>(R.id.tvSecure)
        val tvChannel = row.findViewById<TextView>(R.id.tvChannel)
        val tvStd = row.findViewById<TextView>(R.id.tvStd)
        val tvSignal = row.findViewById<TextView>(R.id.tvSignal)
        val btn = row.findViewById<Button>(R.id.btnConnect)

        tvBssid.text = ap.bssid
        tvBand.text = ap.band
        tvSecure.text = ap.secure
        tvChannel.text = "ch ${ap.channel}"
        // v19：Wi-Fi 标准标注（11be/11ax/…；未知隐藏，band 不再重复带 ·6 后缀）
        val std = standardName(ap.wifiStandard)
        tvStd.visibility = if (std != null) View.VISIBLE else View.GONE
        tvStd.text = std ?: ""
        tvSignal.text = "${ap.level}dBm"

        when {
            isBssidConnected(ap) -> { btn.isEnabled = false; btn.text = "已连接" }
            isConnecting(ap) -> { btn.isEnabled = false; btn.text = "连接中" }
            else -> { btn.isEnabled = true; btn.text = "连接" }
        }

        btn.setOnClickListener { onConnect(ap) }
    }
}
