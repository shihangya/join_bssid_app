# BssidConnect

Android Wi-Fi BSSID 精确连接工具（Kotlin，minSdk 33 / targetSdk 36）。

## 功能
- 按 **BSSID**（而非 SSID）精确连接 Wi-Fi AP，支持 2.4G/5G/6G
- 双模式：`WifiNetworkSpecifier`（Specifier）/ `WifiNetworkSuggestion`（Suggestion），顶栏切换并持久化
- 厂商默认模式：vivo/OPPO 系默认 Suggestion，其余默认 Specifier
- 防漂移守护：每 2s 校验当前 BSSID，漂移自动重锁（Specifier 重发请求 / Suggestion 重提交建议），稳定 10s 自动清零重锁预算
- nudge 唤醒：Suggestion 提交后选网器不评估时，12s/24s/36s 阶梯重提交+触发扫描
- 锁定方案持久化：杀 App / 重启后自动恢复锁定（含密码）；顶栏「解锁」按钮一键解除
- BootReceiver 开机自动恢复（需用户在系统设置中授予自启动权限；ColorOS/vivo 开机广播白名单会拦截未授权应用）
- 保存密码：连接带密码的网络后密码随锁定方案持久化
- 硬件 MAC 查询指引（Android 11+ 应用层限制，提供 ADB 查询方法）
- 诊断文件日志 files/diag.txt（`adb shell run-as com.dev.bssidconnect sh -c "cat files/diag.txt"`）

## 实测兼容性结论
| 设备 | Specifier setBssid | Suggestion setBssid | 推荐模式 |
|---|---|---|---|
| vivo V2183A (Android 16) | ❌ 完全无视 | ✓ 尊重（配合守护可长期锁定） | Suggestion |
| 一加 PGFM10 (ColorOS, Android 16) | ✓ 尊重（零漂移） | ✓ 但会输给已保存网络 | Specifier |

## 构建
```
gradle assembleDebug
```
要求：JDK 17+、Android SDK 36。无 Compose，纯 View 体系。

## 已知平台限制
- vivo：`WifiNetworkSpecifier` 的 BSSID 约束被厂商连接管理无视（含 `cmd wifi connect-network -b`）
- ColorOS：BOOT_COMPLETED 对非白名单应用标记 `SKIPPED (reason: remote app)`，需手动开启自启动