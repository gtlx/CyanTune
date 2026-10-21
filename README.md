# 青调 CyanTune

> 插件化、多作用域的 LineageOS / Android Xposed 系统调教工具。`com.gtlx.cyantune`，AGPL-3.0。
> 由 `status-bar-drift`（状态栏漂移）与 `launcher-tweaks`（桌面 180° 旋转）两个旧模块合并重构而来。

- 目标环境：LineageOS 24 / Android 17（SDK 37）、Vector v2.2 Xposed 框架（legacy API 82）、KernelSU root
- compileSdk/targetSdk 35，minSdk 31
- Xposed API：**legacy API 82**（`de.robv.android.xposed` + `assets/xposed_init`），compileOnly 本地 jar，不引入 libxposed 102

## 功能

| Feature | 目标进程 | 说明 |
|---|---|---|
| `statusbar_drift` | `com.android.systemui` | 状态栏周期性水平微移防烧屏（幅度/周期可配） |
| `launcher_rotation180` | `com.android.launcher3` / trebuchet / nexuslauncher | 解锁桌面 180° 反向竖屏；已修 Android 17 上 `onAttachedToWindow` hook 失效问题 |
| `pocket` | `com.android.systemui` | 口袋防误触：锁屏亮屏 + 距离传感器近距时在锁屏窗口盖全屏透明拦截层；可选暗光二次确认；来电响铃强制放行 |
| 总开关 `global.enabled` | — | 关闭后所有 Feature 不生效 |

设置页另提供两个 root 按钮：**重启状态栏 SystemUI**（`killall com.android.systemui`，状态栏自动重启）
与**重启桌面 Launcher**（`am force-stop com.android.launcher3`），用于改 hook 类功能后快速应用，会短暂闪屏/回桌面、不丢数据。

## 口袋防误触

- 触发：仅在 `KeyguardManager.isKeyguardLocked()` 且 `PowerManager.isInteractive()` 时工作；
  距离传感器近距（`values[0] < maximumRange`）即判定在口袋；可选 `pocket.use_light_gate`
  要求同时环境光 < 5 lux（默认关闭，单近距即判定）。
- 动作：向锁屏窗口根 ViewGroup `addView` 一个 `match_parent`、高 `translationZ`、
  clickable/focusable 的透明 View，消费全部触摸；远距/解锁立即 `removeView`（判重防重复 add）。
- 注入点全部 `findClassIfExists` 软探测、逐级 fallback：
  `com.android.systemui.shade.NotificationShadeWindowView`（A14+/A17 首选，未真机验证）
  → `...statusbar.phone.NotificationShadeWindowView`（旧包名）
  → `com.android.systemui.keyguard.KeyguardRootView`
  → `com.android.keyguard.KeyguardHostView`；全部找不到则 warn 安全降级，不崩 SystemUI。
- 白名单：`TelephonyManager` `PhoneStateListener` 监听通话状态（另有 1s 轮询兜底），
  `CALL_STATE_RINGING` 时即使近距也绝不加遮罩（不挡滑动接听）。闹钟白名单本批未实现。
- 传感器生命周期：仅锁屏可见且非响铃时 `registerListener`（优先选 wakeup 距离传感器 0x52），
  解锁/息屏/响铃立即 `unregisterListener`，不常驻耗电；FileObserver 热更，关开关即时撤遮罩+反注册。

## 配置通道（root 全局配置中心）

App UI 经**按需 su**（无常驻守护）一次写两份 Properties 文本（base64 传输规避 su 换行坑）：

1. 主持久文件 `/data/adb/cyantune/config.conf`（目录 0711、文件 0644、root 拥有）
2. 全局可读副本 `/data/local/tmp/cyantune_config.conf`（0644）

目标进程（SystemUI/Launcher）免 root 直读 tmp 副本，`FileObserver` 监听热更；
若被 SELinux 拒绝，自动回退 `createPackageContext("com.gtlx.cyantune", 0)` 读 App filesDir 副本。
两条通道封装在 `ConfigStore` 内部，对 Feature 透明；再失败则使用内置默认值。

配置项：

```
global.enabled=true
statusbar_drift.enabled=true
statusbar_drift.px=4
statusbar_drift.interval_sec=60
launcher_rotation180.enabled=true
pocket.enabled=true
pocket.use_light_gate=false
```

## 编译（在 arch VM，本机不编译）

```bash
ssh arch
export ANDROID_HOME=~/android-sdk
export PATH=~/code/gradle-8.11.1/bin:$PATH
export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
export JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8'
cd ~/prj/code/cyantune
gradle assembleDebug --no-daemon
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

真机日志：`adb shell "su -c 'tail -f /data/adb/lspd/log/modules_*.log'"`，TAG 为 `[CyanTune]`。

开发与架构细节见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。
