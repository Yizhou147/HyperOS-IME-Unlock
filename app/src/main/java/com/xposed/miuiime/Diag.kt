package com.xposed.miuiime

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 临时诊断通道（logcat 在本 ROM 被系统关闭）：把视图树 / insets / MIUI 底栏管理
 * 内部字段追加写到输入法私有目录 files/miuiime_diag.txt，用 adb+root 取出。
 * 仅存在于 debug 分支，不合并回 main。
 */
object Diag {
    @Volatile var enabled = false

    private var dir: File? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile private var lastDumpAt = 0L

    fun init(context: Context) {
        dir = context.filesDir
    }

    fun dump(tag: String, root: View?, throttleMs: Long = 0) {
        if (!enabled) return
        val d = dir ?: return
        val now = System.currentTimeMillis()
        if (throttleMs > 0 && now - lastDumpAt < throttleMs) return
        lastDumpAt = now
        val sb = StringBuilder()
        sb.append("\n===== ").append(timeFmt.format(Date())).append(' ')
            .append(tag).append(" =====\n")
        if (root == null) {
            sb.append("root=null\n")
        } else {
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            sb.append("decor screen=(").append(loc[0]).append(',').append(loc[1])
                .append(") size=").append(root.width).append('x').append(root.height).append('\n')
            root.rootWindowInsets?.let { ri ->
                val nav = ri.getInsets(android.view.WindowInsets.Type.navigationBars())
                sb.append("navInsets bottom=").append(nav.bottom)
                    .append(" visible=").append(ri.isVisible(android.view.WindowInsets.Type.navigationBars()))
                    .append('\n')
            }
            (root.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
                sb.append("winLP gravity=").append(Integer.toHexString(lp.gravity))
                    .append(" w=").append(lp.width).append(" h=").append(lp.height)
                    .append(" yoff=").append(lp.y).append('\n')
            }
            walk(root, 0, sb)
        }
        runCatching {
            val f = File(d, "miuiime_diag.txt")
            if (f.length() > 3_000_000) f.delete()
            f.appendText(sb.toString())
        }
    }

    private fun walk(v: View, depth: Int, sb: StringBuilder) {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        sb.append("  ".repeat(depth))
            .append(v.javaClass.name.substringAfterLast('.'))
            .append(idSuffix(v))
            .append(" [").append(loc[0]).append(',').append(loc[1])
            .append('-').append(loc[0] + v.width).append(',').append(loc[1] + v.height).append(']')
            .append(" vis=").append(visName(v.visibility))
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            sb.append(" lp=").append(lp.width).append('x').append(lp.height)
                .append(" m(").append(lp.leftMargin).append(',').append(lp.topMargin)
                .append(',').append(lp.rightMargin).append(',').append(lp.bottomMargin).append(')')
            if (lp is LinearLayout.LayoutParams) sb.append(" w=").append(lp.weight)
            if (lp is FrameLayout.LayoutParams) sb.append(" g=").append(Integer.toHexString(lp.gravity))
        }
        if (v.paddingBottom != 0 || v.paddingTop != 0) {
            sb.append(" pad(t=").append(v.paddingTop).append(",b=").append(v.paddingBottom).append(')')
        }
        if (v is ViewGroup && v.height > 0) {
            sb.append(" child=").append(v.childCount)
        }
        sb.append('\n')
        if (v is ViewGroup && depth < 14) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1, sb)
        }
    }

    private fun idName(v: View): String = runCatching {
        if (v.id == View.NO_ID) "" else v.resources.getResourceEntryName(v.id)
    }.getOrDefault("?${v.id}")

    private fun idSuffix(v: View): String {
        val n = idName(v)
        return if (n.isEmpty()) "" else "/$n"
    }

    private fun visName(vis: Int): String = when (vis) {
        View.VISIBLE -> "V"
        View.INVISIBLE -> "I"
        else -> "G"
    }

    fun dumpManagerFields(tag: String, managerClass: Class<*>) {
        if (!enabled) return
        val helper = runCatching {
            managerClass.getField("sBottomViewHelper").get(null)
        }.getOrNull() ?: return
        val sb = StringBuilder()
        sb.append("\n===== ").append(timeFmt.format(Date())).append(' ')
            .append(tag).append(" helper fields =====\n")
        var cls: Class<*>? = helper.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val value = runCatching { f.get(helper) }.getOrNull()
                when (value) {
                    null, is Boolean, is Int, is Long, is Float, is Double ->
                        sb.append(cls.simpleName).append('.').append(f.name)
                            .append('=').append(value).append('\n')
                    is View -> {
                        val loc = IntArray(2)
                        runCatching { value.getLocationOnScreen(loc) }
                        sb.append(cls.simpleName).append('.').append(f.name)
                            .append(" -> ").append(value.javaClass.name.substringAfterLast('.'))
                            .append(" screen=(").append(loc[0]).append(',').append(loc[1])
                            .append(") size=").append(value.width).append('x').append(value.height)
                            .append(" vis=").append(visName(value.visibility)).append('\n')
                    }
                }
            }
            cls = cls.superclass
        }
        val d = dir ?: return
        runCatching { File(d, "miuiime_diag.txt").appendText(sb.toString()) }
    }

    fun findDeclared(cls: Class<*>?, name: String): java.lang.reflect.Method? {
        var c = cls
        while (c != null && c != Any::class.java) {
            c.declaredMethods.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return it
            }
            c = c.superclass
        }
        return null
    }

    fun dumpLine(line: String) {
        if (!enabled) return
        val d = dir ?: return
        runCatching {
            File(d, "miuiime_diag.txt")
                .appendText(timeFmt.format(Date()) + " " + line + "\n")
        }
    }
}
