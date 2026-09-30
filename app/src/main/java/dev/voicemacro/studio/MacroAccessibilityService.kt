package dev.voicemacro.studio

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.voicemacro.studio.safety.*
import dev.voicemacro.studio.capture.*
import android.view.KeyEvent
import dev.voicemacro.studio.db.MacroDatabase
import dev.voicemacro.studio.db.Flow
import dev.voicemacro.studio.db.Step
import dev.voicemacro.studio.db.ActionType
import dev.voicemacro.studio.intent.IntentMatcher
import dev.voicemacro.studio.intent.MatchDecision
import dev.voicemacro.studio.intent.QuantityDecision
import dev.voicemacro.studio.intent.QuantityParser
import dev.voicemacro.studio.replay.SelectorTextMatcher
import dev.voicemacro.studio.replay.SpatialSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Accessibility coordinator for safety, teaching, voice matching, and replay. */
class MacroAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var connected = false
            private set
        @Volatile var eventCount = 0L
            private set
        @Volatile var lastEvent = "No target-app events yet"
            private set
        @Volatile var safetyStatus = "Not checked"
            private set
        @Volatile var lastTargetCheck = "No target screen checked"
            private set
        private val targets = setOf("com.application.zomato", "in.swiggy.android")
    }
    private val gate = SafetyGate()
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var overlay: LinearLayout? = null
    private var banner: TextView? = null
    private var lastAnnouncement = ""
    private var lastLogged = ""
    private var lastWindowTypes = ""
    private var latestEventSource: AccessibilityNodeInfo? = null
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var awaitingTeachCommand = false
    private val speechTimeout = Runnable {
        if (isListening) {
            speechRecognizer?.cancel()
            isListening = false
            awaitingTeachCommand = false
            voiceBanner?.text = "Voice timed out. Check the emulator microphone and try again."
        }
    }
    private val finishSpeech = Runnable {
        if (isListening) {
            voiceBanner?.text = "Processing speech..."
            speechRecognizer?.stopListening()
        }
    }

    private var serviceJob = SupervisorJob()
    private var scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var isRecording = false
    @Volatile private var isReplaying = false
    private var currentFlow: Flow? = null
    private val recordedSteps = mutableListOf<Step>()
    private var lastRecordedAt = 0L
    private var lastRecordedKey = ""
    
    private val monitor = object : Runnable {
        override fun run() { inspectAndReport(); handler.postDelayed(this, 500) }
    }
    override fun onServiceConnected() {
        // Android may reconnect the same service instance after a user or system
        // disable/enable cycle. A cancelled coroutine scope cannot be reused.
        if (!serviceJob.isActive) {
            serviceJob = SupervisorJob()
            scope = CoroutineScope(serviceJob + Dispatchers.IO)
        }
        connected = true
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) ttsReady = (tts?.setLanguage(Locale.forLanguageTag("en-IN")) ?: -1) >= 0
        }
        handler.removeCallbacks(monitor)
        handler.post(monitor)
        Log.i("VoiceMacroSetup", "Service connected; safety observer active")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val app = event.packageName?.toString() ?: return
        if (app in targets) {
            eventCount++
            lastEvent = "$app: ${AccessibilityEvent.eventTypeToString(event.eventType)}"
            
            if (isRecording) {
                Log.d("VoiceMacroRecord", "Event: ${AccessibilityEvent.eventTypeToString(event.eventType)} source=${event.source?.className}")
            }
            
            if (isRecording && (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)) {
                val check = freshCheck()
                if (check.first != app || currentFlow?.packageName != app || !gate.permits { check.second }) {
                    finishRecording("Stopped before an unsafe or unreadable screen")
                    return
                }
                val src = event.source ?: return
                try {
                    val action = if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) ActionType.CLICK else ActionType.TYPE
                    var text = src.text?.toString() ?: src.contentDescription?.toString()
                    if (text.isNullOrBlank() && action == ActionType.CLICK) text = extractTextRecursively(src)
                    val description = src.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    val viewId = src.viewIdResourceName
                    if (action == ActionType.CLICK && text.isNullOrBlank() && description == null && viewId == null) return
                    val selectorContext = if (action == ActionType.CLICK) captureSelectorContext(src, text ?: description) else null
                    val sourceBounds = android.graphics.Rect().also { src.getBoundsInScreen(it) }
                    val normalizedCenter = if (!sourceBounds.isEmpty) {
                        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
                        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
                        Pair(sourceBounds.centerX() * 10_000 / width, sourceBounds.centerY() * 10_000 / height)
                    } else null
                    val key = "$action|${src.className}|$description|$viewId"
                    val now = android.os.SystemClock.uptimeMillis()
                    val step = Step(
                        flowId = currentFlow?.id ?: return,
                        orderIndex = recordedSteps.size,
                        actionType = action,
                        selectorClassName = src.className?.toString(),
                        selectorText = if (action == ActionType.CLICK) text?.takeIf { it.isNotBlank() } else null,
                        selectorDesc = description,
                        selectorViewId = viewId,
                        selectorContext = selectorContext,
                        selectorCenterX = normalizedCenter?.first,
                        selectorCenterY = normalizedCenter?.second,
                        slotBinding = if (action == ActionType.TYPE) src.text?.toString() else null
                    )
                    if (action == ActionType.TYPE && recordedSteps.lastOrNull()?.actionType == ActionType.TYPE && key == lastRecordedKey) {
                        recordedSteps[recordedSteps.lastIndex] = step.copy(orderIndex = recordedSteps.lastIndex)
                    } else if (key != lastRecordedKey || now - lastRecordedAt > 400) {
                        recordedSteps.add(step)
                    }
                    lastRecordedKey = key
                    lastRecordedAt = now
                    safetyStatus = "Recording... (${recordedSteps.size} steps)"
                    showOverlay(safetyStatus, false)
                } finally { src.recycle() }
            }
            
            try {
                val source = event.source
                if (source != null) {
                    // Cache the latest event source. Sometimes virtual trees are disconnected
                    // from window.root, and only accessible via event.source!
                    latestEventSource?.recycle()
                    latestEventSource = source
                }
            } catch (e: Exception) {}
        }
    }
    @Suppress("DEPRECATION")
    private fun freshCheck(): Pair<String?, SafetyResult> {
        if (!connected) return null to SafetyResult(Verdict.UNKNOWN, Reason.UNREADABLE)
        return try {
            val width = resources.displayMetrics.widthPixels
            val height = resources.displayMetrics.heightPixels

            // Try all windows, not just TYPE_APPLICATION — Swiggy restaurant detail opens in a
            // window that may be classified differently. Sort so active windows come first.
            val allWindows = windows
                .sortedWith(compareByDescending<AccessibilityWindowInfo> { if (it.isActive) 1 else 0 }
                    .thenByDescending { it.layer })

            // Log window types (just integers, no content) once per change for diagnosis.
            val typeSummary = allWindows.joinToString { it.type.toString() }
            if (typeSummary != lastWindowTypes) {
                Log.d("VoiceMacroSafety", "Windows(type,active): ${allWindows.joinToString { "${it.type}/${it.isActive}" }}")
                lastWindowTypes = typeSummary
            }

            var bestResult: SafetyResult? = null
            var targetAppFound: String? = null

            // We aggregate results across ALL roots. We collect roots from both the windows list
            // and rootInActiveWindow, because some virtual hierarchies (Compose, React Native)
            // may only resolve properly through the active window route rather than window.root.
            val rootsToInspect = mutableListOf<AccessibilityNodeInfo>()
            
            for (window in allWindows) {
                window.root?.let { rootsToInspect.add(it) }
            }
            rootInActiveWindow?.let { rootsToInspect.add(it) }
            latestEventSource?.let { 
                // Copy the node to avoid recycling issues, or just add it
                rootsToInspect.add(AccessibilityNodeInfo.obtain(it)) 
            }

            val allPackages = mutableSetOf<String>()

            for (root in rootsToInspect) {
                try {
                    root.refresh() // Force cache bypass for React Native skeletons
                    val app = root.packageName?.toString() ?: "unknown"
                    allPackages.add(app)
                    
                    if (app in targets) {
                        targetAppFound = app
                        val result = SensitiveScreenReader.inspect(root, width, height)
                        
                        // Immediate stop if ANY window/root is sensitive
                        if (result.verdict == Verdict.SENSITIVE) {
                            return app to result
                        }
                        
                        // Keep track of the "best" result. CLEAR > MISSING_NODE > EMPTY_TREE
                        if (bestResult == null) {
                            bestResult = result
                        } else if (result.verdict == Verdict.CLEAR) {
                            bestResult = result
                        } else if (bestResult.verdict == Verdict.UNKNOWN && bestResult.reason == Reason.EMPTY_TREE && result.verdict == Verdict.UNKNOWN) {
                            bestResult = result
                        }
                    }
                } finally { root.recycle() }
            }

            if (targetAppFound != null && bestResult != null) {
                if (bestResult.reason == Reason.EMPTY_TREE) {
                    android.util.Log.d("VoiceMacroSafety", "EMPTY_TREE returned. All packages seen: ${allPackages.joinToString()}")
                }
                return targetAppFound to bestResult
            }

            return null to SafetyResult(Verdict.UNKNOWN, Reason.UNREADABLE)
        } catch (_: RuntimeException) { null to SafetyResult(Verdict.UNKNOWN, Reason.UNREADABLE) }
    }

    private fun inspectAndReport() {
        val (app, result) = freshCheck()
        if (app !in targets) {
            gate.observe(result)
            if (isRecording) finishRecording("Stopped when the target app was left")
            safetyStatus = "Inactive or unreadable screen; actions blocked"
            removeOverlay()
            return
        }
        gate.observe(result)
        if (isRecording && (result.verdict != Verdict.CLEAR || gate.latchedReason != Reason.NONE)) {
            finishRecording("Stopped before an unsafe or unreadable screen")
            return
        }
        lastTargetCheck = "$app: ${result.verdict} / ${result.reason}"
        safetyStatus = when {
            isRecording -> "Recording... (${recordedSteps.size} steps)"
            result.verdict == Verdict.SENSITIVE -> "Your turn: ${result.reason.name.lowercase()} screen. Automation blocked."
            result.verdict == Verdict.UNKNOWN && gate.latchedReason in setOf(Reason.PAYMENT, Reason.PASSWORD, Reason.OTP, Reason.LOGIN) -> "Stopped at ${gate.latchedReason.name.lowercase()}. Screen check incomplete; automation blocked."
            result.verdict == Verdict.UNKNOWN -> "Screen check incomplete (${result.reason.name.lowercase()}). Automation blocked."
            gate.latchedReason != Reason.NONE -> "Stopped: ${gate.latchedReason.name.lowercase()}. Recheck manually to clear."
            else -> "Ready. Waiting for command."
        }
        val log = "$lastTargetCheck latch=${gate.latchedReason}"
        if (log != lastLogged) { Log.i("VoiceMacroSafety", log); lastLogged = log }
        showOverlay(safetyStatus, result.verdict != Verdict.CLEAR || gate.latchedReason != Reason.NONE)
        if (ttsReady && safetyStatus != lastAnnouncement) {
            tts?.speak(safetyStatus, TextToSpeech.QUEUE_FLUSH, null, "safety")
            lastAnnouncement = safetyStatus
        }
    }
    private fun toggleRecording() {
        if (isRecording) finishRecording("Recording complete")
        else {
            awaitingTeachCommand = true
            voiceBanner?.text = "Say the command you want to teach."
            startListening()
        }
    }

    private fun startRecording(name: String, originalUtterance: String) {
        if (isRecording || isReplaying) return
        val check = freshCheck()
        if (check.first !in targets || !gate.permits { check.second }) {
            showOverlay("Cannot teach on an unsafe or unreadable screen.", true)
            awaitingTeachCommand = false
            return
        }
        val cleanName = name.trim().removePrefix("teach ").removePrefix("learn ").removePrefix("record ").trim()
        if (cleanName.length < 2) {
            voiceBanner?.text = "Please use a more specific command."
            return
        }
        isRecording = true
        awaitingTeachCommand = false
        currentFlow = Flow(name = cleanName, description = originalUtterance, packageName = check.first!!)
        recordedSteps.clear()
        lastRecordedKey = ""
        lastRecordedAt = 0L
        safetyStatus = "Recording: $cleanName"
        showOverlay(safetyStatus, false)
        if (ttsReady) tts?.speak("Recording started", TextToSpeech.QUEUE_FLUSH, null, "record")
    }

    private fun finishRecording(reason: String) {
        if (!isRecording) return
        isRecording = false
        val flowToSave = currentFlow
        currentFlow = null
        val stepsToSave = recordedSteps.toList()
        recordedSteps.clear()
        if (flowToSave == null || stepsToSave.isEmpty()) {
            safetyStatus = "$reason. No usable steps were captured."
            showOverlay(safetyStatus, true)
            return
        }
        safetyStatus = "Saving ${stepsToSave.size} steps..."
        showOverlay(safetyStatus, false)
        scope.launch {
            runCatching {
                MacroDatabase.getDatabase(this@MacroAccessibilityService).macroDao()
                    .saveRecordedFlow(flowToSave, stepsToSave, emptyList())
            }.onSuccess {
                handler.post {
                    safetyStatus = "$reason. Saved ${stepsToSave.size} steps."
                    showOverlay(safetyStatus, false)
                    if (ttsReady) tts?.speak("Recording saved", TextToSpeech.QUEUE_FLUSH, null, "record")
                }
            }.onFailure {
                handler.post { showOverlay("Recording could not be saved.", true) }
            }
        }
    }

    private var voiceBanner: TextView? = null

    private fun showOverlay(message: String, blocked: Boolean) {
        if (overlay == null) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 8, 16, 8) }
            banner = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f }
            voiceBanner = TextView(this).apply { setTextColor(Color.YELLOW); textSize = 15f; setPadding(0, 8, 0, 8) }
            box.addView(banner)
            box.addView(voiceBanner)
            val controls = LinearLayout(this)
            controls.addView(Button(this).apply {
                text = "Record"
                setOnClickListener { toggleRecording() }
            })
            controls.addView(Button(this).apply {
                text = "Stop Safety"
                setOnClickListener { gate.stop(); inspectAndReport() }
            })
            controls.addView(Button(this).apply {
                text = "Recheck"
                setOnClickListener { gate.resumeManually { freshCheck().second }; inspectAndReport() }
            })
            controls.addView(Button(this).apply {
                text = "Listen"
                setOnClickListener { startListening() }
            })
            box.addView(controls)
            val params = WindowManager.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.9).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 70 }
            try {
                getSystemService(WindowManager::class.java).addView(box, params)
                overlay = box
            } catch (_: RuntimeException) {
                safetyStatus = "Safety display unavailable; automation blocked"
                gate.stop()
                return
            }
        }
        if (banner?.text?.toString() != message) banner?.text = message
        overlay?.setBackgroundColor(
            if (isRecording) android.graphics.Color.rgb(180, 0, 0)
            else if (blocked) android.graphics.Color.rgb(143, 62, 0)
            else android.graphics.Color.rgb(30, 67, 101)
        )
    }

    private fun extractTextRecursively(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val texts = mutableListOf<String>()
        val t = node.text?.toString()
        val c = node.contentDescription?.toString()
        if (!t.isNullOrBlank()) texts.add(t)
        if (!c.isNullOrBlank()) texts.add(c)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val childText = extractTextRecursively(child)
            if (childText.isNotBlank()) texts.add(childText)
            child?.recycle()
        }
        return texts.joinToString(" ").trim()
    }

    /** Captures the nearest compact ancestor label that distinguishes generic controls such as ADD. */
    @Suppress("DEPRECATION")
    private fun captureSelectorContext(node: AccessibilityNodeInfo, primary: String?): String? {
        val primaryTokens = SelectorTextMatcher.canonical(primary.orEmpty()).split(' ').filter { it.isNotBlank() }.toSet()
        var current = node.parent
        var depth = 0
        while (current != null && depth++ < 5) {
            val context = extractTextRecursively(current).trim()
            val stableTokens = SelectorTextMatcher.canonical(context).split(' ').filter { it.isNotBlank() }.toSet()
            val distinguishingTokens = stableTokens - primaryTokens
            val next = current.parent
            current.recycle()
            if (context.length in 2..600 && distinguishingTokens.size >= 2) {
                next?.recycle()
                return context
            }
            current = next
        }
        current?.recycle()
        return null
    }

    private fun startListening() {
        if (isListening) {
            handler.removeCallbacks(speechTimeout)
            handler.removeCallbacks(finishSpeech)
            speechRecognizer?.cancel()
            isListening = false
            awaitingTeachCommand = false
            voiceBanner?.text = "Listening cancelled. Tap Record or Listen to try again."
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            awaitingTeachCommand = false
            voiceBanner?.text = "Microphone permission is required. Open the app to grant it."
            return
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    voiceBanner?.text = "Listening... Tap Listen to cancel."
                }
                override fun onBeginningOfSpeech() {
                    handler.removeCallbacks(speechTimeout)
                    handler.removeCallbacks(finishSpeech)
                    handler.postDelayed(finishSpeech, 4_500)
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    handler.removeCallbacks(finishSpeech)
                    voiceBanner?.text = "Processing speech..."
                }
                override fun onError(error: Int) {
                    handler.removeCallbacks(speechTimeout)
                    handler.removeCallbacks(finishSpeech)
                    isListening = false
                    awaitingTeachCommand = false
                    voiceBanner?.text = "Voice error: $error"
                }
                override fun onResults(results: Bundle?) {
                    handler.removeCallbacks(speechTimeout)
                    handler.removeCallbacks(finishSpeech)
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val spokenText = matches.first()
                        voiceBanner?.text = "Heard: $spokenText"
                        if (awaitingTeachCommand) startRecording(spokenText, spokenText)
                        else handleVoiceIntent(matches)
                    } else {
                        awaitingTeachCommand = false
                        voiceBanner?.text = "No voice recognized"
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN") // Primary language Indian English
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            // Emulator microphones can produce a slightly wrong first hypothesis. Match all
            // returned alternatives and use the strongest unambiguous learned-flow match.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        isListening = true
        handler.removeCallbacks(speechTimeout)
        handler.postDelayed(speechTimeout, 12_000)
        runCatching { speechRecognizer?.startListening(intent) }.onFailure {
            handler.removeCallbacks(speechTimeout)
            isListening = false
            awaitingTeachCommand = false
            voiceBanner?.text = "Could not start voice recognition."
        }
    }

    private fun handleVoiceIntent(spokenAlternatives: List<String>) {
        val spokenText = spokenAlternatives.firstOrNull().orEmpty()
        val teachingName = IntentMatcher.teachingName(spokenText)
        if (teachingName != null) {
            handler.post { startRecording(teachingName, spokenText) }
            return
        }
        scope.launch {
            val db = MacroDatabase.getDatabase(this@MacroAccessibilityService)
            val activePackage = withContext(Dispatchers.Main) { freshCheck().first }
            val flows = db.macroDao().getAllFlows().filter { it.packageName == activePackage }
            val decisions = spokenAlternatives.distinct().map { it to IntentMatcher.decide(it, flows) }
            val bestMatch = decisions.mapNotNull { (heard, result) ->
                (result as? MatchDecision.Matched)?.let { Triple(heard, it, it.match.score) }
            }.maxByOrNull { it.third }
            val decision = bestMatch?.second
                ?: decisions.firstNotNullOfOrNull { it.second as? MatchDecision.Ambiguous }
                ?: MatchDecision.Unknown
            when (decision) {
                is MatchDecision.Matched -> {
                    val matchedUtterance = bestMatch?.first ?: spokenText
                    val quantity = when (val parsed = QuantityParser.parse(matchedUtterance)) {
                        is QuantityDecision.Valid -> parsed.quantity
                        QuantityDecision.Unsupported -> {
                            handler.post {
                                voiceBanner?.text = "Please use one quantity from 1 to 5."
                                if (ttsReady) tts?.speak(
                                    "Please use one quantity from one to five.",
                                    TextToSpeech.QUEUE_FLUSH, null, "quantity_invalid"
                                )
                            }
                            return@launch
                        }
                    }
                    handler.post {
                        voiceBanner?.text = "Matched: ${decision.match.flow.name} · quantity $quantity"
                        if (ttsReady) tts?.speak(
                            "Starting ${decision.match.flow.name}, quantity $quantity",
                            TextToSpeech.QUEUE_FLUSH, null, "replay"
                        )
                    }
                    replayFlow(decision.match.flow, quantity)
                }
                is MatchDecision.Ambiguous -> handler.post {
                    val names = decision.candidates.joinToString(" or ") { it.flow.name }
                    voiceBanner?.text = "Which flow: $names?"
                    if (ttsReady) tts?.speak("Which flow did you mean?", TextToSpeech.QUEUE_FLUSH, null, "ambiguous")
                }
                MatchDecision.Unknown -> handler.post {
                    voiceBanner?.text = "Heard: $spokenText\nI have not learned that flow. Say Teach followed by the command."
                    if (ttsReady) tts?.speak("I have not learned that flow.", TextToSpeech.QUEUE_FLUSH, null, "unknown")
                }
            }
        }
    }

    private enum class StepOutcome {
        DISPATCHED, NODE_NOT_FOUND, AMBIGUOUS_TARGET, SAFETY_BLOCKED,
        ACTION_REJECTED, POSTCONDITION_FAILED, PRECONDITION_FAILED
    }
    private sealed interface NodeLookup {
        data class Found(val node: AccessibilityNodeInfo) : NodeLookup
        data object Missing : NodeLookup
        data object Ambiguous : NodeLookup
    }

    private suspend fun replayFlow(flow: Flow, requestedQuantity: Int = 1) {
        if (isReplaying) {
            handler.post { voiceBanner?.text = "A replay is already running." }
            return
        }
        isReplaying = true
        val db = MacroDatabase.getDatabase(this@MacroAccessibilityService)
        val steps = db.macroDao().getStepsForFlow(flow.id)
        if (requestedQuantity > 1 && (steps.size != 1 || steps.single().actionType != ActionType.CLICK)) {
            isReplaying = false
            handler.post {
                voiceBanner?.text = "Quantity 2–5 requires a learned single-tap item flow."
                if (ttsReady) tts?.speak(
                    "This learned flow cannot safely change quantity.",
                    TextToSpeech.QUEUE_FLUSH, null, "quantity_unsupported_flow"
                )
            }
            return
        }
        val executionSteps = if (requestedQuantity > 1) {
            List(requestedQuantity) { steps.single() }
        } else steps
        var failure: StepOutcome? = if (steps.isEmpty()) StepOutcome.NODE_NOT_FOUND else null
        var failedStepNumber: Int? = if (steps.isEmpty()) 1 else null
        var existingQuantityAtStart: Int? = null
        try {
            for ((executionIndex, step) in executionSteps.withIndex()) {
                if (!connected) { failure = StepOutcome.SAFETY_BLOCKED; break }
                var outcome = StepOutcome.NODE_NOT_FOUND
                var preActionState: String? = null
                for (attempt in 1..5) {
                    outcome = withContext(Dispatchers.Main) {
                        val pre = freshCheck()
                        if (pre.first != flow.packageName || !gate.permits { pre.second }) {
                            return@withContext StepOutcome.SAFETY_BLOCKED
                        }
                        val targetNode = when (val lookup = findNodeForStep(step, flow.packageName)) {
                            is NodeLookup.Found -> lookup.node
                            NodeLookup.Missing -> return@withContext StepOutcome.NODE_NOT_FOUND
                            NodeLookup.Ambiguous -> return@withContext StepOutcome.AMBIGUOUS_TARGET
                        }
                        try {
                            if (executionIndex == 0 && steps.size == 1) {
                                existingQuantityAtStart = existingQuantityNear(targetNode)
                                if (existingQuantityAtStart != null) {
                                    return@withContext StepOutcome.PRECONDITION_FAILED
                                }
                            }
                            // A fresh safety check immediately before every dispatch is mandatory.
                            val immediate = freshCheck()
                            if (immediate.first != flow.packageName || !gate.permits { immediate.second }) {
                                return@withContext StepOutcome.SAFETY_BLOCKED
                            }
                            preActionState = targetStructureFingerprint(targetNode)
                            val accepted = when (step.actionType) {
                                ActionType.CLICK -> targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                ActionType.TYPE -> {
                                    val value = step.slotBinding ?: return@withContext StepOutcome.ACTION_REJECTED
                                    val args = Bundle().apply {
                                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                                    }
                                    targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                                }
                                else -> false
                            }
                            if (accepted) StepOutcome.DISPATCHED else StepOutcome.ACTION_REJECTED
                        } finally { targetNode.recycle() }
                    }
                    if (outcome != StepOutcome.NODE_NOT_FOUND) break
                    kotlinx.coroutines.delay(1000)
                }
                if (outcome != StepOutcome.DISPATCHED) {
                    failure = outcome
                    failedStepNumber = executionIndex + 1
                    break
                }
                val postcondition = verifyTargetChanged(step, flow.packageName, preActionState)
                if (postcondition != StepOutcome.DISPATCHED) {
                    failure = postcondition
                    failedStepNumber = executionIndex + 1
                    break
                }
            }
        } finally {
            isReplaying = false
        }
        handler.post {
            val message = when (failure) {
                null -> "Replay completed and verified for ${flow.name}. Quantity $requestedQuantity."
                StepOutcome.SAFETY_BLOCKED -> "Replay stopped by safety check."
                StepOutcome.NODE_NOT_FOUND -> "Replay failed: target not found at step ${failedStepNumber ?: "?"}."
                StepOutcome.AMBIGUOUS_TARGET -> "Replay stopped: step ${failedStepNumber ?: "?"} matches more than one target."
                StepOutcome.ACTION_REJECTED -> "Replay failed: action was rejected at step ${failedStepNumber ?: "?"}."
                StepOutcome.POSTCONDITION_FAILED -> "Replay failed: no screen change verified at step ${failedStepNumber ?: "?"}."
                StepOutcome.PRECONDITION_FAILED -> "Replay not started: item already has quantity ${existingQuantityAtStart ?: "?"}. Remove it first."
                StepOutcome.DISPATCHED -> "Replay stopped."
            }
            // Keep the primary line reserved for the continuously refreshed safety state.
            // Otherwise the monitor immediately replaces this result and leaves the stale
            // "Matched" voice message visible indefinitely.
            showOverlay(safetyStatus, failure != null || gate.latchedReason != Reason.NONE)
            voiceBanner?.text = message
            if (ttsReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "replay_result")
        }
    }

    /**
     * The current supported click flows must replace or remove the demonstrated target before
     * replay advances. This covers menu ADD -> customization/quantity and final Add item -> cart.
     * A dispatched accessibility action alone is never reported as completion.
     */
    private suspend fun verifyTargetChanged(
        step: Step,
        expectedPackage: String,
        preActionState: String?
    ): StepOutcome {
        repeat(10) {
            kotlinx.coroutines.delay(250)
            val observation = withContext(Dispatchers.Main) {
                val (app, safety) = freshCheck()
                if (app != expectedPackage) return@withContext StepOutcome.SAFETY_BLOCKED
                if (safety.verdict == Verdict.SENSITIVE) return@withContext StepOutcome.SAFETY_BLOCKED
                if (safety.verdict != Verdict.CLEAR) return@withContext null
                when (val lookup = findNodeForStep(step, expectedPackage)) {
                    NodeLookup.Missing -> StepOutcome.DISPATCHED
                    NodeLookup.Ambiguous -> null
                    is NodeLookup.Found -> {
                        val postActionState = targetStructureFingerprint(lookup.node)
                        lookup.node.recycle()
                        if (!preActionState.isNullOrBlank() && postActionState.isNotBlank() &&
                            postActionState != preActionState
                        ) StepOutcome.DISPATCHED else null
                    }
                }
            }
            if (observation != null) return observation
        }
        return StepOutcome.POSTCONDITION_FAILED
    }

    /** Captures the target and nearby control structure without retaining it or logging UI data. */
    @Suppress("DEPRECATION")
    private fun targetStructureFingerprint(node: AccessibilityNodeInfo): String {
        val result = StringBuilder()
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var ancestorDepth = 0
        while (current != null && ancestorDepth++ < 4) {
            result.append("A").append(ancestorDepth).append(':')
            appendNodeShape(result, current)
            for (i in 0 until current.childCount) {
                val child = current.getChild(i) ?: continue
                result.append("|C").append(i).append(':')
                appendNodeShape(result, child)
                child.recycle()
            }
            val parent = current.parent
            current.recycle()
            current = parent
        }
        current?.recycle()
        return result.toString()
    }

    private fun appendNodeShape(result: StringBuilder, node: AccessibilityNodeInfo) {
        result.append(node.className).append(';')
            .append(node.viewIdResourceName).append(';')
            .append(node.text).append(';')
            .append(node.contentDescription).append(';')
            .append(node.childCount).append(';')
            .append(node.isClickable)
    }

    /** Returns an existing stepper quantity near this target, or null for the initial ADD state. */
    @Suppress("DEPRECATION")
    private fun existingQuantityNear(node: AccessibilityNodeInfo): Int? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var ancestorDepth = 0
        while (current != null && ancestorDepth++ < 4) {
            var hasRemove = false
            var quantity: Int? = null
            fun visit(candidate: AccessibilityNodeInfo, depth: Int) {
                val id = candidate.viewIdResourceName.orEmpty()
                if (id.endsWith("/button_remove")) hasRemove = true
                if (id.endsWith("/text_view_title")) {
                    val value = (candidate.text ?: candidate.contentDescription)?.toString()?.trim()?.toIntOrNull()
                    if (value != null) quantity = value
                }
                if (depth >= 3) return
                for (i in 0 until candidate.childCount) {
                    val child = candidate.getChild(i) ?: continue
                    visit(child, depth + 1)
                    child.recycle()
                }
            }
            visit(current, 0)
            if (hasRemove && quantity != null) {
                current.recycle()
                return quantity
            }
            val parent = current.parent
            current.recycle()
            current = parent
        }
        current?.recycle()
        return null
    }

    @Suppress("DEPRECATION")
    private fun findNodeForStep(step: Step, expectedPackage: String): NodeLookup {
        val rootsToInspect = mutableListOf<AccessibilityNodeInfo>()
        for (window in windows) {
            window.root?.let { rootsToInspect.add(it) }
        }
        rootInActiveWindow?.let { rootsToInspect.add(it) }
        latestEventSource?.let { rootsToInspect.add(AccessibilityNodeInfo.obtain(it)) }

        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var secondBestScore = Double.NEGATIVE_INFINITY
        val seenCandidates = mutableSetOf<String>()
        
        for (rootNode in rootsToInspect) {
            if (rootNode.packageName?.toString() != expectedPackage) { rootNode.recycle(); continue }
            val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
            queue.add(rootNode)
            
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                
                val nodeText = extractTextRecursively(node)
                val textScore = step.selectorText?.let { if (nodeText.isBlank()) 0.0 else SelectorTextMatcher.score(it, nodeText) } ?: 0.0
                val textMatch = step.selectorText?.let { textScore >= selectorThreshold(it) } ?: false
                val descScore = step.selectorDesc?.let {
                    val actual = node.contentDescription?.toString().orEmpty()
                    if (actual.isBlank()) 0.0 else SelectorTextMatcher.score(it, actual)
                } ?: 0.0
                val descMatch = step.selectorDesc?.let { descScore >= selectorThreshold(it) } ?: false
                val idMatch = step.selectorViewId?.let { node.viewIdResourceName == it } ?: false
                val classMatch = step.selectorClassName?.let { node.className?.toString() == it } ?: false
                val usable = when (step.actionType) {
                    ActionType.TYPE -> node.isEditable
                    ActionType.CLICK -> true
                    else -> false
                }
                val primaryMatch = textMatch || descMatch || idMatch
                if (primaryMatch && usable) {
                    val contextScore = step.selectorContext?.let { bestAncestorContextScore(node, it) } ?: 0.0
                    val contextMatch = step.selectorContext == null || contextScore >= 0.68
                    if (!contextMatch) {
                        for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
                        node.recycle()
                        continue
                    }
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    val measuredArea = rect.width().toLong() * rect.height().toLong()
                    // React Native can expose a valid virtual node with empty bounds. It is
                    // still eligible; prefer a bounded candidate when one also matches.
                    val boundedBonus = if (measuredArea > 0) 0.01 else 0.0
                    val spatialScore = if (step.selectorCenterX != null && step.selectorCenterY != null && !rect.isEmpty) {
                        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
                        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
                        val candidateX = rect.centerX() * 10_000 / width
                        val candidateY = rect.centerY() * 10_000 / height
                        SpatialSelector.score(step.selectorCenterX, step.selectorCenterY, candidateX, candidateY)
                    } else 0.0
                    val score = (textScore * 0.45) + (descScore * 0.2) + (contextScore * 0.45) +
                        (spatialScore * 1.0) + (if (idMatch) 0.35 else 0.0) +
                        (if (classMatch) 0.05 else 0.0) + boundedBonus
                    val key = "${rect.flattenToString()}|${node.className}|${node.viewIdResourceName}|${SelectorTextMatcher.canonical(nodeText)}|${step.selectorContext?.let { bestAncestorContextKey(node) }.orEmpty()}"
                    if (seenCandidates.add(key) && score > bestScore) {
                        bestNode?.recycle()
                        secondBestScore = bestScore
                        bestScore = score
                        bestNode = AccessibilityNodeInfo.obtain(node)
                    } else if (seenCandidates.contains(key) && score > secondBestScore && score < bestScore) {
                        secondBestScore = score
                    }
                }
                
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) queue.add(child)
                }
                node.recycle()
            }
        }

        if (bestNode == null) return NodeLookup.Missing
        if (secondBestScore.isFinite() && bestScore - secondBestScore < 0.08) {
            bestNode.recycle()
            return NodeLookup.Ambiguous
        }

        if (!bestNode.isClickable) {
            var current: AccessibilityNodeInfo? = bestNode
            while (current != null && !current.isClickable) {
                val parent = current.parent
                current.recycle()
                current = parent
            }
            if (current != null) return NodeLookup.Found(current)
            return NodeLookup.Missing
        }

        return NodeLookup.Found(bestNode)
    }

    private fun selectorThreshold(expected: String): Double {
        val count = SelectorTextMatcher.canonical(expected).split(' ').count { it.isNotBlank() }
        return if (count <= 2) 0.9 else 0.68
    }

    @Suppress("DEPRECATION")
    private fun bestAncestorContextScore(node: AccessibilityNodeInfo, expected: String): Double {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        var best = 0.0
        while (current != null && depth++ < 6) {
            val actual = extractTextRecursively(current)
            val actualTokenCount = SelectorTextMatcher.canonical(actual).split(' ').count { it.isNotBlank() }
            val expectedTokenCount = SelectorTextMatcher.canonical(expected).split(' ').count { it.isNotBlank() }.coerceAtLeast(1)
            // Do not let a whole menu satisfy a local item-card selector merely because
            // the menu happens to contain the recorded item somewhere below it.
            if (actualTokenCount <= expectedTokenCount * 2 + 3) {
                best = maxOf(best, SelectorTextMatcher.contextScore(expected, actual))
            }
            val parent = current.parent
            current.recycle()
            current = parent
        }
        current?.recycle()
        return best
    }

    @Suppress("DEPRECATION")
    private fun bestAncestorContextKey(node: AccessibilityNodeInfo): String {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        val directTokens = SelectorTextMatcher.canonical(extractTextRecursively(node)).split(' ').filter { it.isNotBlank() }.toSet()
        while (current != null && depth++ < 5) {
            val value = SelectorTextMatcher.canonical(extractTextRecursively(current))
            val tokens = value.split(' ').filter { it.isNotBlank() }.toSet()
            val parent = current.parent
            current.recycle()
            if (value.length <= 600 && (tokens - directTokens).size >= 2) {
                parent?.recycle()
                return value
            }
            current = parent
        }
        current?.recycle()
        return ""
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
        banner = null
        voiceBanner = null
    }
    override fun onInterrupt() { gate.stop(); safetyStatus = "Interrupted; automation blocked" }
    private fun disconnect() {
        connected = false
        gate.stop()
        isRecording = false
        isReplaying = false
        awaitingTeachCommand = false
        isListening = false
        currentFlow = null
        recordedSteps.clear()
        handler.removeCallbacks(monitor)
        removeOverlay()
        tts?.shutdown()
        tts = null
        ttsReady = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        handler.removeCallbacks(speechTimeout)
        handler.removeCallbacks(finishSpeech)
        latestEventSource?.recycle()
        latestEventSource = null
        serviceJob.cancel()
    }
    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnect(); super.onDestroy() }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val root = rootInActiveWindow ?: return super.onKeyEvent(event)
            val snapshot = try { SnapshotBuilder.build(root) } finally { root.recycle() }
            if (snapshot != null) {
                val msg = "Captured ${snapshot.nodes.size} nodes"
                Log.d("VoiceMacroCapture", "Phase 2 Capture: Captured ${snapshot.nodes.size} nodes. Signature: ${snapshot.signature}")
                if (ttsReady) {
                    tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "capture")
                }
                
                // Show in the overlay
                handler.post {
                    safetyStatus = "Snapshot: ${snapshot.signature} (${snapshot.nodes.size} nodes)"
                    showOverlay(safetyStatus, false)
                }
            } else {
                Log.e("VoiceMacroCapture", "Phase 2 Capture failed: Snapshot returned null")
            }
            return true // Consume the key event
        }
        return super.onKeyEvent(event)
    }
}
