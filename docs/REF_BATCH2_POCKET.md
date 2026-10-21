# CyanTune 第二批派单：UI 重启按钮 + 口袋防误触

> 基座已真机验证：插件化 core（Feature/Registry/MainHook 路由/ConfigStore root 双通道）在 Android17/Vector 跑通。
> 工程：/home/gtlx/项目/code/cyantune（virtiofs 挂 arch VM ~/prj/code/cyantune）。
> 红线不变：Xposed API 82；本机不编译走 ssh arch；不 commit/push、不装机（父代理统一做）；不 gradle clean、不改旧仓、不加无关依赖；A17 类名一律 findClassIfExists/findMethodExactIfExists 软探测+容错；中文注释 AGPL-3；同步更新 README/DEVELOPMENT。

## 任务一：设置页加「重启 SystemUI / 重启 Launcher」按钮

文件：app/src/main/java/com/gtlx/cyantune/ui/MainActivity.kt（必要时复用 core/RootBridge）

- 在现有设置页底部（root 状态提示附近）加两个按钮：
  - 「重启状态栏 SystemUI」：`RootBridge.exec("killall com.android.systemui")`（SystemUI 会自动重启，状态栏闪一下）。
  - 「重启桌面 Launcher」：优先 `am force-stop com.android.launcher3`（force-stop 更干净，需 root：`su -c "am force-stop com.android.launcher3"`）。
- 点击后：后台线程执行（不能卡 UI），执行期间按钮 disable + 文案“正在重启…”，完成 Toast 提示；失败 Toast 明确提示可能未授 root。
- 这是改 hook 类功能后“应用生效”的常用手段，文案里说明会短暂闪屏/回桌面，不丢数据。
- 注意：重启 SystemUI 时本设置页若在前台不受影响（设置页是 com.gtlx.cyantune 进程），但截图/通知会瞬断，正常现象。
- 纯 UI + RootBridge 调用，不要引入新权限/依赖。

## 任务二：口袋防误触 PocketGuardFeature（SystemUI/Keyguard 层）

新增独立插件文件：app/src/main/java/com/gtlx/cyantune/feature/pocket/PocketGuardFeature.kt
在 FeatureRegistry.FEATURES 注册一行（targetPackages=setOf("com.android.systemui")）。
架构必须符合既有插件契约（Feature 接口：id/displayName/targetPackages/onHookLoadPackage/onConfigChanged）。

### 硬件（已真机核实）
- 距离传感器两个：Proximity Sensor Non-wakeup(id 0x51) 与 **Wakeup(id 0x52)**。用 Wakeup 那个（息屏也能上报），按 `Sensor.TYPE_PROXIMITY` + 默认传感器获取即可（系统通常给 wakeup 或两个都注册，实现时注册 TYPE_PROXIMITY 默认实例；若默认实例息屏不工作，再用 SensorManager 枚举找 flags 含 wakeup 的 proximity）。
- 环境光 tcs3701 存在（TYPE_LIGHT），可作为可选二次确认。

### 触发判定
- 只在**锁屏且屏幕亮起**时工作：通过 KeyguardManager（context.getSystemService + isKeyguardLocked/isDeviceLocked）判断；PowerManager 判屏幕是否亮。
- 近距传感器上报“近”（values[0] < sensor.maximumRange，或 ==0）→ 判定在口袋。
- 可选配置 use_light_gate：近距 + 环境光很低（阈值可定，如 < 5 lux）才算口袋，默认关闭（单纯近距即可），降低误判。

### 动作（SystemUI 层，稳为先，不碰 system_server）
首选方案：hook Keyguard/锁屏根视图，在“锁屏显示且近距”时给锁屏窗口加一个**全屏透明拦截 View**（FLAG 覆盖在 Keyguard 之上、可拦截触摸），消费触摸事件（onTouchReturn true）防误触；离开口袋（远距）立即移除。
- Hook 点需真机探查 Keyguard 相关类（A17 可能变动），候选：
  - `com.android.systemui.keyguard.KeyguardRootView` / `com.android.keyguard.KeyguardHostView` / scrim/notification panel 容器；
  - 或更稳的兜底：hook StatusBar/Keyguard 的 WindowManager 根 ViewGroup（如 PhoneStatusBarView 所在 window / NotificationShadeWindowView），向其 addView 一个 match_parent、最高 elevation、clickable+focusable 的透明遮罩。
- **所有类名/方法用 findClassIfExists 软探测，逐级 fallback；拿不到任何注入点时打 warning 并安全降级（不要崩 SystemUI）。在报告里写清楚最终真机用的是哪个注入点。**
- 遮罩只在 keyguardLocked && proximity NEAR 时存在；FAR/解锁立即 removeView（注意判重，避免重复 add）。
- 传感器监听生命周期：仅在锁屏可见时注册 registerListener，解锁/遮罩移除后 unregister，避免常驻耗电；用主线程 Handler 或 SensorManager 主线程监听。

### 白名单（必须，防止漏接电话/闹钟）
- 响铃中（TelephonyManager.CALL_STATE_RINGING）**不拦截**：即使近距也不加遮罩（来电界面要能滑动接听）。用 TelephonyManager.listen/PhoneStateListener（或 PhoneStateListener(executor)）监听通话状态，响铃时强制放行。
- 闹钟响铃（AlarmManager/全屏 intent 或 AudioManager.RINGER?）——本批可先只做来电白名单；若方便可检测 Keyguard 上是否有 full-screen 通知/OCLOCK，但不强制。报告里说明闹钟白名单是否实现。
- 紧急呼叫/拨号界面不拦截（这些一般在解锁态或非 keyguard，天然不触发；实现时注意遮罩别挡住 keyguard 上的紧急入口——可只拦截锁屏通知区域，或在近距时连整个锁屏都盖住防误触，这里以“防误触优先”，但响铃必须放行）。

### 配置（加进 ConfigStore 的 TuneConfig）
新增 key（集中常量）：
- `pocket.enabled`（默认 true）
- `pocket.use_light_gate`（默认 false）
受 global.enabled 总开关控制。
ConfigStore 的 TuneConfig data class + encode/parse + DEFAULT 要同步加字段（注意这会改构造参数，确认 UI/其他 feature 编译通过）。
设置页 MainActivity 加「口袋防误触」分组：总开关 + “叠加暗光判定”开关 + 一句说明（来电自动放行）。
FileObserver 热更：开关变更后无需重启 SystemUI 即可生效（onConfigChanged 更新 active 判定；关时若有遮罩立即移除并 unregister 传感器）。

### 验证
- VM `gradle assembleDebug` BUILD SUCCESSFUL，报告 APK 路径+mtime。
- 静态自检：xposed_init/manifest/Registry 注册/新配置 key/编译产物。
- 真机安装与验证由父代理做，子代理不要装机。但要在报告里给出“真机应观察到的日志锚点”：如 `[PocketGuard] near → cover`、`[PocketGuard] far → uncover`、`[PocketGuard] ringing, bypass`、注入点类名、传感器注册/反注册日志。

## 交付
- 构建成功（APK + mtime）；新建/修改文件清单；
- 重启按钮实现说明；
- 口袋：最终选用的 hook 注入点（静态推断，标注未真机验证）、白名单实现、传感器生命周期、新配置项；
- 是否同步更新 README/DEVELOPMENT（必须）。
中文报告。
