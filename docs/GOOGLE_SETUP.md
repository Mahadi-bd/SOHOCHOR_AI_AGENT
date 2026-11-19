# Google Drive export: one-time setup

The app creates real Google Docs, Sheets and Slides in the user's own Drive. Google requires the app owner to register the app once.

1. Open https://console.cloud.google.com and create a project (any name).
2. **APIs & Services > Library**: enable **Google Drive API**.
3. **OAuth consent screen**: choose External, fill the app name (Sahachor) and your email. Add the scope `.../auth/drive.file`. While testing, add your own Google account under *Test users*.
4. **Credentials > Create credentials > OAuth client ID**, type **Android**:
   - Package name: `com.sahachor.app`
   - SHA-1 certificate fingerprint of the debug build (the keystore is committed in this repo): `AC:ED:BA:37:BD:58:F3:4C:40:2B:D6:2A:2B:AE:1D:7B:01:F0:72:53`
   - For a Play Store release, add a second client with the SHA-1 of the **Play App Signing key** (Play Console > App integrity).
5. Install the new APK and press **Drive-এ পাঠান** on a document. The first time Google shows a consent screen.

Scope used: `drive.file` only. The app can only see files it created itself.
If the app shows "OAuth ক্লায়েন্ট দরকার", step 4 is missing or the package name / SHA-1 does not match.
