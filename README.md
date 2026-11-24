# BssidConnect

Android Wi-Fi BSSID 精确连接工具（Kotlin，minSdk 33 / targetSdk 36）。

按 **BSSID**（而非 SSID）指定接入 Wi-Fi，前台服务 7×24 防漂移守护 + 探活监测，专治厂商 ROM 的"指定 2.4G 却连上 5G"。

## 核心功能

### 连接与锁定
- 按 **BSSID** 精确连接 AP，支持 2.4G/5G/6G，按 SSID 分组展示（信号排序、信道/频段/加密标注）
- 双模式：`WifiNetworkSpecifier`（Specifier）/ `WifiNetworkSuggestion`（Suggestion），顶栏切换并持久化
- 厂商默认模式：vivo/OPPO 系默认 Suggestion，其余默认 Specifier
- 锁定方案持久化：杀 App / 重启后自动恢复锁定（含密码）；顶栏「解锁」按钮一键解除
- BootReceiver 开机自动恢复（需系统设置授予自启动；ColorOS/vivo 开机广播白名单会拦截未授权应用）
- nudge 唤醒：Suggestion 提交后选网器不评估时，12s/24s/36s 阶梯重提交+触发扫描
- 保存密码：连接带密码的网络后密码随锁定方案持久化，再次连接自动预填

### 守护与告警
- 防漂移守护：每 2s 校验当前 BSSID，漂移自动重锁（Specifier 重发请求 / Suggestion 重提交建议），稳定 10s 自动清零重锁预算
- 前台服务常驻（`connectedDevice` 类型）：守护/探活/告警全部在 `GuardService` 中运行，App 退后台/杀掉后守护持续
- 目标消失告警：目标 BSSID 连续 3 分钟不可达且扫描不到 → 高优横幅告警，AP 回归自动解除
- 断流探活告警：每 5s 探测网关（ICMP 失败回退 TCP:80，绑定 Wi-Fi 网络），连续 5 次失败（约 25s）→ "连接存在但无数据"告警，恢复自动解除

### 状态与诊断
- 状态行实时显示：`已连接 <BSSID>（频段，RSSI，速率，延迟，防漂移守护中），IP：x`
  - RSSI/链路速率每 2s 刷新；**IP 颜色随探活**：探测通=绿色，断流/未知=红色
- 点击 IP/状态行 → **网关探活详情弹窗**：
  - 手动 ping（5 发连测，ICMP→TCP:80 回退，逐发延迟+汇总）
  - 探活目标可配置（IP/域名，数字键盘；保存后守护探活每轮动态生效，空=网关回退链 routes→DHCP→.1）
  - **信号趋势曲线**：RSSI（绿）/ RTT（蓝）双轴自绘，环形 360 点=30 分钟历史，随守护采样实时滚动
  - 弹窗 dismiss 后 1.5s 防抖，关后连击状态行不会误触复开
- 诊断文件日志 `files/diag.txt`（256KB 自动轮转），全部守护/探测/告警事件可追溯：
  `adb shell run-as com.dev.bssidconnect sh -c "cat files/diag.txt"`

### 列表与输入
- AP 列表搜索/过滤框（SSID/BSSID 即时过滤）
- 分组展开状态记忆（重启/刷新后保持）
- 扫描限流提示（4 次/2min 系统限流时显示剩余等待秒数，不空发）
- 连接状态自动刷新（连接中→已连接，含服务侧接管路径）

## 实测兼容性结论

| 设备 | Specifier setBssid | Suggestion setBssid | 推荐模式 |
|---|---|---|---|
| vivo V2183A / V2130A (Android 13/16) | ❌ 完全无视（连接管理自选 AP/频段） | ✓ 尊重（配合守护可长期锁定） | Suggestion |
| 一加 PGFM10 (ColorOS, Android 16) | ✓ 尊重（零漂移，钉死网络） | ✓ 但会输给已保存网络 | Specifier |
| moto X40 (Android 14) | ✓ 尊重 | ✓ | 均可 |
| 华为 HONOR (EMUI 10) | 系统限制无法锁 BSSID | — | 不适配 |

后台保活实测：一加/moto 锁屏后守护不停；vivo 系锁屏冻结 App 线程（前台服务也拦），需用户手动开"后台高耗电"。

## 构建

```
gradle assembleDebug
```

要求：JDK 17+、Android SDK 36（platforms;android-36）。无 Compose，纯 View 体系。

权限：ACCESS_FINE_LOCATION / NEARBY_WIFI_DEVICES / POST_NOTIFICATIONS / FOREGROUND_SERVICE(_CONNECTED_DEVICE) / INTERNET / RECEIVE_BOOT_COMPLETED。

## 已知平台限制

- **vivo**：`WifiNetworkSpecifier` 的 BSSID 约束被厂商连接管理无视（连 `cmd wifi connect-network -b` 也拦不住）；强信号驻留网络竞争时 Suggestion 请求会挂起不动作（删除驻留网络后一次提交即锁定）
- **ColorOS**：BOOT_COMPLETED 对非白名单应用标记 `SKIPPED (reason: remote app)`，需手动开启自启动；Suggestion 候选会输给已保存网络（Specifier 可钉死）
- **Android 11+**：应用层无法读取出厂 MAC（`wifi_sta_factory_mac_address` 仅 adb `dumpsys wifi` 可见）
- **App 自建 socket 需要 INTERNET 权限**：WifiManager/ConnectivityManager 读取和系统代建连接都不需要，但探活这类"App 自己发包"的功能必须声明（曾因漏声明导致 socket EPERM 全断）

## 版本史（要点）

- v9 前：Specifier/Suggestion 调研与厂商行为摸底（两平台对 setBssid 的态度差异是本项目核心发现）
- v10：厂商默认模式 + 锁定持久化 + BootReceiver + 解锁按钮
- v11–v13c：密码记忆、图标、IP 显示、状态行红色标注
- v14：前台服务 GuardService + 目标消失告警；v14b 状态自动刷新；v14c RSSI/限流提示/diag 轮转
- v15：搜索过滤 + 展开记忆 + 断流探活告警 + 速率显示；v15b 探活详情弹窗；v15c 补 INTERNET 权限（探活根因修复）
- v15d：IP 颜色随探活（通绿/断红）+ 移除 MAC 行
- v16：探活目标可配置 + 信号趋势曲线；v16b 弹窗定高；v16c 按钮重排；v16d dismiss 防抖
