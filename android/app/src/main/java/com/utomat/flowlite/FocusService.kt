package com.utomat.flowlite

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow

/** Tracks the focused editable field and inserts dictated text into it. */
class FocusService : AccessibilityService() {
    companion object {
        @Volatile var instance: FocusService? = null
        /** True while an editable field outside FlowLite has input focus. */
        val editableFocused = MutableStateFlow(false)
        @Volatile var focusedPackage: String? = null
        private val PLACEHOLDERS = setOf("message", "type a message", "write a message", "reply", "μήνυμα", "πληκτρολογήστε ένα μήνυμα")
    }

    override fun onServiceConnected() {
        instance = this
        // Android binds accessibility services at boot, so this also restores the bubble after a restart.
        if (!DictationService.running && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED && android.provider.Settings.canDrawOverlays(this))
            runCatching { DictationService.start(this) }.onFailure { android.util.Log.w("FlowLite", "Autostart failed", it) }
    }

    fun collapseShade() {
        if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
    }

    /**
     * True when the focused field is a password field, including one showing its password as text.
     * Accessibility cannot see the "no learning" flag that private browser tabs send to keyboards,
     * so the voice keyboard is the only way to keep incognito dictation out of history.
     */
    fun focusedIsPrivate(): Boolean {
        val root = rootInActiveWindow ?: return false
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val node = if (f.isEditable) f else findFocusedEditable(root) ?: return false
        return node.isPassword || PrivateFields.isPasswordType(node.inputType)
    }

    /** The highlighted text in the focused field, if any. */
    fun selectedText(): String? {
        val node = focusedEditable() ?: return null
        if (showsHint(node)) return null
        val t = node.text?.toString() ?: return null
        val a = minOf(node.textSelectionStart, node.textSelectionEnd)
        val b = maxOf(node.textSelectionStart, node.textSelectionEnd)
        return if (a >= 0 && b <= t.length && a < b) t.substring(a, b) else null
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(recheck)
        editableFocused.value = false
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private val handler = Handler(Looper.getMainLooper())
    private val recheck = Runnable { updateFocus() }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        updateFocus()
        // Compose apps (e.g. Claude) send the focus event before the field reports focus, then only content changes.
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, 300)
    }

    private fun updateFocus() {
        val node = focusedEditable()
        val pkg = node?.packageName?.toString()
        focusedPackage = pkg ?: focusedPackage
        editableFocused.value = node != null && pkg != packageName
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        // Compose apps (e.g. Claude) report their host view as input focus; the focused field sits somewhere in the tree.
        val node = if (f.isEditable) f else findFocusedEditable(root)
        return node?.takeIf { it.isEditable && !it.isPassword }
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque(listOf(root))
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 500) { // ponytail: caps the walk on huge screens; raise if a deep field is missed
            val n = queue.removeFirst()
            if (n.isFocused && n.isEditable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    /**
     * Some apps report their placeholder as the field text without flagging it. WhatsApp sends text "Message",
     * no hint and no cursor (-1). Only an unflagged known placeholder with no cursor counts, so a real draft is never wiped.
     */
    private fun showsHint(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) return true
        val text = node.text?.toString()?.trim() ?: return false
        if (Build.VERSION.SDK_INT >= 26 && text == node.hintText?.toString()) return true
        return node.textSelectionStart < 0 && text.lowercase() in PLACEHOLDERS
    }

    /** Inserts at the cursor with ACTION_SET_TEXT, falling back to clipboard + ACTION_PASTE. */
    fun insert(text: String): Boolean {
        val node = focusedEditable() ?: return false
        val current = if (showsHint(node)) "" else node.text?.toString() ?: ""
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > current.length || end > current.length) { start = current.length; end = start }
        val a = minOf(start, end)
        val b = maxOf(start, end)
        val needsSpace = a > 0 && !current[a - 1].isWhitespace() && text.firstOrNull()?.isLetterOrDigit() == true
        val ins = if (needsSpace) " $text" else text
        val updated = current.substring(0, a) + ins + current.substring(b)

        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated) }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            node.refresh()
            if (node.text?.toString() == updated) {
                val sel = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, a + ins.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, a + ins.length)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
                return true
            }
        }
        return paste(node, ins)
    }

    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // Android 10+ may hide the old clip from a background app; then there is nothing to restore.
        val old = runCatching { cm.primaryClip }.getOrNull()
        cm.setPrimaryClip(ClipData.newPlainText("FlowLite", text))
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (!ok) return false // leave the text on the clipboard so it is not lost
        Handler(Looper.getMainLooper()).postDelayed({
            if (old != null) cm.setPrimaryClip(old)
            else if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
        }, 600)
        return ok
    }
}
