package com.osfans.trime.ime.voice

import android.content.Context
import android.speech.SpeechRecognizer
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 语音识别器（Android SpeechRecognizer）
 *
 * 连续监听模式：点击按钮后持续听，直到用户再次点击才停止。
 * 积累所有识别结果，停止时一次性发送到 API。
 */
class FallbackRecognizer(private val context: Context) {

    /** 当前激活的识别器实例 */
    private var recognizer: SpeechRecognizer? = null

    /** 识别结果流 */
    private val _results = MutableSharedFlow<Result>(replay = 0, extraBufferCapacity = 1)
    val results = _results.asSharedFlow()

    sealed class Result {
        data class Success(val text: String, val confidence: Float = 1.0f) : Result()
        data class Partial(val text: String) : Result()
        data class Error(val message: String) : Result()
        data class Recognizing(val text: String) : Result()
        object NoMatch : Result()
    }

    // ─── 状态 ──────────────────────────────────────────
    private var _isListening = false              // 用户意图：是否应该持续监听
    private var activeInstance: SpeechRecognizer? = null  // 当前活跃的识别实例
    private var restartJob: Job? = null           // 追踪重启协程，避免与 stop 竞态
    var continuousMode: Boolean = false           // 连续积累模式
    var suppressNoMatchError: Boolean = true      // 静音时不报错

    private val accumulatedText = StringBuilder()  // 连续模式下积累的文本
    private var lastPartialText: String = ""        // 最近一次部分结果（fallback when accumulatedText is empty）

    // ─── 创建新实例 + 设置监听器 ─────────────────────
    fun createInstance(): Boolean {
        return try {
            // 旧实例彻底销毁
            recognizer?.destroy()
            recognizer = null

            val r = SpeechRecognizer.createSpeechRecognizer(context)
            r.setRecognitionListener(object : android.speech.RecognitionListener {
                private val scope = CoroutineScope(Dispatchers.Main + Job())

                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d("FR", "onReadyForSpeech")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d("FR", "onEndOfSpeech")
                    scope.launch { _results.emit(Result.Recognizing("正在识别...")) }
                }

                override fun onError(error: Int) {
                    Log.d("FR", "onError code=$error")
                    handleError(error)
                }

                override fun onResults(bundle: Bundle?) {
                    val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    Log.d("FR", "onResults matches=${matches?.size ?: 0}")
                    if (!matches.isNullOrEmpty()) {
                        val best = matches[0]
                        if (continuousMode) {
                            if (accumulatedText.isNotEmpty()) accumulatedText.append(" ")
                            accumulatedText.append(best)
                            scope.launch { _results.emit(Result.Partial(accumulatedText.toString())) }
                            if (activeInstance === recognizer) scheduleRestart()
                        } else {
                            val confidence = bundle.getFloatArray(
                                SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: 1.0f
                            scope.launch { _results.emit(Result.Success(best, confidence)) }
                        }
                    } else {
                        // 无结果（静音超时等），自动重启
                        if (_isListening && activeInstance === recognizer) scheduleRestart()
                    }
                }

                override fun onPartialResults(bundle: Bundle?) {
                    val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty() && continuousMode) {
                        lastPartialText = matches[0]
                        scope.launch { _results.emit(Result.Partial(matches[0])) }
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizer = r
            activeInstance = r
            true
        } catch (e: Exception) {
            Log.e("FR", "createInstance failed", e)
            false
        }
    }

    // ─── 初始化 ────────────────────────────────────────
    fun init(): Boolean = createInstance()

    // ─── 开始监听 ──────────────────────────────────────
    fun startListening(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return false

        if (recognizer == null) {
            if (!createInstance()) return false
        }

        if (continuousMode) accumulatedText.clear()
        _isListening = true
        restartJob?.cancel()

        return try {
            val intent = createIntent()
            recognizer?.startListening(intent)
            Log.d("FR", "startListening OK, continuous=$continuousMode")
            true
        } catch (e: Exception) {
            Log.e("FR", "startListening exception", e)
            // 实例不可用，销毁重建再试
            if (createInstance()) {
                try {
                    recognizer?.startListening(createIntent())
                    return true
                } catch (e2: Exception) {
                    Log.e("FR", "recreate startListening also failed", e2)
                }
            }
            false
        }
    }

    // ─── 停止监听（用户主动停止） ──────────────────────
    fun stopListening() {
        restartJob?.cancel()
        restartJob = null
        _isListening = false

        try { recognizer?.stopListening() } catch (_: Exception) {}

        // 连续模式：发送积累的文本（即使为空也要发送，避免 UI 卡住）
        if (continuousMode) {
            val text = if (accumulatedText.isNotEmpty()) accumulatedText.toString() else lastPartialText
            accumulatedText.clear()
            lastPartialText = ""
            Log.d("FR", "stopListening → emit '${text.length}' chars")
            CoroutineScope(Dispatchers.Main).launch {
                if (text.isNotEmpty()) {
                    _results.emit(Result.Success(text))
                } else {
                    // 无积累文本时，发送空结果让 UI 知道已停止
                    _results.emit(Result.NoMatch)
                }
            }
        }
    }

    // ─── 销毁 ──────────────────────────────────────────
    fun destroy() {
        restartJob?.cancel()
        restartJob = null
        _isListening = false
        recognizer?.destroy()
        recognizer = null
        activeInstance = null
        accumulatedText.clear()
        continuousMode = false
    }

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    // ═══════════════════════════════════════════════════
    //  内部方法
    // ═══════════════════════════════════════════════════

    private fun createIntent(): Intent = Intent(
        android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH
    ).apply {
        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // 静音超时拉到 60 秒：说话中间停顿不会断
        putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_MILLIS", 60000L)
        putExtra("android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_MILLIS", 60000L)
        putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 5000L)
    }

    /** 静默超时后自动重启监听（销毁 → 创建 → 监听） */
    private fun scheduleRestart() {
        if (!_isListening) return
        restartJob?.cancel()
        restartJob = CoroutineScope(Dispatchers.Main + Job()).launch {
            // 销毁旧实例
            recognizer?.destroy()
            recognizer = null

            // 等待资源释放
            delay(500)

            // 检查是否已被 stopListening 取消
            if (!_isListening) return@launch

            if (createInstance()) {
                delay(200)
                if (_isListening) {
                    try {
                        recognizer?.startListening(createIntent())
                    } catch (e: Exception) {
                        Log.e("FR", "restart startListening failed", e)
                    }
                }
            }
        }
    }

    private fun handleError(error: Int) {
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                // 静音超时，不算错误
                Log.d("FR", "No match/timeout → auto restart")
                if (_isListening && activeInstance === recognizer) scheduleRestart()
            }
            SpeechRecognizer.ERROR_CLIENT -> {
                // 客户端内部错误，延迟后重建
                Log.d("FR", "ERROR_CLIENT → rebuild")
                if (_isListening) {
                    restartJob?.cancel()
                    restartJob = CoroutineScope(Dispatchers.Main + Job()).launch {
                        delay(1000)
                        if (_isListening) {
                            if (createInstance()) {
                                delay(300)
                                if (_isListening) {
                                    try { recognizer?.startListening(createIntent()) }
                                    catch (e: Exception) { Log.e("FR", "retry failed", e) }
                                }
                            }
                        }
                    }
                }
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                // 识别器忙，延长延迟
                if (_isListening) {
                    restartJob?.cancel()
                    restartJob = CoroutineScope(Dispatchers.Main + Job()).launch {
                        delay(2000)
                        if (_isListening) {
                            if (createInstance()) {
                                delay(300)
                                if (_isListening) {
                                    try { recognizer?.startListening(createIntent()) }
                                    catch (e: Exception) { Log.e("FR", "retry failed", e) }
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                if (!suppressNoMatchError) {
                    CoroutineScope(Dispatchers.Main).launch {
                        _results.emit(Result.Error("识别错误"))
                    }
                }
            }
        }
    }
}
