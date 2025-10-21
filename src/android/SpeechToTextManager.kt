package com.powerednow.ai

import android.Manifest
import android.content.Intent
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaInterface
import org.json.JSONArray
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import java.util.Locale

class SpeechToTextManager(private val cordova: CordovaInterface) {

    companion object {
        private const val TAG = "SpeechToTextManager"
        private const val REQUEST_RECORD_AUDIO = 1001
        private const val RECORD_AUDIO = Manifest.permission.RECORD_AUDIO

        private const val SILENCE_TIMEOUT_MS = 1500L
        private const val HARD_TIMEOUT_MS    = 15000L
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognitionIntent: Intent? = null

    private var transcriptCallback: CallbackContext? = null
    private var hasFinished = false
    private var latestPartial: String? = null

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var silenceRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null

    fun transcript(callbackContext: CallbackContext) {
        Log.d(TAG, "transcript() called")

        if (transcriptCallback != null) {
            callbackContext.error("Recognition already in progress.")
            return
        }
        transcriptCallback = callbackContext
        hasFinished = false
        latestPartial = null

        if (!hasAudioPermission()) {
            requestAudioPermission()
            return
        }
        startRecognition()
    }

    private fun hasAudioPermission(): Boolean {
        val activity = cordova.activity ?: return false
        return ContextCompat.checkSelfPermission(activity, RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        val activity = cordova.activity ?: run {
            failOnce("Activity unavailable")
            return
        }
        ActivityCompat.requestPermissions(activity, arrayOf(RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    fun onRequestPermissionResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecognition()
            } else {
                failOnce("Microphone permission denied.")
            }
        }
    }

    private fun startRecognition() {
        val activity = cordova.activity ?: run {
            failOnce("Activity unavailable")
            return
        }
        Log.d(TAG, "startRecognition()")

        activity.runOnUiThread {
            try {
                cleanupRecognizer()

                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity).also { sr ->
                    sr.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) { Log.d(TAG, "onReadyForSpeech") }
                        override fun onBeginningOfSpeech() { Log.d(TAG, "onBeginningOfSpeech") }
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() { Log.d(TAG, "onEndOfSpeech") }

                        override fun onError(error: Int) {
                            Log.w(TAG, "onError: $error")
                            val partial = latestPartial?.trim().orEmpty()
                            if (partial.isNotEmpty()) successOnce(partial) else failOnce("Speech error: $error")
                        }

                        override fun onResults(results: Bundle) {
                            val list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = list?.firstOrNull()?.trim().orEmpty()
                            Log.d(TAG, "onResults: '$text'")
                            successOnce(text)
                        }

                        override fun onPartialResults(partialResults: Bundle) {
                            val list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = list?.firstOrNull()?.trim().orEmpty()
                            if (text.isNotEmpty()) {
                                latestPartial = text
                                Log.d(TAG, "onPartialResults: '$text'")
                                scheduleSilenceFinish()
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                }

                speechRecognizer?.startListening(recognitionIntent)

                // hard timeout
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                val runnable = Runnable {
                    Log.w(TAG, "hard timeout reached")
                    val text = latestPartial?.trim().orEmpty()
                    if (text.isNotEmpty()) successOnce(text) else failOnce("Timeout before final recognition.")
                }
                timeoutRunnable = runnable
                handler.postDelayed(runnable, HARD_TIMEOUT_MS)

            } catch (t: Throwable) {
                failOnce("Failed to start recognition: ${t.message}")
            }
        }
    }

    private fun scheduleSilenceFinish() {
        silenceRunnable?.let { handler.removeCallbacks(it) }
        silenceRunnable = Runnable {
            val text = latestPartial?.trim().orEmpty()
            Log.d(TAG, "silence timeout, finishing with partial: '$text'")
            successOnce(text)
        }.also { handler.postDelayed(it, SILENCE_TIMEOUT_MS) }
    }

    private fun cleanupRecognizer() {
        silenceRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        silenceRunnable = null
        timeoutRunnable = null

        try { speechRecognizer?.cancel() } catch (_: Throwable) {}
        try { speechRecognizer?.destroy() } catch (_: Throwable) {}
        speechRecognizer = null
    }

    private fun successOnce(text: String) {
        if (hasFinished) return
        hasFinished = true
        cleanupRecognizer()
        transcriptCallback?.success(text)
        transcriptCallback = null
    }

    private fun failOnce(message: String) {
        if (hasFinished) return
        hasFinished = true
        cleanupRecognizer()
        transcriptCallback?.error(message)
        transcriptCallback = null
    }

    fun onReset() {
        try { speechRecognizer?.stopListening() } catch (_: Throwable) {}
        cleanupRecognizer()
        transcriptCallback = null
        hasFinished = false
        latestPartial = null
    }

    fun onDestroy() {
        onReset()
    }
}
