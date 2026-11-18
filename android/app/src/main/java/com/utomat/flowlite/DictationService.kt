package com.utomat.flowlite

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 16 kHz mono PCM16 microphone capture. */
class Recorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val chunks = ArrayList<ShortArray>()
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16000))
        check(r.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        synchronized(chunks) { chunks.clear() }
        record = r
        running = true
        r.startRecording()
        thread = Thread {
            val buf = ShortArray(1600)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) synchronized(chunks) { chunks += buf.copyOf(n) }
            }
        }.apply { start() }
    }

    /** Stops and returns the audio as floats in [-1, 1]. */
    fun stop(): FloatArray {
        running = false
        thread?.join(1000)
        record?.run { runCatching { stop() }; release() }
        record = null
        synchronized(chunks) {
            val out = FloatArray(chunks.sumOf { it.size })
            var i = 0
            for (c in chunks) for (s in c) out[i++] = s / 32768f
            chunks.clear()
            return out
        }
    }
}

/**
 * Foreground service (microphone type) that owns the floating bubble, records, transcribes and inserts.
 * Started from the app while it is in the foreground so Android allows microphone access.
 */
class DictationService : Service() {
    enum class State { IDLE, RECORDING, PROCESSING }

    companion object {
        val state = MutableStateFlow(State.IDLE)
        private val snoozed = MutableStateFlow(false)
        @Volatile var running = false
        const val ACTION_PASTE_LAST = "com.utomat.flowlite.PASTE_LAST"
        fun start(ctx: Context) {
            val i = Intent(ctx, DictationService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private var bubble: ImageView? = null
    private var bar: LinearLayout? = null
    private var cancelBtn: View? = null
    private var doneBtn: View? = null
    /** Screen x of the bubble itself. The window starts further left while the X button shows. */
    private var bubbleX = 0
    private var sideWidth = 0
    private lateinit var params: WindowManager.LayoutParams
    private val recorder = Recorder()
    private var recordStart = 0L
    private var periodic: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = Prefs(this)
        prefs.snoozeUntil = 0; snoozed.value = false // restarting the service (tile or app) ends a snooze
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification(), type) else startForeground(1, notification())
        addBubble()
        scope.launch {
            combine(FocusService.editableFocused, KeyboardService.shown, snoozed, state) { focused, kb, snooze, s ->
                (focused && !kb && !snooze) || s != State.IDLE }
                .collect { bar?.visibility = if (it) View.VISIBLE else View.GONE }
        }
        scope.launch { state.collect { paintBubble(it) } }
        periodic = SyncManager.startPeriodic(this)
        scope.launch(Dispatchers.Default) { runCatching { Dictation.ensureEngine(this@DictationService) } } // warm up the model
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PASTE_LAST) pasteLast()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        periodic?.cancel()
        bar?.let { runCatching { wm.removeView(it) } }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel("dictation", "Dictation", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val paste = PendingIntent.getService(this, 1, Intent(this, DictationService::class.java).setAction(ACTION_PASTE_LAST),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "dictation")
            .setSmallIcon(R.drawable.ic_parakeet).setContentTitle("FlowLite is ready")
            .setContentText("Tap a text field to show the bubble").setOngoing(true).setContentIntent(open)
            .addAction(R.drawable.ic_parakeet, "Paste last", paste).build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val d = resources.displayMetrics.density
        val size = (56 * d).toInt()
        params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = prefs.bubbleX; y = prefs.bubbleY }
        bubbleX = prefs.bubbleX
        val v = ImageView(this).apply {
            setImageResource(R.drawable.ic_parakeet)
            val pad = size / 5; setPadding(pad, pad, pad, pad)
            contentDescription = "FlowLite dictation"
        }
        // X discards the recording, tick inserts it. Both show only while recording.
        val side = (44 * d).toInt(); val gap = (8 * d).toInt(); sideWidth = side + gap
        fun sideButton(icon: Int, label: String, color: Int, action: () -> Unit) = ImageView(this).apply {
            setImageResource(icon); val p = side / 5; setPadding(p, p, p, p)
            contentDescription = label; visibility = View.GONE
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); action() }
        }
        val cancel = sideButton(R.drawable.ic_close, "Cancel", 0xFF5F6368.toInt()) { if (state.value == State.RECORDING) cancelRecording() }
        val done = sideButton(R.drawable.ic_check, "Insert text", 0xFF1E8E3E.toInt()) { if (state.value == State.RECORDING) stopAndTranscribe() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            addView(cancel, LinearLayout.LayoutParams(side, side).apply { rightMargin = gap })
            addView(v, LinearLayout.LayoutParams(size, size))
            addView(done, LinearLayout.LayoutParams(side, side).apply { leftMargin = gap })
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        var pressStart = 0L; var stopOnUp = false
        // Tap: start, then tick or tap to finish. Hold: talk, release to finish. Move right away: drag the bubble.
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = bubbleX; startY = params.y; dragging = false
                    pressStart = System.currentTimeMillis()
                    stopOnUp = state.value == State.RECORDING
                    if (state.value == State.IDLE) { startRecording(); v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && !stopOnUp && (abs(dx) > slop * 2 || abs(dy) > slop * 2) &&
                        System.currentTimeMillis() - pressStart < 400) {
                        dragging = true
                        if (state.value == State.RECORDING) cancelRecording()
                    }
                    if (dragging) { bubbleX = startX + dx.toInt(); params.y = startY + dy.toInt(); place() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val held = System.currentTimeMillis() - pressStart
                    if (dragging && inSnoozeZone(e.rawX, e.rawY)) {
                        bubbleX = startX; params.y = startY; place(); snooze()
                    } else if (dragging) { prefs.bubbleX = bubbleX; prefs.bubbleY = params.y }
                    else if (state.value == State.RECORDING && (stopOnUp || held >= 400)) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); stopAndTranscribe()
                    } // a quick tap leaves it recording until the next tap
                }
            }
            true
        }
        wm.addView(root, params)
        bubble = v; bar = root; cancelBtn = cancel; doneBtn = done
    }

    private fun inSnoozeZone(x: Float, y: Float): Boolean {
        val m = resources.displayMetrics
        return y > m.heightPixels - 120 * m.density && abs(x - m.widthPixels / 2f) < 90 * m.density
    }

    private fun snooze() {
        prefs.snoozeUntil = System.currentTimeMillis() + 10 * 60_000L
        snoozed.value = true
        toast("Bubble hidden for 10 minutes")
        scope.launch { delay(10 * 60_000L); if (System.currentTimeMillis() >= prefs.snoozeUntil) snoozed.value = false }
    }

    private fun paintBubble(s: State) {
        val color = when (s) { State.IDLE -> 0xFF3559E0.toInt(); State.RECORDING -> 0xFFD93025.toInt(); State.PROCESSING -> 0xFFE8A317.toInt() }
        bubble?.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        bubble?.alpha = if (s == State.IDLE) 0.85f else 1f
        val scale = if (s == State.RECORDING) 1.15f else 1f
        bubble?.animate()?.scaleX(scale)?.scaleY(scale)?.setDuration(120)?.start()
        val sides = if (s == State.RECORDING) View.VISIBLE else View.GONE
        cancelBtn?.visibility = sides; doneBtn?.visibility = sides
        place()
    }

    /** Positions the window so the bubble stays where the user put it, whether or not the X shows. */
    private fun place() {
        val b = bar ?: return
        params.x = (bubbleX - if (cancelBtn?.visibility == View.VISIBLE) sideWidth else 0).coerceAtLeast(0)
        runCatching { wm.updateViewLayout(b, params) }
    }

    private fun startRecording() {
        try {
            recorder.start()
            recordStart = System.currentTimeMillis()
            state.value = State.RECORDING
        } catch (e: Exception) {
            Log.e("FlowLite", "Record failed", e)
            toast("Microphone unavailable: ${e.message}")
        }
    }

    private fun cancelRecording() { recorder.stop(); state.value = State.IDLE }

    private fun stopAndTranscribe() {
        val audio = recorder.stop()
        val durationMs = System.currentTimeMillis() - recordStart
        if (durationMs < 300) { state.value = State.IDLE; return }
        state.value = State.PROCESSING
        val pkg = FocusService.focusedPackage
        val selection = FocusService.instance?.selectedText()
        val private = FocusService.instance?.focusedIsPrivate() ?: false
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    Dictation.transcribe(this@DictationService, audio, durationMs, pkg, selection, private)
                }
                if (result == null) toast("No speech detected")
                else {
                    val inserted = insertOrCopy(result.text)
                    toast(if (inserted) "%.1f s".format(result.transcribeMs / 1000.0) else "Copied to clipboard")
                    if (!private) SyncManager.request(this@DictationService)
                }
            } catch (e: Exception) {
                Log.e("FlowLite", "Transcription failed", e)
                toast("Transcription failed: ${e.message}")
            } finally {
                state.value = State.IDLE
            }
        }
    }

    private fun insertOrCopy(text: String): Boolean {
        val inserted = FocusService.instance?.insert(text) == true
        if (!inserted) (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("FlowLite", text))
        return inserted
    }

    /** Re-inserts the newest transcript from any device, for fields where the first insert failed. */
    private fun pasteLast() = scope.launch {
        FocusService.instance?.collapseShade()
        delay(700) // let focus return to the app behind the notification shade
        val last = withContext(Dispatchers.IO) { FlowDb.get(this@DictationService).transcripts().latest() }
        if (last == null) toast("No dictation yet")
        else if (!insertOrCopy(last.text)) toast("Copied to clipboard")
    }

    private fun toast(msg: String) = scope.launch { Toast.makeText(this@DictationService, msg, Toast.LENGTH_SHORT).show() }
}
