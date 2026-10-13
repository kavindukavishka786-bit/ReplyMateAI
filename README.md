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

## Important outgoing-message loop fix (v1.2.0)

ReplyMate previously could mistake its **own sent message** for an incoming WhatsApp notification, then respond again. Version 1.2.0 prevents that feedback loop in two ways:

1. It accepts only notifications that expose structured Android MessagingStyle messages with a distinct, identifiable sender. It no longer trusts the generic `EXTRA_TEXT` notification preview as proof of an incoming message; ambiguous or unsupported notifications are **ignored**.
2. Immediately **before** sending a manual or automatic reply, it records a short-lived outgoing fingerprint. For that contact, notification updates are blocked for 20 seconds, and echoes of the actual outgoing text are rejected for 10 minutes. No raw messages are saved to disk.

This conservative approach may **miss real messages** received very shortly after a sent reply, or all messages on WhatsApp builds that do not expose sender metadata. That tradeoff avoids repetitive auto-sending. Keep manual mode while testing. It is not an official WhatsApp integration and cannot guarantee correct direction detection on every phone. Send messages from a second WhatsApp account for testing. Stop using the app if self-replies recur.

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
- **Missed messages:** Some phone manufacturers stop apps or delay notifications. The listener is not a guaranteed background queue.
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
