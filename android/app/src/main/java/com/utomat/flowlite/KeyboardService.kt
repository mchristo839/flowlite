package com.utomat.flowlite

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Voice keyboard for fields where the bubble cannot insert text. Uses the same pipeline as the bubble. */
class KeyboardService : InputMethodService() {
    companion object {
        /** True while this keyboard is on screen; the bubble hides then. */
        val shown = MutableStateFlow(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recorder = Recorder()
    private var recordStart = 0L
    private val state = MutableStateFlow(DictationService.State.IDLE)
    private lateinit var mic: ImageView
    private lateinit var status: TextView

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        shown.value = true
        scope.launch(Dispatchers.Default) { runCatching { Dictation.ensureEngine(this@KeyboardService) } }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        shown.value = false
        if (state.value == DictationService.State.RECORDING) { recorder.stop(); state.value = DictationService.State.IDLE }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF1F1F24.toInt()); setPadding(dp(8), dp(12), dp(8), dp(8))
        }
        status = TextView(this).apply { setTextColor(0xFFCCCCCC.toInt()); gravity = Gravity.CENTER; textSize = 14f }
        mic = ImageView(this).apply {
            setImageResource(R.drawable.ic_parakeet); setPadding(dp(20), dp(20), dp(20), dp(20))
            contentDescription = "Dictate"
        }
        var pressStart = 0L; var stopOnUp = false
        // Same gestures as the bubble: tap to start and tap to finish, or hold and release.
        mic.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressStart = System.currentTimeMillis()
                    stopOnUp = state.value == DictationService.State.RECORDING
                    if (state.value == DictationService.State.IDLE) { startRecording(); v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (state.value == DictationService.State.RECORDING && (stopOnUp || System.currentTimeMillis() - pressStart >= 400)) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); stopAndTranscribe()
                    }
            }
            true
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(mic, LinearLayout.LayoutParams(dp(96), dp(96)).apply { topMargin = dp(8); bottomMargin = dp(12) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun key(icon: Int?, label: String, weight: Float, action: () -> Unit) = (if (icon != null)
            ImageView(this).apply { setImageResource(icon); scaleType = ImageView.ScaleType.CENTER }
        else TextView(this).apply { text = label; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER })
            .apply {
                contentDescription = label
                background = GradientDrawable().apply { cornerRadius = 8 * d; setColor(0xFF3A3A42.toInt()) }
                setOnClickListener { action() }
                row.addView(this, LinearLayout.LayoutParams(0, dp(48), weight).apply { setMargins(dp(3), 0, dp(3), 0) })
            }
        key(R.drawable.ic_keyboard, "Switch keyboard", 1f) { switchKeyboard() }
        key(null, "space", 3f) { currentInputConnection?.commitText(" ", 1) }
        key(R.drawable.ic_backspace, "Delete", 1f) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
        key(R.drawable.ic_enter, "Enter", 1f) { if (!sendDefaultEditorAction(true)) sendKeyChar('\n') }
        root.addView(row, LinearLayout.LayoutParams(-1, -2))

        scope.launch {
            state.collect { s ->
                val color = when (s) {
                    DictationService.State.IDLE -> 0xFF3559E0.toInt()
                    DictationService.State.RECORDING -> 0xFFD93025.toInt()
                    DictationService.State.PROCESSING -> 0xFFE8A317.toInt()
                }
                mic.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                if (s != DictationService.State.IDLE || status.text.isEmpty()) status.text = when (s) {
                    DictationService.State.IDLE -> "Tap to talk, or hold"
                    DictationService.State.RECORDING -> "Listening"
                    DictationService.State.PROCESSING -> "Transcribing"
                }
            }
        }
        return root
    }

    /** Password fields and apps asking for no learning (incognito tabs): no history, no sync, no AI rewrite. */
    private fun isPrivate(info: EditorInfo?): Boolean {
        if (info == null) return false
        val variation = info.inputType and (EditorInfo.TYPE_MASK_CLASS or EditorInfo.TYPE_MASK_VARIATION)
        return variation in setOf(
            EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD,
            EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD,
        ) || info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
    }

    private fun switchKeyboard() {
        val switched = Build.VERSION.SDK_INT >= 28 && switchToPreviousInputMethod()
        if (!switched) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Open FlowLite and allow the microphone"; return
        }
        try {
            recorder.start(); recordStart = System.currentTimeMillis(); state.value = DictationService.State.RECORDING
        } catch (e: Exception) {
            Log.e("FlowLite", "Record failed", e); status.text = "Microphone unavailable"
        }
    }

    private fun stopAndTranscribe() {
        val audio = recorder.stop()
        val durationMs = System.currentTimeMillis() - recordStart
        if (durationMs < 300) { state.value = DictationService.State.IDLE; return }
        state.value = DictationService.State.PROCESSING
        val ic = currentInputConnection
        val pkg = currentInputEditorInfo?.packageName
        val selection = ic?.getSelectedText(0)?.toString()
        val private = isPrivate(currentInputEditorInfo)
        scope.launch {
            try {
                val t = withContext(Dispatchers.Default) { Dictation.transcribe(this@KeyboardService, audio, durationMs, pkg, selection, private) }
                if (t == null) status.text = "No speech detected"
                else {
                    val before = if (selection.isNullOrEmpty()) ic?.getTextBeforeCursor(1, 0)?.toString().orEmpty() else ""
                    val space = before.isNotEmpty() && !before[0].isWhitespace() && t.text.firstOrNull()?.isLetterOrDigit() == true
                    ic?.commitText(if (space) " ${t.text}" else t.text, 1)
                    status.text = "%.1f s".format(t.transcribeMs / 1000.0)
                    if (!private) SyncManager.request(this@KeyboardService)
                }
            } catch (e: Exception) {
                Log.e("FlowLite", "Transcription failed", e); status.text = e.message ?: "Transcription failed"
            } finally {
                state.value = DictationService.State.IDLE
            }
        }
    }
}
