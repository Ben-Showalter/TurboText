

# TurboText — a default SMS app for the Kyocera DuraXV Extreme (E4810)

A replacement text messaging app built for keypad-only, non-touch use:
- Full T9 predictive text (press number keys, `*` cycles suggestions, `#` switches
  between Word / Abc / 123 input modes)
- Voice-to-text (hold the left softkey or the Mic/Assistant button while speaking, release to send — powered by Groq's cloud Whisper API; optionally routes through a connected Bluetooth headset's mic instead of the phone's own, see below)
- Works as your phone's default SMS app (shows up when you go to
  Settings > Apps > Default apps > SMS app)

## Step 1 — Install Android Studio (one-time, ~30-45 min)

1. Go to https://developer.android.com/studio and download Android Studio for
   your computer (Windows/Mac/Linux all work).
2. Run the installer and accept the defaults. When it first opens, let it
   download the "Android SDK" components it prompts you about — that's normal
   and can take a while on the first run.

## Step 2 — Open this project

1. Unzip the `TurboText` folder you downloaded from this chat somewhere on
   your computer.
2. Open Android Studio, choose **Open**, and select the `TurboText` folder
   (the one containing `settings.gradle`).
3. Android Studio will "sync" the project automatically — it may pop up a
   banner offering to upgrade the Android Gradle Plugin/Gradle version.
   **Click "Yes"/"Update"** if it offers — that's expected and safe here,
   the versions I set are a reasonable starting point but Android Studio
   will know the exact latest ones.
4. Wait for the bottom status bar to say the sync finished (first time can
   take several minutes while it downloads build tools).

## Step 3 — Build the APK

1. In the top menu: **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
2. When it finishes, a notification appears in the bottom-right — click
   **"locate"** to jump to the `.apk` file. It'll be under
   `TurboText/app/build/outputs/apk/debug/app-debug.apk`.

## Step 4 — Get the APK onto the phone

Easiest path: email the `.apk` to yourself (or upload to Google Drive/Dropbox)
and open that email/link on the phone itself. Or plug the phone into your
computer with a USB cable and drag the file onto the phone's storage.

## Step 5 — Sideload it on the E4810

1. On the phone: **Settings > Security** (or **Apps**) and enable
   **"Unknown sources"** / **"Install unknown apps"** for whatever app you
   used to get the file onto the phone (Email, Files, Drive, etc.) — the
   exact wording/location varies by Android version.
2. Open the `.apk` file using the phone's file manager (or tap the
   download notification) and confirm **Install**.
3. Open **TurboText** from the app list. It will:
   - Ask for SMS/Contacts/Microphone permissions — accept these (arrow keys
     + OK/center key to navigate the permission dialogs).
   - Prompt you to set it as your **default SMS app** — accept this too, or
     it won't be able to send/receive texts (Android's rule, not this app's).
4. That's it — incoming texts now go through TurboText, and you can compose
   with the number pad.

## Voice-to-text setup (required, one-time)

Voice-to-text uses **Groq's cloud Whisper API** — this phone's CPU
struggled even with Vosk's small on-device model, so transcription now
happens on Groq's servers instead. This needs a free Groq API key and an
internet connection at the moment you use voice (it won't work with no
signal/wifi).

1. Get a free API key at https://console.groq.com/keys (sign in, click
   "Create API Key"). No credit card required. If email sign-up rejects
   your address (some providers, e.g. outlook.com, are blocked on Groq's
   end as of this writing), use "Continue with Google" instead.
2. Easiest way — no rebuild needed: on the phone, create a file called
   `groq_api_key.txt` inside a `Turbo Key` folder in Internal storage
   (any file manager can do this), and paste your key in as the only
   content. This is the same file TurboVoice reads too, if you use both
   apps — one key, shared.
   Alternative — bake it into the build instead: open
   `app/src/main/java/com/turbotext/app/GroqConfig.kt` and paste your
   key between the quotes:
   ```kotlin
   const val API_KEY = "gsk_your_actual_key_here"
   ```
   then rebuild the APK (Step 3 above). The file, if present, always
   takes priority over this — this is just a fallback for whenever the
   file isn't there.

**How it works on the phone**: hold the left softkey *or* the
Mic/Assistant button down while speaking, then let go — either works,
recording while held and sending the clip to Groq the moment you
release. You'll see "Recording…" then "Transcribing…" while it works.
Whisper adds punctuation and capitalization on its own, so no extra
setup is needed for that.

**Privacy note**: your voice audio leaves the phone and is sent to Groq's
servers for processing.

**If it's not working**: check that a real key is actually set — either
in the `Turbo Key/groq_api_key.txt` file or pasted into `GroqConfig.kt`
(it ships with a placeholder that won't work on its own) — and that the
phone has a working data/wifi connection at the time you try. Errors
show up as a toast with the specific reason (e.g. "Groq error 401"
usually means the key is wrong or missing).

### Optional: routing voice input through a Bluetooth headset mic

Off by default — voice input uses the phone's own mic unless you turn
this on. To enable it: Settings → **Voice Input Mic** → choose
"Bluetooth Headset". To go back to the phone's mic, pick "Phone
(default)" from that same row.

A few things worth knowing:
- Only works with a genuine headset mic — classic Bluetooth carries a
  mic signal over HFP (the same profile phone calls use), not A2DP (what
  most media-only earbuds/speakers use for music, which has no mic
  uplink at all). A2DP-only device connected, but no HFP? This falls
  back to the phone mic automatically.
- If no Bluetooth device is connected at all when you go to record, it
  also falls back to the phone mic automatically — this setting is "use
  it when available," not "require it."
- The connection is kept warm for about 15 seconds after your last key
  press on the compose/conversation screen, so pressing the mic button
  again shortly after starts recording immediately rather than paying a
  ~450ms Bluetooth negotiation delay each time.

## MMS (picture messages) — what works and what doesn't

- **Sending a photo**: while composing, press the right softkey to open
  **Options**, then choose "Take Photo" or "Choose Photo from Gallery"
  (this replaced a dedicated camera-key shortcut, which turned out to be
  swallowed by the phone's OS before the app ever saw it — a common quirk
  with hardware camera buttons). Once attached, a green "📷 Photo attached"
  line appears above the compose box so you can see it's queued up; send
  normally to include it.
- **Voice message attachments (recording audio, not transcribing it)**:
  not implemented yet. The library this app uses for MMS sending
  (`android-smsmms`) does have an `addAudio()` method, but I couldn't verify
  its exact method signature (what argument type it expects) without a
  source file I wasn't able to fetch. Given how many build-breaking guesses
  we've already worked through together, I'd rather confirm that properly
  before writing code against it than risk another broken build over a
  nice-to-have. Happy to take a real pass at it as a follow-up.
- **Receiving a photo**: now genuinely implemented, not just a
  placeholder — but I want to be upfront about what that means in
  practice. Incoming MMS uses a low-level binary format (the same one
  Google Messages/Textra parse internally), and there's no way for me to
  test this against real carrier traffic in this environment. What I did:
  - The *notification* parser (finding the download URL) is built from
    real, verified field codes sourced directly from AOSP's own published
    `PduHeaders.java` — not guessed from memory.
  - The actual download uses Android's own `SmsManager.downloadMultimediaMessage()`
    system API, so that part is standard/reliable.
  - Extracting the photo and caption text from what comes back uses a
    *heuristic* approach (scanning for JPEG/PNG file signatures and
    readable text) rather than a full, spec-perfect multipart parser —
    that's a deliberate, safer tradeoff: a heuristic that might miss an
    unusual format is better than a byte-perfect-looking parser that's
    silently wrong on data I can't test against.
  - If it doesn't work (or works inconsistently), that's genuinely useful
    information — tell me exactly what happened (did you get a
    notification at all? did the image show up? garbled/missing?) and
    we can iterate, or revert to the old "notify only" placeholder if
    it's more trouble than it's worth. This was explicitly a "let's try
    it and see" experiment, not something delivered with confidence it
    fully works.

## Keypad controls reference

| Key | Conversation list | Thread / Compose screen |
|---|---|---|
| Up/Down (D-pad) | Move between conversations | Move cursor up/down a line in the compose box; Up from the top line selects the newest message, further Up walks to older ones; Down past the newest returns to typing |
| Center/OK | Open conversation | Confirm current word |
| 2-9 | — | Build/predict a word (T9) |
| 2-9 (long-press, Word mode) | — | Types the literal digit instead of a letter |
| `*` tap | — | Switch mode: Word → Abc → 123 |
| `#` | — | Space |
| DEL/Clear | — | Backspace if there's text to clear; otherwise acts as **Back** (this phone's physical Clear key sends the Android BACK code, so both are wired to the same logic) |
| Send/Call key | — | Send |
| Right softkey | New message | Options (attach a photo, etc.) |
| Left softkey | Options (incl. "Set Default Texting App", "Trash Bin") | Hold to record, release to send (voice-to-text) |
| Mic/Assistant button | — | Hold to record, release to send (voice-to-text) — same as the left softkey, an alternative trigger |
| D-pad Up/Down/Left/Right (while mid-word) | — | Move the highlight in the suggestion list |
| Right softkey (while a message is selected) | — | Message options: copy, forward, view details, move to trash |
| `1` | — | Cycle punctuation (`. , ' ? ! - : ; @`) — works in Word mode too |

## Setting TurboText as your default texting app

TurboText no longer prompts you to become the default SMS app the first
time you open it — that only happens now if you ask for it. Open
**TurboText > left softkey (Options) > "Set Default Texting App"**. This
opens Android's own Default Apps settings screen — the same screen also
lets you switch back to your phone's original app later, since Android
doesn't let any app silently change this on its own. If that option isn't
visible, it's under **Settings > Apps > Default apps > SMS app** directly.

## Persistent shortcut notification

TurboText keeps an entry in your notification shade (pull down from the
top) as a quick way to reopen it — tap it any time. It's set up to be
silent and not show an icon in the status bar (a status bar icon should
only appear when an actual new message arrives) — it's no longer marked
"ongoing" since that flag looked like the likely reason a status bar icon
was showing regardless of importance level; given how many non-standard
behaviors this specific device has shown all session, I can't fully
guarantee that fixed it without you testing. Since it's not "ongoing"
anymore, it can be swiped away — if that happens it reappears next time
you open the app or after a reboot, rather than being unkillable.

## Performance

The earlier slowness/freezing when opening or switching screens was mostly
caused by database queries (reading messages, contact names, MMS parts)
running directly on the main UI thread — every screen transition blocked on
that. All of it now runs on a background thread, with the UI updating once
it's done. The T9 dictionary was also being re-parsed from scratch every
time you opened a conversation or compose screen; it's now cached after the
first load. If it's still slow after rebuilding with these changes, that
would be a good next thing to flag — it'd point to something else worth
digging into.

## Notes / things you may want to tweak

- **T9 dictionary** (`app/src/main/assets/t9dict.txt`) now has ~103,000
  unique words — a real, complete English word list (user-supplied),
  already ordered by actual usage frequency. That ordering matters now:
  it's what the engine falls back on before per-user learning kicks in,
  so keep new additions roughly in frequency order too if you add more —
  common words near the top, rare ones near the bottom.
- This build **requires internet access during the Android Studio build**
  (not on the phone) so Gradle can download the Vosk and MMS libraries —
  that's separate from the phone itself, which runs fully offline afterward.
- If your specific softkeys/buttons send different keycodes than
  `KEYCODE_SOFT_LEFT`/`KEYCODE_SOFT_RIGHT`/`KEYCODE_POUND`/`KEYCODE_CAMERA`
  (rugged phones sometimes remap physical buttons), the fix is in
  `ConversationActivity.kt`/`ComposeActivity.kt`'s `dispatchKeyEvent` — tell
  me what happens on the real device and I'll help track down the actual
  codes.
