package com.turbotext.app

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.EditText

enum class InputMode(val label: String) {
    WORD("T9Word"), MULTITAP("ABC"), NUMBER("1234"), EMOJI("Emoji")
}

/**
 * Drives a TextView from raw physical keypad key events — no soft keyboard,
 * no touchscreen required. Wire this into an Activity's dispatchKeyEvent.
 *
 * Controls:
 *   2-9          build/predict a word (T9) or cycle letters (multitap mode)
 *   2-9 (long)   in Word mode, types the literal digit instead of a letter
 *   1            cycle punctuation (. , ' ? ! - : ; @) — works in Word mode too
 *   D-pad Up/Down/Left/Right, while a word is in progress: move the
 *                highlight in the suggestion list
 *   D-pad Left/Right, otherwise: move the text cursor
 *   #            space (confirms current word first)
 *   * (tap)      cycle input mode: Word -> Abc -> 123
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
    private val onModeChanged: (InputMode) -> Unit,
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
    private val learnsWords: Boolean = true
) {
    private val committed = StringBuilder()
    private var cursor = 0
    private var pendingDigits = ""
    private var candidateIndex = 0
    private var mode = initialMode

    // multitap state
    private var multiTapKey: Char? = null
    private var multiTapIndex = 0

    // Manual capitalize override — toggled by a single tap of '*' while a
    // word is being composed in Word mode. Normal auto-capitalization only
    // applies at the very start of a sentence; this lets any word, no
    // matter where it falls, be capitalized on request (proper nouns
    // mid-sentence, etc.). Cleared once the word is confirmed.
    private var forceCapitalizeNext = false

    // A curated set rather than the full Unicode emoji range — keeps
    // browsing with Left/Right reasonably short, and stays within what's
    // most likely to actually render on this phone's system font (not
    // verified on real hardware — if some show as blank boxes, that's
    // this device's font support, not a bug in this list).
    private val emojiList = listOf(
        "😀", "😂", "😍", "😉", "😢", "😡",
        "👍", "👎", "👏", "🙏",
        "❤️", "🔥", "🎉", "✅"
    )
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

    // long-press-for-digit state (Word mode only)
    private var digitHoldRunnable: Runnable? = null
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
        cursor = layout.getOffsetForHorizontal(currentLine - 1, x).coerceIn(0, committed.length)
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
        cursor = layout.getOffsetForHorizontal(currentLine + 1, x).coerceIn(0, committed.length)
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
                insertAtCursor(emojiList[emojiIndex])
                mode = InputMode.WORD
                onModeChanged(mode)
                render()
                return true
            }
            keyCode == KeyEvent.KEYCODE_STAR -> {
                return true
            }
            keyCode == KeyEvent.KEYCODE_POUND -> {
                insertSpace()
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN && hasPendingWord() -> {
                selectNextCandidate()
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_UP && hasPendingWord() -> {
                selectPrevCandidate()
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
                    handleDigit(digit)
                    if (mode == InputMode.WORD) {
                        val runnable = Runnable { triggerDigitHold(digit) }
                        digitHoldRunnable = runnable
                        handler.postDelayed(runnable, longPressWindowMs)
                    }
                }
                return true
            }
        }
        return false
    }

    fun onKeyUp(keyCode: Int): Boolean {
        digitHoldRunnable?.let { handler.removeCallbacks(it) }
        digitHoldRunnable = null

        if (keyCode == KeyEvent.KEYCODE_STAR) {
            if (mode == InputMode.WORD && pendingDigits.isNotEmpty()) {
                forceCapitalizeNext = !forceCapitalizeNext
                render()
            } else {
                cycleMode()
            }
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
                // Tap opened the punctuation picker without inserting
                // anything yet — just close it, digit goes in instead.
                punctuationPickerActive = false
            }
            '0' -> {
                // Tap already inserted a space (and possibly confirmed a
                // pending word first, which should stay) — remove just
                // that trailing space before inserting the digit.
                if (cursor > 0 && committed[cursor - 1] == ' ') {
                    committed.deleteCharAt(cursor - 1)
                    cursor -= 1
                }
            }
            else -> {
                if (pendingDigits.isNotEmpty() && pendingDigits.last() == digit) {
                    pendingDigits = pendingDigits.dropLast(1)
                }
            }
        }
        insertAtCursor(digit.toString())
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
        // '1' has no letters on a standard keypad, so in every mode except
        // NUMBER it opens the punctuation picker instead — confirming
        // whatever word/character is currently pending first, so the mark
        // lands after it even if it was never explicitly confirmed.
        if (digit == '1' && mode != InputMode.NUMBER) {
            when {
                mode == InputMode.WORD && pendingDigits.isNotEmpty() -> confirmWord()
                mode == InputMode.MULTITAP && multiTapKey != null -> commitMultiTapChar()
            }
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
                if (engine.isLetterKey(digit)) {
                    pendingDigits += digit
                    candidateIndex = 0
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
            multiTapKey = digit
            multiTapIndex = 0
        }
        handler.removeCallbacks(multiTapTimeout)
        handler.postDelayed(multiTapTimeout, 700L)
        renderMultiTapPreview(chars[multiTapIndex])
    }

    private fun commitMultiTapChar() {
        val key = multiTapKey ?: return
        val chars = engine.multiTapCharsFor(key)
        if (chars.isNotEmpty()) insertAtCursor(chars[multiTapIndex])
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
            mode == InputMode.EMOJI -> {
                insertAtCursor(emojiList[emojiIndex])
                mode = InputMode.WORD
                onModeChanged(mode)
            }
        }
        insertAtCursor(" ")
        render()
    }

    private fun selectNextCandidate() {
        if (pendingDigits.isEmpty()) return
        val candidates = candidateProvider(pendingDigits)
        if (candidates.isEmpty()) return
        candidateIndex = (candidateIndex + 1) % candidates.size
        render()
    }

    private fun selectPrevCandidate() {
        if (pendingDigits.isEmpty()) return
        val candidates = candidateProvider(pendingDigits)
        if (candidates.isEmpty()) return
        candidateIndex = (candidateIndex - 1 + candidates.size) % candidates.size
        render()
    }

    private fun confirmWord() {
        if (mode == InputMode.MULTITAP) {
            commitMultiTapChar()
            return
        }
        if (pendingDigits.isEmpty()) return
        val candidates = candidateProvider(pendingDigits)
        val baseWord = candidates.getOrNull(candidateIndex)
        var word = baseWord ?: pendingDigits
        if (baseWord != null && learnsWords) {
            engine.recordConfirmed(pendingDigits, baseWord)
        }
        word = applyWordCasing(word, shouldCapitalizeAt(cursor) || forceCapitalizeNext)
        word = resolveWord(word)
        insertAtCursor(word)
        pendingDigits = ""
        candidateIndex = 0
        forceCapitalizeNext = false
        render()
    }

    /** The pronoun "I" is always capitalized regardless of position —
     *  not just when it happens to start a sentence, unlike every other
     *  word. */
    private fun applyWordCasing(word: String, atSentenceStart: Boolean): String {
        if (word.equals("i", ignoreCase = true)) return "I"
        if (atSentenceStart && word.isNotEmpty()) return word.replaceFirstChar { it.uppercase() }
        return word
    }

    /** Inserts text at the cursor (not always the end) and advances the
     *  cursor past it — this is what makes editing mid-message possible. */
    private fun insertAtCursor(text: String) {
        committed.insert(cursor, text)
        cursor += text.length
    }

    /** A word typed here should start with a capital letter if it's the
     *  very first word, or immediately follows sentence-ending punctuation
     *  (". ", "! ", "? ") — standard phone-texting auto-capitalization. */
    private fun shouldCapitalizeAt(position: Int): Boolean {
        val before = committed.substring(0, position).trimEnd()
        if (before.isEmpty()) return true
        return before.last() == '.' || before.last() == '!' || before.last() == '?'
    }

    private fun backspace() {
        when {
            pendingDigits.isNotEmpty() -> {
                pendingDigits = pendingDigits.dropLast(1)
                candidateIndex = 0
                if (pendingDigits.isEmpty()) forceCapitalizeNext = false
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

    private fun cycleMode() {
        // Any in-progress word is committed before switching modes.
        confirmWord()
        mode = when (mode) {
            InputMode.WORD -> InputMode.NUMBER
            InputMode.NUMBER -> InputMode.MULTITAP
            InputMode.MULTITAP -> InputMode.EMOJI
            InputMode.EMOJI -> InputMode.WORD
        }
        onModeChanged(mode)
    }

    /** Called with the result of voice recognition to insert spoken text
     *  at the cursor. */
    fun appendVoiceResult(text: String) {
        val needsLeadingSpace = cursor > 0 && committed.getOrNull(cursor - 1) != ' '
        insertAtCursor((if (needsLeadingSpace) " " else "") + text)
        render()
    }

    /** Sets the field's text to [before]+[after] — no injected cursor
     *  character — and positions the real, native cursor right at the
     *  boundary between them, same position the old fixed-width "|"
     *  character used to occupy. */
    private fun applyDisplay(before: String, after: String) {
        val full = before + after
        outputView.setText(full)
        outputView.setSelection(before.length.coerceIn(0, full.length))
    }

    private fun render() {
        val candidates = if (pendingDigits.isNotEmpty()) candidateProvider(pendingDigits) else emptyList()
        val preview = when {
            punctuationPickerActive -> punctuationList[punctuationIndex]
            pendingDigits.isNotEmpty() -> {
                val best = candidates.getOrNull(candidateIndex) ?: pendingDigits
                if (candidates.isNotEmpty()) applyWordCasing(best, shouldCapitalizeAt(cursor) || forceCapitalizeNext) else best
            }
            mode == InputMode.EMOJI -> emojiList[emojiIndex]
            else -> ""
        }
        val before = committed.substring(0, cursor) + preview
        val after = committed.substring(cursor)
        applyDisplay(before, after)
        scrollCursorIntoView(before.length)
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
            else -> onSuggestionsChanged(candidates, candidateIndex, 12)
        }
    }

    /** setText() alone never scrolls a bounded-height EditText to follow
     *  where the cursor actually is — Android's own auto-scroll-to-follow-
     *  cursor only fires on a real selection *change*, and calling
     *  setText() followed by setSelection() to the same effective position
     *  doesn't reliably count as one on every version. Without this, once the
     *  box grows past its max height, text keeps getting typed below
     *  the visible area with no way to see it. Posted so it runs after
     *  the new text has actually been laid out and measured. */
    private fun scrollCursorIntoView(offset: Int) {
        outputView.post {
            val layout = outputView.layout ?: return@post
            val safeOffset = offset.coerceIn(0, outputView.text?.length ?: 0)
            val line = layout.getLineForOffset(safeOffset)
            val lineTop = layout.getLineTop(line)
            val lineBottom = layout.getLineBottom(line)
            val visibleHeight = outputView.height - outputView.paddingTop - outputView.paddingBottom
            if (visibleHeight <= 0) return@post
            when {
                lineBottom - outputView.scrollY > visibleHeight -> outputView.scrollTo(0, lineBottom - visibleHeight)
                lineTop < outputView.scrollY -> outputView.scrollTo(0, lineTop)
            }
        }
    }
}
