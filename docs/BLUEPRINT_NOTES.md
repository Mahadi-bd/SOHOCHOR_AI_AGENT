# Sahachor: design decisions that sit inside the master blueprint

Nothing here changes the blueprint modules; each item extends one of them.

| Decision | Blueprint module | Notes |
|---|---|---|
| One content, many files: pdf, docx, odt, xlsx, ods, csv, pptx, zip | M4 Document Studio | The model sends one structured content; the app renders every requested format with the same theme. |
| Six colour themes shared by every format | M4 | `Palette` in `docs/DocSpec.kt`. |
| LIFE EASY logo and name on PDF, Word, ODT and PowerPoint | M4 | Logo is `res/drawable-nodpi/life_easy_logo.png`. Spreadsheets and CSV stay clean (data files). |
| Removing the branding is a paid feature later | new, monetisation | `Entitlements.isPro` is false for now. The AI tools get no way to switch branding off. A later phase connects it to Google Play Billing. A user can still edit a finished PDF in another program; that cannot be prevented and is accepted. |
| Google Sheets / Docs / Slides: open from the Documents tab | M4, M13 | Files are standard formats, so Google apps open them. Creating native Google files needs Google sign-in (OAuth) and comes later with M13 integrations. |
| Formula safety (no HYPERLINK, WEBSERVICE, IMPORT*) and CSV injection guard | Security | `SafeFormula` in `docs/Sheets.kt`. |
| Phone actions use system screens (alarm, timer, calendar, dial, SMS/email draft, maps, share, open app) | M7 Phone Control | No risky permissions; dial, SMS and email need the user's confirmation. |
| Several models per API key with automatic fallback; keys rotate on rate limits | LLM router | `ModelFallback`, `ModelProbe`. |

Still to build (in blueprint order): security vault with biometric lock (M10), settings screen with key management, voice, 3D, native Google integration, Play Billing for the paid tier.
