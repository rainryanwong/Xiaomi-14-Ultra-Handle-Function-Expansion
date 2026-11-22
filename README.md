# 摄影手柄功能拓展（HandleFix）

为 **Xiaomi 14 Ultra 专业摄影手柄** 提供功能拓展的 **LSPosed 模块**（包名 `com.handlefix.all`）。
可按相机模式自定义 **录像键 / 快门键 / 自定义拨轮 / 变焦拨杆** 的功能，支持 **录像键长按预览照片** 与
**快门键长按高速连拍**，
并同时适配 **Prometheus Cam 移植相机** 与 **官方相机（HyperOS 3 / HyperOS 4）**。

- 当前版本为 **v13.0.0**。
- [GitHub Releases](https://github.com/rainryanwong/Xiaomi-14-Ultra-Handle-Function-Expansion/releases) 提供正式 APK 和版本源码。

---

## 一、适配范围

| 相机 | 版本 | 说明 |
|---|---|---|
| Prometheus Cam 移植包 | 模块版 V5.1（内嵌 V5.0 相机） | 仅检测到精确 versionCode=750005100 时显示「旧版 Prometheus Cam 手柄适配」；专业拍照模式支持录像键短按打开「专业模式参数预设」 |
| Prometheus Cam 移植包 | NRV-1.3.2 / NRV-1.5.0 / NRV-1.6.1 | 专业拍照模式支持录像键短按打开「专业模式参数预设」；新版基础手柄修复由相机/LSP 提供，建议关闭旧版开关避免重复挂载 |
| Phoenix 移植相机 | Phoenix-0.3.0 及同一 OS4 版本组（基于 HyperOS 4.0 6.6.000510.0） | 选择「Prometheus Cam」方案；传奇街拍 229 自动继承普通街拍 225 的手柄配置；按键列表按已验证过滤表开放「徕卡一瞬」；作者 LSP 风格切换按运行时 Store API 判断 |
| 官方相机（HyperOS 3） | 6.4.000370.0 / 6.4.000530.0 | promFix=关 走 OemHooks |
| 官方相机（HyperOS 4） | 6.6.000510.0 | promFix=关 走 OemHooks（按已验证 descriptor 与能力门适配） |
| 6.8相机移植（小米18Pro系列相机，HyperOS 4.0） | 6.8.001770.0 | 选择「6.8相机移植」映射。基础手柄功能由相机移植模块提供，徕卡经典多 LUT 复用 CameraCompat。三种街拍的取景修复已在 CameraCompat 0.2.86 上验证。 |

首页「适配版本」弹窗有同步说明；其他版本可自测。

## 二、核心开关（模块主界面）

进入模块时检查 Xiaomi 14 Ultra 机型标识，任一已知标识匹配即可放行。
“模块设置与调试”中的“模块诊断”统一提供环境检查与模块诊断两个页签，共用开发者选项和显示模式编号。
“主题设置”可选择跟随系统、浅色或深色；主题随现有配置备份与恢复。
诊断报告保存到 Download/HandleFixConfig/Reports。

- **启用模块功能**：总开关。
- **手柄按键映射方案**可选 Prometheus Cam、官方相机或 6.8相机移植。
- **旧版“Prometheus Cam”手柄适配**（V5.1 开关）：仅检测到模块版 V5.1 内嵌 V5.0 相机的精确 versionCode=750005100 时显示；其它版本隐藏但保留已保存配置，运行时也只按精确版本启用。
- **Phoenix 街拍样式兼容**：当前 Phoenix 上游已经提供街拍样式自定义，模块界面不再显示「启用街拍特别款」和「启用街拍特别款（无手柄）」两个旧开关，但保留兼容代码与已有配置。传奇街拍 229 会在精确版本和入口检查通过后继承普通街拍 225 的手柄配置，不强开相机门控。
- **任意界面长按录像键**：默认关闭；可选择「仅跳转录像界面」或「跳转并启动录制」，响应时间可在 500–1000ms 间调整。相机已经在前台时，当前模式的录像键映射优先。
- **半按对焦策略**：可保留摄影手柄原生半按对焦、禁用手柄半按锁焦，或调用相机原生 FocusManager 对画面中心执行一次对焦。
- **使用摄影手柄充电 / 手柄充电保护**：位于「模块设置与调试」的手机/手柄电量卡下方。主开关通过 Root 同步 `sec_setting_handle_charge_state` 与 `handle_stop_charging`；保护开关写入 `sec_setting_handle_charge_protect`，由系统原生 `CameraHandleProtectManager` 负责约 80% 阈值和 `smart_chg` 仲裁。关闭主开关时仅收起保护选项，不改写保护开关原有状态；再次开启后恢复显示。
- **锁屏/息屏禁用手柄按键**：默认关闭，点击入口进入独立设置页。可选择「锁屏和息屏」「仅锁屏」或「仅息屏」，并多选屏蔽快门、变焦拨杆、拨轮和录像键；选择「全按键不响应」时四个分类项置灰。拦截发生在系统输入策略唤醒前，解锁后自动恢复；功能不解绑 USB、不改变手柄连接及充电状态，设备身份或系统状态读取失败时放行。

录像键配置分为短按、短长按和深长按：短长按阈值为 500–800ms，深长按阈值为 1000–1500ms；两段动作只在松键时择一执行。主页副标题会按这些类别分别统计自定义条目。录像键短按「滤镜选择」已适配 Phoenix OS4 版本组、V5.1/NRV 系列、官方 OS3 6.4.000370.0/6.4.000530.0 和官方 OS4 6.6.000510.0，支持拍照、录像、专业拍照、专业录像、人像、街拍（含特别款）与超清；再次短按使用相机原生卸载流程保持当前滤镜并关闭面板。V5.1/NRV 和 Phoenix 的前摄拍照、录像场景会在真实滤镜页可见时由拨轮切换相邻滤镜，其余场景继续交给相机原生拨轮链。快门键长按提供「徕卡经典/生动轮拍」和「高速连拍」，快门长按响应时间为 500–1000ms；高速连拍仅在已确认能力的拍照 163 与普通街拍 225 显示。

超清 175 现在作为常用模式直接显示。普通状态仍按相机版本、模式与已核实能力过滤功能；开启开发者选项后，四类按键及其长按页会显示各自完整功能目录并允许保存，但不会绕过相机进程内的版本、真实 descriptor 与模式安全门，因此未适配组合可能不生效。

备份会保存完整配置根对象，包括全部按键映射、录像键两段长按、快门键短按/长按功能码、`shutterLongPressMs`、徕卡经典风格自定义、街拍特别款、系统快捷录像、半按对焦和界面选项。导入时以备份自身的相机方案执行兼容清洗，并将快门长按阈值归一化到 500–1000ms，再同步恢复相机进程和 `system_server` 的配置镜像。

## 三、构建

环境：
- JDK 21（本项目优先使用 `D:\DevTools\jdks\jdk-21.0.12.1+1`）
- Android SDK（compileSdk 37 / minSdk 33 / targetSdk 34）
- Gradle wrapper 9.7.0（自动下载）

命令：
```powershell
$env:JAVA_HOME = "D:\DevTools\jdks\jdk-21.0.12.1+1"
.\gradlew.bat :app:assembleRelease
```
产物：`app/build/outputs/apk/release/app-release.apk`；或直接 `powershell -ExecutionPolicy Bypass -File build.ps1`（会顺带复制到 outputs 并命名带版本号）。

公开源码可运行 `.\gradlew.bat :app:assembleDebug` 构建本地调试 APK。正式包的签名密钥由维护者保存。

> 注意：AGP 9 内置 Kotlin，**不要**在 `app/build.gradle.kts` 重复添加 `org.jetbrains.kotlin.android` 插件，否则编译报错。

## 四、安装 / 使用

1. LSPosed 勾选模块，作用域勾选 **[相机] 和 [系统框架]**；
2. 重启手机生效（模块安装/更新后必须重启）；
3. 首次使用建议先开一次相机，再回模块「模块设置与调试 → 模块诊断」验证 hook 是否加载。

打开模块 App 后通过已有相机/系统作用域进行约 2 秒的实时加载检测。当前版本的运行时响应可确认至少一个作用域已加载；无响应只能判定「未确认」，不能等同于模块关闭。提示提供「重新检测 / 继续进入设置」，继续进入不会伪造检测成功；返回键可退出。进入后，二级页面返回不重新挂起主界面，保留所在分页与滚动位置。传统 API 82、Root JSON 和原有诊断均保留，不读取 LSPosed 数据库、不修改作用域、不依赖旧日志，不迁移现代 Hook API。

Phoenix 系列选择「Prometheus Cam」映射后，模块会按精确版本与入口能力提供传奇街拍 229 的配置继承以及「徕卡一瞬」候选项；模块不会替代 Phoenix 上游的街拍样式开关，也不会强行打开相机模式门控。

## 五、源码结构

```
app/src/main/java/com/handlefix/
├── HandleFixModule.java      LSPosed 入口：版本判定 + 映射 Profile 分发安装
├── CameraBuilds.java         已验证 versionCode、Phoenix OS4 版本族与精确版本门
├── ModeConfig.java           配置体系（JSON）+ 开关判定 + 过滤表清洗
├── CameraCapabilityRegistry.java  按版本/真实入口维护安全能力门
├── RecordLongPressDispatcher.java 录像键短长按/深长按的唯一松键分发入口
├── ShutterHoldBurstController.java 快门长按高速连拍首拍接受门与独立手势事务
├── LeicaStateAdapter.java    徕卡经典/生动只读状态适配（与多 LUT 分离）
├── FocusPolicyHooks.java     半按对焦原生/禁用/中心单次 AF 策略
├── GripInputGuard.java       锁屏/息屏状态、手柄输入身份与分类屏蔽判定
├── GripMotionGuardHooks.java system_server 拨轮 MotionEvent 的窄范围拦截
├── QuickVideoLaunchHooks.java 将系统快捷录像请求接回相机原生 VideoModule gate
├── ClassicStyleController.java Phoenix/NRV 作者多 LUT 风格目录与切换控制器
├── NativeFilterPanelHooks.java 多相机族滤镜面板打开/拨轮同步/原生卸载
├── PromHooks.java            PrometheusCam 老包（V5.1 以下）共有补丁 fix1-5/S0
├── LegacyParamPanelHooks.java 旧版 Prometheus/NRV 专业参数预设录像键入口
├── PhoenixWheelHooks.java     Phoenix OS4 版本族专业参数预设波轮入口
├── LeicaHooks.java           徕卡经典/生动切换 + 手柄设置页注入
├── ModeAwareHooks.java       模式感知按键映射（独有）+ NRV 门槛
├── ZoomHooks.java            变焦拨杆 / 快门连拍 / 焦段（独有）
├── OemHooks.java             官方 / Phoenix OS4 Profile 映射 + 快门决策
│                              官方 Profile 有 OS4 EV 显示补偿；Phoenix 仅夜景 169 复用同一协议
│                              顶部保留历史混淆类名候选；支持清单以已实机验证版本为准
├── EnvHooks.java             版本探测 + 环境记录 + 模式缓存
├── StreetSpecialHooks.java   Phoenix OS4 传奇街拍：版本族 + 229 入口校验及 225 配置别名
├── StreetSpecialWithoutGripHooks.java 相机手柄工厂作用域内的只读状态覆盖
├── activation/              API82 实时加载探测、请求校验、单一状态源
├── system/SystemHandleFix.java  系统层按键透传入口
├── system/GlobalRecordVideoHook.java 任意界面长按录像键与 VIDEO_CAMERA 启动
└── ui/                        Miuix Compose UI（主页/按键配置/环境检查/模块诊断/备份恢复）
    ├── MainActivity.kt        主界面、系统快捷录像与半按对焦策略
    ├── GripInputGuardActivity.kt 锁屏/息屏输入保护独立设置页
    ├── KeyConfigActivity.kt   按键模式配置（过滤表）
    ├── EnvCheckActivity.kt / HookProbeActivity.kt   环境检查 / 模块诊断
    ├── GripChargeSettings.kt  手柄充电主开关、80% 原生保护与能力检查
    ├── ImportBackupActivity.kt / ModuleTools.kt     备份恢复 / 重启检测
    ├── ModuleTexts.kt         使用说明 / 更新日志 / 提示文案
    └── ...
```

## 六、文档索引

- `README.md`：本文件
- [6.8 相机移植后续事项](docs/camera68-port.md)

## 七、协作约定（重要）

- **git 推送**：只在用户明确要求时 `git push`，平时提交仅留在本地；
- **版本号**：12.0.x 迭代，默认逢百进位（如 12.0.100 → 12.1.0），用户指定才直接进位；
- **版本源码**自 13.0.0 起在 Releases 单独提供。模块里的「本模块项目地址」打开仓库主页。
- **修复原则**：按根因做最小改动，不要补丁叠叠乐；新代码补注释（写清目的与原理）；
- **相机类名**：以 OemHooks 顶部映射表 + dex 反查为准，勿照抄 jadx 显示名（大小写冲突会改名）。
