package com.utomat.flowlite

import android.view.inputmethod.EditorInfo

/** Fields whose dictation is never saved, synced or sent for AI rewrite. Shared by the keyboard and the bubble. */
object PrivateFields {
    private val PASSWORD_TYPES = setOf(
        EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD,
        EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD,
    )

    fun isPasswordType(inputType: Int) =
        (inputType and (EditorInfo.TYPE_MASK_CLASS or EditorInfo.TYPE_MASK_VARIATION)) in PASSWORD_TYPES
}
