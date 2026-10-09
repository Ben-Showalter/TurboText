package com.turbotext.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.EditText

private const val TAG = "T9InputControllerDebug"

enum class InputMode { WORD, MULTITAP, NUMBER, EMOJI }

/** Casing state for whichever of WORD/MULTITAP is active — cycled by '*'
 *  (LOWER -> CAP_NEXT -> ALL_CAPS -> Emoji -> LOWER). No casing concept
 *  applies to NUMBER (no letters) or while actually in EMOJI. */
private enum class CaseState { LOWER, CAP_NEXT, ALL_CAPS }

/**
 * Drives a TextView from raw physical keypad key events — no soft keyboard,
 * no touchscreen required. Wire this into an Activity's dispatchKeyEvent.
 *
 * Controls:
 *   2-9          build/predict a word (T9) or cycle letters (multitap mode)
 *   2-9 (long)   in Word mode, types the literal digit instead of a letter
 *   1            cycle punctuation (. , ' ? ! - : ; @) — works in Word mode too
 *   D-pad Left/Right, while a word is in progress: step the highlight
 *                one candidate at a time through the suggestion list
 *   D-pad Up/Down, while a word is in progress: jump the highlight to
 *                the candidate the suggestions bar draws directly
 *                above/below the current one (see SuggestionRenderer —
 *                up to 3 wrapped rows)
 *   D-pad Left/Right, otherwise: move the text cursor
 *   #            space (confirms current word first)
 *   # (long)     new line, where the field allows one
 *   * (tap)      in Word/Multitap mode, cycles casing: lower -> Cap (next
 *                word/letter only, also promoted automatically at a
 *                sentence start) -> ALL CAPS (sticky until cycled past) ->
 *                Emoji -> lower. No effect in Number mode. The top-level
 *                mode (Word/ABC/123) itself is switched with selectMode(),
 *                meant to be wired to a menu — see the host Activity's
 *                left-softkey handling — not this key.
 *   DPAD_CENTER  confirm current word
 *   DEL          backspace (at the cursor)
 *
 * Text is tracked as a buffer plus a cursor position, and new text is
 * inserted at the cursor rather than always appended at the end — that's
 * what makes Left/Right cursor movement and mid-message editing possible.
 */
class T9InputController(
    private val engine: T9Engine,
    private val outputView: EditText,
    private val onModeChanged: (String) -> Unit,
    private val onSuggestionsChanged: (candidates: List<String>, selectedIndex: Int, windowSize: Int) -> Unit = { _, _, _ -> },
    initialMode: InputMode = InputMode.WORD,
    // Lets a caller substitute what Word mode searches — e.g. the
    // recipient field uses this to search contact names by T9 digit
    // code instead of the dictionary, while everything else about Word
    // mode (candidate cycling, the raw-digits fallback, capitalize
    // override) stays the same. Defaults to the normal dictionary.
    private val candidateProvider: (String) -> List<String> = engine::candidatesFor,
    // Runs on whatever candidate gets confirmed, right before it's
    // inserted — e.g. turning a matched contact's name into their
    // actual phone number/email for sending, while what was shown
    // and typed stays the name. Identity by default (body text needs
    // no such transform).
    private val resolveWord: (String) -> String = { it },
    // The body learns which word you meant for a given digit sequence
    // (engine.recordConfirmed) so it's ranked first next time — that
    // would be wrong to do here when candidateProvider is searching
    // contact names instead of the dictionary, since a name isn't a
    // word the dictionary should learn.
    private val learnsWords: Boolean = true,
    // The message body wants standard T9 behavior: the top-ranked
    // candidate word shown live in the field as you type, updating with
    // every digit. The recipient field searches contact names instead,
    // and there a live-updating guessed name in the field itself reads as
    // "it already picked someone" rather than "here's what's typed so
    // far, pick from the list below" — the suggestions bar already shows
    // the matching contacts (see candidateProvider), so the field itself
    // just needs to reflect what was actually pressed until one of those
    // candidates is actually confirmed. True flips the in-progress preview
    // from the guessed candidate to the raw digits typed so far.
    private val previewRawDigits: Boolean = false,
    // The suggestions-bar TextView itself, when the host can hand it over.
    // Lets Up/Down candidate navigation (selectCandidateAbove/Below) read
    // the bar's real wrapped layout and jump to the candidate actually
    // drawn directly above/below the current one, instead of guessing with
    // a fixed per-row count that never matches variable-width words. Purely
    // an enhancement — null, or a bar not yet measured, falls back to the
    // old fixed estimate.
    private val suggestionsBarView: android.widget.TextView? = null,
    // Whether holding '#' starts a new line. Off for one-line fields
    // (recipient, a word to add, a list name), where it just types a space.
    private val allowNewLines: Boolean = true
) {
    private val committed = StringBuilder()
    private var cursor = 0
    private var pendingDigits = ""
    private var candidateIndex = 0
    private var mode = initialMode

    // Matches the windowSize passed to onSuggestionsChanged for word
    // candidates in render() below — kept as one constant so Up/Down's
    // row jump (SuggestionRenderer.columnsPerRow) can never drift out of
    // sync with what's actually drawn in the suggestions bar.
    private val wordCandidateWindow = 12

    // multitap state
    private var multiTapKey: Char? = null
    private var multiTapIndex = 0

    // Casing state for Word/Multitap mode — cycled manually via '*' and
    // promoted automatically at a sentence start (see
    // autoPromoteCaseIfSentenceStart). CAP_NEXT capitalizes exactly one
    // unit — a whole word in Word mode, a single character in Multitap
    // (which has no word boundary) — then reverts itself to LOWER;
    // ALL_CAPS is sticky until '*' cycles past it. Also drives the mode
    // indicator's own capitalization (see currentLabel()), so the user can
    // see which state is active without typing anything yet.
    private var caseState = CaseState.LOWER

    // Remembers which of WORD/MULTITAP Emoji mode was entered from, so
    // inserting an emoji (or cycling '*' past Emoji) returns to the right
    // place instead of always landing back in Word mode.
    private var emojiHomeMode = InputMode.WORD

    // A curated set rather than the full Unicode emoji range — keeps
    // browsing with Left/Right reasonably short, and stays within what's
    // most likely to actually render on this phone's system font (not
    // verified on real hardware — if some show as blank boxes, that's
    // this device's font support, not a bug in this list).
    // Most-used first (see EmojiUsage) — re-sorted each time Emoji mode
    // is entered, never while browsing, so the list doesn't shift under
    // the cursor.
    private var emojiList = EmojiUsage.ordered(outputView.context)
    private var emojiIndex = 0

    // Punctuation picker — entered via '1' (outside Number mode), not a
    // persistent mode like EMOJI is, just a temporary overlay: confirms
    // whatever word is pending first, then lets you browse marks with
    // Left/Right the same way emoji browsing works.
    private val punctuationList = listOf(
        ".", ",", "'", "?", "!", "-", ":", ";", "@", "(", ")", "\"", "/",
        "#", "$", "%", "&", "*", "+", "=", "_", "~", "<", ">"
    )
    private var punctuationPickerActive = false
    private var punctuationIndex = 0
    private val handler = Handler(Looper.getMainLooper())
    private val multiTapTimeout = Runnable { commitMultiTapChar() }

    // long-press-for-digit state (Word mode only). digitHoldKeyCode tracks
    // which physical key digitHoldRunnable actually belongs to — typing
    // fast enough for two keys' down/up events to overlap (this key's
    // ACTION_DOWN arriving before a previous key's ACTION_UP) means the
    // key-up that eventually arrives for the *earlier* key must not
    // cancel the timer that was rescheduled for the *later* one.
    private var digitHoldRunnable: Runnable? = null
    private var digitHoldKeyCode: Int? = null
    private val longPressWindowMs = 700L

    // A previous attempt at this (hand-drawing a fixed-width "|" character
    // into the text and toggling its color to fake a blink) existed
    // because native cursorVisible=true reportedly didn't render on this
    // hardware — but that was never independently re-verified, and it had
    // a real cost: the field's text was never actually empty (the "|" was
    // always present), so EditText's own hint could never show. Using the
    // real native cursor instead — if it turns out not to render on this
    // device after all, that's the signal to revisit this.
    fun startCursorBlink() {
        outputView.isCursorVisible = true
    }

    fun stopCursorBlink() {
        outputView.isCursorVisible = false
    }

    fun currentText(): String = committed.toString()

    /** True if there's anything to backspace — lets the caller decide whether
     *  DEL should clear a character or act as a "back" button instead. */
    fun hasContent(): Boolean =
        committed.isNotEmpty() || pendingDigits.isNotEmpty() || multiTapKey != null

    fun currentMode(): InputMode = mode

    /** The mode indicator's text — its own capitalization mirrors
     *  [caseState] (t9word/T9Word/T9WORD, abc/Abc/ABC) so the indicator
     *  doubles as the casing-state display. NUMBER and EMOJI have no
     *  casing concept, so they're just "123"/"Emoji". */
    fun currentLabel(): String = when (mode) {
        InputMode.WORD -> when (caseState) {
            CaseState.LOWER -> "t9word"
            CaseState.CAP_NEXT -> "T9Word"
            CaseState.ALL_CAPS -> "T9WORD"
        }
        InputMode.MULTITAP -> when (caseState) {
            CaseState.LOWER -> "abc"
            CaseState.CAP_NEXT -> "Abc"
            CaseState.ALL_CAPS -> "ABC"
        }
        InputMode.NUMBER -> "123"
        InputMode.EMOJI -> "Emoji"
    }

    /** Switches the top-level mode (Word/Multitap/Number) — called from
     *  the host Activity's left-softkey mode menu. Flushes whatever's
     *  pending first (same as leaving for Emoji via '*' does) and always
     *  resets to LOWER — picking a mode fresh from a menu is a deliberate
     *  restart, not a continuation of whatever casing happened to be
     *  active before. */
    fun selectMode(newMode: InputMode) {
        confirmWord()
        mode = newMode
        caseState = CaseState.LOWER
        onModeChanged(currentLabel())
        render()
    }

    /** Forces whatever's currently pending (a Word-mode candidate still
     *  only in preview, or a multitap character mid-cycle) to actually
     *  commit into the text — needed before reading currentText(), which
     *  only reflects committed text and would otherwise silently drop
     *  something still shown on screen but not yet confirmed. */
    fun confirmPending() {
        if (pendingDigits.isNotEmpty() || multiTapKey != null) confirmWord()
    }

    /** True when the cursor is at the very start with nothing pending —
     *  the signal that Clear should back out (saving whatever's typed as
     *  a draft) rather than backspacing, even if there's still text
     *  further along that the user hasn't deleted. */
    fun isCursorAtStart(): Boolean =
        !punctuationPickerActive && cursor == 0 && pendingDigits.isEmpty() && multiTapKey == null

    /** True while a word is being built via T9 digits — callers can use this
     *  to decide whether Up/Down should move the suggestion highlight
     *  instead of doing something else (like scrolling a message list). */
    fun hasPendingWord(): Boolean =
        punctuationPickerActive || (mode == InputMode.WORD && pendingDigits.isNotEmpty())

    /** True while the punctuation picker overlay (opened via '1') is showing
     *  — callers that intercept D-pad/Center themselves (rather than
     *  forwarding everything to onKeyDown) need this to know when those
     *  keys belong to the picker instead of their own handling. */
    fun isPunctuationPickerActive(): Boolean = punctuationPickerActive

    /** Moves the cursor up one visual line (not character position),
     *  preserving its horizontal spot as closely as possible — same as a
     *  normal text editor. Returns false if already on the top line
     *  (nothing to do), which is the caller's signal to exit the compose
     *  box into message selection instead. */
    fun moveCursorLineUp(): Boolean {
        val layout = outputView.layout ?: return false
        val currentLine = layout.getLineForOffset(cursor)
        if (currentLine <= 0) return false
        val x = layout.getPrimaryHorizontal(cursor)
        val oldCursor = cursor
        cursor = layout.getOffsetForHorizontal(currentLine - 1, x).coerceIn(0, committed.length)
        Log.d(TAG, "moveCursorLineUp: cursor $oldCursor->$cursor currentLine=$currentLine x=$x lineCount=${layout.lineCount}")
        render()
        return true
    }

    /** Same as moveCursorLineUp, but downward. Returns false if already on
     *  the bottom line. */
    fun moveCursorLineDown(): Boolean {
        val layout = outputView.layout ?: return false
        val currentLine = layout.getLineForOffset(cursor)
        if (currentLine >= layout.lineCount - 1) return false
        val x = layout.getPrimaryHorizontal(cursor)
        val oldCursor = cursor
        cursor = layout.getOffsetForHorizontal(currentLine + 1, x).coerceIn(0, committed.length)
        Log.d(TAG, "moveCursorLineDown: cursor $oldCursor->$cursor currentLine=$currentLine x=$x lineCount=${layout.lineCount}")
        render()
        return true
    }

    fun setText(text: String) {
        committed.setLength(0)
        committed.append(text)
        cursor = committed.length
        pendingDigits = ""
        render()
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val digit = digitFor(keyCode)
        when {
            punctuationPickerActive && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                punctuationIndex = (punctuationIndex - 1 + punctuationList.size) % punctuationList.size
                render()
                return true
            }
            punctuationPickerActive && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                punctuationIndex = (punctuationIndex + 1) % punctuationList.size
                render()
                return true
            }
            punctuationPickerActive && (isCenterKey(keyCode) || keyCode == KeyEvent.KEYCODE_1) -> {
                // repeatCount==0 matters here: Android auto-repeats
                // ACTION_DOWN while a key is held, and without this guard
                // that repeat re-enters this branch (since the picker is
                // now active) and confirms the guessed mark before the
                // long-press timer below even gets a chance to fire —
                // ending up with both the mark and the held digit inserted.
                if (event.repeatCount == 0) {
                    insertAtCursor(punctuationList[punctuationIndex])
                    punctuationPickerActive = false
                    render()
                }
                return true
            }
            punctuationPickerActive && keyCode == KeyEvent.KEYCODE_POUND -> {
                if (event.repeatCount == 0) {
                    insertAtCursor(punctuationList[punctuationIndex])
                    insertAtCursor(" ")
                    punctuationPickerActive = false
                    render()
                }
                return true
            }
            punctuationPickerActive && (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_BACK) -> {
                // Cancels the picker without inserting or backspacing —
                // a natural "never mind" gesture while it's showing.
                punctuationPickerActive = false
                render()
                return true
            }
            punctuationPickerActive -> {
                // Swallow everything else while the picker is up, same
                // reasoning as emoji mode — avoids accidentally typing.
                return true
            }
            mode == InputMode.EMOJI && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                emojiIndex = (emojiIndex - 1 + emojiList.size) % emojiList.size
                render()
                return true
            }
            mode == InputMode.EMOJI && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                emojiIndex = (emojiIndex + 1) % emojiList.size
                render()
                return true
            }
            mode == InputMode.EMOJI && isCenterKey(keyCode) -> {
                // Stays in Emoji mode rather than reverting to
                // emojiHomeMode — inserting several emoji in a row
                // shouldn't require re-entering Emoji mode via '*' each
                // time. '*' (cycleCase) is still the way out, back to
                // emojiHomeMode.
                insertEmoji(emojiList[emojiIndex])
                render()
                return true
            }
            keyCode == KeyEvent.KEYCODE_STAR -> {
                return true
            }
            keyCode == KeyEvent.KEYCODE_POUND -> {
                // Tap for a space, hold for a new line. The space goes in
                // right away (same instant feedback as a digit's letter)
                // and is swapped for a line break if the key is still held
                // after longPressWindowMs — sharing the digit keys' hold
                // timer, so onKeyUp cancels it the same way. Auto-repeats
                // are ignored, or holding # would keep adding spaces.
                if (event.repeatCount == 0) {
                    digitHoldRunnable?.let { handler.removeCallbacks(it) }
                    digitHoldRunnable = null
                    digitHoldKeyCode = null
                    insertSpace()
                    if (allowNewLines) {
                        val runnable = Runnable { triggerPoundHold() }
                        digitHoldRunnable = runnable
                        digitHoldKeyCode = keyCode
                        handler.postDelayed(runnable, longPressWindowMs)
                    }
                }
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN && hasPendingWord() -> {
                selectCandidateBelow()
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_UP && hasPendingWord() -> {
                selectCandidateAbove()
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && hasPendingWord() -> {
                selectNextCandidate()
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT && hasPendingWord() -> {
                selectPrevCandidate()
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                cursor = (cursor - 1).coerceAtLeast(0)
                render()
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                cursor = (cursor + 1).coerceAtMost(committed.length)
                render()
                return true
            }
            keyCode == KeyEvent.KEYCODE_DEL -> {
                backspace()
                return true
            }
            isCenterKey(keyCode) -> {
                confirmWord()
                return true
            }
            digit != null -> {
                // Only react to the initial press — Android auto-repeats
                // ACTION_DOWN while a key is held, which would otherwise
                // spam extra letters into the word as you hold it. Holding
                // is instead detected with our own timer below, independent
                // of the OS's repeat timing.
                if (event.repeatCount == 0) {
                    // A still-pending hold-timer from an earlier digit
                    // whose own key-up hasn't been processed yet (typing
                    // fast enough for two keys' down/up events to overlap)
                    // must be cancelled here, not just on key-up below —
                    // otherwise it's orphaned once digitHoldRunnable below
                    // gets overwritten to point at *this* key's timer
                    // instead, and fires on its own 700ms later, inserting
                    // a stray literal digit wherever the cursor happens to
                    // be by then (e.g. "4hogs").
                    digitHoldRunnable?.let { handler.removeCallbacks(it) }
                    digitHoldRunnable = null
                    digitHoldKeyCode = null
                    handleDigit(digit)
                    if (mode == InputMode.WORD) {
                        val runnable = Runnable { triggerDigitHold(digit) }
                        digitHoldRunnable = runnable
                        digitHoldKeyCode = keyCode
                        handler.postDelayed(runnable, longPressWindowMs)
                    }
                }
                return true
            }
        }
        return false
    }

    fun onKeyUp(keyCode: Int): Boolean {
        // Only cancels the pending timer if it actually belongs to *this*
        // key — a late key-up for a key that's since been superseded (see
        // the digit-down handling above) must not cancel a different,
        // still-legitimately-held key's own timer.
        if (keyCode == digitHoldKeyCode) {
            digitHoldRunnable?.let { handler.removeCallbacks(it) }
            digitHoldRunnable = null
            digitHoldKeyCode = null
        }

        if (keyCode == KeyEvent.KEYCODE_STAR) {
            cycleCase()
            return true
        }
        return false
    }

    /** Fires when a digit key in Word mode has been held past the
     *  long-press threshold — undoes the letter that the initial tap
     *  already added (for instant typing feedback) and types the raw
     *  digit instead. */
    private fun triggerDigitHold(digit: Char) {
        when (digit) {
            '1' -> {
                // Only reachable when pendingDigits was empty at tap time
                // (see handleDigit) — tap opened the punctuation picker
                // without inserting anything yet, so just close it, digit
                // goes in instead. Mid-sequence, '1' already took the
                // generic path below instead, same as every other digit.
                punctuationPickerActive = false
            }
            else -> {
                // Covers '0' too — the tap either appended it to
                // pendingDigits or (mid multitap/NUMBER mode) did
                // something else entirely; either way, undoing means
                // dropping it back off pendingDigits if it's there before
                // typing the literal digit instead.
                if (pendingDigits.isNotEmpty() && pendingDigits.last() == digit) {
                    pendingDigits = pendingDigits.dropLast(1)
                }
            }
        }
        // The tap may have auto-capitalized a word that never happened
        // (holding a digit at the start of a sentence) — without this the
        // capital stuck around and landed on the next word, e.g. "5 In".
        if (pendingDigits.isEmpty() && caseState == CaseState.CAP_NEXT) {
            caseState = CaseState.LOWER
            onModeChanged(currentLabel())
        }
        insertAtCursor(digit.toString())
        render()
    }

    /** '#' held past the long-press threshold: the space its tap already
     *  added becomes a line break, to start a new paragraph. */
    private fun triggerPoundHold() {
        if (cursor > 0 && committed[cursor - 1] == ' ') {
            committed.replace(cursor - 1, cursor, "\n")
        } else {
            insertAtCursor("\n")
        }
        render()
    }

    /** Several key listeners elsewhere in the app treat DPAD_CENTER and
     *  ENTER as the same physical "OK" press (this device's D-pad center key
     *  isn't consistent about which code it sends) — matched here too so
     *  the picker/confirm branches below don't silently swallow one of them. */
    private fun isCenterKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER

    private fun digitFor(keyCode: Int): Char? = when (keyCode) {
        KeyEvent.KEYCODE_0 -> '0'
        KeyEvent.KEYCODE_1 -> '1'
        KeyEvent.KEYCODE_2 -> '2'
        KeyEvent.KEYCODE_3 -> '3'
        KeyEvent.KEYCODE_4 -> '4'
        KeyEvent.KEYCODE_5 -> '5'
        KeyEvent.KEYCODE_6 -> '6'
        KeyEvent.KEYCODE_7 -> '7'
        KeyEvent.KEYCODE_8 -> '8'
        KeyEvent.KEYCODE_9 -> '9'
        else -> null
    }

    private fun handleDigit(digit: Char) {
        // '1' and '0' have no letters on a standard keypad — '1' opens the
        // punctuation picker, '0' is the space key on most phones — but
        // mid-sequence in Word mode that used to make a raw number
        // unreachable the moment it needed a 0 or 1: typing "490" built
        // pendingDigits fine through '4' and '9', but '0' was silently
        // swallowed (not a letter key) and '1' would confirm-and-abandon
        // whatever was pending in favor of inserting a punctuation mark —
        // there was no way to get "491" as the raw-digits fallback
        // candidate (see T9Engine.candidatesFor) at all. Once a sequence
        // is already in progress, 0/1 now just extend it like every other
        // digit; their single-key behavior below only fires when nothing
        // is pending yet.
        if ((digit == '0' || digit == '1') && mode == InputMode.WORD && pendingDigits.isNotEmpty()) {
            pendingDigits += digit
            candidateIndex = defaultCandidateIndex(pendingDigits)
            render()
            return
        }
        // '1' has no letters on a standard keypad, so in every mode except
        // NUMBER it opens the punctuation picker instead — confirming
        // whatever's currently pending in MULTITAP first, so the mark
        // lands after it even if it was never explicitly confirmed. A
        // pending WORD-mode sequence never reaches here — handled above.
        if (digit == '1' && mode != InputMode.NUMBER) {
            if (mode == InputMode.MULTITAP && multiTapKey != null) commitMultiTapChar()
            punctuationPickerActive = true
            punctuationIndex = guessPunctuationIndex()
            render()
            return
        }
        when (mode) {
            InputMode.NUMBER -> {
                insertAtCursor(digit.toString())
                render()
            }
            InputMode.MULTITAP -> handleMultiTap(digit)
            InputMode.WORD -> {
                // pendingDigits is empty here (the branch above already
                // handled the non-empty case) — '0' starting a fresh
                // sequence is a deliberate start of raw-number entry, same
                // as any letter digit would be.
                if (engine.isLetterKey(digit) || digit == '0') {
                    autoPromoteCaseIfSentenceStart()
                    pendingDigits += digit
                    candidateIndex = defaultCandidateIndex(pendingDigits)
                    render()
                }
            }
            InputMode.EMOJI -> { /* digit keys don't do anything here — Left/Right/Center browse and insert instead */ }
        }
    }

    /** A rough guess at the most likely punctuation mark for whatever's
     *  just been typed, based on the text since the last sentence-ending
     *  mark (or the start of the message). Just picks where the picker
     *  starts — still fully browsable/overridable with Left/Right, so a
     *  wrong guess costs one extra press rather than inserting the wrong
     *  thing outright. */
    private fun guessPunctuationIndex(): Int {
        val text = committed.substring(0, cursor)
        var lastEnd = -1
        for (i in text.indices) {
            if (text[i] == '.' || text[i] == '!' || text[i] == '?') lastEnd = i
        }
        val sentence = text.substring(lastEnd + 1).trim().lowercase()
        val words = sentence.split(Regex("\\s+")).filter { it.isNotEmpty() }

        val questionStarters = setOf(
            "who", "what", "when", "where", "why", "how",
            "is", "are", "am", "was", "were", "do", "does", "did",
            "can", "could", "would", "will", "should", "has", "have", "had"
        )

        val guess = when {
            words.isEmpty() -> "."
            words.first() in questionStarters -> "?"
            words.size <= 2 -> ","
            else -> "."
        }
        val idx = punctuationList.indexOf(guess)
        return if (idx >= 0) idx else 0
    }

    private fun handleMultiTap(digit: Char) {
        val chars = engine.multiTapCharsFor(digit)
        if (chars.isEmpty()) return

        if (multiTapKey == digit) {
            multiTapIndex = (multiTapIndex + 1) % chars.size
        } else {
            commitMultiTapChar()
            autoPromoteCaseIfSentenceStart()
            multiTapKey = digit
            multiTapIndex = 0
        }
        handler.removeCallbacks(multiTapTimeout)
        handler.postDelayed(multiTapTimeout, 700L)
        renderMultiTapPreview(applyCaseTransform(chars[multiTapIndex]))
    }

    /** Commits the currently-cycling multitap character with [caseState]
     *  applied — CAP_NEXT capitalizes just this one character then reverts
     *  itself to LOWER (multitap has no word-boundary "confirm" event like
     *  Word mode's confirmWord(), so casing here is inherently per-
     *  character rather than per-word), ALL_CAPS stays sticky. Doesn't
     *  apply the "I" pronoun rule — see applyCaseTransform's doc for why
     *  that's Word-mode-only. */
    private fun commitMultiTapChar() {
        val key = multiTapKey ?: return
        val chars = engine.multiTapCharsFor(key)
        if (chars.isNotEmpty()) {
            autoPromoteCaseIfSentenceStart()
            insertAtCursor(applyCaseTransform(chars[multiTapIndex]))
            if (caseState == CaseState.CAP_NEXT) {
                caseState = CaseState.LOWER
                onModeChanged(currentLabel())
            }
        }
        multiTapKey = null
        multiTapIndex = 0
        render()
    }

    private fun renderMultiTapPreview(char: String) {
        val before = committed.substring(0, cursor) + char
        val after = committed.substring(cursor)
        applyDisplay(before, after)
    }

    /** '#' is space now — confirms whatever's in progress (a pending T9
     *  word, or a pending multitap character) first, same as the old '0'
     *  behavior did, just triggered by a different key. */
    private fun insertSpace() {
        when {
            mode == InputMode.WORD && pendingDigits.isNotEmpty() -> confirmWord()
            mode == InputMode.MULTITAP && multiTapKey != null -> commitMultiTapChar()
            // Stays in Emoji mode rather than reverting to emojiHomeMode
            // — same reasoning as the Center-key insert branch above.
            mode == InputMode.EMOJI -> insertEmoji(emojiList[emojiIndex])
        }
        insertAtCursor(" ")
        render()
    }

    /** Where a freshly typed/edited digit sequence should default the
     *  highlight to. candidateProvider's raw-digits fallback is always
     *  listed first (see T9Engine.candidatesFor's own doc) so it's one
     *  Left press away without cycling through every word first, but
     *  that means index 0 is no longer automatically "the best guess"
     *  the way it always was — this skips past it to the first real word
     *  match instead, falling back to 0 only when the digits are the
     *  only candidate at all (nothing better to default to). */
    private fun defaultCandidateIndex(digits: String): Int {
        val candidates = lookup(digits)
        return if (candidates.size > 1 && candidates[0] == digits) 1 else 0
    }

    private fun selectNextCandidate() {
        if (pendingDigits.isEmpty()) return
        val candidates = lookup(pendingDigits)
        if (candidates.isEmpty()) return
        candidateIndex = (candidateIndex + 1) % candidates.size
        render()
    }

    private fun selectPrevCandidate() {
        if (pendingDigits.isEmpty()) return
        val candidates = lookup(pendingDigits)
        if (candidates.isEmpty()) return
        candidateIndex = (candidateIndex - 1 + candidates.size) % candidates.size
        render()
    }

    /** Jumps the highlight to the candidate the suggestions bar draws
     *  directly below the current one instead of stepping one at a time
     *  like Left/Right — clamped to the last candidate rather than
     *  wrapping, since there's no sensible "row below the bottom row". */
    private fun selectCandidateBelow() {
        if (pendingDigits.isEmpty()) return
        val candidates = lookup(pendingDigits)
        if (candidates.isEmpty()) return
        candidateIndex = verticalCandidateTarget(candidates, 1)
        render()
    }

    /** Same as selectCandidateBelow but upward, clamped to the first
     *  candidate rather than wrapping. */
    private fun selectCandidateAbove() {
        if (pendingDigits.isEmpty()) return
        val candidates = lookup(pendingDigits)
        if (candidates.isEmpty()) return
        candidateIndex = verticalCandidateTarget(candidates, -1)
        render()
    }

    /** The candidate Up/Down should land on: the one the suggestions bar
     *  actually renders directly above/below the current highlight (read
     *  from the bar's wrapped layout), or — before the bar has been
     *  measured, or when no bar was supplied — a fixed per-row estimate.
     *  Either way clamped to the ends, never wrapping. */
    private fun verticalCandidateTarget(candidates: List<String>, direction: Int): Int {
        suggestionsBarView?.layout?.let { layout ->
            SuggestionRenderer.verticalNeighbor(
                layout, candidates, candidateIndex, direction, wordCandidateWindow
            )?.let { return it }
        }
        val columns = SuggestionRenderer.columnsPerRow(wordCandidateWindow)
        return if (direction < 0) {
            (candidateIndex - columns).coerceAtLeast(0)
        } else {
            (candidateIndex + columns).coerceAtMost(candidates.size - 1)
        }
    }

    private fun confirmWord() {
        if (mode == InputMode.MULTITAP) {
            commitMultiTapChar()
            return
        }
        if (pendingDigits.isEmpty()) return
        autoPromoteCaseIfSentenceStart()
        val candidates = lookup(pendingDigits)
        val baseWord = candidates.getOrNull(candidateIndex)
        val rawWord = baseWord ?: pendingDigits
        if (baseWord != null && learnsWords) {
            engine.recordConfirmed(pendingDigits, baseWord)
        }
        val resolved = resolveWord(rawWord)
        // A candidate that actually resolved to something else (e.g. the
        // recipient field turning a matched contact name into that
        // contact's phone number/email) is opaque resolved data, not
        // typed text — casing doesn't belong on it, and applying it here
        // would also break the match itself, since resolveWord looks the
        // *original* candidate up by exact name (see resolveRecipientWord
        // in ComposeActivity).
        val word = if (resolved != rawWord) {
            resolved
        } else {
            applyWordCasing(rawWord, atWordStart = isPrecededByWordBoundary(cursor))
        }
        insertAtCursor(word)
        pendingDigits = ""
        candidateIndex = 0
        if (caseState == CaseState.CAP_NEXT) {
            caseState = CaseState.LOWER
            onModeChanged(currentLabel())
        }
        render()
    }

    /** True when [position] sits at the very start of the message or right
     *  after whitespace — i.e. confirming a word there starts a fresh word
     *  rather than landing inside one already typed. Gates the "I" pronoun
     *  rule below: without this, confirming "i" with the cursor placed
     *  inside an already-confirmed word (e.g. between "sa" and "d" in
     *  "sad", to spell "said") capitalized it regardless of what it was
     *  actually being inserted into, producing "saId" instead of "said". */
    private fun isPrecededByWordBoundary(position: Int): Boolean =
        position <= 0 || committed[position - 1].isWhitespace()

    /** Promotes LOWER to CAP_NEXT the instant a fresh unit starts at a
     *  sentence boundary — called right before a Word-mode word starts
     *  accumulating digits, and right before each Multitap character
     *  cycles/commits. This is what makes the mode indicator flip to
     *  "T9Word"/"Abc" live, as soon as typing begins there, rather than
     *  only retroactively once the word is confirmed. A no-op once
     *  caseState is already CAP_NEXT (manually set) or ALL_CAPS (sticky)
     *  — this never downgrades either. */
    private fun autoPromoteCaseIfSentenceStart() {
        if (caseState == CaseState.LOWER && shouldCapitalizeAt(cursor)) {
            caseState = CaseState.CAP_NEXT
            onModeChanged(currentLabel())
        }
    }

    /** Word-mode-only casing for a word actually being confirmed: the "I"
     *  pronoun is always capitalized once it's a real standalone word
     *  (atWordStart — see isPrecededByWordBoundary's doc) regardless of
     *  caseState, since that's a grammar rule rather than part of the
     *  casing cycle — but only when caseState is LOWER, since CAP_NEXT/
     *  ALL_CAPS already capitalize "I" correctly as part of their normal
     *  transform (applyCaseTransform) with no special case needed. */
    private fun applyWordCasing(word: String, atWordStart: Boolean): String {
        if (word.isEmpty()) return word
        if (caseState == CaseState.LOWER && atWordStart && word.equals("i", ignoreCase = true)) return "I"
        return applyCaseTransform(word)
    }

    /** Applies [caseState]'s transform with no other rules attached —
     *  shared by the live Word-mode candidate preview, the live Multitap
     *  cycling preview, and Multitap's actual character commit. None of
     *  those have Word-mode confirmWord()'s "this is a complete,
     *  standalone word" signal, so none of them apply the "I" pronoun
     *  special case in applyWordCasing above — doing so per-preview-
     *  keystroke or per-multitap-character would recreate exactly the
     *  "capitalized before the rest of the word was even typed" bug that
     *  gating fixed for Word mode in the first place. */
    private fun applyCaseTransform(text: String): String = when (caseState) {
        CaseState.LOWER -> text
        CaseState.CAP_NEXT -> text.replaceFirstChar { it.uppercase() }
        CaseState.ALL_CAPS -> text.uppercase()
    }

    /** Inserts text at the cursor (not always the end) and advances the
     *  cursor past it — this is what makes editing mid-message possible. */
    private fun insertAtCursor(text: String) {
        committed.insert(cursor, text)
        cursor += text.length
    }

    /** A word typed here should start with a capital letter if it's the
     *  very first word, starts a new line, or immediately follows
     *  sentence-ending punctuation (". ", "! ", "? ") — standard
     *  phone-texting auto-capitalization. */
    private fun shouldCapitalizeAt(position: Int): Boolean {
        val line = committed.substring(0, position).trimEnd(' ')
        if (line.isEmpty() || line.last() == '\n') return true
        val before = line.trimEnd()
        return before.last() == '.' || before.last() == '!' || before.last() == '?'
    }

    private fun backspace() {
        when {
            pendingDigits.isNotEmpty() -> {
                pendingDigits = pendingDigits.dropLast(1)
                candidateIndex = if (pendingDigits.isEmpty()) 0 else defaultCandidateIndex(pendingDigits)
                // Backspacing a word-in-progress away cancels an auto- or
                // manually-set CAP_NEXT for it — but ALL_CAPS stays sticky,
                // same as it does everywhere else.
                if (pendingDigits.isEmpty() && caseState == CaseState.CAP_NEXT) {
                    caseState = CaseState.LOWER
                    onModeChanged(currentLabel())
                }
            }
            multiTapKey != null -> {
                multiTapKey = null
            }
            cursor > 0 -> {
                // Most emoji live outside the Basic Multilingual Plane and
                // need a surrogate pair (2 Kotlin Chars) to represent one
                // visible character. Deleting just one left an orphaned,
                // invalid code unit behind — which is exactly what was
                // rendering as a tofu/replacement-character glyph, and
                // also why Clear needed two presses to actually empty the
                // field (the orphan still counted as content).
                val deleteCount = if (
                    cursor >= 2 &&
                    Character.isLowSurrogate(committed[cursor - 1]) &&
                    Character.isHighSurrogate(committed[cursor - 2])
                ) 2 else 1
                committed.delete(cursor - deleteCount, cursor)
                cursor -= deleteCount
            }
        }
        render()
    }

    /** '*' cycles casing within Word/Multitap — LOWER -> CAP_NEXT ->
     *  ALL_CAPS -> Emoji -> LOWER — and does nothing in Number mode (no
     *  letters, no casing concept). The top-level mode (Word/ABC/123)
     *  itself is switched only via selectMode() now, wired to a menu
     *  rather than this key. */
    private fun cycleCase() {
        when (mode) {
            InputMode.WORD, InputMode.MULTITAP -> when (caseState) {
                CaseState.LOWER -> caseState = CaseState.CAP_NEXT
                CaseState.CAP_NEXT -> caseState = CaseState.ALL_CAPS
                CaseState.ALL_CAPS -> {
                    // Any in-progress word/character is flushed before
                    // leaving Word/Multitap for Emoji — otherwise it'd be
                    // left stranded mid-pendingDigits with nothing able to
                    // confirm it (Emoji mode's own key handling doesn't
                    // know about pendingDigits at all).
                    confirmWord()
                    emojiHomeMode = mode
                    mode = InputMode.EMOJI
                    emojiList = EmojiUsage.ordered(outputView.context)
                    emojiIndex = 0
                }
            }
            InputMode.EMOJI -> {
                mode = emojiHomeMode
                caseState = CaseState.LOWER
            }
            InputMode.NUMBER -> return
        }
        onModeChanged(currentLabel())
        // Without this, the suggestions bar keeps showing whatever it last
        // had until the next key press calls render() — so switching into
        // Emoji left the picker not showing at all until some other key
        // was pressed first.
        render()
    }

    /** Called with the result of voice recognition to insert spoken text
     *  at the cursor. */
    fun appendVoiceResult(text: String) {
        val needsLeadingSpace = cursor > 0 && committed.getOrNull(cursor - 1) != ' '
        insertAtCursor((if (needsLeadingSpace) " " else "") + text)
        render()
    }

    /** Sets the field's text to [before]+[after] — no injected cursor
     *  character — and positions the cursor at the boundary between them,
     *  same position the old fixed-width "|" character used to occupy.
     *  Both setSelection() and the explicit setCursorPosition() below are
     *  set to that same boundary; see the latter's doc for why both exist. */
    private fun applyDisplay(before: String, after: String) {
        val full = before + after
        val position = before.length.coerceIn(0, full.length)
        Log.d(TAG, "applyDisplay: cursor=$cursor before.length=${before.length} position=$position full.length=${full.length}")
        outputView.setText(full)
        outputView.setSelection(position)
        // The blinking cursor NoImeEditText draws is positioned from this
        // explicit call, not from reading selectionStart/selectionEnd back
        // off the view — see setCursorPosition's own doc for why. Safe cast:
        // every field this controller is ever pointed at is a NoImeEditText
        // (see the layout XML), setSelection above is kept for anything else
        // in the framework that might still read it.
        (outputView as? NoImeEditText)?.setCursorPosition(position)
    }

    private fun insertEmoji(emoji: String) {
        insertAtCursor(emoji)
        EmojiUsage.recordUse(outputView.context, emoji)
    }

    /** Every candidate lookup goes through here so the engine knows the
     *  word before the cursor (used for TT9-style word-pair ordering). */
    private fun lookup(digits: String): List<String> {
        if (learnsWords) engine.setPreviousWord(previousWordBeforeCursor())
        return candidateProvider(digits)
    }

    /** The whole word immediately before the cursor, or null when the
     *  cursor is mid-word (a compound) or at the very start. */
    private fun previousWordBeforeCursor(): String? {
        if (cursor <= 0) return null
        val before = committed.substring(0, cursor)
        if (before.last().isLetterOrDigit()) return null
        val trimmed = before.trimEnd()
        var start = trimmed.length
        while (start > 0 && (trimmed[start - 1].isLetter() || trimmed[start - 1] == '\'')) start--
        return trimmed.substring(start).takeIf { it.isNotEmpty() }
    }

    private fun render() {
        val candidates = if (pendingDigits.isNotEmpty()) lookup(pendingDigits) else emptyList()
        val preview = when {
            punctuationPickerActive -> punctuationList[punctuationIndex]
            pendingDigits.isNotEmpty() && previewRawDigits -> pendingDigits
            pendingDigits.isNotEmpty() -> {
                val best = candidates.getOrNull(candidateIndex) ?: pendingDigits
                if (candidates.isNotEmpty()) applyCaseTransform(best) else best
            }
            mode == InputMode.EMOJI -> emojiList[emojiIndex]
            else -> ""
        }
        val before = committed.substring(0, cursor) + preview
        val after = committed.substring(cursor)
        applyDisplay(before, after)
        when {
            punctuationPickerActive -> onSuggestionsChanged(punctuationList, punctuationIndex, 12)
            mode == InputMode.EMOJI -> onSuggestionsChanged(emojiList, emojiIndex, 8)
            // Wider than the old 5 — the raw-digits fallback candidate
            // (added in candidatesFor) sits at the end of the list, and a
            // narrow window centered on the top word match could leave it
            // out of view entirely unless the total candidate count is
            // small. 12 matches the punctuation picker's own window size,
            // and is enough that it's visible (not highlighted, just
            // visible) alongside the top few word matches in the common
            // case rather than requiring a scroll to even see it exists.
            else -> onSuggestionsChanged(candidates, candidateIndex, wordCandidateWindow)
        }
    }

    // Scrolling the field to keep the cursor visible is handled by
    // NoImeEditText itself (see its onDraw) — computed from the same
    // Layout it uses to position the drawn cursor, on every draw, so the
    // two can never disagree. A separate scroll step here (the previous
    // approach) was a later, independent calculation that could land on a
    // stale layout and drift out of sync with where the cursor actually
    // got drawn, especially right after a single large insert like a
    // whole voice-dictated paragraph landing in one appendVoiceResult call.
}
