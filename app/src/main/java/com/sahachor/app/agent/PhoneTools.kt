package com.sahachor.app.agent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import org.json.JSONObject
import java.time.ZoneId

/** Keeps digits and a leading +; returns null if it does not look like a phone number. */
internal fun cleanPhone(raw: String): String? {
    val t = raw.trim()
    val digits = t.filter { it.isDigit() }
    if (digits.length !in 3..15) return null
    return (if (t.startsWith("+")) "+" else "") + digits
}

/**
 * Phone actions use the system's own screens (Intents) instead of risky permissions: nothing is sent, dialled or saved
 * until the user confirms in the target app. That is also what Google Play expects from an assistant.
 */
class PhoneActions(private val ctx: Context) {
    fun start(intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }

    fun opened(what: String, ok: Boolean) =
        if (ok) ToolResult(true, "$what — the screen is now open; the user must confirm or finish it there.")
        else ToolResult(false, "no app on this phone can do that")
}

private abstract class PhoneTool(protected val phone: PhoneActions) : Tool

private class SetAlarmTool(p: PhoneActions) : PhoneTool(p) {
    override val name = "set_alarm"
    override val help = "Set a clock alarm. args: hour (0-23), minute (0-59), label (optional). For reminders with notifications use create_reminder instead"
    override val risk = Risk.MEDIUM
    override suspend fun execute(args: JSONObject): ToolResult {
        val h = args.optInt("hour", -1); val m = args.optInt("minute", 0)
        if (h !in 0..23 || m !in 0..59) return ToolResult(false, "hour must be 0-23 and minute 0-59")
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label").take(80))
        return phone.opened("Alarm %02d:%02d".format(h, m), phone.start(i))
    }
}

private class SetTimerTool(p: PhoneActions) : PhoneTool(p) {
    override val name = "set_timer"
    override val help = "Start a countdown timer. args: seconds (1-86400), label (optional)"
    override val risk = Risk.MEDIUM
    override suspend fun execute(args: JSONObject): ToolResult {
        val s = args.optInt("seconds", -1)
        if (s !in 1..86400) return ToolResult(false, "seconds must be 1-86400")
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label").take(80))
        return phone.opened("Timer of $s seconds", phone.start(i))
    }
}

private class DialTool(p: PhoneActions) : PhoneTool(p) {
    override val name = "dial"
    override val help = "Open the dialer with a number (the user presses call). args: number"
    override val risk = Risk.HIGH
    override fun describe(args: JSONObject) = "ডায়ালারে এই নম্বর খোলা হবে: ${args.optString("number")}"
    override suspend fun execute(args: JSONObject): ToolResult {
        val n = cleanPhone(args.optString("number")) ?: return ToolResult(false, "not a valid phone number")
        return phone.opened("Dialer with $n", phone.start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", n, null))))
    }
}

private class ComposeSmsTool(p: PhoneActions) : PhoneTool(p) {
    override val name = "compose_sms"
    override val help = "Open the SMS app with a prepared message (the user presses send). args: number, text"
    override val risk = Risk.HIGH
    override fun describe(args: JSONObject) = "${args.optString("number")} নম্বরে এই SMS-এর খসড়া খোলা হবে: ${args.optString("text").take(200)}"
    override suspend fun execute(args: JSONObject): ToolResult {
        val n = cleanPhone(args.optString("number")) ?: return ToolResult(false, "not a valid phone number")
        val i = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", n, null)).putExtra("sms_body", args.optString("text").take(1000))
        return phone.opened("SMS draft to $n", phone.start(i))
    }
}

private class ComposeEmailTool(p: PhoneActions) : PhoneTool(p) {
    override val name = "compose_email"
    override val help = "Open the email app with a prepared draft (the user presses send). args: to (optional), subject, body"
    override val risk = Risk.HIGH
    override fun describe(args: JSONObject) = "ইমেইলের খসড়া খোলা হবে। বিষয়: ${args.optString("subject").take(100)}"
    override suspend fun execute(args: JSONObject): ToolResult {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_SUBJECT, args.optString("subject").take(200)).putExtra(Intent.EXTRA_TEXT, args.optString("body").take(5000))
        args.optString("to").trim().takeIf { it.contains("@") }?.let { i.putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
        return phone.opened("Email draft", phone.start(i))
    }
}

private class AddCalendarEventTool(p: PhoneActions) : PhoneTool(p) {
    override val name = "add_calendar_event"
    override val help = "Open the calendar with a new event ready to save. args: title, start (local 2026-11-16T15:00), end (optional), location (optional), description (optional)"
    override val risk = Risk.MEDIUM
    override suspend fun execute(args: JSONObject): ToolResult {
        val zone = ZoneId.systemDefault()
        val start = TimeParse.parse(args.optString("start"), zone) ?: return ToolResult(false, "could not read 'start'; use format 2026-11-16T15:00")
        val end = TimeParse.parse(args.optString("end"), zone)?.takeIf { it > start } ?: (start + 3_600_000L)
        val title = args.optString("title").trim().ifEmpty { return ToolResult(false, "title is missing") }
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.Events.TITLE, title.take(200))
            .putExtra(CalendarContract.Events.EVENT_LOCATION, args.optString("location").take(200))
            .putExtra(CalendarContract.Events.DESCRIPTION, args.optString("description").take(2000))
        return phone.opened("Calendar event \"$title\"", phone.start(i))
    }
}

private class OpenUrlTool(p: PhoneActions) : PhoneTool(p) {
    override val name = "open_url"
    override val help = "Open a web page in the browser. args: url (https only)"
    override val risk = Risk.MEDIUM
    override suspend fun execute(args: JSONObject): ToolResult {
        val u = com.sahachor.app.agent.UrlGuard.parse(args.optString("url")) ?: return ToolResult(false, "only valid https:// links are allowed")
        return phone.opened("Browser at ${u.host}", phone.start(Intent(Intent.ACTION_VIEW, Uri.parse(u.toString()))))
    }
}

private class OpenMapsTool(p: PhoneActions) : PhoneTool(p) {
    override val name = "open_maps"
    override val help = "Search a place or get directions in the maps app. args: query"
    override val risk = Risk.MEDIUM
    override suspend fun execute(args: JSONObject): ToolResult {
        val q = args.optString("query").trim().ifEmpty { return ToolResult(false, "query is missing") }
        return phone.opened("Maps for \"${q.take(60)}\"", phone.start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q.take(200))))))
    }
}

private class ShareTextTool(p: PhoneActions) : PhoneTool(p) {
    override val name = "share_text"
    override val help = "Open the share sheet with some text. args: text"
    override val risk = Risk.MEDIUM
    override suspend fun execute(args: JSONObject): ToolResult {
        val t = args.optString("text").trim().ifEmpty { return ToolResult(false, "text is missing") }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.take(5000))
        return phone.opened("Share sheet", phone.start(Intent.createChooser(send, "শেয়ার করুন")))
    }
}

private class OpenAppTool(p: PhoneActions, private val ctx: Context) : PhoneTool(p) {
    override val name = "open_app"
    override val help = "Open an installed app by its name. args: name"
    override val risk = Risk.MEDIUM
    override suspend fun execute(args: JSONObject): ToolResult {
        val q = args.optString("name").trim().lowercase()
        if (q.isEmpty()) return ToolResult(false, "name is missing")
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinct()
        val exact = apps.filter { it.first.lowercase() == q }
        val matches = exact.ifEmpty { apps.filter { q in it.first.lowercase() } }
        if (matches.isEmpty()) return ToolResult(false, "no installed app matches '$q'")
        if (matches.size > 1) return ToolResult(false, "several apps match: ${matches.take(5).joinToString { it.first }}; ask the user which one")
        val intent = pm.getLaunchIntentForPackage(matches[0].second) ?: return ToolResult(false, "cannot open that app")
        return phone.opened(matches[0].first, phone.start(intent))
    }
}

object PhoneTools {
    fun all(ctx: Context): List<Tool> {
        val p = PhoneActions(ctx.applicationContext)
        return listOf(
            SetAlarmTool(p), SetTimerTool(p), DialTool(p), ComposeSmsTool(p), ComposeEmailTool(p),
            AddCalendarEventTool(p), OpenUrlTool(p), OpenMapsTool(p), ShareTextTool(p), OpenAppTool(p, ctx.applicationContext),
        )
    }
}
