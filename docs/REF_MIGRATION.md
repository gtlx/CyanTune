# CyanTune 青调 — 合并重构交接（开发单 REF）

> 本文是父代理给开发子代理的**权威规格**，照做即可，不要重新调研架构。
> 目标：把 `status-bar-drift` 和 `launcher-tweaks` 两个 Xposed 模块合并为一个**插件化、多作用域**的系统调教工具 CyanTune，并修好 Android 17 上失效的桌面旋转 hook。口袋模式是**下一阶段**，本单不实现功能（仅可留接口位置）。

## 0. 真机事实（已核实，直接采信）

- 设备：Redmi Note12 Turbo (marble)，**LineageOS 24 / Android 17 / SDK 37**（不是 SDK 34）。
- Root：**KernelSU 3.3.1**；Xposed 框架是 **Vector v2.2（API Version 102，legacy bridge 兼容 API 82）**。`su` 可用，`adb` 已连。
- 桌面真实入口 Activity：**`com.android.launcher3.uioverrides.QuickstepLauncher`**（继承 `com.android.launcher3.Launcher`），包名 `com.android.launcher3`。
- 距离传感器有两个（口袋模式下阶段用）：`proximity Non-wakeup`(0x51) 与 `proximity Wakeup`(0x52)。
- 编译机：**本机不编译**，一律 `ssh arch`（virtiofs 已把本机 `~/项目/code` 挂到 VM 的 `~/prj/code`，同一份文件，零同步）。
- Vector 日志：`adb shell "su -c 'tail -f /data/adb/lspd/log/modules_*.log'"`，我们的 TAG 会以 `[CyanTune]` 出现。

### 旧两仓现状（只读参考，禁止修改它们）
- `/home/gtlx/项目/code/status-bar-drift`（包 com.gtlx.statusbardrift, v0.2.0）
- `/home/gtlx/项目/code/launcher-tweaks`（包 com.gtlx.launchertweaks, v0.1.0）
- 新仓：`/home/gtlx/项目/code/cyantune`（骨架已建：settings/build.gradle.kts、gradle.properties、app/build.gradle.kts、AndroidManifest、assets/xposed_init、res/values、libs/xposed-api-82.jar；core 下已有父代理草拟的 Feature.kt/TuneLog.kt/RootBridge.kt，可按需调整）。

## 1. 定型决策（不得更改）

- 名称：**青调 CyanTune**；applicationId/namespace = `com.gtlx.cyantune`；versionName `0.1.0`, versionCode 1。
- Xposed API：**继续用 legacy API 82**（`de.robv.android.xposed` + `assets/xposed_init` + `IXposedHookLoadPackage`），compileOnly 本地 jar。**不要引入 libxposed 102 / 不要加 maven 仓库**。
- 配置通道：**root 方向**，放弃旧 ContentProvider。单一全局配置文件，所有目标进程（SystemUI/Launcher…）读同一份。
- root 形态：**按需 su**（RootBridge 已草拟），无常驻守护。
- compileSdk/targetSdk = 35（build.gradle 已写，可保留 35；minSdk 31）。
- 中文注释、AGPL-3.0 头、Material 主题、简洁设置 UI（Soft UI 非本单重点，能用即可）。

## 2. 插件化架构（验收红线：必须真落地，不是纸上接口）

```
com.gtlx.cyantune
├── MainHook                 唯一 Xposed 入口；只做“按进程包名路由”
├── core
│   ├── Feature              插件接口
│   ├── FeatureContext       运行时上下文（classLoader/lpparam/config/log）
│   ├── FeatureRegistry      注册表：List<Feature>，按 packageName 反查命中的 feature
│   ├── ConfigStore          root 全局配置中心（见 §3）
│   ├── RootBridge           按需 su（已草拟，可完善）
│   └── TuneLog              统一日志（已草拟）
├── feature
│   ├── statusbar/StatusBarDriftFeature.kt
│   └── launcher/Rotation180Feature.kt
└── ui/MainActivity.kt       合并设置页
```

### Feature 契约（最终形态）
```kotlin
interface Feature {
    val id: String            // 如 "statusbar_drift"，同时是配置 key 前缀
    val displayName: String
    val targetPackages: Set<String>
    fun onHookLoadPackage(ctx: FeatureContext)   // 注册 hook；不要假设 Context 已就绪
    fun onConfigChanged(ctx: FeatureContext) {}  // 配置热更新
}
```
- **FeatureContext 不强依赖 Application Context**：SystemUI/Launcher 的 Context 由各 Feature 在自己 hook 到 View/Activity 时获取（旧代码已验证 Application.onCreate hook 不可靠，沿用“hook PhoneStatusBarView / Launcher.onCreate 拿 context”的成熟做法）。配置读取走 ConfigStore（读文件不需要 Context）。
- `FeatureRegistry`：显式 `listOf(StatusBarDriftFeature, Rotation180Feature)`；提供 `featuresForPackage(pkg): List<Feature>`。
- **MainHook 只路由**：`handleLoadPackage` 里构造 FeatureContext，取 registry 命中当前 `lpparam.packageName` 的 feature 逐个 `onHookLoadPackage`；配置变更由 ConfigStore 的 FileObserver 统一回调到当前进程命中的 feature 的 `onConfigChanged`。
- 新增一个功能应满足：**新建一个 Feature 文件 + 在 Registry 加一行，框架其余零改动**（在 README 写明）。

## 3. root 配置中心 ConfigStore（替代两个旧 ConfigProvider/DriftConfig）

- 存储格式：`java.util.Properties` 文本。
- **写入（仅 App UI 进程，经 RootBridge）**：一次 su 脚本写两份：
  1. 主持久：`/data/adb/cyantune/config.conf`（目录 0711，文件 0644，root 拥有）
  2. 全局可读副本：`/data/local/tmp/cyantune_config.conf`（0644，`chmod` 保证 SystemUI/Launcher 等 uid 可读；其父目录 `/data/local/tmp` 默认可进入）
  - 用 `mkdir -p` + `cat`/`tee` 写入后 `chmod 644`，注意 su 下中文/换行坑（用 base64 传输最稳：把内容 base64 后 `echo <b64> | base64 -d > 文件`，避免 echo 吞换行）。
- **读取（目标进程，免 root 直读）**：直接读 `/data/local/tmp/cyantune_config.conf`；不存在/解析失败→内置默认值并打日志。
- **热更新**：对 `/data/local/tmp/cyantune_config.conf` 注册 `android.os.FileObserver`（CLOSE_WRITE/MOVED_TO），变更后 reload + 分发给命中 feature。
- App 端读回当前值：同样读 `/data/local/tmp` 副本即可（读不到再读默认）。
- **SELinux fallback（实现时真机验证）**：先按上面做，安装后必须在真机确认 SystemUI 进程真能读到该文件（看日志 `[CyanTune] config loaded`）。若 SELinux 拒绝（读不到/permission denied），fallback 到旧仓已验证的 **createPackageContext 方案**：App filesDir 文件 644 + 上层目录 711，目标进程 `context.createPackageContext("com.gtlx.cyantune",0)` 读取。把两条通道都封装在 ConfigStore 内部，对 Feature 透明，优先 tmp 文件、失败回退 packageContext。报告里写明真机最终走的是哪条。

### 配置项（合并两个旧模块）
- `statusbar_drift.enabled`(bool, 默认 true)、`statusbar_drift.px`(int 默认 4)、`statusbar_drift.interval_sec`(int 默认 60)
- `launcher_rotation180.enabled`(bool, 默认 true)
- 统一加一个总开关 `global.enabled`(bool 默认 true)，false 时所有 feature 不生效。
- 用合理前缀，Properties key 字符串集中成常量。

## 4. 两个 Feature 的迁移与修复

### StatusBarDriftFeature（迁移，目标包 com.android.systemui）
- 完整搬旧 `StatusBarDriftFeature.kt` 的漂移循环逻辑（Handler postDelayed、translationX ±px、attach/detach、onConfigChanged 重启循环、view 未测量时 `view.post{}` 重试）。
- hook `com.android.systemui.statusbar.phone.PhoneStatusBarView` 的 onAttachedToWindow/onDetachedFromWindow（旧法已在真机验证 OK，保留）。
- 区别：配置从 ConfigStore 读（不再是 DriftConfig/Provider）；受 `statusbar_drift.enabled` 与 `global.enabled` 控制。
- 注意旧 MainHook 日志里曾出现一次 `ClassNotFoundException: PhoneStatusBarView` 的**第二次命中**（多 classLoader），要对 findClass 失败容错（try/catch 跳过该次命中，不要崩）。

### Rotation180Feature（迁移 + ★修 Android 17 hook★，目标包 launcher3/trebuchet/nexuslauncher）
旧版报错根因（真机日志）：
```
[LauncherTweaks] [Rotation180Feature] hooked Activity.setRequestedOrientation OK
[LauncherTweaks] [Rotation180Feature] ERROR: hook onAttachedToWindow FAILED
```
即 `findAndHookMethod(Launcher::class, "onAttachedToWindow", ...)` 在 Android 17 找不到精确方法（方法被移动/签名变化）。要求：
1. **类解析多级 fallback**：依次尝试 `com.android.launcher3.uioverrides.QuickstepLauncher` → `com.android.launcher3.Launcher`（trebuchet 类名也兜底），用 `XposedHelpers.findClassIfExists`，谁存在用谁。
2. `setRequestedOrientation` hook 保留（hook `android.app.Activity` 基类，已成功），把 PORTRAIT/SENSOR_PORTRAIT/USER_PORTRAIT 改成 UNSPECIFIED。
3. **onAttachedToWindow 软探测 + 降级**：先用 `XposedHelpers.findMethodExactIfExists(launcherClass,"onAttachedToWindow")` 探测；存在才 hook；**不存在则降级 hook Activity.onResume**（基类必有），在 afterHookedMethod 里判断 `thisObject` 是该 launcher 实例时设置 `requestedOrientation = UNSPECIFIED`。任何一级失败只打一条 warning 并继续，**不允许一个 hook 点失败就整体 ERROR 放弃**。
4. 真机验证：重启 Launcher 后日志应同时出现 setRequestedOrientation hook OK，且不再有 `onAttachedToWindow FAILED`（降级路径打 `onResume fallback active` 之类）。

## 5. UI（MainActivity，简洁可用即可）
- 一个页面，Material Light NoActionBar（沿用）：
  - 总开关；
  - 「状态栏漂移」分组：开关 + 幅度 px（SeekBar 2–8 或步进）+ 周期间隔秒；
  - 「桌面 180° 旋转」分组：开关；
  - 底部一行小字：root 状态（ConfigStore 写入是否成功）与“保存后实时生效，部分需重启对应进程”。
- 保存→ConfigStore.save（root 写两处）；写入失败给明确提示（说明可能未授 root）。
- **图标**：Manifest 引用 `@mipmap/ic_launcher`，必须提供真实 mipmap 资源（从 launcher-tweaks 或 status-bar-drift 的 `app/src/main/res/mipmap-*` 整组拷过来），否则编译失败。
- strings 用资源文件（已建 res/values/strings.xml、arrays.xml）。

## 6. 构建与验收（在 arch VM）

编译（注意中文路径，走英文软链 + UTF-8）：
```bash
ssh arch
export ANDROID_HOME=~/android-sdk
export PATH=~/code/gradle-8.11.1/bin:$PATH
export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
export JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8'
cd ~/prj/code/cyantune
gradle assembleDebug --no-daemon
```
要求 `BUILD SUCCESSFUL`，产出 `app/build/outputs/apk/debug/app-debug.apk`。

**禁止**：
- 禁止 `git add/commit/push`（提交由父代理统一做）。
- 禁止 `gradle clean` / 删全局缓存 / flutter clean；只允许删本项目 build 子目录。
- 禁止修改 status-bar-drift、launcher-tweaks 两个旧仓。
- 禁止在本机（非 VM）编译，禁止改动 Android SDK/Gradle/Java 环境，禁止修 VM 环境问题（遇到环境问题停下报告）。
- 不要顺手加无关依赖/仓库/Manifest 组件。

**真机冒烟（可选但加分，adb 已连）**：把 APK 路径交给父代理即可，是否安装由父代理决定；你也可以只做静态自检（清单 meta-data、xposed_init 入口全限定名、R 资源引用、mipmap 存在）。

## 7. 交付物
1. 可编译的 cyantune 工程（BUILD SUCCESSFUL + APK 绝对路径与构建时间戳）。
2. 新建/修改文件清单。
3. 插件化对账：证明“加功能只需新文件+Registry 一行”（Registry/MainHook 实际代码）。
4. 旋转 hook 修复点说明（用了哪种类 fallback / onAttached→onResume 降级）。
5. ConfigStore 真机/静态的 SELinux 通道结论（若未上真机，标注“未真机验证”，由父代理验收）。
6. README + docs/DEVELOPMENT.md（中文：架构、如何新增 Feature、配置通道、编译方法）；暂不写 pocket 实现细节，可在 roadmap 列“口袋模式（规划中）”。
