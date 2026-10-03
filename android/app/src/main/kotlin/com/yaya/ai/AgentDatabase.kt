package com.yaya.ai

import android.content.ContentValues
import android.content.Context
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

    companion object {
        private const val DB_NAME = "yaya_agent.db"
        /** 数据库版本：只增不减；升级时在 [onUpgrade] 补对应 CASE 分支。 */
        private const val DB_VERSION = 2
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

    // ── messages ─────────────────────────────────────────────

    fun insertMessage(sessionId: String, role: String, text: String, ok: Boolean) {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("role", role)
            put("text", text)
            put(COL_OK, if (ok) 1 else 0)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("messages", null, values)
    }

    /** 会话消息 JSON 数组（供检查点快照与 Dart 恢复）。 */
    fun messagesJson(sessionId: String): String {
        val arr = JSONArray()
        readableDatabase.query(
            "messages", null, "session_id=?", arrayOf(sessionId), null, null, "id ASC",
        ).use { m ->
            while (m.moveToNext()) {
                arr.put(
                    JSONObject().apply {
                        put("role", m.getString(1))
                        put("text", m.getString(2))
                        put("ok", m.getInt(3) == 1)
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

    // ── 检查点 ───────────────────────────────────────────────

    fun saveCheckpoint(sessionId: String, messagesJson: String) {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("messages_json", messagesJson)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("checkpoints", null, values)
    }

    /** 会话检查点的消息快照列表（新 → 旧）。 */
    fun checkpointMessagesList(sessionId: String): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.query(
            "checkpoints", null, "session_id=?", arrayOf(sessionId), null, null, "id DESC",
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(c.getColumnIndexOrThrow("messages_json")))
        }
        return out
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

    /** 全局统计 JSON：`{sessions,messages,toolCalls,errors,totalTokens}`。 */
    fun statsJson(): String {
        fun qInt(sql: String): Int {
            readableDatabase.rawQuery(sql, null).use { c ->
                return if (c.moveToFirst()) c.getInt(0) else 0
            }
        }
        return JSONObject().apply {
            put("sessions", qInt("SELECT COUNT(*) FROM sessions"))
            put("messages", qInt("SELECT COUNT(*) FROM messages WHERE role IN ('user','assistant','tool')"))
            put("toolCalls", qInt("SELECT COUNT(*) FROM messages WHERE role='tool'"))
            put("errors", qInt("SELECT COUNT(*) FROM messages WHERE role='system' AND text LIKE '错误:%'"))
            put("totalTokens", qInt("SELECT COALESCE(SUM(CAST(text AS INTEGER)),0) FROM messages WHERE role='usage'"))
            put("checkpoints", qInt("SELECT COUNT(*) FROM checkpoints"))
        }.toString()
    }
}
