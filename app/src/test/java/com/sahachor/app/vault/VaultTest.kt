package com.sahachor.app.vault

import com.sahachor.app.agent.ToolLabels
import com.sahachor.app.llm.maskKey
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

class VaultTest {
    private fun key(seed: Int) = SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")

    @Test fun encryptDecryptRoundTripKeepsBengali() {
        val c = VaultCrypto(key(1))
        val plain = "গোপন ফাইল: পাসপোর্ট নম্বর".toByteArray()
        val blob = c.encrypt(plain)
        assertTrue(blob.size > plain.size) // IV and authentication tag are added
        assertArrayEquals(plain, c.decrypt(blob))
    }

    @Test fun everyEncryptionUsesAFreshIv() {
        val c = VaultCrypto(key(2))
        val a = c.encrypt(ByteArray(64) { 7 })
        val b = c.encrypt(ByteArray(64) { 7 })
        assertFalse(a.copyOfRange(0, 12).contentEquals(b.copyOfRange(0, 12)))
        assertFalse(a.contentEquals(b))
    }

    @Test fun tamperedOrWrongKeyDataIsRejected() {
        val c = VaultCrypto(key(3))
        val blob = c.encrypt("hello".toByteArray())
        val bad = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { c.decrypt(bad) }
        assertThrows(AEADBadTagException::class.java) { VaultCrypto(key(4)).decrypt(blob) }
        assertThrows(IllegalArgumentException::class.java) { c.decrypt(ByteArray(10)) }
    }

    @Test fun indexRoundTripAndGarbageIsSafe() {
        val items = listOf(VaultItem("11111111-1111-1111-1111-111111111111", "পাসপোর্ট.pdf", "application/pdf", 1234, 99L))
        assertEquals(items, VaultIndex.fromJson(VaultIndex.toJson(items)))
        assertTrue(VaultIndex.fromJson("not json").isEmpty())
    }

    @Test fun safeNamesAndMimeTypes() {
        assertEquals("পাসপোর্ট.pdf", VaultNames.safe("../../পাসপোর্ট.pdf"))
        assertEquals("a_b.txt", VaultNames.safe("a:b.txt"))
        assertEquals("file", VaultNames.safe("..."))
        assertEquals("application/pdf", VaultNames.mimeFor("x.PDF"))
        assertEquals("application/octet-stream", VaultNames.mimeFor("x.unknown"))
    }

    @Test fun readLimitedStopsAtTheLimit() {
        assertEquals(5, readLimited(ByteArrayInputStream(ByteArray(5)), 10)?.size)
        assertNull(readLimited(ByteArrayInputStream(ByteArray(11)), 10))
    }

    @Test fun apiKeysAreMasked() {
        assertEquals("AQ.A••••xyz", maskKey("AQ.A" + "x".repeat(20) + "xyz"))
        assertEquals("••••", maskKey("short"))
    }

    @Test fun toolLabelsAreBengaliAndHaveADefault() {
        assertTrue(ToolLabels.of("create_document").contains("ফাইল"))
        assertTrue(ToolLabels.of("set_alarm").contains("ফোনের স্ক্রিন"))
        assertEquals("কাজ করছি…", ToolLabels.of("something_new"))
    }
}
