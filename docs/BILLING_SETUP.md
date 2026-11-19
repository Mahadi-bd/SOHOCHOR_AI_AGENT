# Paid tier (remove LIFE EASY branding): one-time setup

Billing only works for an app installed from Google Play. A GitHub APK shows "Google Play সংযোগ পাওয়া যাচ্ছে না" in Settings, which is expected.

1. Create a Google Play developer account and an app with package `com.sahachor.app`.
2. Upload a signed build (internal testing track is enough) so Billing can be tested.
3. **Monetize > Products > Subscriptions**: create a subscription with product ID exactly `remove_branding`, add a base plan (for example monthly) and activate it.
4. Add your Google account as a **license tester** (Play Console > Settings > License testing) to buy without being charged.
5. Install the app from the testing track and open Settings > LIFE EASY Pro.

How it works in the app: `BillingManager` reads active purchases on every start and sets `Entitlements`. While active, generated documents carry no logo or LIFE EASY name. The state is cached encrypted so it also works offline. The AI tools cannot change it.
