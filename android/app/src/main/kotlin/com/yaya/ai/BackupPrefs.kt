package com.yaya.ai

import org.w3c.dom.Element
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * SharedPreferences XML 解析（ROADMAP 任务 34）——纯 JVM，`javax.xml` 在 Android 上同样可用。
 *
 * 恢复配置时**不走文件覆盖**：直接覆盖 `shared_prefs` 下的 XML 会被进程内已缓存的
 * SharedPreferences 实例反向覆盖（用户在重启前的任何一次写入都会冲掉刚恢复的配置）。
 * 因此备份里的 XML 先解析成键值，再经 SharedPreferences API 逐键写回（BackupHost）。
 */
sealed class PrefValue {
    data class Str(val value: String) : PrefValue()
    data class Flag(val value: Boolean) : PrefValue()
    /** `<int>` / `<long>`：整数统一用 Long 承载，`integer=true` 表示写回时用 putInt。 */
    data class Num(val value: Long, val integer: Boolean) : PrefValue()
    data class Real(val value: Float) : PrefValue()
    data class StrSet(val value: Set<String>) : PrefValue()
    /** 未知类型（未来新增/非标准标签）：显式跳过并计数上报，不静默。 */
    data class Unsupported(val kind: String) : PrefValue()
}

object PrefXml {
    /** 解析 [file]（`shared_prefs/<name>.xml`）为一个有序键值表。 */
    fun read(file: File): Map<String, PrefValue> = read(file.readText(Charsets.UTF_8))

    fun read(xml: String): Map<String, PrefValue> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }
        // 备份来自外部存储：尽量关闭外部实体（防 XXE）。Android 的 DOM 实现不支持部分
        // Xerces feature，逐个 try——不支持时保持平台默认行为，不阻断恢复。
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        ).forEach { (feature, value) -> runCatching { factory.setFeature(feature, value) } }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = doc.documentElement ?: return emptyMap()
        val out = LinkedHashMap<String, PrefValue>()
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            if (name.isNullOrEmpty()) continue
            out[name] = valueOf(el)
        }
        return out
    }

    /**
     * Android 的 SharedPreferences XML 把非字符串值放在 `value` 属性里
     * （`<int name="x" value="3" />`），只有 `<string>` 与 `<set>` 的子项用文本内容。
     */
    private fun scalar(el: Element): String =
        el.getAttribute("value").ifEmpty { el.textContent ?: "" }.trim()

    private fun valueOf(el: Element): PrefValue = when (el.tagName) {
        "string" -> PrefValue.Str(el.textContent ?: "")
        "boolean" -> PrefValue.Flag(scalar(el).equals("true", ignoreCase = true))
        "int" -> PrefValue.Num(scalar(el).toLongOrNull() ?: 0L, integer = true)
        "long" -> PrefValue.Num(scalar(el).toLongOrNull() ?: 0L, integer = false)
        "float" -> PrefValue.Real(scalar(el).toFloatOrNull() ?: 0f)
        "set" -> {
            val items = LinkedHashSet<String>()
            val children = el.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i) as? Element ?: continue
                if (child.tagName == "string") child.textContent?.let { items += it }
            }
            PrefValue.StrSet(items)
        }
        else -> PrefValue.Unsupported(el.tagName)
    }
}
