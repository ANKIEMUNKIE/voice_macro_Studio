package dev.voicemacro.studio

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.voicemacro.studio.db.MacroDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var tts: TextToSpeech? = null
    private var speechReady = false
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var databaseNotice = ""
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            status.text = "Accessibility: ${if (MacroAccessibilityService.connected) "connected" else "not connected"}\n\n" +
                "Target-app events: ${MacroAccessibilityService.eventCount}\n" +
                "${MacroAccessibilityService.lastEvent}\n\n" +
                "Safety: ${MacroAccessibilityService.safetyStatus}\n\n" +
                "Last target check: ${MacroAccessibilityService.lastTargetCheck}\n\n" +
                "Speech recognizer available: ${SpeechRecognizer.isRecognitionAvailable(this@MainActivity)}" +
                if (databaseNotice.isBlank()) "" else "\n\n$databaseNotice"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(box) }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
        box.addView(TextView(this).apply { text = "Voice Macro Studio"; textSize = 28f; accessibilityHeadingCompat() })
        box.addView(TextView(this).apply {
            text = "Phases 1–5 · Audit build\n\nSafety, spoken teaching, local flow storage, matching, and replay are under validation. Orange means blocked. Unreadable, login, OTP, password, and payment screens cannot be bypassed.\n\nTap Record, speak the command to teach, demonstrate it, then tap Record again to save. Listen replays only a confidently matched flow for the current app.\n"
            textSize = 17f
        })
        status = TextView(this).apply { textSize = 16f }
        box.addView(status)
        box.addView(Button(this).apply {
            text = "Open accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        box.addView(Button(this).apply {
            text = "Test spoken feedback"
            setOnClickListener {
                if (speechReady) tts?.speak("Voice Macro Studio setup is ready.", TextToSpeech.QUEUE_FLUSH, null, "setup")
            }
        })
        box.addView(Button(this).apply {
            text = "Grant Mic Permission"
            setOnClickListener {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
            }
        })
        box.addView(Button(this).apply {
            text = "Delete all learned flows"
            setOnClickListener {
                isEnabled = false
                databaseNotice = "Deleting learned flows..."
                activityScope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            MacroDatabase.getDatabase(this@MainActivity).macroDao().deleteAllLearnedFlows()
                        }
                    }
                    databaseNotice = if (result.isSuccess) {
                        "All learned flows and replay history were deleted."
                    } else {
                        isEnabled = true
                        "Could not delete learned flows."
                    }
                }
            }
        })
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val languageResult = tts?.setLanguage(Locale.forLanguageTag("en-IN"))
                speechReady = languageResult != null && languageResult >= 0
            }
        }
    }

    private fun View.accessibilityHeadingCompat() {
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { activityScope.cancel(); tts?.shutdown(); super.onDestroy() }
}
