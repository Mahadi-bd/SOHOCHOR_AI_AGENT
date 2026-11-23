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

| Live "thinking" indicator with status text, seconds counter and a Stop button | UX, agent engine | `ThinkingIndicator`; `AgentEngine.onStatus`; the job is cancellable end to end. Runs offline (no web embed). |
| Encrypted vault opened with fingerprint or screen lock | M10 Security Vault | Files and file names AES-256-GCM, key in the Android Keystore; never visible to the AI tools; locks when the app leaves the screen. |
| App lock and screenshot block (optional, in Settings) | M10 | System BiometricPrompt with PIN/pattern fallback. |
| Settings: keys (masked), theme, pinned document colour, security, clear chat | UI | `SettingsScreen`. |

| Voice: speak commands (Bengali or English) and optional spoken answers | M12 Voice | `VoiceInput` (the phone's recognizer) and `Speaker` (text-to-speech). Spoken sentences go through the same agent and confirmation gate as typed ones. |
| 3D loader (turning cube behind the bird) | M12 3D | `Mesh3D` is plain maths drawn on a Compose canvas: offline, no web view. A model viewer (three.js) stays on the roadmap. |
| "Drive-এ পাঠান": creates native Google Docs/Sheets/Slides in the user's Drive | M13 Integrations | `drive.file` scope only; needs the one-time setup in `docs/GOOGLE_SETUP.md`. |
| Paid tier that removes the branding (Google Play subscription `remove_branding`) | monetisation | `BillingManager` sets `Entitlements`; setup in `docs/BILLING_SETUP.md`. Works only for Play Store installs. |

| Web Watcher: watch a website for new documents, read them with the user's own AI key (scanned Bengali PDFs through vision), notify; plus one-off site search | new module M14 | `watch/` package (`WatchEngine`, `WatchWorker`), agent tools `watch_add`, `watch_list`, `watch_remove`, `watch_check_now`, `search_site`. DB version 2 with a migration that keeps all existing data. https sites only; background checks at best every 15 minutes (Android WorkManager). |

| More AI providers: DeepSeek and GitHub Models (the models behind GitHub Copilot's platform, via a GitHub token) | LLM router | `ProviderType.DEEPSEEK`, `ProviderType.GITHUB`; GitHub Models is marked experimental. There is no public API for the Copilot chat product itself, and Microsoft's own route is Azure OpenAI. |
| Bengali language polish for generated documents (spelling, danda spacing, one style per document, mixed sadhu/cholit report) and office-letter commands `/notice`, `/application`, `/letter`, `/order` | M4 Document Studio | `BengaliLang`, ideas from the owner's Python Bengali office document agent. Word-boundary rules were rebuilt for Bengali; tone rewriting was left out on purpose. |
| Voice listens continuously (restarts itself after pauses) and reads the whole answer aloud in chunks | M12 Voice | `VoiceInput`, `SpeechText.chunks`. |

## Planned UI/UX redesign (decided, not built yet)
Direction from the owner: a Cyberpunk-inspired neon dark UI with glassmorphism and a glowing orb AI avatar, plus a Planet Earth `.glb` 3D model that the owner will supply. All current screens (`ui/` package) are temporary. When the redesign starts: put colours, shapes and glass effects in one theme layer first, then restyle screen by screen; the orb replaces the bird/cube in `ThinkingIndicator`; the `.glb` goes through a model viewer (three.js in a web view or a native renderer).

Next in the blueprint: the UI/UX redesign, 3D model viewer, native Google Docs editing, optional Telegram alerts for the watcher.
