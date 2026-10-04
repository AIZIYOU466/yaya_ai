package com.yaya.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Git 版本管理桥（ROADMAP 任务 23）：经 proot 容器对工作区 `/workspace` 执行 git 命令。
 *
 * 工作区已由 ProotManager bind 进容器固定路径 `/workspace`（与 file 工具同一目录），
 * 因此这里 `git -C /workspace ...` 即管理工作区的版本。git 二进制由容器提供
 * （Alpine 需先 `apk add git`，见 [detect]）。
 */
object GitHost {

    /** 执行一条 git 子命令，返回 `{ok, output}`。参数数组逐词 shell 转义，防注入。 */
    fun run(context: Context, subargsJson: String, timeoutMs: Long = 30000): String {
        val arr = JSONArray(subargsJson)
        val args = (0 until arr.length()).map { arr.getString(it) }
        val cmd = "git --no-pager -C /workspace " + args.joinToString(" ") { shellQuote(it) }
        val out = try {
            ProotManager.runCommandBlocking(context, cmd, timeoutMs)
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("message", e.message ?: "git 执行失败").toString()
        }
        // git 错误通常以 `fatal:` / `error:` 开头；据此标记失败。
        val t = out.trimStart()
        val ok = !(t.startsWith("fatal:") || t.startsWith("error:"))
        return JSONObject().put("ok", ok).put("output", out).toString()
    }

    /** 检测环境：git 是否可用、工作区是否已是 git 仓库。 */
    fun detect(context: Context): String {
        val gitOk = try {
            val v = ProotManager.runCommandBlocking(context, "git --version", 10000)
            v.startsWith("git version")
        } catch (_: Exception) {
            false
        }
        val isRepo = if (gitOk) {
            try {
                val r = ProotManager.runCommandBlocking(
                    context, "git --no-pager -C /workspace rev-parse --is-inside-work-tree", 10000
                )
                r == "true"
            } catch (_: Exception) {
                false
            }
        } else false
        return JSONObject().put("gitOk", gitOk).put("isRepo", isRepo).toString()
    }

    /** shell 单引号转义：用户输入（提交信息/分支名）经此安全包裹，防止命令注入。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}