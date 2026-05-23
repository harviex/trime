package com.osfans.trime.ime.ai

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 调用 AI 润色 API 的服务
 * 支持两种后端：
 * 1. OpenRouter (默认) — 免费模型，不限流
 * 2. 自定义 API — 兼容 {text, language} -> {polished_text} 格式
 */
class AiPolishService(
    private val scope: CoroutineScope,
    private var apiUrl: String = DEFAULT_OPENROUTER_URL
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed class PolishResult {
        data class Success(
            val originalText: String,
            val polishedText: String,
            val confidence: Float = 1.0f
        ) : PolishResult()
        data class Error(val message: String, val isNetworkError: Boolean = false) : PolishResult()
        object EmptyInput : PolishResult()
    }

    private val _results = MutableSharedFlow<PolishResult>(replay = 0)
    val results = _results.asSharedFlow()

    /** 同步调用润色 API */
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

    /** 判断是否为 OpenRouter URL */
    private fun isOpenRouterUrl(url: String): Boolean {
        return url.contains("openrouter.ai") || url.contains("api.cesia.cc")
    }

    /** 调用 OpenRouter API，支持429重试和备用模型 */
    private fun polishWithOpenRouter(text: String): PolishResult {
        val apiKey = getOpenRouterApiKey()
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
                // 429 限流时换下一个模型
                if (result.message.contains("429") || result.message.contains("rate limit")) {
                    Log.w(TAG, "模型 $model 被限流，切换下一个")
                    continue
                }
                // 其他错误也尝试下一个模型
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

        // 根据文本长度动态设置 max_tokens，长文本需要更多 token
        val maxTokens = (text.length * 2).coerceIn(512, 2048)

        val json = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.3)
            put("max_tokens", maxTokens)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://github.com/harviex/trime")
            .addHeader("X-Title", "Trime")
            .build()

        Log.d(TAG, "OpenRouter 请求 [$model]: ${text.take(50)}...")

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "未知错误"
                    Log.e(TAG, "OpenRouter 错误 [$model]: ${response.code} - $errorBody")
                    return PolishResult.Error("API 错误(${response.code}): ${errorBody.take(200)}")
                }

                val respBody = response.body?.string()
                    ?: return PolishResult.Error("响应为空")

                Log.d(TAG, "OpenRouter 响应 [$model]: ${respBody.take(200)}")

                val respJson = JSONObject(respBody)
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

                // 尝试其他格式
                val content = respJson.optString("content", "")
                if (content.isNotEmpty()) {
                    return PolishResult.Success(text, content)
                }

                PolishResult.Error("OpenRouter 返回格式异常: ${respBody.take(200)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenRouter 异常 [$model]", e)
            PolishResult.Error("网络错误: ${e.message ?: "未知"}", isNetworkError = true)
        }
    }

    /** 调用自定义 API（兼容旧格式） */
    private fun polishWithCustomApi(text: String): PolishResult {
        val json = JSONObject().apply {
            put("text", text)
            put("language", "zh")
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Trime/1.0")
            .build()

        Log.d(TAG, "自定义 API 请求: $apiUrl, 文本长度: ${text.length}")

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "未知错误"
                Log.e(TAG, "API 请求失败: ${response.code} - $errorBody")
                return PolishResult.Error(
                    "API 错误(${response.code}): ${errorBody.take(200)}",
                    isNetworkError = response.code in 400..599
                )
            }

            val respBody = response.body?.string()
                ?: return PolishResult.Error("响应为空")

            val result = parsePolishResponse(respBody)
            Log.d(TAG, "润色结果: $result")
            return result
        }
    }

    /** 解析自定义 API 响应 */
    private fun parsePolishResponse(body: String): PolishResult {
        return try {
            val json = JSONObject(body)

            // 格式1: { "polished_text": "..." }
            if (json.has("polished_text")) {
                val polished = json.getString("polished_text")
                val original = json.optString("original", "")
                val confidence = json.optDouble("confidence", 1.0).toFloat()

                if (isPlaceholder(polished)) {
                    return PolishResult.Error("API 返回空结果 (placeholder)")
                }

                PolishResult.Success(original, polished, confidence)
            }
            // 格式2: { "result": "..." }
            else if (json.has("result")) {
                val result = json.getString("result")
                if (isPlaceholder(result)) {
                    return PolishResult.Error("API 返回空结果 (placeholder)")
                }
                PolishResult.Success(body, result)
            }
            // 格式3: { "data": { "text": "..." } }
            else if (json.has("data")) {
                val data = json.getJSONObject("data")
                val text = data.optString("text", data.optString("polished", ""))
                if (isPlaceholder(text)) {
                    return PolishResult.Error("API 返回空结果 (placeholder)")
                }
                PolishResult.Success(body, text)
            }
            // 格式4: 直接是字符串
            else {
                return PolishResult.Error("API 返回格式不可解析")
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

    /** 获取 OpenRouter API Key */
    private fun getOpenRouterApiKey(): String? {
        return _apiKey
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
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
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
        val apiKey = getOpenRouterApiKey() ?: return null

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

        // 魔法模式也支持多模型重试，prompt 包含原文需要更大 token
        val maxTokens = (prompt.length * 2).coerceIn(512, 4096)
        val models = listOf(_modelId, OPENROUTER_MODEL_FALLBACK)
        for (model in models.distinct()) {
            val json = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", 0.3)
                put("max_tokens", maxTokens)
            }

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(apiUrl)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("HTTP-Referer", "https://github.com/harviex/trime")
                .addHeader("X-Title", "Trime")
                .build()

            try {
                val result = client.newCall(request).execute()
                if (result.isSuccessful) {
                    val respBody = result.body?.string() ?: continue
                    val respJson = JSONObject(respBody)
                    val choices = respJson.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        return choices.getJSONObject(0).getJSONObject("message").getString("content").trim()
                    }
                } else {
                    Log.w(TAG, "魔法模型 $model HTTP ${result.code}: ${result.body?.string()?.take(100)}")
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
        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(apiUrl)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val jsonResp = JSONObject(body)
            return jsonResp.optString("polished_text", "")
        }
    }

    companion object {
        private const val TAG = "AiPolishService"
        const val DEFAULT_OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val OPENROUTER_MODEL = "google/gemma-4-26b-a4b-it:free"
        const val OPENROUTER_MODEL_FALLBACK = "mistralai/mistral-7b-instruct:free"
        const val DEFAULT_CUSTOM_URL = "https://typeless-ai-service.vercel.app/api/polish"
    }
}
