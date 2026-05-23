package com.osfans.trime.ime.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream

/**
 * 音频录制器，支持 VAD（语音活动检测）
 * 录制完成后通过 Flow 回调返回 PCM 数据和 WAV 字节
 */
class AudioRecorder(
    private val scope: CoroutineScope
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 4 // 缓冲区倍数
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null

    // 最小静音帧数（大约 0.8 秒静音后停止）
    private val minSilenceFrames = 16 // ~1s at 20ms/frame
    private val frameSize = SAMPLE_RATE / 50 // 20ms frame

    // 事件流：状态变化、音频数据、RMS 音量
    private val _events = MutableSharedFlow<RecorderEvent>(replay = 0)
    val events = _events.asSharedFlow()

    sealed class RecorderEvent {
        data class RmsChanged(val rms: Float) : RecorderEvent()
        data class AudioData(val pcmData: ByteArray) : RecorderEvent()
        object Started : RecorderEvent()
        object Stopped : RecorderEvent()
        data class Error(val message: String) : RecorderEvent()
        object VadSilence : RecorderEvent()
    }

    /**
     * 开始录制并自动检测语音结束点 (VAD)
     * 返回录制到的全部 PCM 数据
     */
    fun startRecordingWithVad(): Flow<List<ByteArray>> = flow {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR
        val minBufferSize = if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            16000
        } else {
            bufferSize
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufferSize * 2
        ).also { recorder ->
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                scope.launch { _events.emit(RecorderEvent.Error("AudioRecord 初始化失败")) }
                return@flow
            }
        }

        isRecording = true
        audioRecord?.startRecording()
        scope.launch { _events.emit(RecorderEvent.Started) }

        val allChunks = mutableListOf<ByteArray>()
        val buffer = ShortArray(frameSize)
        var silenceCount = 0
        var speechDetected = false
        val silenceThreshold = 500 // 静音阈值

        while (isRecording) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (read <= 0) continue

            val pcmBytes = ShortArray(read).apply { System.arraycopy(buffer, 0, this, 0, read) }
            val byteData = ShortArrayToByteArray(pcmBytes)

            // 计算 RMS
            val rms = calculateRms(pcmBytes, read)
            scope.launch { _events.emit(RecorderEvent.RmsChanged(rms)) }

            // VAD 逻辑
            if (rms > silenceThreshold) {
                speechDetected = true
                silenceCount = 0
                allChunks.add(byteData)
            } else if (speechDetected) {
                silenceCount++
                allChunks.add(byteData) // 保留静音帧前后衔接
                if (silenceCount >= minSilenceFrames) {
                    scope.launch { _events.emit(RecorderEvent.VadSilence) }
                    break
                }
            } else {
                // 还没有检测到语音，丢弃静音数据
            }
        }

        if (allChunks.isNotEmpty()) {
            emit(allChunks)
        }
    }

    fun stop() {
        isRecording = false
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        scope.launch { _events.emit(RecorderEvent.Stopped) }
    }

    fun isRecording(): Boolean = isRecording

    /**
     * 将 PCM 转为 WAV 格式
     */
    fun pcmToWav(pcmData: List<ByteArray>): ByteArray {
        val totalSize = pcmData.sumOf { it.size }
        val wavHeader = createWavHeader(totalSize)
        val output = ByteArrayOutputStream()
        output.write(wavHeader)
        pcmData.forEach { output.write(it) }
        return output.toByteArray()
    }

    private fun createWavHeader(dataSize: Int): ByteArray {
        val header = ByteArray(44)
        // RIFF
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        // 文件大小 - 8
        val fileSize = dataSize + 36
        header[4] = (fileSize and 0xff).toByte()
        header[5] = ((fileSize shr 8) and 0xff).toByte()
        header[6] = ((fileSize shr 16) and 0xff).toByte()
        header[7] = ((fileSize shr 24) and 0xff).toByte()
        // WAVE
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // fmt
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        // fmt chunk size = 16
        header[16] = 16
        // PCM format
        header[18] = 1
        // channels = 1
        header[22] = 1
        // sample rate = 16000
        header[24] = (SAMPLE_RATE and 0xff).toByte()
        header[25] = ((SAMPLE_RATE shr 8) and 0xff).toByte()
        header[26] = ((SAMPLE_RATE shr 16) and 0xff).toByte()
        header[27] = ((SAMPLE_RATE shr 24) and 0xff).toByte()
        // byte rate
        val byteRate = SAMPLE_RATE * 2
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        // block align = 2
        header[32] = 2
        // bits per sample = 16
        header[34] = 16
        // data chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        // data size
        header[40] = (dataSize and 0xff).toByte()
        header[41] = ((dataSize shr 8) and 0xff).toByte()
        header[42] = ((dataSize shr 16) and 0xff).toByte()
        header[43] = ((dataSize shr 24) and 0xff).toByte()
        return header
    }

    private fun ShortArrayToByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0x00FF).toByte()
            bytes[i * 2 + 1] = ((shorts[i].toInt() and 0xFF00) shr 8).toByte()
        }
        return bytes
    }

    private fun calculateRms(buffer: ShortArray, readSize: Int): Float {
        var sum = 0.0
        for (i in 0 until readSize) {
            sum += buffer[i] * buffer[i]
        }
        return kotlin.math.sqrt(sum / readSize).toFloat()
    }
}
