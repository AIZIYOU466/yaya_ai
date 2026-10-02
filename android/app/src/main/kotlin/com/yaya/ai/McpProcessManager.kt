package com.yaya.ai

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object McpProcessManager {
    private val servers = mutableMapOf<String, Process>()

    fun startStdioServer(
        context: Context,
        name: String,
        command: String,
        args: List<String>
    ): Boolean {
        return try {
            val process = ProcessBuilder(command, *args.toTypedArray())
                .redirectErrorStream(true)
                .start()
            servers[name] = process
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stopServer(name: String) {
        servers[name]?.destroy()
        servers.remove(name)
    }

    fun callTool(
        name: String,
        toolName: String,
        params: JSONObject
    ): JSONObject {
        val process = servers[name]
            ?: throw Exception("Server $name not running")

        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", System.currentTimeMillis())
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", toolName)
                put("arguments", params)
            })
        }

        process.outputStream.write("$request\n".toByteArray())
        process.outputStream.flush()

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val response = reader.readLine() ?: ""
        return JSONObject(response)
    }

    fun listTools(name: String): JSONObject {
        val process = servers[name]
            ?: throw Exception("Server $name not running")

        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/list")
            put("params", JSONObject())
        }

        process.outputStream.write("$request\n".toByteArray())
        process.outputStream.flush()

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val response = reader.readLine() ?: ""
        return JSONObject(response)
    }

    fun stopAll() {
        servers.values.forEach { it.destroy() }
        servers.clear()
    }
}
