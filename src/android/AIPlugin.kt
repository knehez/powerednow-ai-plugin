package com.powerednow.ai

import org.apache.cordova.*
import org.json.JSONArray

class AIPlugin : CordovaPlugin() {

    private lateinit var stt: SpeechToTextManager
    private lateinit var tts: TextToSpeechManager
    private lateinit var llm: LlmManager

    override fun pluginInitialize() {
        stt = SpeechToTextManager(cordova)
        tts = TextToSpeechManager(cordova)
        llm = LlmManager(cordova)
    }

    override fun execute(action: String, args: JSONArray, callbackContext: CallbackContext): Boolean {
        return when (action) {
            "ask" -> {
                val question = args.optString(0, "").trim()
                llm.complete(question, callbackContext)
                true
            }
            "transcript" -> {
                stt.transcript(callbackContext)
                true
            }
            "speak" -> {
                val text = args.optString(0, "").trim()
                tts.speak(text, callbackContext)
                true
            }
            else -> false
        }
    }

    override fun onRequestPermissionResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionResult(requestCode, permissions, grantResults)
        stt.onRequestPermissionResult(requestCode, permissions, grantResults)
    }

    override fun onReset() {
        super.onReset()
        stt.onReset()
        tts.onReset()
        llm.onReset()
    }

    override fun onDestroy() {
        stt.onDestroy()
        tts.onDestroy()
        llm.onDestroy()
        super.onDestroy()
    }
}
