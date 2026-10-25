package com.yaya.ai

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地持久化（AGENTS.md R13）：会话 / 消息 / 检查点。
 *
 * 用 SQLiteOpenHelper 零依赖实现（避免引入 Room/ksp 依赖在容器内不可验证的构建风险）；
 * SQLite 内部锁保证线程安全，写操作均来自后台线程（agentTaskThread）。
 * 版本管理用 DB_VERSION + onUpgrade CASE 分支：后续版本递增时在 `onUpgrade` 中按
 * `oldVersion` 逐级迁移（任务 7 迁移框架的落地形式）。
 */
class AgentDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val appContext = context.applicationContext

    companion object {
        private const val DB_NAME = "yaya_agent.db"
        /** 数据库版本：只增不减；升级时在 [onUpgrade] 补对应 CASE 分支。 */
        private const val DB_VERSION = 4
        private const val COL_OK = "ok"

        @Volatile
        private var instance: AgentDatabase? = null

        fun get(context: Context): AgentDatabase =
            instance ?: synchronized(this) {
                instance ?: AgentDatabase(context).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                ok INTEGER NOT NULL DEFAULT 1,
                images TEXT NOT NULL DEFAULT '[]',
                prompt_tokens INTEGER NOT NULL DEFAULT 0,
                completion_tokens INTEGER NOT NULL DEFAULT 0,
                cached_tokens INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                cost_usd REAL NOT NULL DEFAULT 0,
                usage_kind TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE checkpoints (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                messages_json TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE memories (
                name TEXT PRIMARY KEY,
                description TEXT NOT NULL,
                content TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 迁移框架：版本递增时按 (oldVersion -> oldVersion+1) 逐级迁移。
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE memories (
                    name TEXT PRIMARY KEY,
                    description TEXT NOT NULL,
                    content TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
        if (oldVersion < 3) {
            // 任务 26：消息附带图片（受管文件路径 JSON 数组）。
            db.execSQL("ALTER TABLE messages ADD COLUMN images TEXT NOT NULL DEFAULT '[]'")
        }
        if (oldVersion < 4) {
            // 任务 30：usage 事件明细（prompt/completion/cached/耗时/费用/来源）。
            db.execSQL("ALTER TABLE messages ADD COLUMN prompt_tokens INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN completion_tokens INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN cached_tokens INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN cost_usd REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN usage_kind TEXT")
        }
    }

    // ── sessions ─────────────────────────────────────────────

    fun upsertSession(id: String, title: String) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("id", id)
            put("title", title)
            put("created_at", now)
            put("updated_at", now)
        }
        writableDatabase.insertWithOnConflict(
            "sessions", null, values, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun touchSession(id: String) {
        writableDatabase.execSQL(
            "UPDATE sessions SET updated_at=? WHERE id=?",
            arrayOf(System.currentTimeMillis(), id),
        )
    }

    /** 所有会话列表 `[{id,title,updatedAt,messageCount}]`，按 updated_at 倒序（多会话管理，任务 24）。 */
    fun sessionsJson(): String {
        val arr = JSONArray()
        val db = readableDatabase
        db.query("sessions", null, null, null, null, null, "updated_at DESC").use { c ->
            while (c.moveToNext()) {
                val sid = c.getString(c.getColumnIndexOrThrow("id"))
                val count = db.query(
                    "messages", null,
                    "session_id=? AND role NOT IN ('usage','policy','system')",
                    arrayOf(sid), null, null, null,
                ).use { mc -> mc.count }
                arr.put(
                    JSONObject().apply {
                        put("id", sid)
                        put("title", c.getString(c.getColumnIndexOrThrow("title")))
                        put("updatedAt", c.getLong(c.getColumnIndexOrThrow("updated_at")))
                        put("messageCount", count)
                    }
                )
            }
        }
        return arr.toString()
    }

    fun renameSession(id: String, title: String) {
        val values = ContentValues().apply { put("title", title) }
        writableDatabase.update("sessions", values, "id=?", arrayOf(id))
    }

    /** 删除会话及其全部消息、检查点与受管图片文件。 */
    fun deleteSession(id: String) {
        val images = imagePathsForSession(id)
        writableDatabase.delete("messages", "session_id=?", arrayOf(id))
        writableDatabase.delete("checkpoints", "session_id=?", arrayOf(id))
        writableDatabase.delete("sessions", "id=?", arrayOf(id))
        ChatImages.delete(appContext, images)
    }

    private fun imagePathsForSession(id: String): List<String> {
        val images = mutableListOf<String>()
        readableDatabase.query(
            "messages", arrayOf("images"), "session_id=? AND images != '[]'", arrayOf(id),
            null, null, null,
        ).use { c ->
            while (c.moveToNext()) {
                val raw = c.getString(0) ?: continue
                runCatching { JSONArray(raw) }.getOrNull()?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotBlank() }?.let(images::add)
                    }
                }
            }
        }
        return images
    }

    // ── messages ─────────────────────────────────────────────

    fun insertMessage(
        sessionId: String,
        role: String,
        text: String,
        ok: Boolean,
        images: String = "[]",
    ) {
        insertMessageFull(sessionId, role, text, ok, images)
    }

    /** 一次用量事件（任务 30）：明细落库，供回复下展示与会话/全局费用累计。 */
    fun insertUsage(
        sessionId: String,
        promptTokens: Int,
        completionTokens: Int,
        totalTokens: Int,
        cachedTokens: Int,
        durationMs: Long,
        costUsd: Double,
        kind: String?,
    ) {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("role", "usage")
            put("text", totalTokens.toString())
            put(COL_OK, 1)
            put("prompt_tokens", promptTokens)
            put("completion_tokens", completionTokens)
            put("cached_tokens", cachedTokens)
            put("duration_ms", durationMs)
            put("cost_usd", costUsd)
            if (kind.isNullOrEmpty()) putNull("usage_kind") else put("usage_kind", kind)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("messages", null, values)
    }

    private fun insertMessageFull(
        sessionId: String,
        role: String,
        text: String,
        ok: Boolean,
        images: String = "[]",
        promptTokens: Int = 0,
        completionTokens: Int = 0,
        cachedTokens: Int = 0,
        durationMs: Long = 0,
        costUsd: Double = 0.0,
        usageKind: String? = null,
    ) {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("role", role)
            put("text", text)
            put(COL_OK, if (ok) 1 else 0)
            put("images", images)
            put("prompt_tokens", promptTokens)
            put("completion_tokens", completionTokens)
            put("cached_tokens", cachedTokens)
            put("duration_ms", durationMs)
            put("cost_usd", costUsd)
            if (usageKind.isNullOrEmpty()) putNull("usage_kind") else put("usage_kind", usageKind)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("messages", null, values)
    }

    /** 会话消息 JSON 数组（供检查点快照与 UI 恢复）。 */
    fun messagesJson(sessionId: String): String {
        val arr = JSONArray()
        readableDatabase.query(
            "messages", null, "session_id=?", arrayOf(sessionId), null, null, "id ASC",
        ).use { m ->
            while (m.moveToNext()) {
                val rawImages = m.getString(m.getColumnIndexOrThrow("images")) ?: "[]"
                arr.put(
                    JSONObject().apply {
                        put("role", m.getString(m.getColumnIndexOrThrow("role")))
                        put("text", m.getString(m.getColumnIndexOrThrow("text")))
                        put("ok", m.getInt(m.getColumnIndexOrThrow(COL_OK)) == 1)
                        put(
                            "images",
                            runCatching { JSONArray(rawImages) }.getOrDefault(JSONArray()),
                        )
                        put("promptTokens", m.getInt(m.getColumnIndexOrThrow("prompt_tokens")))
                        put("completionTokens", m.getInt(m.getColumnIndexOrThrow("completion_tokens")))
                        put("cachedTokens", m.getInt(m.getColumnIndexOrThrow("cached_tokens")))
                        put("durationMs", m.getLong(m.getColumnIndexOrThrow("duration_ms")))
                        put("costUsd", m.getDouble(m.getColumnIndexOrThrow("cost_usd")))
                        m.getStringOrNull("usage_kind")?.let { put("kind", it) }
                    }
                )
            }
        }
        return arr.toString()
    }

    fun clearMessages(sessionId: String) {
        writableDatabase.delete("messages", "session_id=?", arrayOf(sessionId))
    }

    // ── 会话恢复 ─────────────────────────────────────────────

    /** 最近会话及其全部消息（含 sessionId / title），供重启后恢复展示；无会话返回 null。 */
    fun recentSessionJson(): String? {
        val db = readableDatabase
        db.query("sessions", null, null, null, null, null, "updated_at DESC", "1").use { cur ->
            if (!cur.moveToFirst()) return null
            val sid = cur.getString(cur.getColumnIndexOrThrow("id"))
            val title = cur.getString(cur.getColumnIndexOrThrow("title"))
            return JSONObject().apply {
                put("sessionId", sid)
                put("title", title)
                put("messages", JSONArray(messagesJson(sid)))
            }.toString()
        }
    }

    /** 指定会话 JSON（含 sessionId/title/messages）；不存在返回 null。 */
    fun sessionJson(id: String): String? {
        val db = readableDatabase
        db.query("sessions", null, "id=?", arrayOf(id), null, null, null).use { c ->
            if (!c.moveToFirst()) return null
            val title = c.getString(c.getColumnIndexOrThrow("title"))
            return JSONObject().apply {
                put("sessionId", id)
                put("title", title)
                put("messages", JSONArray(messagesJson(id)))
            }.toString()
        }
    }

    // ── 检查点 ───────────────────────────────────────────────

    fun saveCheckpoint(sessionId: String, messagesJson: String) {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("messages_json", messagesJson)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("checkpoints", null, values)
    }

    /** 会话检查点快照列表（新 → 旧）：`{id, created_at, messages_json}`。 */
    fun checkpointRecords(sessionId: String): List<Triple<Long, Long, String>> {
        val out = mutableListOf<Triple<Long, Long, String>>()
        readableDatabase.query(
            "checkpoints", null, "session_id=?", arrayOf(sessionId), null, null, "id DESC",
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Triple(
                        c.getLong(c.getColumnIndexOrThrow("id")),
                        c.getLong(c.getColumnIndexOrThrow("created_at")),
                        c.getString(c.getColumnIndexOrThrow("messages_json")),
                    )
                )
            }
        }
        return out
    }

    private fun Cursor.getStringOrNull(column: String): String? {
        val idx = getColumnIndexOrThrow(column)
        return if (isNull(idx)) null else getString(idx)
    }

    /** 用指定检查点的消息快照覆盖会话 messages 表（真回滚）。返回回滚后的消息 JSON。 */
    fun restoreToCheckpoint(sessionId: String, checkpointId: Long): String? {
        val snapshot = readableDatabase.query(
            "checkpoints", null, "id=? AND session_id=?", arrayOf(checkpointId.toString(), sessionId),
            null, null, null,
        ).use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("messages_json")) else null }
            ?: return null
        val before = imagePathsForSession(sessionId)
        val arr = try { JSONArray(snapshot) } catch (_: Exception) { JSONArray() }
        val keep = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val imgs = m.optJSONArray("images") ?: continue
            for (j in 0 until imgs.length()) {
                imgs.optString(j).takeIf { it.isNotBlank() }?.let(keep::add)
            }
        }
        var committed: Boolean
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("messages", "session_id=?", arrayOf(sessionId))
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                insertMessageFull(
                    sessionId = sessionId,
                    role = m.optString("role"),
                    text = m.optString("text"),
                    ok = m.optBoolean("ok", true),
                    images = m.optJSONArray("images")?.toString() ?: "[]",
                    promptTokens = m.optInt("promptTokens"),
                    completionTokens = m.optInt("completionTokens"),
                    cachedTokens = m.optInt("cachedTokens"),
                    durationMs = m.optLong("durationMs"),
                    costUsd = m.optDouble("costUsd", 0.0),
                    usageKind = m.optString("kind").takeIf { it.isNotBlank() },
                )
            }
            writableDatabase.setTransactionSuccessful()
            committed = true
        } finally {
            writableDatabase.endTransaction()
        }
        // 快照之外的图片（回滚丢弃的消息）不再被任何消息引用：删除文件防泄漏。
        if (committed) ChatImages.delete(appContext, before.filterNot { it in keep })
        return snapshot
    }

    // ── 自动记忆（memories 表，AGENTS.md R13 / ROADMAP 任务 10） ──────────

    /** 记忆清单 JSON 数组：`[{name,description}]`。 */
    fun memListJson(): String {
        val arr = JSONArray()
        readableDatabase.query(
            "memories", null, null, null, null, null, "updated_at DESC",
        ).use { c ->
            while (c.moveToNext()) {
                arr.put(
                    JSONObject().apply {
                        put("name", c.getString(c.getColumnIndexOrThrow("name")))
                        put("description", c.getString(c.getColumnIndexOrThrow("description")))
                    }
                )
            }
        }
        return arr.toString()
    }

    /** 读取记忆正文；不存在返回 null。 */
    fun memRead(name: String): String? {
        readableDatabase.query(
            "memories", null, "name=?", arrayOf(name), null, null, null,
        ).use { c ->
            return if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("content")) else null
        }
    }

    fun memSave(name: String, description: String, content: String) {
        val values = ContentValues().apply {
            put("name", name)
            put("description", description)
            put("content", content)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "memories", null, values, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** 局部替换记忆正文；旧片段不存在返回 false。 */
    fun memEdit(name: String, oldString: String, newString: String): Boolean {
        val current = memRead(name) ?: return false
        if (!current.contains(oldString)) return false
        memSave(name, descriptionOf(name) ?: "", current.replace(oldString, newString))
        return true
    }

    fun memDelete(name: String): Boolean =
        writableDatabase.delete("memories", "name=?", arrayOf(name)) > 0

    private fun descriptionOf(name: String): String? {
        readableDatabase.query(
            "memories", null, "name=?", arrayOf(name), null, null, null,
        ).use { c ->
            return if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("description")) else null
        }
    }

    // ── 统计面板（任务 17） ──────────────────────────────────────────

    /** 全局统计 JSON：`{sessions,messages,toolCalls,errors,totalTokens,...,totalCost}`。 */
    fun statsJson(): String {
        fun qInt(sql: String): Int {
            readableDatabase.rawQuery(sql, null).use { c ->
                return if (c.moveToFirst()) c.getInt(0) else 0
            }
        }
        fun qLong(sql: String): Long {
            readableDatabase.rawQuery(sql, null).use { c ->
                return if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }
        fun qDouble(sql: String): Double {
            readableDatabase.rawQuery(sql, null).use { c ->
                return if (c.moveToFirst()) c.getDouble(0) else 0.0
            }
        }
        return JSONObject().apply {
            put("sessions", qInt("SELECT COUNT(*) FROM sessions"))
            put("messages", qInt("SELECT COUNT(*) FROM messages WHERE role IN ('user','assistant','tool')"))
            put("toolCalls", qInt("SELECT COUNT(*) FROM messages WHERE role='tool'"))
            put("errors", qInt("SELECT COUNT(*) FROM messages WHERE role='system' AND text LIKE '错误:%'"))
            put("totalTokens", qInt("SELECT COALESCE(SUM(CAST(text AS INTEGER)),0) FROM messages WHERE role='usage'"))
            put("promptTokens", qInt("SELECT COALESCE(SUM(prompt_tokens),0) FROM messages WHERE role='usage'"))
            put("completionTokens", qInt("SELECT COALESCE(SUM(completion_tokens),0) FROM messages WHERE role='usage'"))
            put("cachedTokens", qInt("SELECT COALESCE(SUM(cached_tokens),0) FROM messages WHERE role='usage'"))
            put("durationMs", qLong("SELECT COALESCE(SUM(duration_ms),0) FROM messages WHERE role='usage'"))
            put("totalCost", qDouble("SELECT COALESCE(SUM(cost_usd),0) FROM messages WHERE role='usage'"))
            put("checkpoints", qInt("SELECT COUNT(*) FROM checkpoints"))
        }.toString()
    }
}
