package com.osfans.trime.ime.ai

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.widget.Toast
import com.osfans.trime.ime.voice.FallbackRecognizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import android.util.Log

/**
 * Typeless 引擎 —— 核心编排层
 *
 * 工作流程（纯手动按钮模式）:
 * 1. 用户按麦克风按钮 → SpeechRecognizer.startListening()
 * 2. 系统 VAD 检测静音结束 → onResults 回调
 * 3. 文本发送到润色 API
 * 4. 润色结果自动上屏 (commitText)
 */
class TypelessEngine(
    private val context: Context,
    private val service: InputMethodService
) {
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var polishService: AiPolishService? = null
    private var fallbackRecognizer: FallbackRecognizer? = null

    // 引擎状态
    private var _state = MutableStateFlow(State.IDLE)
    val state = _state.asStateFlow()

    enum class State {
        IDLE,          // 空闲
        LISTENING,     // 正在录音/识别
        PROCESSING,    // 正在润色
        ERROR          // 出错
    }

    // 日志回调
    var onLogMessage: ((String) -> Unit)? = null

    // 魔法模式回调（识别到指令后触发）
    var onMagicResult: ((String) -> Unit)? = null

    // 润色完成回调（用于统计）
    var onPolishComplete: ((String, String, Long) -> Unit)? = null

    // 识别结果开始处理回调
    var onResultProcessing: (() -> Unit)? = null

    // 润色结果已上屏回调
    var onResultCommitted: (() -> Unit)? = null

    // 是否处于魔法模式
    var magicMode: Boolean = false

    // 是否跳过润色（直接上屏识别结果）
    var skipPolish: Boolean = false

    // 识别完成回调（新流程：识别完成后等待用户选择 AI+/AI×）
    var onRecognitionComplete: ((String) -> Unit)? = null

    init {
        // 启动识别结果监听协程
        engineScope.launch {
            while (fallbackRecognizer == null) delay(50)

            fallbackRecognizer?.results?.collect { result ->
                when (result) {
                    is FallbackRecognizer.Result.Success -> {
                        log("📝 识别成功：${result.text.take(50)}")
                        if (magicMode) {
                            // 魔法模式：触发回调，不润色上屏
                            onMagicResult?.invoke(result.text)
                        } else {
                            // 新流程：识别完成，通知 UI 等待用户选择
                            val text = result.text
                            withContext(Dispatchers.Main) {
                                onRecognitionComplete?.invoke(text)
                            }
                        }
                    }
                    is FallbackRecognizer.Result.Partial -> {
                        // 实时部分结果，显示在状态栏
                        log("🎤 ${result.text}")
                    }
                    is FallbackRecognizer.Result.Recognizing -> {
                        // 静默，不显示正在识别状态
                    }
                    is FallbackRecognizer.Result.Error -> {
                        log("❌ ${result.message}")
                        _state.value = State.ERROR
                    }
                    is FallbackRecognizer.Result.NoMatch -> {
                        if (magicMode) {
                            // 魔法模式：无匹配也要触发回调，避免 UI 卡住
                            onMagicResult?.invoke("")
                        } else {
                            // 普通模式：通知 UI 识别结束（即使是空结果）
                            withContext(Dispatchers.Main) {
                                onRecognitionComplete?.invoke("")
                            }
                        }
                    }
                }
            }
        }
    }

    /** 初始化引擎 */
    fun initialize(apiKey: String? = null) {
        polishService = AiPolishService(engineScope)
        if (!apiKey.isNullOrEmpty()) {
            polishService?.updateApiKey(apiKey)
        }
        fallbackRecognizer = FallbackRecognizer(context)

        if (fallbackRecognizer?.init() == true) {
            log("✅ Trime AI 引擎已就绪")
        } else {
            log("❌ Trime AI 引擎初始化失败")
        }
    }

    /** 开始监听 */
    fun startListening(continuous: Boolean = false) {
        fallbackRecognizer?.continuousMode = continuous
        if (fallbackRecognizer?.startListening() == true) {
            _state.value = State.LISTENING
        } else {
            _state.value = State.ERROR
            showError("录音启动失败")
        }
    }

    /** 停止监听 */
    fun stopListening() {
        fallbackRecognizer?.stopListening()
        _state.value = State.IDLE
    }

    /** 发送文本润色并提交 */
    private fun polishAndCommit(text: String) {
        engineScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    _state.value = State.PROCESSING
                    onResultProcessing?.invoke()  // 通知开始处理
                }
                log("🔄 正在润色... (${text.length}字)")

                val polishResult = polishService?.polishText(text)

                val finalText = when (polishResult) {
                    is AiPolishService.PolishResult.Success -> {
                        val cleaned = cleanPolishedText(polishResult.polishedText)
                        if (isPlaceholder(cleaned)) {
                            log("⚠️ API 返回占位符，使用原文")
                            text
                        } else {
                            log("✨ 润色完成: ${cleaned.take(50)}")
                            cleaned
                        }
                    }
                    is AiPolishService.PolishResult.Error -> {
                        log("⚠️ 润色失败: ${polishResult.message}，使用原文")
                        text
                    }
                    is AiPolishService.PolishResult.EmptyInput -> {
                        log("🗑️ 输入为空，跳过")
                        ""
                    }
                    null -> {
                        log("❌ 服务不可用，使用原文")
                        text
                    }
                }

                if (finalText.isNotEmpty()) {
                    // 触发润色完成回调（用于统计）
                    if (finalText != text) {
                        onPolishComplete?.invoke(text, finalText, 0L)
                    }
                    commitText(finalText)
                    // 通知润色结果已上屏
                    withContext(Dispatchers.Main) {
                        onResultCommitted?.invoke()
                    }
                }
                withContext(Dispatchers.Main) { _state.value = State.IDLE }
            } catch (e: Exception) {
                log("❌ 润色异常: ${e.message}")
                try { commitText(text) } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    _state.value = State.ERROR
                    onResultCommitted?.invoke()  // 异常时也要重置状态
                }
            }
        }
    }

    private fun cleanPolishedText(raw: String): String {
        return raw.trim().removePrefix("```").removeSuffix("```").trim()
    }

    private fun isPlaceholder(text: String): Boolean {
        val t = text.trim().lowercase()
        return t == "polished_text" || t == "(polished_text)" || t == "<polished_text>" || t.isEmpty()
    }

    /** 提交文本到输入框 */
    private suspend fun commitText(text: String) {
        val ic = service.currentInputConnection ?: run {
            log("❌ 无输入框连接")
            return
        }
        withContext(Dispatchers.Main) {
            ic.commitText(text, 1)
            log("✅ 已上屏: ${text.take(50)}")
            _state.value = State.IDLE
        }
    }

    private fun showToast(msg: String) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {}
    }

    private fun showError(msg: String) {
        log("❌ $msg")
        showToast(msg)
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLogMessage?.invoke(msg)
    }

    /** 获取 AiPolishService（供魔法模式使用） */
    fun getPolishService(): AiPolishService? = polishService

    /** 润色文字并上屏（供外部调用，自动处理协程） */
    fun polishTextAsync(text: String, callback: (String) -> Unit) {
        engineScope.launch {
            try {
                val result = polishService?.polishText(text)
                val finalText = when (result) {
                    is AiPolishService.PolishResult.Success -> {
                        val cleaned = cleanPolishedText(result.polishedText)
                        if (cleaned.isNotEmpty()) cleaned else text
                    }
                    is AiPolishService.PolishResult.Error -> {
                        log("⚠️ 润色失败: ${result.message}，使用原文")
                        text
                    }
                    is AiPolishService.PolishResult.EmptyInput -> text
                    null -> {
                        log("⚠️ 润色服务不可用，使用原文")
                        text
                    }
                }
                // 最终保護：如果 finalText 为空，使用原文
                val safeText = finalText.ifEmpty { text }
                withContext(Dispatchers.Main) {
                    callback(safeText)
                }
            } catch (e: Exception) {
                log("❌ 润色异常: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback(text)
                }
            }
        }
    }

    /** 更新 API URL */
    fun updateApiUrl(url: String) {
        val normalized = normalizeApiUrl(url.trim())
        polishService?.updateApiUrl(normalized)
    }

    /**
     * 规范化 API URL
     * - 如果 URL 只有域名（如 https://api.cesia.cc），自动追加 /api/v1/chat/completions
     * - 如果 URL 已包含路径，保持原样
     */
    private fun normalizeApiUrl(url: String): String {
        if (url.isEmpty()) return DEFAULT_OPENROUTER_URL
        // 如果 URL 已经包含 /api/ 路径，直接返回
        if (url.contains("/api/")) return url
        // 如果 URL 以 / 结尾，追加 chat/completions
        if (url.endsWith("/")) return "${url}api/v1/chat/completions"
        // 否则追加完整路径
        return "$url/api/v1/chat/completions"
    }

    companion object {
        private const val TAG = "TypelessEngine"
        const val DEFAULT_OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
    }

    /** 更新模型 ID */
    fun updateModelId(model: String) { polishService?.updateModelId(model) }

    /** 销毁引擎 */
    fun destroy() {
        fallbackRecognizer?.destroy()
        fallbackRecognizer = null
        polishService?.shutdown()
        polishService = null
        engineScope.cancel()
        log("🔻 引擎已销毁")
    }
}
