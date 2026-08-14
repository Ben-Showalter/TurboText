package com.turbotext.app

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.widget.TextView
import androidx.core.content.ContextCompat

object HintHelper {
    /** "Press [icon] button to dictate" — a flat single-color mic glyph
     *  inline, matching the hint text's own faded look rather than a
     *  colorful emoji or icon badge. Set via code (view.hint = ...)
     *  rather than XML, since android:hint only accepts a plain string,
     *  not a Spannable. */
    fun dictateHint(view: TextView): CharSequence {
        val drawable = ContextCompat.getDrawable(view.context, R.drawable.ic_mic_hint)
            ?: return "Press mic button to dictate"
        val size = view.textSize.toInt().coerceAtLeast(1)
        drawable.mutate().setBounds(0, 0, size, size)

        val text = "Press * button to dictate"
        val iconIndex = text.indexOf('*')
        val spannable = SpannableString(text)
        spannable.setSpan(
            ImageSpan(drawable, ImageSpan.ALIGN_BASELINE),
            iconIndex, iconIndex + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }
}
