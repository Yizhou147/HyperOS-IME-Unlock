package com.xposed.miuiime

import android.app.AndroidAppHelper
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
import com.github.kyuubiran.ezxhelper.utils.findAllMethods
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
    private val monitoredImeInputFrames = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val originalImeContentBottomPaddings = WeakHashMap<View, Int>()
    private val originalFullscreenAreaHeights = WeakHashMap<ViewGroup, IntArray>()
    private val adjustedImeContentViews = WeakHashMap<ViewGroup, WeakReference<View>>()
    private val miuiBottomFrameViews = WeakHashMap<
        ViewGroup,
        Triple<WeakReference<ViewGroup>, WeakReference<View>, WeakReference<View>>
    >()
    private var navBarColor: Int? = null
    // 临时诊断计数（定位搜狗底栏抬高问题用，定位后移除）
    private var diagCount = 0

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
        diag("startHook pkg=${lpparam.packageName} isNonCustomize=$isNonCustomize")
        if (isNonCustomize) {
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
                diag("loadDex: InputMethodBottomManager already loaded -> early return (hooks skipped)")
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
                diag("loadDex: InputMethodBottomManager loaded -> installing hooks")
                if (isNonCustomize) {
                    hookSIsImeSupport(it)
                    hookIsXiaoAiEnable(it)
                    hookMiuiBottomInsetCompatibility(it)
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
        diag("Hook MIUI IME Done! pkg=${lpparam.packageName}")
    }

    /**
     * 跳过包名检查，直接开启输入法优化
     *
     * @param clazz 声明或继承字段的类
     */
    private fun hookSIsImeSupport(clazz: Class<*>) {
        kotlin.runCatching {
            clazz.putStaticObject("sIsImeSupport", 1)
            Log.i("Success:Hook field sIsImeSupport")
            diag("Success:Hook field sIsImeSupport on ${clazz.name}")
        }.onFailure {
            Log.i("Failed:Hook field sIsImeSupport")
            Log.i(it)
            diag("Failed:Hook field sIsImeSupport on ${clazz.name}: $it")
        }
        // 切换输入法（含系统安全键盘）会在同一进程内销毁并重建输入法服务。此时承载
        // IMEBottomManager 的 dex/class 已经加载过，载入流程中"已加载就早退"的分支
        // 不会再置位支持状态，而 onDestroy 又会把 sIsImeSupport 重置为 -1，
        // 结果底栏不再添加、键盘贴底。因而在读取端兜住：让 isImeSupport() 恒为 true。
        kotlin.runCatching {
            val methods = findAllMethods(clazz) {
                name == "isImeSupport" && returnType == Boolean::class.javaPrimitiveType
            }
            methods.hookReturnConstant(true)
            Log.i("Success:Hook method isImeSupport")
            diag("Success:Hook method isImeSupport on ${clazz.name}, count=${methods.size}")
        }.onFailure {
            Log.i("Failed:Hook method isImeSupport")
            Log.i(it)
            diag("Failed:Hook method isImeSupport on ${clazz.name}: $it")
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
            diag("Failed:Hook MIUI bottom inset compatibility: $it")
        }

        clazz.declaredMethods
            .filter { it.name == "onWindowShown" || it.name == "changeViewForMiuiBottom" }
            .forEach { method ->
                kotlin.runCatching {
                    method.isAccessible = true
                    method.hookAfter {
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
        dumpFrameState("register(addMiuiBottomView)", rootView, fullscreenArea, inputFrame, bottomArea, null)
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

    // ---- 临时诊断（定位搜狗底栏抬高问题，定位后整体移除）----

    /**
     * 本机 main logcat 被系统关闭（logcat -b main 无输出），无法从模块打日志，
     * 因此把布局数据追加写到输入法进程私有目录，再用 adb + root 取出。
     */
    private fun diag(line: String) {
        kotlin.runCatching {
            val app = AndroidAppHelper.currentApplication() ?: return
            val file = java.io.File(app.filesDir, "miuiime_diag.txt")
            if (file.length() > 256 * 1024) return
            file.appendText(line + "\n")
        }
    }

    private fun describeView(v: View?): String {
        if (v == null) return "null"
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return "${v.javaClass.simpleName}@[${loc[0]},${loc[1]}] ${v.width}x${v.height}" +
            " padT=${v.paddingTop} padB=${v.paddingBottom}" +
            " bottom=${loc[1] + v.height}" +
            " lp=${v.layoutParams?.height} vis=${v.visibility}" +
            " parent=${v.parent?.javaClass?.simpleName}"
    }

    private fun dumpFrameState(
        reason: String,
        rootView: View,
        fullscreenArea: ViewGroup,
        inputFrame: ViewGroup,
        bottomArea: View,
        navigationInset: Int?,
        extra: String = ""
    ) {
        if (diagCount >= 60) return
        diagCount++
        val sb = StringBuilder()
        sb.appendLine("===== #$diagCount $reason =====")
        sb.appendLine("navInset=$navigationInset $extra")
        sb.appendLine("root       ${describeView(rootView)}")
        sb.appendLine("fullscreen ${describeView(fullscreenArea)}")
        sb.appendLine("inputFrame ${describeView(inputFrame)}")
        sb.appendLine("bottomArea ${describeView(bottomArea)}")
        for (i in 0 until fullscreenArea.childCount) {
            sb.appendLine("  fs[$i] ${describeView(fullscreenArea.getChildAt(i))}")
        }
        for (i in 0 until inputFrame.childCount) {
            sb.appendLine("  if[$i] ${describeView(inputFrame.getChildAt(i))}")
        }
        diag(sb.toString())
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
            ?.takeIf { it > 0 }

        val bottomAreaActive = navigationInset?.let {
            isBottomAreaActive(rootView, inputFrame, bottomArea, it)
        } == true
        dumpFrameState(
            "reconcile active=$bottomAreaActive",
            rootView, fullscreenArea, inputFrame, bottomArea, navigationInset,
            extra = "content=${describeView(contentView)}"
        )

        if (!bottomAreaActive) {
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
