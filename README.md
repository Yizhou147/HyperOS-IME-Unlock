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

## 下载

云编译产物：push 到 `main` 分支后由 GitHub Actions 自动构建并发布到 [Releases](../../releases)

所有构建使用同一固定签名，可直接覆盖安装升级，无需卸载旧版。

## 特别说明

1. 全面屏优化与百度输入法官方版存在兼容问题，这不属于本模块 BUG。
2. 其他版本的 MIUI / HyperOS 适配依赖系统内部实现，存在不可用的可能性。
3. 如仍有个别输入法布局异常（例如键盘异常抬高），请附带输入法版本号、系统版本号、是否在切换输入法后才出现，以及日志反馈。
4. 不接受任何为特定输入法适配 xxx 的请求，这不现实不合理。
5. 使用了小白条沉浸模块的系统，可能在部分输入法上无法使用全面屏优化。

## 更新日志

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
