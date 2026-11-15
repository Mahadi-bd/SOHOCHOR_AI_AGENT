package com.sahachor.app.llm

import com.sahachor.app.agent.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class AgentTest {
    private class FakeTool(override val name: String, override val risk: Risk) : Tool {
        override val help = "fake"
        var runs = 0
        override suspend fun execute(args: JSONObject): ToolResult { runs++; return ToolResult(true, "done") }
    }

    private class Script(vararg replies: String) : Chatter {
        private val q = ArrayDeque(replies.toList())
        val seen = mutableListOf<List<ChatMsg>>()
        override suspend fun chat(system: String?, messages: List<ChatMsg>): Result<String> {
            seen += messages.toList()
            return Result.success(q.removeFirstOrNull() ?: q.lastOrNull() ?: """{"tool":"echo","args":{}}""")
        }
    }

    private fun engine(c: Chatter, vararg t: Tool) = AgentEngine(c, t.toList(), { "" }, { _, _ -> })
    private val hi = listOf(ChatMsg("user", "hi"))

    @Test fun plainTextIsFinalReply() = runBlocking {
        val r = engine(Script("নমস্কার")).run(hi)
        assertEquals(AgentStep.Reply("নমস্কার"), r)
    }

    @Test fun lowRiskToolRunsThenModelContinues() = runBlocking {
        val t = FakeTool("echo", Risk.LOW)
        val s = Script("""{"tool":"echo","args":{"x":1}}""", "শেষ")
        val r = engine(s, t).run(hi)
        assertEquals(AgentStep.Reply("শেষ"), r)
        assertEquals(1, t.runs)
        assertTrue(s.seen.last().last().content.startsWith("TOOL_RESULT echo: done"))
    }

    @Test fun highRiskNeedsConfirmationAndRunsOnlyIfApproved() = runBlocking {
        val t = FakeTool("del", Risk.HIGH)
        val s = Script("""{"tool":"del","args":{"id":1}}""", "মুছে ফেলা হয়েছে")
        val e = engine(s, t)
        val step = e.run(hi) as AgentStep.Confirm
        assertEquals(0, t.runs)
        assertTrue(e.resume(step.pending, true) is AgentStep.Reply)
        assertEquals(1, t.runs)
    }

    @Test fun rejectedHighRiskNeverExecutes() = runBlocking {
        val t = FakeTool("del", Risk.HIGH)
        val e = engine(Script("""{"tool":"del","args":{}}""", "ঠিক আছে, করিনি"), t)
        val step = e.run(hi) as AgentStep.Confirm
        e.resume(step.pending, false)
        assertEquals(0, t.runs)
    }

    @Test fun runawayToolLoopIsStopped() = runBlocking {
        val s = Script("""{"tool":"echo","args":{}}""")
        val r = engine(s, FakeTool("echo", Risk.LOW)).run(hi)
        assertTrue(r is AgentStep.Reply)
        assertEquals(AgentEngine.MAX_STEPS, s.seen.size)
    }

    @Test fun unknownToolDoesNotCrash() = runBlocking {
        val r = engine(Script("""{"tool":"nope","args":{}}""", "ok")).run(hi)
        assertEquals(AgentStep.Reply("ok"), r)
    }

    @Test fun providerErrorIsReturnedNotThrown() = runBlocking {
        val c = Chatter { _, _ -> Result.failure(LlmException(LlmError.RateLimit)) }
        assertEquals(AgentStep.Failed(LlmError.RateLimit), engine(c).run(hi))
    }

    @Test fun normalizeDropsLeadingAssistantAndMergesSameRole() {
        val n = AgentEngine.normalize(listOf(ChatMsg("assistant", "a"), ChatMsg("user", "1"), ChatMsg("user", "2"), ChatMsg("assistant", "x")))
        assertEquals(listOf(ChatMsg("user", "1\n2"), ChatMsg("assistant", "x")), n)
    }

    @Test fun parserFindsJsonInsideProseAndFences() {
        val c = ToolCallParser.parse("Sure:\n```json\n{\"tool\":\"remember\",\"args\":{\"key\":\"a}b\"}}\n```")
        assertEquals("remember", c?.tool)
        assertEquals("a}b", c?.args?.getString("key"))
        assertNull(ToolCallParser.parse("কোনো টুল লাগবে না {এটা JSON নয়}"))
    }

    @Test fun timeParsing() {
        val dhaka = ZoneId.of("Asia/Dhaka")
        val a = TimeParse.parse("2026-11-16T09:00", dhaka)!!
        assertEquals(a, TimeParse.parse("2026-11-16 09:00", dhaka))
        assertEquals(a, TimeParse.parse("2026-11-16T09:00:00+06:00", ZoneId.of("UTC")))
        assertNull(TimeParse.parse("কাল সকালে", dhaka))
        assertNull(TimeParse.parse("2026-11-16", dhaka))
    }
}
