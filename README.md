# 解锁 HyperOS 全面屏优化

解锁 MIUI / HyperOS 全面屏键盘优化限制，并修复部分输入法解锁后**键盘异常增高**的问题。

> **本项目为 fork**，上游为 [RC1844/MIUI_IME_Unlock](https://github.com/RC1844/MIUI_IME_Unlock)。
> 本 fork 的改动：
> - 移植 [WeType_UI_Enhanced](https://github.com/NEORUAA/WeType_UI_Enhanced) 的"键盘异常增高"修复（该修复已在 HyperOS 4.0 / Android 17 实机验证）
> - 同步上游"输入法服务重建后解锁失效"的修复（上游 PR #34：hook `isImeSupport()` 恒返回 true）
> - 项目更名为"解锁 HyperOS 全面屏优化"
> - 新增 GitHub Actions 云编译，push 到 `main` 自动构建发布
> - 版本号不再跟随上游，自本版本起以 **1.0** 计

## 测试环境

- HyperOS 4.0.0.27 Beta（Xiaomi 17 Pro / Android 17）——键盘增高修复验证环境
- MIUI 12.5（Android 11, API 30）——上游原始测试环境

## 使用方法

Xposed API Version >= 93

在作用域勾选需要解除限制的输入法即可，小米定制版也要勾上

## 修复说明（1.0）

### 键盘异常增高

解锁全面屏优化后，MIUI / HyperOS 会把输入法窗口的可用区域扩展到屏幕底部（含导航栏），
并在底部叠加 MIUI 底栏。部分输入法（如微信输入法 3.5.2、Gboard）会把内容视图填满整个
输入区域，导致键盘内容顶入底栏 / 导航栏区域，键盘看起来异常增高。

本模块 hook `InputMethodBottomManager.addMiuiBottomView`，检测到该情况时：
- 将输入法内容视图底部 padding 减去导航栏 inset；
- 将 fullscreenArea 高度加回导航栏 inset。

把被"顶高"的空间让还给 MIUI 底栏，键盘高度恢复正常；底栏隐藏或异常时自动完整还原，无副作用。

### 切换输入法后失效、键盘贴底

切换输入法（含调用系统安全键盘）会在**同一进程内**销毁并重建输入法服务。此时承载
`InputMethodBottomManager` 的 dex / class 已经加载过，载入流程中"已加载就早退"的分支不会
再置位支持状态，而 `onDestroy` 又会把 `sIsImeSupport` 重置为 `-1`，于是底栏不再添加、
键盘贴底。

本模块在读取端兜住该问题：hook `InputMethodBottomManager` 与 `InputMethodServiceInjector`
的 `isImeSupport()` 布尔方法，使其恒返回 `true`（与上游 PR #34 一致）。

## 修复说明（1.1）

### 百度拼音等"工具栏挂在候选区"的输入法：顶栏悬空、底栏离底

百度拼音官方版（targetSdk 34）解锁后出现两个症状：不打字时工具栏与键盘之间空出整屏级
空洞（实测 751px），打字/切换输入法后 MIUI 底栏离屏幕底部 138px。根因：

1. 这类输入法把工具栏 / 候选栏挂在 `candidatesView` 里，MIUI 重排后 `candidatesArea`
   是 wrap_content，只占 fullscreenArea 顶部一小截，工具栏悬在区域顶部；
2. 键盘收起 / 服务切换的过渡态里 MIUI 底栏会塌缩到窗口顶部（高度 0 但仍 isShown），
   legacy 补偿逻辑误判"底栏没贴底"，清掉了输入法实际需要的窗口 margin，
   窗口被撑满而内容仍是旧几何，底栏反而离底。

修复：过渡态护栏（底栏高度不足导航栏 inset 时不补偿）；把 MIUI 预留的 `extractArea`
占位由 GONE 改为 INVISIBLE，用 LinearLayout 权重把候选区自然压到键盘正上方；
fullscreenArea 缺 weight 时补上 weight=1.0 吸收窗口余量。

## 下载

云编译产物：push 到 `main` 分支后由 GitHub Actions 自动构建，维护人验收后手动发布到
[Releases](../../releases)

每个版本提供两个安装包（签名相同，按需二选一）：

- `Unlock_HyperOS_IME.apk` —— 正常版
- `Unlock_HyperOS_IME_diag.apk` —— 带诊断版：把输入法窗口视图树 / insets 写入
  `/data/data/<输入法包名>/files/miuiime_diag.txt`，遇到布局问题反馈时请附上该文件
  （本 ROM logcat 被系统关闭，这是唯一取证通道；root 下 `cp` 到 `/data/local/tmp` 再取出）

所有构建使用同一固定签名，可直接覆盖安装升级，无需卸载旧版。

## 特别说明

1. 其他版本的 MIUI / HyperOS 适配依赖系统内部实现，存在不可用的可能性。
2. 如仍有个别输入法布局异常（例如键盘异常抬高），请改用带诊断版安装，附带输入法版本号、
   系统版本号、是否在切换输入法后才出现，以及 `/data/data/<输入法包名>/files/miuiime_diag.txt` 反馈。
3. 不接受任何为特定输入法适配 xxx 的请求，这不现实不合理。
4. 使用了小白条沉浸模块的系统，可能在部分输入法上无法使用全面屏优化。

## 更新日志

1.1

    修复百度拼音官方版等"工具栏挂在候选区"的输入法解锁后顶栏悬空（实测 751px 空洞）、
    打字/切换输入法后底栏离底 138px 的问题
    （过渡态护栏 + extractArea 占位下压候选区 + fullscreenArea 余量权重）
    Releases 每个版本提供两个包：正常版与带诊断版（视图树/insets 写入
    /data/data/<输入法包名>/files/miuiime_diag.txt，便于反馈取证）

1.0

    修复切换输入法（含调用系统安全键盘）后全面屏优化失效、键盘贴底
    （切换输入法会在同一进程内重建输入法服务，而底部管理类已加载过，
    早退分支不再置位支持状态；已在读取端 hook isImeSupport() 恒返回 true，
    与上游 PR #34 一致）
    修复部分输入法（微信输入法 3.5.2、Gboard 等）全面屏优化后键盘异常增高
    作用域新增 com.xiaomi.type
    项目更名为"解锁 HyperOS 全面屏优化"
    新增 GitHub Actions 云编译，push 到 main 自动构建发布
    版本号不再跟随上游，自本版本起以 1.0 计（versionCode 16，可覆盖升级旧版 1.17）

v1.16

    Hook 小米短语包名校验，修复第三方输入法无法获取系统剪贴板列表

v1.14

    修复背景颜色翻转屏幕后重置
    不再记录完全透明的颜色

v1.12

    适配 Android 12

v1.11

    重构 MainHook，优化执行逻辑

v1.10

    修复崩溃问题

v1.09

    无实质更新
    加了个try

v1.08

    删除一个hook函数，解决重复hook
    整理代码

v1.07

    增加检查prop ro.miui.support_miui_ime_bottom
    底部按钮改为反色按钮
    尝试在安卓9使用与安卓10相同的hook方法

v1.06

    更换检查MIUI版本为检查Android版本
    删除旧方法依赖的代码

v1.05

    感谢 @ketal178 帮忙删除无用的代码
    删除一些非必要的Hook
    删除一些过时的代码
    尝试修复小爱语音输入按钮失效问题

v1.04

    对 MIUI12 改回使用旧的hook点

v1.03

    加入对 MIUI 版本的检查
    修复 MIUI12 输入法切换菜单不全的问题
    现在仅对 MIUI12、MIUI12.5 生效

v1.02

    优化执行逻辑
    部分hook方法不会对定制版进行hook
    增加对底视图颜色的修改，颜色完全由输入法控制
