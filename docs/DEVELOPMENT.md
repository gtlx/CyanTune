# CyanTune 开发文档

## 1. 插件化架构

```
com.gtlx.cyantune
├── MainHook                 唯一 Xposed 入口；只做“按进程包名路由”，零功能逻辑
├── core
│   ├── Feature              插件接口（id / displayName / targetPackages / 两个回调）
│   ├── FeatureContext       运行时上下文（packageName / classLoader / lpparam），不强依赖 Application Context
│   ├── FeatureRegistry      注册表：显式 listOf(...)，featuresForPackage(pkg) 反查
│   ├── ConfigStore          root 全局配置中心（双通道读取 + FileObserver 热更）
│   ├── RootBridge           按需 su（每次现开，执行完即关）
│   └── TuneLog              统一日志（logcat + XposedBridge.log）
├── feature
│   ├── statusbar/StatusBarDriftFeature.kt
│   ├── launcher/Rotation180Feature.kt
│   └── pocket/PocketGuardFeature.kt
└── ui/MainActivity.kt       合并设置页（纯代码布局，Material Light 主题；含重启 SystemUI/Launcher root 按钮）
```

### Feature 契约

```kotlin
interface Feature {
    val id: String                    // 同时是配置 key 前缀，如 "statusbar_drift"
    val displayName: String
    val targetPackages: Set<String>
    fun onHookLoadPackage(ctx: FeatureContext)   // 注册 hook；此时 Context 不可靠
    fun onConfigChanged(ctx: FeatureContext) {}  // 配置热更新（主线程）
}
```

- **Context 获取**：不要 hook `Application.onCreate`（SystemUI/Launcher 上不可靠，旧仓真机验证过）。
  在自己 hook 到的 View/Activity 上取 `context.applicationContext`，并调用
  `ConfigStore.loadAndWatch(context, ...)` 启动配置读取与热更监听。
- **隔离**：MainHook 对每个 Feature 的调用单独 try/catch，单个 Feature 出错不影响其他。

### 如何新增一个 Feature（验收红线）

1. 新建 `app/src/main/java/com/gtlx/cyantune/feature/<名>/XxxFeature.kt`，实现 `Feature`。
2. 在 `FeatureRegistry.FEATURES` 列表里加一行。
3. 若需注入新进程，在 `res/values/arrays.xml` 的 `xposed_scope` 加包名（供 LSPosed/Vector 默认勾选）。
4. 配置项用 `<feature.id>.xxx` 前缀，key 常量加到 `TuneConfig`。

**框架（MainHook / ConfigStore / 其他 Feature）零改动。**

## 2. 配置通道

见 README “配置通道”。要点：

- 写入只在 App UI 进程：`ConfigStore.saveFromUi(context, TuneConfig(...))`，经 `RootBridge` 执行一段 su 脚本，
  base64 解码写两处，先 `mkdir -p`、写后 `chmod`。
- 读取在目标进程：Feature 拿到 Context 后 `ConfigStore.loadAndWatch(context) { cfg -> ... }`。
  内部顺序：`/data/local/tmp/cyantune_config.conf` → createPackageContext filesDir 副本 → 默认值。
- 热更：监听 tmp 文件所在目录的 `CLOSE_WRITE | MOVED_TO`（兼容写临时文件再 rename），
  reload 后主线程回调所有监听者；MainHook 把变更分发到本进程命中 Feature 的 `onConfigChanged`。
- **SELinux 真机结论待验收**：tmp 直读通道尚未在真机确认；若被拒，日志会出现
  `tmp config channel unavailable ... packageContext fallback` 并自动走 fallback。

## 3. Android 17 桌面旋转 hook 修复

旧版失败点：`findAndHookMethod(Launcher::class, "onAttachedToWindow", ...)` 在 A17 找不到精确方法。

修复（`Rotation180Feature`）：

1. **类解析多级 fallback**（`findClassIfExists` 软探测，谁存在用谁）：
   `com.android.launcher3.uioverrides.QuickstepLauncher`
   → `com.android.launcher3.Launcher`
   → `org.lineageos.trebuchet.Launcher`
2. `setRequestedOrientation` hook `android.app.Activity` 基类（A17 已验证成功），
   且用 `launcherClass.isInstance(thisObject)` 限定只干预 Launcher 实例，
   把 PORTRAIT / SENSOR_PORTRAIT / USER_PORTRAIT 改成 UNSPECIFIED。
3. `onAttachedToWindow` 用 `findMethodExactIfExists` 软探测：
   - 存在 → `XposedBridge.hookMethod` 精确 hook；
   - 不存在（A17）→ 打 `onResume fallback active`，降级 hook `Activity.onResume`（基类必有），
     after 中判断是 Launcher 实例后置 `requestedOrientation = UNSPECIFIED`。
4. 任一 hook 点失败只打一条 warning 继续，不会一个点失败就整体放弃。

真机验收日志应出现：`resolved launcher class: ...QuickstepLauncher`、
`hooked Activity.setRequestedOrientation OK`、`onResume fallback active`（A17 预期走降级），
且不再有 `hook onAttachedToWindow FAILED`。

## 4. 编译与静态自检

编译命令见 README。静态自检：

- `app/src/main/assets/xposed_init` 内容为 `com.gtlx.cyantune.MainHook`
- Manifest 含 `xposedmodule/xposedminversion=82` meta-data、`xposedscope` 数组、`@mipmap/ic_launcher` 图标
- 图标资源：`res/mipmap-xxxhdpi/ic_launcher.png`、`ic_launcher_round.png`（从旧仓整组拷入）

## 5. 口袋防误触（PocketGuardFeature，`feature/pocket/`）

目标进程 `com.android.systemui`，注册在 Registry 第三行。

- **状态机（主线程 1s 轮询 + 传感器/广播事件驱动）**：
  active = `global.enabled && pocket.enabled`；工作条件 = active && `isKeyguardLocked`
  && `PowerManager.isInteractive` && 非响铃；满足时注册传感器并按近距/暗光决定是否加遮罩，
  其余状态一律撤遮罩 + 反注册传感器。
- **传感器**：默认 `TYPE_PROXIMITY`，枚举时优先 `isWakeUpSensor`（真机 wakeup 0x52，息屏可上报）；
  近距判定 `values[0] < maximumRange`；`TYPE_LIGHT` 仅在开启 `pocket.use_light_gate` 时注册，
  阈值 5 lux，无光感或尚无读数时宁可不盖（不误触优先→不误挡）。
- **注入点**：hook 四个候选类的 `onAttachedToWindow/onDetachedFromWindow`（全部
  `findClassIfExists` 软探测，单点失败只 warn）：
  `systemui.shade.NotificationShadeWindowView` → `systemui.statusbar.phone.NotificationShadeWindowView`
  → `systemui.keyguard.KeyguardRootView` → `keyguard.KeyguardHostView`。
  按候选优先级选当前 attached+visible 的 ViewGroup，addView 一个
  MATCH_PARENT、`translationZ=999999`、clickable/focusable、`#00000000` 的 View，
  OnTouchListener 返回 true 吞全部触摸；去重（已挂则只 bringToFront）。
- **白名单**：`TelephonyManager.listen(PhoneStateListener.LISTEN_CALL_STATE)`（SystemUI 特权，
  失败安全降级）+ 每秒 `telephonyManager.callState` 轮询兜底；CALL_STATE_RINGING 时
  强制 `removeCover("ringing,bypass")` 且不注册传感器。闹钟白名单本批未实现。
- **热更**：`onConfigChanged` 里若被关掉，立即 removeCover + unregisterListener。
- 真机日志锚点：`hook injection point: <类名>`、`near → cover`、`far → uncover`、
  `ringing, bypass`、`sensors registered/unregistered`、`cover added/removed (...)`；
  全部找不到注入点时打 `no injection point found ... safe degrade`。

## 6. 设置页重启按钮

`MainActivity.runRootRestart`：点击后后台线程调 `RootBridge.exec`（`killall com.android.systemui`
/ `am force-stop com.android.launcher3`），执行期间两个按钮 disable 且文案变“正在重启…”，
完成回主线程恢复并 Toast；失败 Toast“可能未授予 root 权限”。无新权限/依赖。

## 7. Roadmap

- 后续可选：闹钟响铃白名单（检测 Keyguard 全屏 intent 通知 / AudioManager）、注入点真机确认与调整、
  紧急呼叫入口保护策略。新增 Feature 仍只需 `feature/<名>/XxxFeature.kt` + Registry 一行。
