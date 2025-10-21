package com.powerednow.ai

import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaInterface

import android.util.Log
import android.content.Context
import java.io.File

import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.ai.edge.localagents.fc.*
import com.google.ai.edge.localagents.core.proto.Content
import com.google.ai.edge.localagents.core.proto.Part

class LlmManager(private val cordova: CordovaInterface) {

    companion object {
        private const val TAG = "LlmManager"
        private const val DEFAULT_SYSTEM_PROMPT = "You are a function-calling AI assistant."
        private const val MODEL_FILENAME = "gemma3-1b-it-int4.task"
    }

    private val appContext: Context?
        get() = cordova.activity?.applicationContext

    private var llm: LlmInference? = null
    private var model: GenerativeModel? = null
    private var chat: ChatSession? = null

    init {
        Log.d(TAG, "Initializing LLM Manager.")
        val ctx = appContext
        if (ctx != null) {
            tryInit(ctx)
        } else {
            Log.w(TAG, "No Activity yet; will initialize lazily on first use.")
        }
    }

    private fun tryInit(ctx: Context) {
        try {
            // Model path
            val filesDir = ctx.getExternalFilesDir(null)
            Log.d(TAG, "App external files dir: $filesDir")
            val modelPath = File(filesDir, MODEL_FILENAME).absolutePath
            Log.i(TAG, "Using model path: $modelPath")

            // LLM backend init
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(2048)
                .build()
            llm = LlmInference.createFromOptions(ctx, options)

            // Formatter
            val formatter = GemmaFormatter()

            // System instruction
            val systemInstruction = Content.newBuilder()
                .setRole("system")
                .addParts(Part.newBuilder().setText(DEFAULT_SYSTEM_PROMPT))
                .build()

            // Backend → GenerativeModel
            val backend = LlmInferenceBackend(llm!!, formatter)
            model = GenerativeModel(backend, systemInstruction)
            chat = model!!.startChat()

            Log.i(TAG, "LLM initialized successfully.")
        } catch (t: Throwable) {
            Log.e(TAG, "LLM initialization failed", t)
        }
    }

    private fun ensureInitialized(): Boolean {
        if (model != null) return true
        val ctx = appContext ?: run {
            Log.w(TAG, "ensureInitialized(): no Activity yet.")
            return false
        }
        tryInit(ctx)
        return model != null
    }

    fun complete(question: String, callback: CallbackContext) {
        if (question.isBlank()) {
            callback.error("Question cannot be empty.")
            return
        }

        ensureInitialized()

        val resp = chat!!.sendMessage(question)
        val message = resp.candidatesList[0].content.partsList[0].text

        Log.d(TAG, "Prompt: $question")
        Log.d(TAG, "Response: $message")

        cordova.activity?.runOnUiThread {
            callback.success(message)
        } ?: callback.error("Activity unavailable.")
    }

    fun onReset()  { Log.d(TAG, "onReset()") }
    fun onDestroy(){ Log.d(TAG, "onDestroy()") }
}
