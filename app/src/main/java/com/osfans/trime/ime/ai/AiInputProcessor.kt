// SPDX-FileCopyrightText: 2024 Harvie
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.ai

import android.content.Context
import android.content.SharedPreferences
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.voice.FallbackRecognizer
import com.osfans.trime.ime.voice.VoiceInputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Cesia 风格的 AI 输入处理器
 * 整合语音识别、AI 润色、魔法修改、历史记录
 */
class AiInputProcessor(
    private val context: Context,
    private val service: TrimeInputMethodService
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val prefs: SharedPreferences get() = context.getSharedPreferences("cesia_ai_prefs", Context.MODE_PRIVATE)

    // 语音组件
    lateinit var voiceManager: VoiceInputManager
    lateinit var polishService: AiPolishService

    // 状态回调
    var onLogMessage: ((String) -> Unit)? = null
    var onResultCommitted: (() -> Unit)? = null
    var onRecognitionComplete: ((String) -> Unit)? = null

    // 魔法模式
    var magicMode = false
    var magicOriginalText = ""

    // 历史记录（最近10条）
    private val sentMessages = mutableListOf<String>()
    private val maxSentMessages = 10

    fun init() {
        val apiKey = prefs.getString("openrouter_api_key", "") ?: ""
        val modelId = prefs.getString("model_id", AiPolishService.OPENROUTER_MODEL) ?: AiPolishService.OPENROUTER_MODEL

        polishService = AiPolishService(scope)
        if (apiKey.isNotEmpty()) {
            polishService.updateApiKey(apiKey)
        }
        polishService.updateModelId(modelId)

        voiceManager = VoiceInputManager(context, scope)
    }

    /** 开始语音输入 */
    fun startVoiceInput(aiMode: Boolean? = null) {
        scope.launch {
            onLogMessage?.invoke(if (aiMode == true) "🎤 语音+润色模式" else "🎤 语音输入模式")
            voiceManager.startRecording(aiMode, object : VoiceInputManager.VoiceRecognitionListener {
                override fun onResult(text: String) {
                    if (aiMode == true && text.isNotEmpty()) {
                        polishRecognizedText(text)
                    } else {
                        commitText(text)
                        onResultCommitted?.invoke()
                    }
                }

                override fun onPartialResult(text: String) {
                    onLogMessage?.invoke("📝 $text")
                }

                override fun onError(error: String) {
                    onLogMessage?.invoke("⚠️ $error")
                    onResultCommitted?.invoke()
                }
            })
        }
    }

    /** 停止语音输入 */
    fun stopVoiceInput() {
        voiceManager.stopRecording()
    }

    /** 润色文字 */
    fun polishRecognizedText(text: String) {
        onLogMessage?.invoke("✨ 正在施展魔法...")
        scope.launch {
            when (val result = polishService.polishText(text)) {
                is AiPolishService.PolishResult.Success -> {
                    commitText(result.polishedText)
                    addToHistory(result.polishedText)
                    onResultCommitted?.invoke()
                }
                is AiPolishService.PolishResult.Error -> {
                    // fallback: 直接上屏原文字
                    onLogMessage?.invoke("⚠️ 润色失败: ${result.message}，直接上屏")
                    commitText(text)
                    onResultCommitted?.invoke()
                }
                AiPolishService.PolishResult.EmptyInput -> {
                    onLogMessage?.invoke("⚠️ 输入为空")
                    onResultCommitted?.invoke()
                }
            }
        }
    }

    /** 魔法修改 */
    fun magicRewrite(prompt: String) {
        onLogMessage?.invoke("🪄 魔法修改...")
        scope.launch {
            val result = polishService.polishWithPrompt(prompt)
            if (result.isNullOrEmpty()) {
                onLogMessage?.invoke("⚠️ 魔法修改失败")
            } else {
                commitText(result)
                addToHistory(result)
            }
            onResultCommitted?.invoke()
        }
    }

    /** 添加到历史记录 */
    fun addToHistory(text: String) {
        if (text.isBlank()) return
        synchronized(sentMessages) {
            sentMessages.remove(text) // 去重
            sentMessages.add(0, text)
            while (sentMessages.size > maxSentMessages) {
                sentMessages.removeAt(sentMessages.lastIndex)
            }
        }
        saveHistory()
    }

    /** 获取历史记录 */
    fun getHistory(): List<String> {
        return synchronized(sentMessages) { sentMessages.toList() }
    }

    private fun saveHistory() {
        prefs.edit()
            .putStringSet("history", sentMessages.toSet())
            .putInt("_history_order", sentMessages.size) // placeholder for ordering
            .apply()
        // 保持顺序用逗号分隔
        val joined = sentMessages.joinToString("\u001F") // Unit Separator
        prefs.edit().putString("history_ordered", joined).apply()
    }

    private fun loadHistory() {
        val joined = prefs.getString("history_ordered", "") ?: ""
        if (joined.isNotEmpty()) {
            synchronized(sentMessages) {
                sentMessages.clear()
                sentMessages.addAll(joined.split("\u001F").take(maxSentMessages))
            }
        }
    }

    /** 上屏文字 */
    fun commitText(text: String) {
        service.currentInputConnection?.commitText(text, 1)
        addToHistory(text)
    }

    fun destroy() {
        voiceManager.destroy()
        polishService.shutdown()
    }
}
