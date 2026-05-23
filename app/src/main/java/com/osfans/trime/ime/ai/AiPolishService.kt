package com.osfans.trime.ime.ai

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 调用 AI 润色 API 的服务
 * 使用 HttpURLConnection（不依赖 OkHttp）
 * 支持 OpenRouter + 多模型 fallback + 429 重试
 */
class AiPolishService(
    private val scope: CoroutineScope,
    private var apiUrl: String = DEFAULT_OPENROUTER_URL
) {
    sealed class PolishResult {
        data class Success(
            val originalText: String,
            val polishedText: String,
            val confidence: Float = 1.0f
        ) : PolishResult()
        data class Error(val message: String, val isNetworkError: Boolean = false) : PolishResult()
        object EmptyInput : PolishResult()
    }

    suspend fun polishText(text: String): PolishResult = withContext(Dispatchers.IO) {
        if (text.isBlank() || text.length < 2) {
            return@withContext PolishResult.EmptyInput
        }
        try {
            if (isOpenRouterUrl(apiUrl)) {
                polishWithOpenRouter(text)
            } else {
                polishWithCustomApi(text)
            }
        } catch (e: IOException) {
            Log.e(TAG, "网络错误", e)
            PolishResult.Error("网络连接失败: ${e.message ?: "未知"}", isNetworkError = true)
        } catch (e: Exception) {
            Log.e(TAG, "处理错误", e)
            PolishResult.Error("处理失败: ${e.message ?: "未知"}")
        }
    }

    private fun isOpenRouterUrl(url: String): Boolean {
        return url.contains("openrouter.ai") || url.contains("api.cesia.cc")
    }

    private fun polishWithOpenRouter(text: String): PolishResult {
        val apiKey = _apiKey
        if (apiKey.isNullOrEmpty()) {
            return PolishResult.Error("OpenRouter API Key 未配置")
        }

        val systemPrompt = "你是一个文本润色与输入排版高手。请将输入的口语文字处理为通顺的书面文字，并严格执行以下规则：\n严禁删减核心信息，严禁随意扩写。仅修正错别字、口语和语序，加入标点。只输出润色排版后的纯文本。禁止解释，禁止添加任何前缀（如\"润色后：\"）或后缀。如果用户输入的内容包含多个观点、步骤或长篇大论，请自动通过\"换行分段\"或使用\"* \"进行分点陈列。"

        val models = listOf(_modelId, OPENROUTER_MODEL_FALLBACK)
        var lastError = ""

        for (model in models.distinct()) {
            val result = tryOpenRouterModel(text, systemPrompt, model, apiKey)
            if (result is PolishResult.Success) return result
            if (result is PolishResult.Error) {
                lastError = result.message
                if (result.message.contains("429") || result.message.contains("rate limit")) {
                    Log.w(TAG, "模型 $model 被限流，切换下一个")
                    continue
                }
                continue
            }
        }
        return PolishResult.Error("所有模型均失败: $lastError")
    }

    private fun tryOpenRouterModel(text: String, systemPrompt: String, model: String, apiKey: String): PolishResult {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", text)
            })
        }

        val maxTokens = (text.length * 2).coerceIn(512, 2048)

        val json = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.3)
            put("max_tokens", maxTokens)
        }

        Log.d(TAG, "OpenRouter 请求 [$model]: ${text.take(50)}...")

        return try {
            val response = httpPost(apiUrl, json.toString(), mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $apiKey",
                "HTTP-Referer" to "https://github.com/harviex/trime",
                "X-Title" to "Trime"
            ))

            if (response.first !in 200..299) {
                Log.e(TAG, "OpenRouter 错误 [$model]: ${response.first} - ${response.second}")
                return PolishResult.Error("API 错误(${response.first}): ${response.second.take(200)}")
            }

            Log.d(TAG, "OpenRouter 响应 [$model]: ${response.second.take(200)}")

            val respJson = JSONObject(response.second)
            val choices = respJson.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val content = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                if (content.isNotEmpty()) {
                    return PolishResult.Success(text, content)
                }
            }

            val content = respJson.optString("content", "")
            if (content.isNotEmpty()) {
                return PolishResult.Success(text, content)
            }

            PolishResult.Error("OpenRouter 返回格式异常: ${response.second.take(200)}")
        } catch (e: Exception) {
            Log.e(TAG, "OpenRouter 异常 [$model]", e)
            PolishResult.Error("网络错误: ${e.message ?: "未知"}", isNetworkError = true)
        }
    }

    private fun polishWithCustomApi(text: String): PolishResult {
        val json = JSONObject().apply {
            put("text", text)
            put("language", "zh")
        }

        Log.d(TAG, "自定义 API 请求: $apiUrl, 文本长度: ${text.length}")

        val response = httpPost(apiUrl, json.toString(), mapOf(
            "Content-Type" to "application/json",
            "User-Agent" to "Trime/1.0"
        ))

        if (response.first !in 200..299) {
            Log.e(TAG, "API 请求失败: ${response.first} - ${response.second}")
            return PolishResult.Error(
                "API 错误(${response.first}): ${response.second.take(200)}",
                isNetworkError = response.first in 400..599
            )
        }

        val result = parsePolishResponse(response.second)
        Log.d(TAG, "润色结果: $result")
        return result
    }

    private fun parsePolishResponse(body: String): PolishResult {
        return try {
            val json = JSONObject(body)
            if (json.has("polished_text")) {
                val polished = json.getString("polished_text")
                val original = json.optString("original", "")
                val confidence = json.optDouble("confidence", 1.0).toFloat()
                if (isPlaceholder(polished)) {
                    return PolishResult.Error("API 返回空结果 (placeholder)")
                }
                PolishResult.Success(original, polished, confidence)
            } else if (json.has("result")) {
                val result = json.getString("result")
                if (isPlaceholder(result)) {
                    return PolishResult.Error("API 返回空结果 (placeholder)")
                }
                PolishResult.Success(body, result)
            } else if (json.has("data")) {
                val data = json.getJSONObject("data")
                val text = data.optString("text", data.optString("polished", ""))
                if (isPlaceholder(text)) {
                    return PolishResult.Error("API 返回空结果 (placeholder)")
                }
                PolishResult.Success(body, text)
            } else {
                PolishResult.Error("API 返回格式不可解析")
            }
        } catch (e: Exception) {
            val trimmed = body.trim()
            if (isPlaceholder(trimmed)) {
                return PolishResult.Error("API 返回空结果 (placeholder)")
            }
            PolishResult.Success("", trimmed)
        }
    }

    private fun isPlaceholder(text: String): Boolean {
        val t = text.trim().lowercase()
        return t == "polished_text" ||
                t == "(polished_text)" ||
                t == "<polished_text>" ||
                t == "text" ||
                t == "..." ||
                t.isEmpty()
    }

    /** 魔法模式：使用自定义 prompt 调用 API */
    fun polishWithPrompt(prompt: String): String? {
        return try {
            if (isOpenRouterUrl(apiUrl)) {
                polishWithPromptOpenRouter(prompt)
            } else {
                polishWithPromptCustom(prompt)
            }
        } catch (e: Exception) {
            Log.e(TAG, "魔法模式异常", e)
            null
        }
    }

    private fun polishWithPromptOpenRouter(prompt: String): String? {
        val apiKey = _apiKey ?: return null

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", "你是一个文本编辑助手。根据用户指令修改原文。只输出修改后的文本，不要解释。")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val maxTokens = (prompt.length * 2).coerceIn(512, 4096)
        val models = listOf(_modelId, OPENROUTER_MODEL_FALLBACK)

        for (model in models.distinct()) {
            val json = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", 0.3)
                put("max_tokens", maxTokens)
            }

            try {
                val response = httpPost(apiUrl, json.toString(), mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $apiKey",
                    "HTTP-Referer" to "https://github.com/harviex/trime",
                    "X-Title" to "Trime"
                ))

                if (response.first in 200..299) {
                    val respJson = JSONObject(response.second)
                    val choices = respJson.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        return choices.getJSONObject(0).getJSONObject("message").getString("content").trim()
                    }
                } else {
                    Log.w(TAG, "魔法模型 $model HTTP ${response.first}: ${response.second.take(100)}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "魔法模型 $model 异常: ${e.message}")
            }
        }
        Log.e(TAG, "魔法修改所有模型均失败")
        return null
    }

    private fun polishWithPromptCustom(prompt: String): String? {
        val json = JSONObject().apply {
            put("text", prompt)
            put("language", "zh")
        }

        val response = httpPost(apiUrl, json.toString(), mapOf(
            "Content-Type" to "application/json"
        ))

        if (response.first !in 200..299) return null
        val jsonResp = JSONObject(response.second)
        return jsonResp.optString("polished_text", "")
    }

    /** HTTP POST 工具方法 */
    private fun httpPost(urlStr: String, body: String, headers: Map<String, String>): Pair<Int, String> {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        conn.doOutput = true
        conn.doInput = true

        headers.forEach { (key, value) ->
            conn.setRequestProperty(key, value)
        }

        // 写入请求体
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(body)
            writer.flush()
        }

        // 读取响应
        val responseCode = conn.responseCode
        val responseBody = try {
            if (responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            } else {
                BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: Exception) {
            ""
        }

        conn.disconnect()
        return Pair(responseCode, responseBody)
    }

    private var _apiKey: String? = null
    private var _modelId: String = OPENROUTER_MODEL

    fun updateApiKey(key: String) {
        _apiKey = key.trim()
        Log.d(TAG, "OpenRouter API Key 已更新")
    }

    fun updateModelId(model: String) {
        _modelId = model.trim()
        Log.d(TAG, "模型已更新为: $_modelId")
    }

    fun updateApiUrl(newUrl: String) {
        apiUrl = newUrl.trim()
        Log.d(TAG, "API URL 更新为: $apiUrl")
    }

    fun getApiUrl(): String = apiUrl

    fun shutdown() {
        // HttpURLConnection 不需要手动关闭连接池
    }

    companion object {
        private const val TAG = "AiPolishService"
        const val DEFAULT_OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val OPENROUTER_MODEL = "google/gemma-4-26b-a4b-it:free"
        const val OPENROUTER_MODEL_FALLBACK = "mistralai/mistral-7b-instruct:free"
        const val DEFAULT_CUSTOM_URL = "https://typeless-ai-service.vercel.app/api/polish"
    }
}
