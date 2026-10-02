package com.yaya.ai

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AgentAccessibilityService? = null
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

    fun captureScreenTree(): JSONObject {
        val root = rootInActiveWindow ?: return JSONObject()
        return nodeToJson(root)
    }

    private fun nodeToJson(node: AccessibilityNodeInfo): JSONObject {
        val json = JSONObject()
        json.put("id", node.hashCode().toString())
        json.put("text", node.text?.toString() ?: "")
        json.put("className", node.className?.toString() ?: "")
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        json.put("bounds", "${rect.left},${rect.top},${rect.right},${rect.bottom}")

        val children = JSONArray()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                children.put(nodeToJson(child))
            }
        }
        json.put("children", children)
        return json
    }

    fun executeAction(action: JSONObject): Boolean {
        val type = action.getString("type")
        val nodeId = action.optString("nodeId", "")
        val node = findNode(rootInActiveWindow, nodeId) ?: return false

        return when (type) {
            "click" -> {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            "input" -> {
                val text = action.getString("text")
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            "scroll" -> {
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            else -> false
        }
    }

    private fun findNode(
        root: AccessibilityNodeInfo?,
        id: String
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.hashCode().toString() == id) return root
        for (i in 0 until root.childCount) {
            findNode(root.getChild(i), id)?.let { return it }
        }
        return null
    }
}
