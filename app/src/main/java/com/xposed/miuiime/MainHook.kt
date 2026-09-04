package com.xposed.miuiime

import android.content.Context
import android.os.Binder
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.Log
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.getStaticObject
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.hookReplace
import com.github.kyuubiran.ezxhelper.utils.hookReturnConstant
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAuto
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAutoAs
import com.github.kyuubiran.ezxhelper.utils.invokeStaticMethodAuto
import com.github.kyuubiran.ezxhelper.utils.loadClassOrNull
import com.github.kyuubiran.ezxhelper.utils.putStaticObject
import com.github.kyuubiran.ezxhelper.utils.sameAs
import dalvik.system.BaseDexClassLoader
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

private const val TAG = "miuiime"

class MainHook : IXposedHookLoadPackage {
    private val miuiImeList: List<String> = listOf(
        "com.iflytek.inputmethod.miui",
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.baidu.input_mi",
        "com.miui.catcherpatch",
        "com.xiaomi.type",
    )
    private val imeUnlockClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val monitoredImeInputFrames = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val originalImeContentBottomPaddings = WeakHashMap<View, Int>()
    private val originalFullscreenAreaHeights = WeakHashMap<ViewGroup, IntArray>()
    private val adjustedImeContentViews = WeakHashMap<ViewGroup, WeakReference<View>>()
    private val miuiBottomFrameViews = WeakHashMap<
        ViewGroup,
        Triple<WeakReference<ViewGroup>, WeakReference<View>, WeakReference<View>>
    >()
    private var navBarColor: Int? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 检查是否支持全面屏优化
        if (PropertyUtils["ro.miui.support_miui_ime_bottom", "0"] != "1") return
        EzXHelperInit.initHandleLoadPackage(lpparam)
        EzXHelperInit.setLogTag(TAG)
        Log.i("miuiime is supported")

        when (lpparam.packageName) {
            "android" -> startPermissionHook()
            "com.miui.phrase" -> startPackageValidationHook(lpparam)
            else -> startHook(lpparam)
        }
    }

    private fun startHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 检查是否为小米定制输入法
        val isNonCustomize = !miuiImeList.contains(lpparam.packageName)
        if (isNonCustomize) {
            // 窗口每次弹出/重建时兜底重新置位，防止系统运行期重置解锁状态
            hookImeWindowReassert()
            val sInputMethodServiceInjector =
                loadClassOrNull("android.inputmethodservice.InputMethodServiceInjector")
                    ?: loadClassOrNull("android.inputmethodservice.InputMethodServiceStubImpl")

            sInputMethodServiceInjector?.also {
                hookSIsImeSupport(it)
                hookIsXiaoAiEnable(it)
                setPhraseBgColor(it)
            } ?: Log.e("Failed:Class not found: InputMethodServiceInjector")
        }

        hookDeleteNotSupportIme(
            "android.inputmethodservice.InputMethodServiceInjector\$MiuiSwitchInputMethodListener",
            lpparam.classLoader
        )

        // 获取常用语的ClassLoader
        findMethod("android.inputmethodservice.InputMethodModuleManager") {
            name == "loadDex" && parameterTypes.sameAs(ClassLoader::class.java, String::class.java)
        }.hookBefore { param ->
            val loader = param.args[0] as ClassLoader
            val dexPath = param.args[1] as String
            // 系统原始逻辑，若已加载dex则直接返回，避免重复hook
            if (loader !is BaseDexClassLoader) throw NoSuchMethodException("addDexPath method not found.")
            runCatching {
                Class.forName("com.miui.inputmethod.InputMethodBottomManager", true, loader)
                param.result = null
                return@hookBefore
            }
            loader.invokeMethodAuto("addDexPath", dexPath)

            hookDeleteNotSupportIme(
                "com.miui.inputmethod.InputMethodBottomManager\$MiuiSwitchInputMethodListener",
                loader
            )
            loadClassOrNull(
                "com.miui.inputmethod.InputMethodBottomManager",
                loader
            )?.also {
                if (isNonCustomize) {
                    hookSIsImeSupport(it)
                    hookIsXiaoAiEnable(it)
                    hookMiuiBottomInsetCompatibility(it)
                    hookImeVersionSupportGate(it)
                }

                // 针对A11的修复切换输入法列表
                it.getDeclaredMethod("getSupportIme").hookReplace { _ ->
                    it.getStaticObject("sBottomViewHelper")
                        .getObjectAs<InputMethodManager>("mImm").enabledInputMethodList
                }
            } ?: Log.e("Failed:Class not found: com.miui.inputmethod.InputMethodBottomManager")
            param.result = null
        }

        Log.i("Hook MIUI IME Done!")
    }

    /**
     * 跳过包名检查，直接开启输入法优化
     *
     * @param clazz 声明或继承字段的类
     */
    private fun hookSIsImeSupport(clazz: Class<*>) {
        kotlin.runCatching {
            clazz.putStaticObject("sIsImeSupport", 1)
            imeUnlockClasses.add(clazz)
            Log.i("Success:Hook field sIsImeSupport")
        }.onFailure {
            Log.i("Failed:Hook field sIsImeSupport")
            Log.i(it)
        }
    }

    /**
     * 兜底：在 IME 窗口每次弹出 / 服务重建时重新把 sIsImeSupport 置 1。
     *
     * 失效场景：切换输入法（含系统安全键盘）会销毁并重建输入法服务，重建时 MIUI
     * 会重新评估"当前 IME 是否支持全面屏优化"，可能在窗口显示过程中把解锁状态重置。
     * 这里 hook 窗口生命周期的多个时机，每次窗口弹出/重建都重新置位。
     *
     * 注意：onWindowShown / onStartInputView 等都是 protected 方法，getMethod 只能
     * 拿到 public，必须用 getDeclaredMethod，否则对应时机兜底静默失效。
     */
    private fun hookImeWindowReassert() {
        kotlin.runCatching {
            val ims = loadClassOrNull("android.inputmethodservice.InputMethodService")
                ?: error("Failed to load InputMethodService")
            val reassert = {
                imeUnlockClasses.forEach { clazz ->
                    kotlin.runCatching { clazz.putStaticObject("sIsImeSupport", 1) }
                }
            }
            val lifecycleNames = listOf(
                "onCreate",
                "onCreateInputView",
                "onCreateCandidatesView",
                "onWindowShown",
                "onStartInput",
                "onStartInputView",
                "onComputeInsets",
                "onBindInput"
            )
            lifecycleNames.forEach { name ->
                runCatching {
                    findAnyMethod(ims, name).hookAfter { reassert() }
                }.onFailure {
                    Log.i("Failed:Hook IME lifecycle $name")
                    Log.i(it)
                }
            }
            Log.i("Success:Hook IME window reassert")
        }.onFailure {
            Log.i("Failed:Hook IME window reassert")
            Log.i(it)
        }
    }

    private fun findAnyMethod(clazz: Class<*>, name: String): Method {
        return clazz.declaredMethods.firstOrNull { it.name == name }
            ?: error("Method not found: ${clazz.name}.$name")
    }

    /**
     * 修复 IME 版本支持检查导致的解锁失效（安全键盘切换/服务重建后触发）。
     *
     * 现象：MIUI IMEBottomManager 打印 "ime version code is not support : xxx" 后
     * 判定当前输入法不支持全面屏优化，不再添加 MIUI 底栏，键盘贴底。
     * 该检查独立于 sIsImeSupport 字段，且只在运行期某些路径（如安全键盘切换后的
     * 服务重建）执行，冷启动 hook 字段无法覆盖。
     *
     * 这里运行时用 DexKit 在承载 IMEBottomManager 的 dex 里按错误字符串定位检查方法，
     * 让返回值恒为"支持"。
     *
     * @param clazz com.miui.inputmethod.InputMethodBottomManager
     */
    private fun hookImeVersionSupportGate(clazz: Class<*>) {
        kotlin.runCatching {
            System.loadLibrary("dexkit")
        }.onFailure {
            Log.i("Failed: load dexkit lib for IME version gate")
            return
        }

        val dexPath = kotlin.runCatching {
            clazz.protectionDomain?.codeSource?.location?.path
        }.getOrNull()
        if (dexPath.isNullOrEmpty()) {
            Log.i("Failed: resolve dex path for IME version gate")
            return
        }

        kotlin.runCatching {
            DexKitBridge.create(dexPath).use { bridge ->
                // 注意：MIUI 实际日志常量是 "ime version code is not support : xxx"（带拼接后缀），
                // usingStrings 默认是精确匹配（Equals），必须用 Contains 才能命中，
                // 否则版本检查方法匹配不到、hook 不生效，切换输入法/安全键盘后解锁就会失效。
                val candidates = bridge.findMethod {
                    matcher {
                        usingStrings(
                            listOf("ime version code is not support"),
                            StringMatchType.Contains
                        )
                    }
                }
                if (candidates.isEmpty()) {
                    Log.i("Failed: IME version gate method not found by string")
                    return@use
                }
                Log.i("VersionGate: found ${candidates.size} method(s)")
                candidates.forEach { data ->
                    kotlin.runCatching {
                        val method = data.getMethodInstance(clazz.classLoader)
                        method.isAccessible = true
                        val ret = method.returnType
                        Log.i(
                            "VersionGate: ${method.declaringClass.name}.${method.name}" +
                                "(${method.parameterTypes.joinToString { it.simpleName }}): ${ret.simpleName}"
                        )
                        when {
                            ret == java.lang.Boolean.TYPE || ret == java.lang.Boolean::class.java ->
                                method.hookReturnConstant(true)
                            // 若检查方法返回 int 版本号，返回超大值保证调用方">=最低支持版本"判定通过
                            ret == java.lang.Integer.TYPE || ret == java.lang.Integer::class.java ->
                                method.hookReturnConstant(99999)
                            else -> Log.i("VersionGate: skip return ${ret.simpleName}")
                        }
                    }.onFailure {
                        Log.i("Failed: hook IME version gate method")
                        Log.i(it)
                    }
                }
            }
        }.onFailure {
            Log.i("Failed: Hook IME version support gate")
            Log.i(it)
        }
    }

    /**
     * 小爱语音输入按钮失效修复
     *
     * @param clazz 声明或继承方法的类
     */
    private fun hookIsXiaoAiEnable(clazz: Class<*>) {
        kotlin.runCatching {
            clazz.getMethod("isXiaoAiEnable").hookReturnConstant(false)
        }.onFailure {
            Log.i("Failed:Hook method isXiaoAiEnable")
            Log.i(it)
        }
    }

    /**
     * 在适当的时机修改抬高区域背景颜色
     *
     * @param clazz 声明或继承字段的类
     */
    private fun setPhraseBgColor(clazz: Class<*>) {
        kotlin.runCatching {
            // 导航栏颜色被设置后, 将颜色存储起来并传递给常用语
            findMethod("com.android.internal.policy.PhoneWindow") {
                name == "setNavigationBarColor" && parameterTypes.sameAs(Int::class.java)
            }.hookAfter { param ->
                if (param.args[0] == 0) return@hookAfter

                navBarColor = param.args[0] as Int
                customizeBottomViewColor(clazz)
            }

            // 当常用语被创建后, 将背景颜色设置为存储的导航栏颜色
            clazz.findMethod { name == "addMiuiBottomView" }.hookAfter {
                customizeBottomViewColor(clazz)
            }
        }.onFailure {
            Log.i("Failed to set the color of the MiuiBottomView")
            Log.i(it)
        }
    }

    /**
     * 将导航栏颜色赋值给输入法优化的底图
     *
     * @param clazz 声明或继承字段的类
     */
    private fun customizeBottomViewColor(clazz: Class<*>) {
        navBarColor?.let {
            val color = -0x1 - it
            clazz.invokeStaticMethodAuto(
                "customizeBottomViewColor",
                true, navBarColor, color or -0x1000000, color or 0x66000000
            )
        }
    }

    /**
     * 针对A10的修复切换输入法列表
     *
     * @param className 声明或继承方法的类的名称
     */
    private fun hookDeleteNotSupportIme(className: String, classLoader: ClassLoader) {
        kotlin.runCatching {
            findMethod(className, classLoader) { name == "deleteNotSupportIme" }
                .hookReturnConstant(null)
        }.onFailure {
            Log.i("Failed:Hook method deleteNotSupportIme")
            Log.i(it)
        }
    }

    /**
     * 修复部分输入法全面屏优化后键盘异常增高的问题
     *
     * 解锁全面屏优化后，MIUI 会把输入法窗口的可用区域扩展到屏幕底部（含导航栏），
     * 并在底部叠加 MIUI 底栏。部分输入法（如微信输入法 3.5.2、Gboard）会把内容视图
     * 填满整个 inputFrame，导致键盘内容顶入底栏/导航栏区域，键盘看起来异常增高。
     *
     * 这里检测该情况后，将内容视图底部 padding 减去导航栏 inset，同时把
     * fullscreenArea 高度加回导航栏 inset，把被顶高的空间让还给 MIUI 底栏。
     *
     * @param clazz com.miui.inputmethod.InputMethodBottomManager
     */
    private fun hookMiuiBottomInsetCompatibility(clazz: Class<*>) {
        kotlin.runCatching {
            clazz.findMethod {
                name == "addMiuiBottomView" &&
                    Modifier.isStatic(modifiers) &&
                    parameterTypes.size >= 6 &&
                    Context::class.java.isAssignableFrom(parameterTypes[0]) &&
                    LayoutInflater::class.java.isAssignableFrom(parameterTypes[1]) &&
                    ViewGroup::class.java.isAssignableFrom(parameterTypes[2]) &&
                    ViewGroup::class.java.isAssignableFrom(parameterTypes[3]) &&
                    View::class.java.isAssignableFrom(parameterTypes[4]) &&
                    View::class.java.isAssignableFrom(parameterTypes[5])
            }.hookAfter { param ->
                val fullscreenArea = param.args.getOrNull(2) as? ViewGroup ?: return@hookAfter
                val inputFrame = param.args.getOrNull(3) as? ViewGroup ?: return@hookAfter
                val rootView = param.args.getOrNull(4) as? View ?: return@hookAfter
                val bottomArea = param.args.getOrNull(5) as? View ?: return@hookAfter
                registerMiuiBottomFrame(fullscreenArea, inputFrame, rootView, bottomArea)
            }
        }.onFailure {
            Log.i("Failed:Hook MIUI bottom inset compatibility")
            Log.i(it)
        }

        clazz.declaredMethods
            .filter { it.name == "onWindowShown" || it.name == "changeViewForMiuiBottom" }
            .forEach { method ->
                kotlin.runCatching {
                    method.isAccessible = true
                    method.hookAfter {
                        // 窗口每次显示/底栏每次调整时，同步把解锁状态重新置位，
                        // 防止切换输入法后系统在窗口流程中重置 sIsImeSupport。
                        kotlin.runCatching { clazz.putStaticObject("sIsImeSupport", 1) }
                        reconcileCurrentImeFrame(clazz)
                    }
                }.onFailure {
                    Log.i("Failed:Hook MIUI bottom inset lifecycle method ${method.name}")
                    Log.i(it)
                }
            }
    }

    private fun registerMiuiBottomFrame(
        fullscreenArea: ViewGroup,
        inputFrame: ViewGroup,
        rootView: View,
        bottomArea: View
    ) {
        miuiBottomFrameViews[inputFrame] = Triple(
            WeakReference(fullscreenArea),
            WeakReference(rootView),
            WeakReference(bottomArea)
        )
        if (monitoredImeInputFrames.add(inputFrame)) {
            inputFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                reconcileMiuiBottomFrame(inputFrame)
            }
        }
        inputFrame.post { reconcileMiuiBottomFrame(inputFrame) }
    }

    private fun reconcileCurrentImeFrame(clazz: Class<*>) {
        val currentInputFrame = kotlin.runCatching {
            clazz.getStaticObject("sBottomViewHelper")
                .getObjectAs<ViewGroup>("mInputFrame")
        }.getOrNull()
        val inputFrames = currentInputFrame?.let(::listOf)
            ?: miuiBottomFrameViews.keys.toList()
        inputFrames.forEach { inputFrame ->
            inputFrame.post { reconcileMiuiBottomFrame(inputFrame) }
            inputFrame.postDelayed({ reconcileMiuiBottomFrame(inputFrame) }, 100L)
        }
    }

    private fun reconcileMiuiBottomFrame(inputFrame: ViewGroup) {
        val frameViews = miuiBottomFrameViews[inputFrame] ?: return
        val fullscreenArea = frameViews.first.get() ?: return
        val rootView = frameViews.second.get() ?: return
        val bottomArea = frameViews.third.get() ?: return
        val contentView = (0 until inputFrame.childCount)
            .firstNotNullOfOrNull { index ->
                inputFrame.getChildAt(index).takeIf { it.visibility == View.VISIBLE }
            }
        val navigationInset = rootView.rootWindowInsets
            ?.getInsets(WindowInsets.Type.navigationBars())
            ?.bottom

        if (navigationInset == null || navigationInset <= 0 ||
            !isBottomAreaActive(rootView, inputFrame, bottomArea, navigationInset)
        ) {
            restoreMiuiBottomFrame(inputFrame, fullscreenArea)
            return
        }
        if (contentView == null) {
            restoreMiuiBottomFrame(inputFrame, fullscreenArea)
            return
        }
        val adjustedContentReference = adjustedImeContentViews[inputFrame]
        val adjustedContentView = adjustedContentReference?.get()
        if (adjustedContentReference != null && adjustedContentView !== contentView) {
            restoreMiuiBottomFrame(inputFrame, fullscreenArea)
        }

        val originalPadding = originalImeContentBottomPaddings[contentView]
        val isCurrentContentAdjusted = adjustedImeContentViews[inputFrame]?.get() === contentView
        val isAlreadyAdjusted = isCurrentContentAdjusted &&
            originalPadding == navigationInset &&
            contentView.paddingBottom == 0
        val fillsInputFrame = inputFrame.paddingBottom == 0 &&
            contentView.top == inputFrame.paddingTop &&
            contentView.bottom == inputFrame.height
        if (!fillsInputFrame ||
            contentView.paddingBottom != navigationInset && !isAlreadyAdjusted
        ) {
            if (isCurrentContentAdjusted) restoreMiuiBottomFrame(inputFrame, fullscreenArea)
            return
        }

        originalImeContentBottomPaddings.putIfAbsent(contentView, contentView.paddingBottom)
        if (!isAlreadyAdjusted) {
            contentView.setPadding(
                contentView.paddingLeft,
                contentView.paddingTop,
                contentView.paddingRight,
                contentView.paddingBottom - navigationInset
            )
        }
        adjustedImeContentViews[inputFrame] = WeakReference(contentView)
        if (!expandFullscreenArea(fullscreenArea, navigationInset)) {
            restoreMiuiBottomFrame(inputFrame, fullscreenArea)
        }
    }

    private fun expandFullscreenArea(fullscreenArea: ViewGroup, navigationInset: Int): Boolean {
        val params = fullscreenArea.layoutParams ?: return false
        val previous = originalFullscreenAreaHeights[fullscreenArea]
        val currentHeight = params.height
        val baseHeight = when {
            previous == null -> currentHeight
            currentHeight == previous[2] && navigationInset == previous[1] -> return true
            currentHeight == previous[2] || currentHeight == previous[0] -> previous[0]
            else -> currentHeight
        }
        val targetHeight = if (baseHeight >= 0) {
            baseHeight + navigationInset
        } else {
            fullscreenArea.measuredHeight + navigationInset
        }
        originalFullscreenAreaHeights[fullscreenArea] = intArrayOf(
            baseHeight,
            navigationInset,
            targetHeight
        )
        if (currentHeight != targetHeight) {
            params.height = targetHeight
            fullscreenArea.layoutParams = params
        }
        return true
    }

    private fun restoreMiuiBottomFrame(inputFrame: ViewGroup, fullscreenArea: ViewGroup) {
        adjustedImeContentViews.remove(inputFrame)?.get()?.let { view ->
            val paddingBottom = originalImeContentBottomPaddings.remove(view)
            if (paddingBottom != null && view.paddingBottom == 0) {
                view.setPadding(
                    view.paddingLeft,
                    view.paddingTop,
                    view.paddingRight,
                    paddingBottom
                )
            }
        }
        val height = originalFullscreenAreaHeights.remove(fullscreenArea) ?: return
        val params = fullscreenArea.layoutParams ?: return
        if (params.height == height[2]) {
            params.height = height[0]
            fullscreenArea.layoutParams = params
        }
    }

    private fun isBottomAreaActive(
        rootView: View,
        inputFrame: View,
        bottomArea: View,
        navigationInset: Int
    ): Boolean {
        if (!bottomArea.isShown || bottomArea.height < navigationInset) return false
        val rootLocation = IntArray(2)
        val inputLocation = IntArray(2)
        val bottomLocation = IntArray(2)
        rootView.getLocationOnScreen(rootLocation)
        inputFrame.getLocationOnScreen(inputLocation)
        bottomArea.getLocationOnScreen(bottomLocation)
        return bottomLocation[1] + bottomArea.height == rootLocation[1] + rootView.height &&
            inputLocation[1] + inputFrame.height == bottomLocation[1]
    }

    /**
     * Hook 获取应用列表权限，使当前输入法可见其他输入法。
     * 用于修复部分输入法（搜狗输入法小米版等）缺少获取输入法列表权限，导致切换输入法功能不能显示其他输入法的问题。
     */
    private fun startPermissionHook() {
        runCatching {
            findMethod("com.android.server.inputmethod.InputMethodManagerServiceImpl") {
                name == "isCallingBetweenCustomIME"
            }.hookAfter { param ->
                if (param.result == true) return@hookAfter
                val context = param.args[0] as Context
                val uid = param.args[1] as Int
                val currentInputMethodPackageName = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD
                )?.substringBefore('/') ?: return@hookAfter
                val packagesForUid = context.packageManager.getPackagesForUid(uid) ?: return@hookAfter
                if (packagesForUid.contains(currentInputMethodPackageName)) {
                    param.result = true
                }
            }
        }.onFailure {
            Log.i("Failed: Hook method isCallingBetweenCustomIME")
            Log.i(it)
        }
    }

    /**
     * Hook InputProvider的输入法白名单，修复当前输入法无法获得剪贴板的问题
     */
    private fun startPackageValidationHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(lpparam.appInfo.sourceDir).use { bridge ->
                bridge.findMethod {
                    matcher {
                        declaredClass = "com.miui.provider.InputProvider"
                        returnType = "boolean"
                        usingStrings(
                            "InputProvider",
                            "Invalid caller UID: ",
                            "No package name for UID: ",
                            "Package validation failed: ",
                            "Unexpected error during package validation"
                        )
                    }
                }.singleOrNull()?.getMethodInstance(lpparam.classLoader)?.hookBefore { param ->
                    val callingUid = Binder.getCallingUid()
                    val context = param.thisObject.invokeMethodAutoAs<Context>("getContext") ?: return@hookBefore
                    val packagesForUid = context.packageManager.getPackagesForUid(callingUid) ?: return@hookBefore
                    val currentInputMethodPackageName = Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.DEFAULT_INPUT_METHOD
                    )?.substringBefore('/') ?: return@hookBefore
                    if (packagesForUid.contains(currentInputMethodPackageName)) {
                        param.result = true
                    }
                }
            }
        }.onFailure {
            Log.i("Failed: Hook package validation")
            Log.i(it)
        }
    }
}
