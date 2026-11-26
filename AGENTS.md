# AGENTS.md: working on TurboText

Read this before you change anything. It holds what TurboText's history taught: rules that came from
something breaking on a real phone. When your instinct and a rule here disagree, the rule wins. If the
user tells you otherwise in the task, the user wins, and you update this file in the same commit.

## 1. Read the Flip-DumbPhoneGuide first

The shared house rules for these phones live in
**[Ben-Showalter/Flip-DumbPhoneGuide](https://github.com/Ben-Showalter/Flip-DumbPhoneGuide/blob/main/AGENTS.md)**
(public, MIT). They cover keys and softkeys, focus and lists, text input, the 240×320 layout, hiding the
system soft-key bar, the outer display, network and battery, distribution and testing. Its `docs/` explain
each rule; its `templates/` are what `SystemBars.kt` and `NoImeEditText.kt` here came from.

Read its `AGENTS.md`, or clone it next to this repo. **Its rules apply to TurboText unless this file says
otherwise.** This file doesn't repeat them; it adds what is specific to TurboText.

If you find a new device quirk, tell the user in your reply, so it can be added to the guide. You may
not be able to push there yourself.

## 2. What TurboText is

- A replacement texting app (SMS and MMS) for the **Kyocera DuraXV Extreme E4810**. It's used on the
  E4610 and E4811 too, though they're less tested. These run **Android 9 (API 28)**: keypad only, no
  touchscreen, no Google Play services.
- It must be the phone's **default texting app**. Its **Background Helper** (an accessibility service,
  `KeyButtonAccessibilityService`) keeps it running; without it, it shows a small "running" notification.
- **Main features:**
  - groups, kept in their own list;
  - picture, video, voice, contact and file messages;
  - broadcast lists from a CSV;
  - Schedule Send;
  - Conversation Backgrounds;
  - search;
  - archive and import;
  - a daily texting report;
  - Groq dictation and translation;
  - in-app updates from GitHub Releases;
  - alerts on the outer screen.
- **Flip Launcher** (`Ben-Showalter/flip-launcher`) has no direct link to TurboText. It opens the default
  texting app, and counts and reads aloud TurboText's notifications through its notification listener. So
  notification changes affect the launcher.
- The maintainer is **not a programmer**. Explain results in plain words. When a phone setting is
  needed, give the exact `adb` command.

## 3. How the user likes to work

- **Plan first.** Show the plan and wait for an OK before changing code.
- **Show anything visual before committing it**: pictures, crops, layouts. Use a preview image or page.
- Commit and push to the branch the session names. **Never open a pull request unless asked.**
- **User-facing words are plain and short.** Use the names the user chose: "Schedule Send",
  "Conversation Background", "Contact Options", "Waiting for signal", "Picture Delivery Reports".
- When a change can't be tested here, say so plainly and say what to try on the phone.
- When the user reports a bug, ask for or read the **bug report** before guessing (§7).

## 4. Building and checking

- Gradle 8.2 with the Android Gradle plugin 8.2.0 and Kotlin 1.9.10. compileSdk 34, targetSdk 33,
  minSdk 24.
- Modules:
  - `:app`;
  - `:mmslib`, the vendored klinker/Fossify MMS library;
  - `:stubs:kyocera-sublcd`, compile-only stubs for the outer screen.
- **Unit tests** live in `app/src/test` and test pure logic. Keep new decision logic in small helpers
  with no Android dependencies, so it can be tested: `SendState`, `MicTaps`, `BackgroundContrast`,
  `MessageMerge` and `MessageSearch` are examples.
- **Cloud sessions often can't build:** Google's Maven and SDK (dl.google.com) may be blocked. If so, say
  so and let the user build in Android Studio. **Never accept the Android SDK license on the user's
  behalf**, and don't set up a whole SDK without asking.
- **Releases** (see README "Publishing a release"):
  - raise `versionCode` and set `versionName` in `app/build.gradle`;
  - build a signed `assembleRelease`;
  - publish a GitHub Release tagged `v<versionName>`, and not as a draft. The app ignores drafts.
- Keep `RELEASE_NOTES.md` in the user's plain style when a release is prepared.

## 5. Where TurboText differs from the guide

| Topic | Guide | TurboText (and why) |
|---|---|---|
| Camera key | "The OS swallows it" | It reaches the app: `KEYCODE_F3` on the E4810, a Kyocera code named `KEYCODE_SOFT_LEFT_LOWER` on the E4610 (matched by name, see `CameraKey`). While writing, it takes a picture. |
| Volume keys | Avoid; they steal focus | Never swallow them in `dispatchKeyEvent`. While a voice message plays they set its volume (`STREAM_MUSIC`). |
| Mic/Assistant (F4 / 287) | Match both codes | Hold to dictate (Groq), release to transcribe. A double tap (400 ms) starts an audio message (`MicTaps`). |
| Text input | System T9 or own T9 | Always TurboText's own T9: `NoImeEditText` + `T9InputController` + the TT9 dictionary (`assets/t9`). # is space; hold # for a new line. **Never bring back the phone's Paste or text-selection tool.** The user hid it on purpose, and paste goes through TurboText's own clipboard (`ClipboardPaste`). |
| `*` / `#` | Map zoom out / in | On the Conversation Background preview: # zooms in, \* zooms out. |
| Picking pictures | (not covered) | TurboText's own `MediaPickerActivity`. The gallery app opened its file browser instead. |

In a conversation the softkeys are: left = typing mode, or **Search** when a message is selected;
right = **Options**. CALL/SEND sends. Holding OK on a selected message opens **React** (or copies a
message that can't be reacted to); Copy is also in Options.

## 6. Telephony rules (each one fixed a real failure)

**Sending**
- Use `SmsManager.getDefault()`. `getSystemService(SmsManager::class.java)` is null below API 31 and
  crashed Send on these phones.
- **Texts:**
  - Insert the outbox row first, then send.
  - Use one explicit sent-PendingIntent per part (`FLAG_MUTABLE`, and a `?part=i` data URI so the parts
    stay separate).
  - `SmsSentReceiver` decides once all the parts have reported.
- **A send result only ever moves a message forward** (`SendState`). A late or repeated answer must not
  turn Sent into Failed, or a failed part into Sent.
- **Nothing may stay on "Sending" forever:**
  - text watchdog: 5 min (`SendRetry.armSmsWatchdog`);
  - picture watchdog and retries: `MmsSentReceiver`, armed as soon as the message is saved;
  - start-up sweep: `StuckSends`.
- **No signal:** the message waits as "Waiting for signal" (`SendRetry`). Waiting messages leave the queue
  one at a time, and anything that throws is marked Failed.
- **Picture messages** go through `MmsTransmitter` → `sendMultimediaMessage`:
  - always pass the carrier's HTTP headers from `MmsConfig` (on Verizon's network: `X-VzW-MDN`) and the
    size limit, as the klinker library did. Dropping them in October lined up with random failures to
    iPhones on US Mobile;
  - **always tell the carrier our own number:** From = `OwnNumber.best` (line number, else SIM number, else
    learned, read the way Signal does), and on Verizon's network add `X-VzW-MDN` with it (`VerizonNetwork`)
    when mms_config has none. US Mobile's SIM has no mms_config headers, and without our number Verizon refused
    pictures with 0x84 "sender address unresolved";
  - **recipients must be plain `+digits`** (`MmsAddress.clean`, applied in `MmsTransmitter.send`). The MMS
    library only marks digits, "+", "-" and "." as a phone number; "+1 269-359-3836" went out as an unknown
    address and Verizon refused it with 0x84. The same contact's address is written differently depending on
    how the conversation was opened, so this looked random;
  - keep pictures within `AttachmentPolicy` (600 KB cap);
  - **picture delivery reports are off by default** (Advanced > Picture Delivery Reports).

**Calls**
- Every call, dial-pad and call-log intent goes through `DialerIntents`, which aims it at the default phone app.
  A plain `tel:` intent showed a "which app" chooser when a second phone app was installed.

**Receiving**
- The **RECEIVE_MMS** permission is required. Without it, Android silently drops every picture message,
  group message and picture delivery report. TurboText asks for it at startup. If it's missing, use
  `adb shell pm grant com.turbotext.app android.permission.RECEIVE_MMS`.
- **Conversation key:** use the thread id everywhere (`NotificationHelper.conversationKey`).

**Notifications and read state**
- Each incoming message cancels the conversation's notification and posts a fresh one under a new id,
  so every message alerts. They aren't grouped, and the count goes in the subtext.
- Opening a thread marks it read, and so does leaving it (`onPause`).

## 7. Debugging: logs, not guesses

- **The bug report:** Settings > Advanced > Save Bug Report writes `Download/TurboText-bug-report-*.txt`.
  It contains the app's own log (`AppLog`): a logcat copy that restarts itself, plus `AppLog.event`
  lines written straight to the file.
- Every important step logs an event. Tags: `Sms`, `Mms`, `MmsSend`, `Retry`, `Notify`, `Camera`,
  `Background`, `Attach`.
- **Add an event line when you add a send, receive or alert path.**
- **Never log message text.** Numbers and ids are fine.

## 8. Map of the code

All app code is in `app/src/main/java/com/turbotext/app/`.

| Area | Files |
|---|---|
| Sending | `PendingSends` (the "Sending" bubble), `MessageSender`, `SmsRepository.sendMessage/transmitSms`, `SmsSentReceiver`, `SmsDeliveredReceiver`, `MmsTransmitter`, `MmsSentReceiver`, `SendRetry`, `SendState`, `StuckSends`, `ScheduledSends`, `BroadcastSender`, `AttachmentPolicy`, `VideoCompressor` |
| Receiving | `SmsDeliverReceiver`, `MmsReceiver` / `LibraryMmsReceivedReceiver` (library), `MmsReceivePostProcessor`, `MmsAcknowledger`, `OwnNumber` |
| Data and refresh | `SmsRepository`, `MessageCache`, `ConversationListCache`, `GroupThreadsCache`, `MessageEvents`, `MessageMerge`, `RefreshGate`, `ProviderChangeTracker`, `ConversationPrewarm` |
| Screens | `MainActivity` (list, Contact Options), `ConversationActivity`, `ComposeActivity`, `GroupMessagesActivity`, `MediaPickerActivity`, `WallpaperPickerActivity`, `MediaViewerActivity`, `Settings*` / `AdvancedSettingsActivity`, broadcast list screens |
| Typing | `T9InputController`, `Tt9WordEngine`, `WordPredictor`, `NoImeEditText`, `SuggestionRenderer`, `SearchBoxDialog`, `KeypadTimeDialog` |
| Reactions | `ReactionParser` (iPhone / Google Messages wording, lone emoji, what to send), `MessageReactions` (applied in `ConversationActivity.showMessages`; display only, nothing stored), Settings > Advanced > Show Reactions |
| Alerts | `NotificationHelper`, `SoundNotificationHelper`, `NotificationSounds`, `SoundPicker`, `CoverScreenNotifier` (outer screen), `DndHelper` |
| Look | `ThemeHelper` / `AppTheme`, `MessageAdapter`, `SlideUpItemAnimator` (new messages slide up; nothing else animates), `BubbleDrawable`, `ConversationWallpaper`, `BackgroundContrast`, `SystemBars` |
| Other | `ArchiveFormat` / `ArchiveManager`, `ReportSender` (daily report, `BootReceiver`), `UpdateChecker`, `AppLog`, `GroqVoiceInputHelper` / `GroqTranslateHelper` |

## 9. Pitfalls that bit before

- **`lateinit` order in `onCreate`:** a helper called before the field is assigned crashes every time
  the screen opens. That was the wallpaper crash. Look views up with `findViewById` there, or call
  later.
- **A `gone` view measures 0×0.** Measure a visible parent instead.
- **`/*` inside a KDoc comment** (for example `"image/*"`) opens a nested comment and breaks the build.
- **Keep heavy work off the main thread.** On the E4610 a list load once took 6 s. Use the caches;
  refresh only the newest messages (`MessageCache.refreshRecent`).
- **Don't load a whole thread's history when it opens.** It made long threads sluggish. A conversation shows
  the newest Messages Shown (Advanced; 200 by default, via `ThreadWindow`), and Load More fetches older
  messages (`getMessagesPage`).
- **Test helpers must not need Android.** A main-thread `Handler` in an object broke the JVM tests;
  make such things lazy or nullable.
- **One unit-test class per file.**
- When removing a row from a D-pad settings list, derive the last index from the rows list, never
  from a hard-coded number.

## 10. Assets and credits

- `assets/sounds/`: 75 AOSP notification sounds (Apache-2.0, see its `NOTICE.txt`).
- `assets/wallpapers/`: 44 Conversation Backgrounds at 480×640. Credits are in its `NOTICE.txt`:
  Unsplash photographers, and the author's own photos.
- `assets/t9/`: the T9 dictionary, from Traditional T9.
- The MMS structure was ported from DPAD Messaging (MIT); keep its attribution. See
  `docs/queued-mms-dpad-port.md`.
