package com.sahachor.app.google

import com.sahachor.app.docs.Branding
import com.sahachor.app.docs.Entitlements
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DriveMimeTest {
    @Test fun officeFilesBecomeNativeGoogleFiles() {
        assertEquals("application/vnd.google-apps.spreadsheet", DriveMime.googleType("a.xlsx"))
        assertEquals("application/vnd.google-apps.spreadsheet", DriveMime.googleType("a.CSV"))
        assertEquals("application/vnd.google-apps.document", DriveMime.googleType("a.docx"))
        assertEquals("application/vnd.google-apps.presentation", DriveMime.googleType("a.pptx"))
        assertNull(DriveMime.googleType("a.pdf"))
        assertNull(DriveMime.googleType("a.zip"))
    }

    @Test fun convertedFilesDropTheExtensionAndCarryTheTargetType() {
        val j = JSONObject(DriveMime.metadata("রিপোর্ট.docx", DriveMime.googleType("রিপোর্ট.docx")))
        assertEquals("রিপোর্ট", j.getString("name"))
        assertEquals("application/vnd.google-apps.document", j.getString("mimeType"))
        val p = JSONObject(DriveMime.metadata("রিপোর্ট.pdf", null))
        assertEquals("রিপোর্ট.pdf", p.getString("name"))
        assertFalse(p.has("mimeType"))
    }

    @Test fun proTierSwitchesBrandingOffAndBackOn() {
        try {
            Entitlements.set(true)
            assertFalse(Branding.enabled())
        } finally {
            Entitlements.set(false)
        }
        assertTrue(Branding.enabled())
    }
}
