package com.sahachor.app.docs

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BengaliLangTest {
    @Test fun spellingSlipsAreFixedEvenForWordsEndingInVowelSigns() {
        val st = BengaliLang.Stats()
        assertEquals("এটা জরুরি এবং বেশি দরকার", BengaliLang.polishText("এটা জরুরী এবং বেশী দরকার", "cholit", st))
        assertEquals(2, st.spelling)
        assertEquals("আপনার সহযোগিতার জন্য ধন্যবাদ", BengaliLang.polishText("আপনার সহযোগীতার জন্য ধন্যবাদ", "none"))
        assertEquals("বাড়িতে বেশিরভাগ", BengaliLang.polishText("বাড়ীতে বেশীরভাগ", "none"))
    }

    @Test fun wordsAndAmountsAreNeverRewritten() {
        assertEquals("১০০ টাকা পাওয়া গেছে", BengaliLang.polishText("১০০ টাকা পাওয়া গেছে", "cholit"))
        assertEquals("আমি আসব", BengaliLang.polishText("আমি আসব", "cholit"))
        assertEquals("তার কাজ ভালো", BengaliLang.polishText("তার কাজ ভালো", "cholit"))
        assertEquals("Hello  world", BengaliLang.polishText("Hello  world", "cholit"))
    }

    @Test fun strongSadhuVerbsBecomeCholitOnlyInCholitMode() {
        val st = BengaliLang.Stats()
        assertEquals("কাজ হবে এবং আমরা করতে পারি", BengaliLang.polishText("কাজ হইবে এবং আমরা করিতে পারি", "cholit", st))
        assertEquals(2, st.converted)
        assertEquals("কাজ হইবে", BengaliLang.polishText("কাজ হইবে", "sadhu"))
        assertEquals("সভা হইবেন", BengaliLang.polishText("সভা হইবেন", "cholit")) // longer words are left alone
    }

    @Test fun dandaSpacingIsTidied() {
        assertEquals("সভা হবে।", BengaliLang.polishText("সভা হবে ।।", "cholit"))
    }

    @Test fun mixedStylesAreReported() {
        val (sadhu, cholit) = BengaliLang.markers("কাজ হইবে এবং কাজ হয়েছে")
        assertEquals(listOf("হইবে"), sadhu); assertEquals(listOf("হয়েছে"), cholit)
        val doc = SpecParser.doc(JSONObject("""{"title":"জরুরী নোটিশ","sections":[{"heading":"বিষয়","body":"কাজ হইবে এবং কাজ হয়েছে।","bullets":["বেশী"],"table":[["ক","খ"],["বেশী","১০ টাকা"]]}]}"""))!!
        val (p, report) = BengaliLang.polishDoc(doc, "cholit")
        assertEquals("জরুরি নোটিশ", p.title)
        assertTrue(report.mixed)
        val msg = report.message("cholit")!!
        assertTrue(msg.contains("ভাষা যাচাই") && msg.contains("সাধু-চলিত মিশ্রণ"))
        val table = p.blocks.filterIsInstance<Block.Table>().single()
        assertEquals(listOf("বেশি", "১০ টাকা"), table.rows[1])
        val sadhuReport = BengaliLang.polishDoc(doc, "sadhu").second.message("sadhu")!!
        assertTrue(sadhuReport.contains("সাধু ভাষায় চলিত রূপ আছে"))
    }

    @Test fun cleanTextGivesNoMessage() {
        val doc = SpecParser.doc(JSONObject("""{"title":"নোটিশ","sections":[{"heading":"বিষয়","body":"সভা হবে।"}]}"""))!!
        assertNull(BengaliLang.polishDoc(doc, "cholit").second.message("cholit"))
    }

    @Test fun slidesAreCleanedToo() {
        val deck = SpecParser.deck(JSONObject("""{"title":"জরুরী সভা","subtitle":"বেশী","slides":[{"title":"কাজ","bullets":["কাজ হইবে"]}]}"""))!!
        val (p, _) = BengaliLang.polishDeck(deck, "cholit")
        assertEquals("জরুরি সভা", p.title); assertEquals("বেশি", p.subtitle); assertEquals(listOf("কাজ হবে"), p.slides[0].bullets)
    }
}
