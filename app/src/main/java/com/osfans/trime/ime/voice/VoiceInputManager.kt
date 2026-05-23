package com.osfans.trime.ime.voice

import android.content.Context
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 语音输入管理器
 *
 * 整合录音和语音识别流程：
 * - 优先使用 Android 内置 SpeechRecognizer（Google 语音识别）
 * - 支持连续监听模式，自动重启
 * - 提供统一的回调接口
 */
class VoiceInputManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "VoiceInputManager"
    }

    // ─── 回调接口 ──────────────────────────────────────
    interface VoiceRecognitionListener {
        fun onResult(text: String)
        fun onError(error: String)
        fun onPartialResult(text: String)
    }

    // ─── 子组件 ────────────────────────────────────────
    private val audioRecorder = AudioRecorder(scope)
    private val fallbackRecognizer = FallbackRecognizer(context)

    // ─── 状态 ──────────────────────────────────────────
    private var listener: VoiceRecognitionListener? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    private var useSpeechRecognizer = true // 优先使用 SpeechRecognizer

    // ─── 初始化 ────────────────────────────────────────
    fun init(): Boolean {
        val available = fallbackRecognizer.isAvailable()
        Log.d(TAG, "SpeechRecognizer available: $available")
        if (available) {
            fallbackRecognizer.init()
        }
        return available
    }

    // ─── 监听器设置 ────────────────────────────────────
    fun setListener(listener: VoiceRecognitionListener) {
        this.listener = listener
    }

    // ─── 开始录音 ──────────────────────────────────────
    fun startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording, ignoring startRecording()")
            return
        }

        isRecording = true

        if (useSpeechRecognizer && fallbackRecognizer.isAvailable()) {
            // 优先使用 Android SpeechRecognizer
            startSpeechRecognizerRecording()
        } else {
            // Fallback: 使用 AudioRecorder 录音
            startAudioRecorderRecording()
        }
    }

    // ─── 停止录音 ──────────────────────────────────────
    fun stopRecording() {
        if (!isRecording) {
            Log.w(TAG, "Not recording, ignoring stopRecording()")
            return
        }

        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        if (useSpeechRecognizer && fallbackRecognizer.isAvailable()) {
            fallbackRecognizer.stopListening()
        } else {
            audioRecorder.stop()
        }
    }

    // ─── 识别音频数据 ──────────────────────────────────
    /**
     * 对录制的音频数据进行离线识别（Vosk/Whisper 等）
     * 当 SpeechRecognizer 不可用时使用此方法
     */
    fun recognize(audioData: ByteArray) {
        // TODO: 集成 Vosk 或 Whisper 离线识别引擎
        // 当前版本：通过回调返回提示
        Log.d(TAG, "recognize() called with ${audioData.size} bytes")
        scope.launch(Dispatchers.Main) {
            listener?.onError("离线语音识别引擎未集成，请使用 Google 语音识别")
        }
    }

    // ─── 是否正在录音 ──────────────────────────────────
    fun isRecording(): Boolean = isRecording

    // ─── 销毁 ──────────────────────────────────────────
    fun destroy() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        audioRecorder.stop()
        fallbackRecognizer.destroy()
    }

    // ═══════════════════════════════════════════════════
    //  内部方法
    // ═══════════════════════════════════════════════════

    /**
     * 使用 Android SpeechRecognizer 进行识别
     */
    private fun startSpeechRecognizerRecording() {
        Log.d(TAG, "Starting SpeechRecognizer recording")

        // 收集 FallbackRecognizer 的结果
        scope.launch {
            fallbackRecognizer.results.collect { result ->
                when (result) {
                    is FallbackRecognizer.Result.Success -> {
                        Log.d(TAG, "SpeechRecognizer success: ${result.text}")
                        listener?.onResult(result.text)
                    }
                    is FallbackRecognizer.Result.Partial -> {
                        Log.d(TAG, "SpeechRecognizer partial: ${result.text}")
                        listener?.onPartialResult(result.text)
                    }
                    is FallbackRecognizer.Result.Error -> {
                        Log.e(TAG, "SpeechRecognizer error: ${result.message}")
                        listener?.onError(result.message)
                    }
                    is FallbackRecognizer.Result.Recognizing -> {
                        // 中间状态，可忽略或显示 loading
                        Log.d(TAG, "SpeechRecognizer recognizing...")
                    }
                    is FallbackRecognizer.Result.NoMatch -> {
                        Log.d(TAG, "SpeechRecognizer no match")
                        listener?.onError("未识别到语音")
                    }
                }
            }
        }

        val started = fallbackRecognizer.startListening()
        if (!started) {
            Log.w(TAG, "SpeechRecognizer start failed, falling back to AudioRecorder")
            useSpeechRecognizer = false
            startAudioRecorderRecording()
        }
    }

    /**
     * 使用 AudioRecorder 录音（Fallback 模式）
     */
    private fun startAudioRecorderRecording() {
        Log.d(TAG, "Starting AudioRecorder recording")

        recordingJob = scope.launch {
            try {
                // 收集录音事件
                launch {
                    audioRecorder.events.collect { event ->
                        when (event) {
                            is AudioRecorder.RecorderEvent.Started -> {
                                Log.d(TAG, "AudioRecorder started")
                            }
                            is AudioRecorder.RecorderEvent.Stopped -> {
                                Log.d(TAG, "AudioRecorder stopped")
                            }
                            is AudioRecorder.RecorderEvent.VadSilence -> {
                                Log.d(TAG, "AudioRecorder VAD silence detected")
                                // VAD 检测到静音，自动停止并识别
                                val wavData = audioRecorder.pcmToWav(emptyList())
                                recognize(wavData)
                            }
                            is AudioRecorder.RecorderEvent.RmsChanged -> {
                                // 可用于 UI 音量指示
                            }
                            is AudioRecorder.RecorderEvent.AudioData -> {
                                // 实时音频数据
                            }
                            is AudioRecorder.RecorderEvent.Error -> {
                                Log.e(TAG, "AudioRecorder error: ${event.message}")
                                listener?.onError(event.message)
                            }
                        }
                    }
                }

                // 开始 VAD 录音
                audioRecorder.startRecordingWithVad().collect { pcmChunks ->
                    Log.d(TAG, "AudioRecorder got ${pcmChunks.size} chunks")
                    if (pcmChunks.isNotEmpty()) {
                        val wavData = audioRecorder.pcmToWav(pcmChunks)
                        recognize(wavData)
                    } else {
                        listener?.onError("未录制到音频")
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "AudioRecorder cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecorder error", e)
                listener?.onError("录音失败: ${e.message}")
            } finally {
                isRecording = false
            }
        }
    }
}
