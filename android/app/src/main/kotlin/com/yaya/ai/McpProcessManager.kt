package com.yaya.ai

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 一个 stdio MCP 服务器的启动配置。 */
data class McpServerConfig(
    val name: String,
    val command: String,
    val args: List<String>,
)

/**
 * stdio MCP 服务器进程管理（AGENTS.md R10）。
 *
 * 每个服务器一个子进程 + 一个后台读线程：读线程把 stdout 的行投递到阻塞队列，
 * 请求方按 JSON-RPC `id` 匹配取响应（跳过通知等无关消息），避免阻塞死等。
 * 传输仅支持 stdio；进程与协议留在本层，工具语义由 Rust Core 统一表达。
 */
object McpProcessManager {
    private const val TAG = "McpProcessManager"
    private const val PROTOCOL_VERSION = "2025-06-18"
    private const val REQUEST_TIMEOUT_MS = 20_000L

    private class Server(
        val process: Process,
        val writer: BufferedWriter,
        val incoming: LinkedBlockingQueue<String>,
    ) {
        var nextId = 1L
    }

    private val servers = LinkedHashMap<String, Server>()

    /** 按配置启停：启动新增的启用项，停止被移除/禁用的项。幂等。 */
    @Synchronized
    fun syncEnabled(configs: List<McpServerConfig>) {
        // R10：服务器名不得含 __（否则 MCP 工具名无法无歧义解析）。
        val (valid, rejected) = configs.partition { !it.name.contains("__") }
        rejected.forEach { Log.w(TAG, "MCP 服务器 \"${it.name}\" 含有 \"__\"，已跳过") }
        val wanted = valid.associateBy { it.name }
        servers.keys.filter { it !in wanted.keys }.forEach { stop(it) }
        for ((name, cfg) in wanted) {
            if (!servers.containsKey(name)) start(name, cfg)
        }
    }

    @Synchronized
    fun stopAll() {
        servers.keys.toList().forEach { stop(it) }
    }

    /** 列出所有已运行服务器暴露的工具，元素为 `{server,name,description,inputSchema}`。 */
    @Synchronized
    fun listTools(): JSONArray {
        val out = JSONArray()
        for ((name, server) in servers) {
            val result = try {
                request(server, "tools/list", JSONObject())
            } catch (e: Exception) {
                throw Exception("MCP 服务器 $name 列出工具失败: ${e.message}")
            }
            val tools = result.optJSONArray("tools") ?: continue
            for (i in 0 until tools.length()) {
                val t = tools.optJSONObject(i) ?: continue
                out.put(
                    JSONObject().apply {
                        put("server", name)
                        put("name", t.optString("name"))
                        put("description", t.optString("description"))
                        put(
                            "inputSchema",
                            t.optJSONObject("inputSchema")
                                ?: JSONObject().put("type", "object"),
                        )
                    },
                )
            }
        }
        return out
    }

    /** 调用工具，返回面向模型的文本（`content[].text` 拼接）。 */
    @Synchronized
    fun callTool(serverName: String, toolName: String, args: JSONObject): String {
        val server = servers[serverName]
            ?: throw Exception("MCP 服务器 $serverName 未运行")
        val result = request(
            server,
            "tools/call",
            JSONObject().apply {
                put("name", toolName)
                put("arguments", args)
            },
        )
        val text = buildString {
            val content = result.optJSONArray("content")
            if (content != null) {
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    if (item.optString("type") == "text") {
                        if (isNotEmpty()) append('\n')
                        append(item.optString("text"))
                    }
                }
            }
        }
        if (result.optBoolean("isError", false)) {
            throw Exception(if (text.isEmpty()) "工具返回错误" else text)
        }
        if (text.isEmpty()) {
            // 结构化结果（structuredContent）也回填，避免模型收到空响应。
            val structured = result.opt("structuredContent")
            return if (structured != null) structured.toString() else "(无文本输出)"
        }
        return text
    }

    private fun start(name: String, cfg: McpServerConfig) {
        try {
            val process = ProcessBuilder(listOf(cfg.command) + cfg.args).start()
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
            val incoming = LinkedBlockingQueue<String>()
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            Thread {
                try {
                    reader.forEachLine { incoming.put(it) }
                } catch (_: Exception) {
                    // 进程退出/管道关闭
                } finally {
                    incoming.put(EOF)
                    // 进程退出后从 servers 移除，避免后续调用挂满 20s 超时。
                    synchronized(this@McpProcessManager) {
                        servers.remove(name)
                    }
                    Log.i(TAG, "MCP 服务器 $name 已退出")
                }
            }.apply {
                isDaemon = true
                this.name = "mcp-$name"
            }.start()
            // 消费 stderr，防管道填满导致 MCP 服务器阻塞。
            Thread {
                try {
                    process.errorStream.bufferedReader().forEachLine {
                        Log.d(TAG, "MCP[$name] stderr: $it")
                    }
                } catch (_: Exception) {}
            }.apply {
                isDaemon = true
                this.name = "mcp-$name-stderr"
                start()
            }

            val server = Server(process, writer, incoming)
            servers[name] = server
            initialize(name, server)
        } catch (e: Exception) {
            Log.w(TAG, "启动 MCP 服务器 $name 失败: ${e.message}")
            servers.remove(name)
        }
    }

    private fun stop(name: String) {
        val server = servers.remove(name) ?: return
        try {
            server.process.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "停止 MCP 服务器 $name 失败: ${e.message}")
        }
    }

    private fun initialize(name: String, server: Server) {
        request(
            server,
            "initialize",
            JSONObject().apply {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().apply {
                    put("name", "YAYai")
                    put("version", "1.0")
                })
            },
        )
        // 握手完成通知：无响应，不等待。
        synchronized(server) {
            write(server, JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            })
        }
        Log.i(TAG, "MCP 服务器 $name 初始化完成")
    }

    private fun write(server: Server, message: JSONObject) {
        server.writer.write(message.toString())
        server.writer.write("\n")
        server.writer.flush()
    }

    /** 发一次 JSON-RPC 请求并等待 id 匹配的响应。 */
    private fun request(server: Server, method: String, params: JSONObject): JSONObject {
        synchronized(server) {
            val id = server.nextId++
            write(server, JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            })

            val deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw Exception("MCP 请求超时: $method")
                val line = server.incoming.poll(remaining, TimeUnit.MILLISECONDS)
                    ?: throw Exception("MCP 请求超时: $method")
                if (line == EOF) throw Exception("MCP 服务器已退出")
                val msg = try {
                    JSONObject(line)
                } catch (_: Exception) {
                    continue // 非 JSON 行（如日志）忽略
                }
                val respId = msg.opt("id")
                if (respId !is Number || respId.toLong() != id) continue // 通知/其它响应
                msg.optJSONObject("error")?.let { err ->
                    throw Exception(err.optString("message", "MCP 错误"))
                }
                return msg.optJSONObject("result") ?: JSONObject()
            }
        }
    }

    private const val EOF = "\u0000eof"
}