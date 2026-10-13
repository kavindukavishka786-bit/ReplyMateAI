# ReplyMate AI (Android, Kotlin + Jetpack Compose)

**ReplyMate AI** is a personal, opt-in WhatsApp notification assistant. It generates an actual, message-aware response to *new* incoming messages with **Gemini 3.5 Flash-Lite**, rather than repeating the sender's message. It can either let you review/edit and approve each draft, or (only when you explicitly enable it) attempt an automatic Android notification direct reply.

![status](https://img.shields.io/badge/Android-8.0%2B-64E8D5) ![language](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7A94FF)

## Current features

- Compose dark dashboard, guided permission setup, master power switch, manual/automatic mode, review inbox, contact rules, settings, and trial response generation.
- **Safe starting settings:** app OFF; manual-review mode; no contacts allowed until you add their WhatsApp display names; group chats OFF; 20 automatic sends/day; 15-minute cooldown; anti-duplicate guard.
- Supports WhatsApp **personal** application package `com.whatsapp` only. Does not request Contacts, SMS, Accessibility, Media, microphone or storage permission.
- Uses Android `NotificationListenerService` and Android `RemoteInput` directly from WhatsApp notification actions. No unofficial WhatsApp SDK, no QR login, no web scraping.
- API key is provided on your phone **after installation**. AES-256/GCM Android Keystore-backed encrypted storage at rest; never hard-coded or stored in GitHub.
- Sends the sender's displayed notification title, current notification text, any short-lived conversation context, and your AI style/optional owner-approved details to Gemini over HTTPS. No cloud server of our own.
- Pending drafts and brief per-contact conversation history are **memory-only** and disappear if Android kills the app. There is no chat history database.
- Editable drafts and explicit Approve/Discard buttons. Drafts expire after five minutes.
- Optional automatic mode asks for explicit confirmation. Limits, contact allow-list and master switch rechecked before sending.
- The Gemini prompt responds to what the sender means instead of copying their words. If Gemini echoes the message, ReplyMate retries once and blocks a second echoed result.
- Recent successful replies (up to four turns per contact) are held in memory for two hours for natural follow-up conversations, never written to chat storage.
- Optional owner-approved personal details let you customize how the assistant handles questions about your availability or work. Never add secrets.
- Test screen lets you check Gemini without sending a WhatsApp message.
- GitHub Actions produces `app-debug.apk`, no Android Studio needed.

## v1.5.1 — Durable duplicate guard and background recovery

- Changed-timestamp refreshes are suppressed for the full **15-minute** receipt window, including after process restart. The guard is shared across listener instances, synchronously saves salted fingerprints on IO before Gemini, and refuses new work if the bounded guard is full or saving fails. No message text or contact names are stored in this guard. **Trade-off:** identical text from the same sender within 15 minutes may be skipped; WhatsApp notifications provide no reliable message ID to disambiguate it. Distinct messages remain eligible immediately. An unsuccessful generation is not automatically retried, to avoid ambiguous duplicate sends.
- Outgoing response fingerprints persist for 10 minutes to block a reposted own reply after process restart. No outgoing text is stored. Direct reply actions must belong to WhatsApp, carry free-form RemoteInput, and must still be available for the same notification before automatic sending. Notification removal invalidates queued automatic actions. Listener disconnection cancels pending jobs; Gemini stays on IO, and cancellation is checked again before dispatch.
- Manual approval atomically claims a draft once, rechecks the master switch, current allow/block rules, group permission, daily cap, notification access, expiry, and echoed output. Manual and automatic sends share one counter/send lock. Gemini style, owner facts, Sinhala responses, short-lived successful conversation memory, encrypted API-key storage, and conservative incoming-sender checks are preserved.
- A single low-frequency foreground-service health monitor requests listener rebind only if disconnected and permission remains granted. It does not poll WhatsApp or hold a permanent wake lock. Sticky restart honors saved Stop and setup permissions. Returning to the Activity reads current settings instead of a stale Compose lifecycle closure.
- **Restart after reboot** is OFF by default. Opt in on Home to resume an already-requested background session after reboot/app update. Recovery requires master ON, saved session, notification access, visible-notification permission and a readable API key. Stop remains respected. The specialUse service does not circumvent Android restrictions or force-stop.
- Home shows actual battery-optimization status, a shortcut to app settings and XOS setup guidance. Menu names vary by Infinix model. Set ReplyMate battery use to Unrestricted / Don't optimize, allow background activity and Auto-start where offered, and check Power Marathon / Recent-app locking. These settings require you to act on your phone; the APK cannot silently change them.

**Verification:** GitHub Actions runs JVM and Robolectric tests (Android API 28 and 34), lint, APK build, signature validation and package/version checks, then installs/launches/navigates/resumes the APK on an Android 14 emulator. Reports and the APK are uploaded. This verifies the code/build, not end-to-end Gemini or WhatsApp delivery on a physical Infinix phone. Test one trusted contact: one message → one reply; refresh after 90 seconds → no duplicate; a different follow-up → one reply. Then test Home/lock screen/swipe-away and optional reboot. Confirm the foreground notification and connected listener, and test OFF, Stop, revoked access and removed allowlist entries. No real WhatsApp messages are sent by CI.

Debug APKs are for testing. A fresh runner's debug signing key may differ from your installed APK; if Android rejects an update as a signature mismatch, a reinstall is required and clears app settings/API key. A stable private release signing key must be configured separately before public distribution.

## v1.5.0 — Duplicate reply and background reliability repair

**Duplicate AI sends:** Android/WhatsApp can re-post the SAME incoming chat message with a different MessagingStyle timestamp. Prior builds used timestamp as part of the unique-message key, so repeated notifications could generate two or more replies. Version 1.5.0 adds a receipt guard that blocks exact repeat events for 15 minutes and coalesces matching sender + message text for 12 seconds even if the notification timestamp changes. Receipt fingerprints are salted SHA-256 hashes saved locally, not message text, contact names or credentials. The guard checks before Gemini API calls. There is a trade-off: a contact sending *identical text twice* inside 12 seconds may receive only one reply. Distinct messages remain eligible immediately.

**Replies while the app is closed:** Choosing **Automatic** with the master switch enabled now starts the visible background foreground service while the Activity is open (provided notification access, notification permission and a Gemini API key are configured). The UI's **Keep running** button still works, and the persistent foreground notification should remain while you leave the ReplyMate screen or swipe away the app's task. The user-approved keep-alive preference is saved locally and can be recovered when the Activity is opened again; Android-managed listener disconnections request a rebind if permission remains granted. **Stop** remains respected: it will not restart against the user's explicit choice.

**Phone setup:** Open ReplyMate → confirm master switch ON, choose Automatic, and confirm the ongoing `ReplyMate AI • Background mode` notification. Set the app's battery usage to Unrestricted, allow background activity and manufacturer-specific Auto-start if offered. For Infinix/XOS, check the phone's app startup / Power Marathon / battery manager controls. Whitelisting is user-controlled, not performed programmatically. **Do not force-stop the app** from Android app settings; force-stopped apps cannot automatically restart. Even a foreground service can be killed by the OS, aggressive power management, revoked notification access, lack of mobile data or WhatsApp changing its notification format. There is no promise of uninterrupted delivery or official WhatsApp support.

**Test plan:** On a secondary WhatsApp account, send one message; verify exactly one AI reply. Send a different message right afterward; it should be eligible immediately. Next, leave the ReplyMate screen, lock the phone, and send a new message. Confirm the persistent status notification remains and one reply arrives. Do not test on a primary account until the behavior is stable.

## Main-thread network exception fixed (v1.4.3)

**Symptom:** Settings → Try a smart reply succeeds, but live incoming WhatsApp messages show `Stage: Generate AI response. Unexpected NetworkOnMainThreadException` and neither drafts nor auto-replies are generated.

**Cause:** The per-contact notification queue used `scope.launch(start = CoroutineStart.UNDISPATCHED)` to keep message order. `UNDISPATCHED` starts on the notification callback's Android main thread **even when the CoroutineScope uses `Dispatchers.IO`**. If cached settings and the message queue do not suspend before `GeminiClient.draft`, its synchronous `HttpURLConnection` executes on main, which Android forbids.

**Fix:** v1.4.3 retains ordered per-contact processing, but forces an actual dispatcher switch via `withContext(Dispatchers.Default)` **inside the acquired contact mutex**, then runs Gemini work within `withContext(Dispatchers.IO)`. A direct IO-to-IO context switch can execute inline and is not sufficient. The main-thread callback is free immediately, and Gemini HTTP requests execute on an I/O worker. A JVM regression test checks the dispatcher handoff behavior.

**Testing:** Install v1.4.3 and enable notification access and one approved test contact. Set **Manual** mode first. Have the contact send a WhatsApp notification while the app is closed; confirm a draft appears with **no new NetworkOnMainThreadException**. Then try Automatic only if approved. The old error remains visible until a later failure or settings reset and the historic Errors today count is not cleared automatically. This only addresses that specific exception; WhatsApp notifications may still be unavailable or supply expired reply actions. The app is not an official WhatsApp automation solution.

## Diagnosing unexpected failures (v1.4.2)

If Home showed only `Unexpected reply processing error` in v1.4.1, that label hid the actual exception. This update records **processing stage** and a **safe exception category**, so a new failure can be investigated without uploading personal WhatsApp conversations.

On Home, check **Latest error** after one new message from your approved test contact. A message such as `Stage: Generate AI response. Network connection was interrupted...` points to transport, whereas `Stage: Save counters. Unexpected IllegalStateException...` points to local app state. **The old v1.4.1 error cannot be interpreted retroactively**; a new attempt is necessary.

Check **Settings → Try a smart reply** with a simple test sentence. The test now sanitizes exception messages as well. If the in-app Gemini test works but background messaging fails, examine the **Latest error** and Notification access / direct-reply support. If the test itself fails, investigate the API key, model access, mobile data and Google AI Studio quota. To protect privacy, do not share API keys, full logcat output, or sensitive message text.

The update does not prove all platform-specific causes are fixed. Only the actual failure from the device can establish the next targeted repair. The best test is one trusted contact, Manual mode first, and a lock-screen incoming WhatsApp message.

## Error diagnostics (v1.4.1)

Previous builds showed only an **Errors today** counter; failures in Gemini generation were silently swallowed. Starting v1.4.1, the dashboard has a **Latest error** section beneath the counters, showing a sanitized explanation and local time. It can distinguish:
- HTTP 400 (invalid request), HTTP 401/403 (API key/access), HTTP 404 (model access), HTTP 429 (quota), and HTTP 5xx (temporary server issue).
- Network connection failures and Gemini response timeouts.
- Empty/model-echo output and failed WhatsApp notification reply actions.

No incoming message, sender name, API key, request contents, or raw error stack trace is written to these diagnostics. The local error count is still daily; a prior day's last error is shown with its timestamp.

**How to diagnose:** Install v1.4.1, open **Settings → Try a smart reply**, and test `Hi, how are you?`. If it fails, read the exact safe error. Return to Home to see the last error from live WhatsApp processing after the next attempted reply. If the result says **HTTP 404**, you can switch between **Settings → AI model → 3.5 Flash-Lite / 3.1 Flash-Lite** based on which model your Google AI Studio key can access. If it says **HTTP 429**, wait or check quotas—trying to bypass rate limits is not an option. For **HTTP 401/403**, check your API key, model access and project restrictions in Google AI Studio without publishing the key. Google API errors, permissions and quotas depend on your project. There is no promise that switching models will fix restrictions.

## Instant per-message replies (v1.4.0)

The previous automatic mode could skip fast follow-up messages because of a default 15-minute cooldown, a 20-second post-send suppression window, and a one-in-flight-message-per-contact gate.

Version 1.4.0 introduces **Rules → Instant replies** (ON by default, including for existing installations). When enabled, the old cooldown is bypassed. An updated notification's multiple fresh `MessagingStyle` messages are processed individually in chronological order with a bounded per-contact queue (up to 12 pending messages), instead of discarding new messages while Gemini is busy. Separate contacts can be processed concurrently. Previously processed notification messages are deduplicated.

Outward-message protection still checks structured sender metadata, your own outgoing message text fingerprint, contact allowlists, and daily send limits. **Real incoming messages are no longer silenced for 20 seconds simply because the bot has just replied.**

To use: choose **Automatic** mode, turn ReplyMate ON, start **Keep running**, enable **Rules → Instant replies**, and ensure the daily send limit is sufficient. An actual AI reply is sent after Gemini has produced it and Android's WhatsApp notification direct-reply action accepts it. There is no guaranteed fixed delivery time, and multiple messages from the same person are handled in order (not simultaneously).

Caveats: only *fresh* notifications received in the last 3 minutes with valid sender/timestamp metadata can be queued; the listener is not a WhatsApp message-history reader. If the queue is full, the service is killed, WhatsApp hides or groups notifications without proper message metadata, Gemini quota is exhausted, the daily send cap is reached, or Android is offline, some messages will not receive automatic replies. The app does not bypass WhatsApp's restrictions and automatic messaging can put a personal account at risk.

## Background operation (v1.3.0)

**Why the app seemed to reply only when opened:** Android can stop background processes, especially on phones with aggressive battery management. A system-bound `NotificationListenerService` receives WhatsApp notifications independently of the Activity, but it can still be disconnected or killed by the OS. And **Manual** mode intentionally waits for the user to approve a draft, even when notification detection happens in the background.

To opt in to continuous background operation:
1. Open ReplyMate after updating, save/verify your API key, and grant **Notification access** and **Allow notifications**.
2. Switch **Enable ReplyMate** ON and ensure your approved contacts are correct.
3. In **Background operation**, tap **Keep running**. An ongoing "ReplyMate AI • Background mode" notification appears with a **Stop background** button. You can leave the app screen after starting it.
4. Use **Battery optimization settings** to set ReplyMate to unrestricted / not optimized (wording depends on Android). Some devices also require Auto-start/Background activity permission from the manufacturer's battery settings.
5. If you want replies **sent** while the app is closed, choose **Automatic** in Reply mode and confirm the safety warning. In **Manual** mode, messages become review drafts but are not sent until you approve them.
6. Have one trusted contact send a new WhatsApp message while ReplyMate is in the background and the screen is off. Test with **Manual** first to ensure the new draft appears; then test Automatic if you accept the account risks.

**Limitations:** "Keep running" is an explicitly started Android *specialUse foreground service*, not a bypass of system controls or WhatsApp terms. It requires a visible ongoing notification on supported Android versions, uses extra battery, does not read missed historical messages or guarantee uninterrupted delivery, and may stop if Android or the device manufacturer restricts it. Your phone must have an internet connection and WhatsApp notifications must show a compatible incoming sender + direct reply action. After a reboot, reopen ReplyMate and re-enable Keep running if necessary; the app never starts a foreground service silently at boot. If you force-stop ReplyMate, Android disables its background work until you reopen it. Google Play publication of the specialUse foreground-service type requires additional eligibility review.

## Existing safety protections

All earlier inbound-only sender checks, outgoing-message echo guard, 10-minute echoed-own-text guard, allowlist, daily send cap and cooldown remain active. These checks intentionally skip uncertain notifications.

## Important outgoing-message loop fix (v1.2.0)

ReplyMate previously could mistake its **own sent message** for an incoming WhatsApp notification, then respond again. Version 1.2.0 prevents that feedback loop in two ways:

1. It accepts only notifications that expose structured Android MessagingStyle messages with a distinct, identifiable sender. It no longer trusts the generic `EXTRA_TEXT` notification preview as proof of an incoming message; ambiguous or unsupported notifications are **ignored**.
2. It records an outgoing text fingerprint immediately before sending. Since v1.4.0, it **no longer blocks all notifications for 20 seconds**; only exact echoes of its own outgoing text are rejected for up to 10 minutes. No raw messages are saved to disk.

This conservative approach may **miss legitimate messages with the exact same text as an outgoing reply**, or all messages on WhatsApp builds that do not expose sender metadata. That tradeoff avoids repetitive auto-sending. Keep manual mode while testing. It is not an official WhatsApp integration and cannot guarantee correct direction detection on every phone. Send messages from a second WhatsApp account for testing. Stop using the app if self-replies recur.

## Personalized reply examples (v1.1.0)

| Incoming message | Example of what Gemini can write (actual output varies) |
| --- | --- |
| `Hi` | `Hey! I'm the AI helping with messages. What's up?` |
| `Can we meet tomorrow?` | `The owner will need to confirm. What time did you have in mind?` |
| `ඔයාට අද වෙලාව තියෙනවද?` | `මම එයාගේ AI සහායකයා. අද වෙලාව තියෙනවද කියලා එයාට තහවුරු කරන්න වෙයි.` |
| `What did we decide?` | Can refer to recent replies *sent through ReplyMate*, if the app is still running. It cannot read your full WhatsApp chat history. |

In **Settings → Assistant personality**, customize tone. In **Settings → Personal details**, enter only non-sensitive facts that the assistant is allowed to share or use. Use **Try a smart reply** with a test message before enabling automatic mode.

## Important limitations

This is an **experimental first implementation**, not guaranteed to work on every WhatsApp / Android build. WhatsApp is not officially authorizing personal-account automation. Account warnings or restrictions are possible, particularly if you enable unattended replies. This app only works for notifications that include a compatible Android reply action. WhatsApp notification grouping, hidden previews, custom OEM battery rules, message edits, notification dismissal, Android process restarts, and connectivity can prevent or delay responses. Name-based filters use displayed notification titles, **not authenticated contact identities**; matching names could cause false positives. For all these reasons, *keep manual review enabled* and test with a secondary WhatsApp number.

The app does not connect to the official WhatsApp Business Platform. It does not promise continuous delivery or bypass WhatsApp controls.

**Security and privacy:** The key is encrypted on-device when idle but must be decrypted to call Gemini. A rooted/compromised device can expose it. Requests are sent directly from the phone to Google. Google API quota, data-use terms, and the free tier can change; free is *not unlimited*. Do not use the bot for highly sensitive chats. Because the app reads WhatsApp notification access, Android may expose message previews from more apps, but this code filters by `com.whatsapp` before reading text. Never publish personal messages or Gemini credentials in your repository. Privacy is a design choice, not a security audit.

## Build without Android Studio: phone-only route

1. Download the **ReplyMateAI.zip** archive, unzip it into Android `Download/ReplyMateAI` using Files (the `app` and `.github` folders should be in the same root).
2. Get **Termux** from [F-Droid](https://f-droid.org/en/packages/com.termux/) and open it. (Google Play versions may differ.)
3. Run:

```sh
pkg update
pkg install git gh
termux-setup-storage
cd ~/storage/downloads/ReplyMateAI
# If extraction created two nested ReplyMateAI folders, cd into the folder with settings.gradle.kts.
git init
git branch -M main
git config user.name "YOUR_GITHUB_NAME"
git config user.email "YOUR_GITHUB_EMAIL"
git add .
git commit -m "Initial ReplyMate Android project"
gh auth login
# Complete GitHub's browser-based authentication; never paste tokens in chat.
gh repo create ReplyMateAI --private --source=. --remote=origin --push
```

4. Go to **GitHub → ReplyMateAI → Actions → Build ReplyMate APK**. If necessary, tap **Run workflow**. The action automatically runs on pushes to `main`.
5. Open the successful build → **Artifacts → ReplyMate-AI-Debug-APK**. Download and unzip the workflow artifact to find `app-debug.apk`.
6. On Android, install the APK. You may need **Allow installs from this source** for your browser/file manager. Install only APKs that you built yourself.
7. Open ReplyMate → **Settings** → Save your Gemini API key. Create one at <https://aistudio.google.com/api-keys>. First use **Try the AI** to verify the key and network.
8. Grant **Notification access** in Android Settings (the app links to it). On Android 13+, allow notifications so the app can show review alerts. **Sideloading restriction:** If Android says *Restricted setting*, first go to Settings → Apps → ReplyMate AI → menu (⋮) → **Allow restricted settings**, then return and grant notification access. This permission is sensitive; enable it only for the APK you built and checked yourself. Some devices label the steps differently. Grant access only if you understand that a notification listener can see notification contents.
9. Open **Rules** → enter one test contact's EXACT WhatsApp notification display name on a separate line. Leave **Allow any contact** OFF and **Include groups** OFF.
10. Choose **Manual**, turn on the master switch, ask that contact to message your WhatsApp, then open **Reviews** to approve, edit, or discard. For the first test, leave WhatsApp notifications fully enabled and phone connected to the internet.
11. Use automatic mode only if you intentionally accept its risk and understand how to stop it instantly with the master switch.

### Alternative desktop build

Use JDK 17, Android SDK platform 36, and Gradle 8.13:

```sh
gradle :app:assembleDebug
```

The project intentionally uses the `gradle/actions/setup-gradle` GitHub Action to install Gradle; it does not include the binary Gradle wrapper JAR. GitHub Actions does not require Android Studio or Gradle installed on your phone.

## Troubleshooting

- **No reply drafts:** Check Notification access, the master switch, key, approved contact name, WhatsApp notification content, and whether the notification has a reply action. Turn off WhatsApp's hidden message previews for the test if necessary, with privacy in mind.
- **Manual approval fails:** WhatsApp's notification action may already have expired or been dismissed. Send manually in WhatsApp instead. Review items also expire after five minutes and vanish on process death.
- **Key test fails:** Confirm your Google AI Studio project/API-key restrictions, network, available Gemini quota, and model availability. Model string: `gemini-3.5-flash-lite`.
- **App replies to its own outgoing messages:** Upgrade to **v1.2.0**, turn off the app before updating, then test on a second number with manual review enabled. Version 1.2.0 drops ambiguous notification types and suppresses feedback from outgoing direct replies.
- **Replies mirror the incoming text:** Install v1.1.0 or newer, test in **Settings → Try a smart reply**, verify your Gemini key is working and review the generated result. The new echo guard prevents unchanged copies from being auto-sent.
- **Upgrading the debug APK:** GitHub-hosted builds can use different debug signing certificates. If Android reports a conflicting package or refuses to update, back up your configuration manually, uninstall the old version and install the new APK. You will need to grant permissions and enter your API key again.
- **Missed messages:** Some phone manufacturers stop apps or delay notifications. The listener is not a guaranteed background queue. Check the **Background operation** controls, battery restrictions, and listener status. Opening the app cannot recover messages that never arrived as notifications.
- **Build fails:** Review the log in GitHub Actions. Dependencies are retrieved on the runner; a temporary Google Maven or SDK outage may cause a build failure. The ZIP was produced without an Android SDK and has not been compiled on a device.

## File structure

```
.github/workflows/android-apk.yml          # CI: compiles and uploads APK
app/build.gradle.kts                       # Android + Compose dependencies
app/src/main/AndroidManifest.xml           # notification listener + permissions
app/src/main/java/com/replymate/ai/
  MainActivity.kt                          # professional Compose screens and controls
  data/AppSettings.kt                      # persisted controls and daily counts
  data/SecretStore.kt                       # Android Keystore AES-GCM
  logic/GeminiClient.kt                     # Google Gemini REST client
  logic/ReplyQueue.kt                      # pending reviews + Android direct reply
  logic/ContactRules.kt                    # exact-match contact rules
  logic/WhatsAppListener.kt                 # filters/limits/notification processing
  logic/ConversationMemory.kt               # short-lived, per-contact in-memory context
  logic/ReplyQuality.kt                     # echo prevention
```

## Licensing

Sample source released under the MIT License (see LICENSE). This project is independent of WhatsApp/Meta and Google.
