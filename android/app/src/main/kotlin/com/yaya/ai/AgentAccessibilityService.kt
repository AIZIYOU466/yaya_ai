package com.yaya.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务：为 Agent 提供屏幕树读取与动作执行（core 的工具层经 JNI 调用）。
 *
 * 节点 id 采用「子索引路径」（如 "0/1/3"）而非 hashCode：观察与操作是两次独立调用，
 * 节点实例会被重新获取，hashCode 每次都变，会导致动作定位必然失败。
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ── 观察 ────────────────────────────────────────────────

    fun captureScreenTree(): String? {
        val root = rootInActiveWindow ?: return null
        val count = intArrayOf(0)
        val json = nodeToJson(root, "0", 0, count) ?: return null
        return json.toString()
    }

    /**
     * 截取当前屏幕（Android 11+），返回 JPEG base64 data URL，供模型视觉分析。
     * 依赖无障碍服务的 takeScreenshot 能力；版本过低或失败返回 null（不静默伪造）。
     */
    fun captureScreenshot(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        var shot: ScreenshotResult? = null
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    shot = screenshot
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            },
        )
        if (!latch.await(3, TimeUnit.SECONDS)) return null
        val result = shot ?: return null
        val buffer = result.hardwareBuffer
        try {
            var bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            // 降采样：最长边 1024px，节省上下文与传输体积。
            val maxDim = maxOf(bitmap.width, bitmap.height)
            if (maxDim > 1024) {
                val scale = 1024f / maxDim
                bitmap = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true,
                )
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            return "data:image/jpeg;base64,$b64"
        } finally {
            buffer.close()
        }
    }

    private fun nodeToJson(
        node: AccessibilityNodeInfo,
        id: String,
        depth: Int,
        count: IntArray,
    ): JSONObject? {
        if (depth > 40 || count[0] > 2000) return null

        val children = JSONArray()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childJson = nodeToJson(child, "$id/$i", depth + 1, count) ?: continue
            children.put(childJson)
        }

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val clickable = node.isClickable
        val editable = node.isEditable
        val scrollable = node.isScrollable

        // 裁剪：无有效属性且无有效后代 → 跳过，避免噪声淹没模型。
        if (text.isEmpty() && desc.isEmpty() && !clickable && !editable && !scrollable &&
            children.length() == 0
        ) {
            return null
        }
        count[0]++

        val rect = Rect()
        node.getBoundsInScreen(rect)

        return JSONObject().apply {
            put("id", id)
            put("text", text)
            put("class", node.className?.toString() ?: "")
            put("desc", desc)
            put("bounds", JSONObject().apply {
                put("left", rect.left)
                put("top", rect.top)
                put("right", rect.right)
                put("bottom", rect.bottom)
            })
            put("clickable", clickable)
            put("editable", editable)
            put("scrollable", scrollable)
            put("children", children)
        }
    }

    // ── 动作 ────────────────────────────────────────────────

    fun executeAction(action: JSONObject): JSONObject {
        val type = action.optString("type")
        return try {
            when (type) {
                "tap" -> tap(action)
                "input" -> input(action)
                "scroll" -> scroll(action)
                "swipe" -> swipe(action)
                "system" -> system(action)
                "launch" -> launch(action)
                else -> fail("未知动作: $type")
            }
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        }
    }

    private fun tap(action: JSONObject): JSONObject {
        val id = action.optString("id", "")
        if (id.isNotEmpty()) {
            val node = findByPath(rootInActiveWindow, id) ?: return fail("未找到节点 $id")
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return if (ok) ok("已点击节点 $id") else fail("节点 $id 不支持点击")
        }
        val x = action.optInt("x", Int.MIN_VALUE)
        val y = action.optInt("y", Int.MIN_VALUE)
        if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) return fail("缺少 id 或坐标")
        return if (tapAt(x, y)) ok("已点击坐标 ($x,$y)") else fail("手势被拒绝")
    }

    private fun input(action: JSONObject): JSONObject {
        val id = action.optString("id", "")
        val node = findByPath(rootInActiveWindow, id) ?: return fail("未找到节点 $id")
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                action.optString("text", ""),
            )
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) ok("已输入文本") else fail("节点 $id 不支持文本输入")
    }

    private fun scroll(action: JSONObject): JSONObject {
        val id = action.optString("id", "")
        val node = if (id.isNotEmpty()) {
            findByPath(rootInActiveWindow, id) ?: return fail("未找到节点 $id")
        } else {
            findScrollable(rootInActiveWindow) ?: return fail("未找到可滚动节点")
        }
        val forward = action.optString("direction", "forward") != "backward"
        val code = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val ok = node.performAction(code)
        return if (ok) ok("已滚动") else fail("节点不支持滚动")
    }

    private fun swipe(action: JSONObject): JSONObject {
        val path = Path().apply {
            moveTo(action.optInt("from_x").toFloat(), action.optInt("from_y").toFloat())
            lineTo(action.optInt("to_x").toFloat(), action.optInt("to_y").toFloat())
        }
        val duration = action.optLong("duration_ms", 300L).coerceAtLeast(1L)
        return if (dispatch(path, duration)) ok("已滑动") else fail("手势被拒绝")
    }

    private fun system(action: JSONObject): JSONObject {
        val code = when (action.optString("action")) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return fail("未知系统操作: ${action.optString("action")}")
        }
        return if (performGlobalAction(code)) ok("已执行系统操作") else fail("系统操作被拒绝")
    }

    private fun launch(action: JSONObject): JSONObject {
        val pkg = action.optString("package", "")
        if (pkg.isEmpty()) return fail("缺少 package")
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: return fail("未找到应用: $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return ok("已启动 $pkg")
    }

    // ── 工具 ────────────────────────────────────────────────

    private fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat(), y.toFloat())
        }
        return dispatch(path, 50L)
    }

    private fun dispatch(path: Path, duration: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /** 按子索引路径定位：id "0/1/3" = root.getChild(1).getChild(3)。 */
    private fun findByPath(root: AccessibilityNodeInfo?, id: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val parts = id.split("/")
        var node: AccessibilityNodeInfo = root
        for (i in 1 until parts.size) {
            val idx = parts[i].toIntOrNull() ?: return null
            node = node.getChild(idx) ?: return null
        }
        return node
    }

    private fun findScrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            findScrollable(root.getChild(i))?.let { return it }
        }
        return null
    }

    private fun ok(message: String) = JSONObject().put("ok", true).put("message", message)

    private fun fail(message: String) = JSONObject().put("ok", false).put("message", message)
}