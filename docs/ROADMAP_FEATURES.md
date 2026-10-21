# CyanTune 功能批次规划（框架单验收通过后再做）

> 依赖：先完成并真机验证 docs/REF_MIGRATION.md 的插件化底座（Feature/Registry/ConfigStore）。
> 所有功能都做成独立 Feature 文件 + Registry 注册一行，走统一 ConfigStore（root 全局配置）。
> 用户已定：只做这三个，其余候选（网速/高刷/时钟电量增强等）一律不做。

## 批次顺序建议
1. 防烧屏全家桶（与现有 drift 同作用域、同进程，风险最低，顺带验证插件扩展）
2. 口袋防误触（已基本定型，SystemUI/Keyguard 层）
3. 三指下滑截屏（手势检测点需真机调研，风险最高，放最后）

---

## Feature A：防烧屏全家桶（BurnInGuard）
- 作用域：`com.android.systemui`
- 现状：已有 statusbar 整体水平漂移（StatusBarDriftFeature）。本功能是“全家桶”，在其之外补可独立开关的细分位移，复用同一套 Handler 定时循环与 attach/detach 生命周期思路。
- 子项（每项独立配置开关/幅度）：
  - 已存在：状态栏整体水平漂移（保留为独立 Feature 或并入本族，见实现时取舍，注意不重复位移同一 View）。
  - 新增：状态栏图标/时钟区微小位移（translationX/Y 2–4px，周期更长更慢，避免视觉跳动）。
  - 新增：导航条（gesture/three-button navbar）轻微水平/垂直位移。
- 原则：幅度小、周期长（默认 4px / 数分钟级），用户无感；只在常亮区域做。
- 关键：先在真机 dumpsys 定位状态栏各子 View 与 NavigationBarView 的类名/层级（A17 可能变动），对 findClass 失败容错，单个子项失败不影响其余。
- 配置：`burnin.<子项>.enabled`、`burnin.px`、`burnin.interval_sec`、总开关继承 global.enabled。

## Feature B：口袋防误触（PocketGuard）
- 作用域：`com.android.systemui`（Keyguard/锁屏层，用户已选“稳”方案，不碰 system_server）
- 硬件（已核实）：proximity Non-wakeup(0x51) + Wakeup(0x52)；ambient light tcs3701（可辅助判断“近距且暗光=口袋”）。
- 判定：锁屏态(KeyguardManager isKeyguardLocked / isDeviceLocked) + 近距传感器 NEAR（可选叠加环境光很暗）= 在口袋。
- 动作（SystemUI 层）：
  - 在锁屏窗口覆盖一个透明拦截层，吞掉触摸/按键，防误触拨号/相机/紧急呼叫；
  - 或检测到在口袋且被误点亮时强制熄屏（goToSleep，走 root/或 SystemUI 已有能力，实现时按真机可行性定）。
- ★白名单：来电/响铃状态（TelephonyManager CALL_STATE_RINGING）必须不拦截、不熄屏，保证能正常接电话；闹钟响同理放行。
- 出袋恢复：距离变 FAR 即移除拦截层，恢复正常。
- 配置：`pocket.enabled`、`pocket.use_light_gate`（是否叠加暗光判定，默认关，单纯近距即可）、`pocket.force_sleep`（是否强制熄屏，默认关，先只做拦截层更稳）。
- 风险：传感器在 SystemUI 进程注册的生命周期/耗电（只在锁屏显示时注册监听，解锁立即反注册）；Wakeup 传感器选择需真机测上报可靠性。

## Feature C：三指下滑截屏（ThreeFingerScreenshot）
- 缺口已确认：LineageOS 24 marble 无三指下滑截屏（系统“three finger”字样仅外接触摸板导航；settings/LineageParts 无入口）。
- 作用域与检测点（★需真机调研，未拍板）：
  - 首选在 SystemUI 内找能收到全局触摸的注入点（手势处理器/导航相关类）检测“三指接触 + 整体向下滑动”MotionEvent（三点同时 down、累计下滑距离过阈、横向偏移小）。
  - 系统原生三指截图在 AOSP 是 framework 层 PhoneWindowManager(system_server) 经 config 开启；SystemUI 只负责执行（ScreenshotHelper/TakeScreenshotService）。因此 SystemUI 层手势检测可能有边角不稳风险；若真机证实 SystemUI 拿不到全局触摸，再与用户确认是否升级到 system_server hook（风险高，崩了重启整机）。
- 执行截图：SystemUI 是 system uid 且有 screenshot 权限，优先直接 hook/调用其内部截图入口（ScreenshotHelper/ScreenshotController）触发，等价于按键截屏；避免自己用 MediaProjection（要弹窗授权，体验差）。
- 防冲突：检测成功后消费手势，避免同时触发通知栏下拉；加冷却（如 1.5s 一次）。
- 配置：`threefinger.enabled`、灵敏度（滑动距离阈值，提供 低/中/高）。
- 验收：任意界面三指下滑触发系统截图动画与截图保存；来电/键盘输入等场景不误触发。

---

## 派单通用红线（每单 context 都带）
- 本机不编译，`ssh arch`（virtiofs），gradle assembleDebug --no-daemon，UTF-8 环境（见 REF §6）。
- 不 commit/push、不装机（父代理统一提交+真机验收）；不 gradle clean、不动旧仓、不加无关依赖；坚持 Xposed API 82。
- A17 类名/方法可能变动：一律 findClassIfExists/findMethodExactIfExists 软探测 + 多级 fallback，单点失败 warn 不崩。
- 每 Feature 自带开关，受 global.enabled 总开关控制；配置走 ConfigStore + FileObserver 热更。
- 中文注释、AGPL-3、中文 README/DEVELOPMENT 同步更新（用户文档洁癖硬性要求）。
