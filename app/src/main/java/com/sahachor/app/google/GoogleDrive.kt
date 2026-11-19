package com.sahachor.app.google

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.sahachor.app.llm.Http
import com.sahachor.app.vault.VaultNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Which native Google file a document becomes when it is uploaded to Drive. */
object DriveMime {
    fun googleType(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "xlsx", "ods", "csv" -> "application/vnd.google-apps.spreadsheet"
        "docx", "odt" -> "application/vnd.google-apps.document"
        "pptx" -> "application/vnd.google-apps.presentation"
        else -> null // pdf, zip and others are stored as they are
    }

    fun metadata(name: String, google: String?): String {
        val o = JSONObject().put("name", if (google != null) name.substringBeforeLast('.') else name)
        if (google != null) o.put("mimeType", google)
        return o.toString()
    }
}

data class DriveResult(val ok: Boolean, val link: String?, val message: String)

/** Consent screen for the narrow "drive.file" scope: the app can only see files it created itself. */
object GoogleAuth {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"

    fun authorize(activity: Activity, onToken: (String) -> Unit, onNeedsUi: (IntentSender) -> Unit, onError: (String) -> Unit) {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { r ->
                val sender = r.pendingIntent?.intentSender
                val token = r.accessToken
                if (r.hasResolution() && sender != null) onNeedsUi(sender)
                else if (token != null) onToken(token)
                else onError("Google থেকে অনুমতি পাওয়া যায়নি।")
            }
            .addOnFailureListener { e -> onError(errorText(e)) }
    }

    fun tokenFromResult(activity: Activity, data: Intent?): String? = try {
        Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data).accessToken
    } catch (e: ApiException) { null }

    private fun errorText(e: Exception): String =
        if (e is ApiException && e.statusCode == 10) "Google সংযোগ এখনো সেট করা হয়নি (OAuth ক্লায়েন্ট দরকার)। docs/GOOGLE_SETUP.md দেখুন।"
        else "Google-এর সাথে যুক্ত হওয়া যায়নি। ফোনে Google Play Services আছে কি না দেখুন।"
}

object DriveExporter {
    suspend fun upload(token: String, file: File): DriveResult = withContext(Dispatchers.IO) {
        val google = DriveMime.googleType(file.name)
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(DriveMime.metadata(file.name, google).toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(file.readBytes().toRequestBody(VaultNames.mimeFor(file.name).toMediaType()))
            .build()
        val req = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,webViewLink")
            .header("Authorization", "Bearer $token")
            .post(body).build()
        try {
            Http.client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.isSuccessful) {
                    val kind = when (google) {
                        null -> "ফাইল"
                        "application/vnd.google-apps.spreadsheet" -> "Google Sheet"
                        "application/vnd.google-apps.document" -> "Google Doc"
                        else -> "Google Slides"
                    }
                    DriveResult(true, JSONObject(text).optString("webViewLink").ifEmpty { null }, "Drive-এ $kind হিসেবে তৈরি হয়েছে।")
                } else if (r.code == 401) {
                    DriveResult(false, null, "Google অনুমতি কাজ করেনি। আবার চেষ্টা করুন।")
                } else if (r.code == 403) {
                    DriveResult(false, null, "Google Drive API চালু করা নেই। Google Cloud Console-এ Drive API Enable করুন (docs/GOOGLE_SETUP.md)।")
                } else {
                    DriveResult(false, null, "Google Drive ত্রুটি (${r.code})।")
                }
            }
        } catch (e: IOException) {
            DriveResult(false, null, "ইন্টারনেট সংযোগে সমস্যা হয়েছে।")
        }
    }
}
