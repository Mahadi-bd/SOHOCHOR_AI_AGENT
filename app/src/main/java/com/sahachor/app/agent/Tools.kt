package com.sahachor.app.agent

import com.sahachor.app.data.*
import com.sahachor.app.reminders.ReminderScheduler
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val fmt = DateTimeFormatter.ofPattern("d MMM, HH:mm")
private fun when_(ms: Long?, zone: ZoneId) = ms?.let { fmt.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "no time"

class CreateReminderTool(private val db: AppDatabase, private val scheduler: ReminderScheduler, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : Tool {
    override val name = "create_reminder"
    override val help = "Set a reminder. args: title (string), at (local time, format 2026-11-16T09:00)"
    override val risk = Risk.MEDIUM
    override suspend fun execute(args: JSONObject): ToolResult {
        val title = args.optString("title").trim()
        if (title.isEmpty()) return ToolResult(false, "title is missing")
        val ms = TimeParse.parse(args.optString("at"), zone()) ?: return ToolResult(false, "could not read 'at'; use format 2026-11-16T09:00")
        val now = System.currentTimeMillis()
        if (ms <= now + 5_000) return ToolResult(false, "that time is in the past; ask the user for a future time")
        val id = db.tasks().insert(TaskItem(title = title.take(200), dueAt = ms, createdAt = now))
        val scheduled = scheduler.schedule(id, title, ms)
        return ToolResult(true, "reminder #$id set for ${when_(ms, zone())}" + if (scheduled) "" else " (warning: alarm could not be scheduled)")
    }
}

class ListTasksTool(private val db: AppDatabase, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : Tool {
    override val name = "list_tasks"
    override val help = "List the user's open tasks and reminders. args: none"
    override val risk = Risk.LOW
    override suspend fun execute(args: JSONObject): ToolResult {
        val t = db.tasks().open()
        return ToolResult(true, if (t.isEmpty()) "no open tasks" else t.joinToString("\n") { "#${it.id} ${it.title} (${when_(it.dueAt, zone())})" })
    }
}

class CompleteTaskTool(private val db: AppDatabase, private val scheduler: ReminderScheduler) : Tool {
    override val name = "complete_task"
    override val help = "Mark a task done. args: id (number from list_tasks)"
    override val risk = Risk.MEDIUM
    override suspend fun execute(args: JSONObject): ToolResult {
        val id = args.optLong("id", -1)
        val t = db.tasks().byId(id) ?: return ToolResult(false, "no task with id $id")
        db.tasks().update(t.copy(done = true)); scheduler.cancel(id)
        return ToolResult(true, "task #$id marked done")
    }
}

class DeleteTaskTool(private val db: AppDatabase, private val scheduler: ReminderScheduler) : Tool {
    override val name = "delete_task"
    override val help = "Permanently delete a task or reminder. args: id (number from list_tasks)"
    override val risk = Risk.HIGH
    override fun describe(args: JSONObject) = "টাস্ক #${args.optLong("id", -1)} স্থায়ীভাবে মুছে ফেলা হবে"
    override suspend fun execute(args: JSONObject): ToolResult {
        val id = args.optLong("id", -1)
        if (db.tasks().byId(id) == null) return ToolResult(false, "no task with id $id")
        db.tasks().delete(id); scheduler.cancel(id)
        return ToolResult(true, "task #$id deleted")
    }
}

class RememberTool(private val db: AppDatabase) : Tool {
    override val name = "remember"
    override val help = "Save a lasting fact/preference about the user. args: type (profile|preference|fact|habit), key (short label), value"
    override val risk = Risk.MEDIUM
    override suspend fun execute(args: JSONObject): ToolResult {
        val key = args.optString("key").trim(); val value = args.optString("value").trim()
        if (key.isEmpty() || value.isEmpty()) return ToolResult(false, "key and value are required")
        val type = args.optString("type").trim().lowercase().takeIf { it in setOf("profile", "preference", "fact", "habit") } ?: "fact"
        db.memory().upsert(Memory(type = type, key = key.take(80), value = value.take(500), updatedAt = System.currentTimeMillis()))
        return ToolResult(true, "saved: $key")
    }
}

class RecallTool(private val db: AppDatabase) : Tool {
    override val name = "recall"
    override val help = "Search saved memories. args: query"
    override val risk = Risk.LOW
    override suspend fun execute(args: JSONObject): ToolResult {
        val q = args.optString("query").trim()
        if (q.isEmpty()) return ToolResult(false, "query is required")
        val r = db.memory().search("%${q.replace("%", "").replace("_", "")}%")
        return ToolResult(true, if (r.isEmpty()) "nothing found" else r.joinToString("\n") { "${it.type}/${it.key}: ${it.value}" })
    }
}
