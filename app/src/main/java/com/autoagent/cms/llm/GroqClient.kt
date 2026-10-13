package com.autoagent.cms.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class GroqClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
) : LlmClient {
    override val provider = LlmProvider.GROQ

    override suspend fun generate(apiKey: String, request: LlmRequest): LlmResponse =
        withContext(Dispatchers.IO) {
            val messages = buildJsonArray {
                addJsonObject {
                    put("role", "system")
                    put("content", request.systemPrompt)
                }
                request.messages.forEach { message ->
                    addJsonObject {
                        put("role", message.role)
                        put("content", message.content)
                    }
                }
            }
            val body = buildJsonObject {
                put("model", request.model)
                put("messages", messages)
                put("temperature", request.temperature)
                put("max_tokens", request.maxTokens.coerceIn(1, 600))
                if (request.jsonMode) {
                    put("response_format", buildJsonObject {
                        put("type", "json_object")
                    })
                }
            }.toString()

            val httpRequest = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = try {
                http.newCall(httpRequest).execute()
            } catch (e: IOException) {
                throw LlmFailure.Network("Groq network request failed.", e)
            }

            response.use { res ->
                val responseBody = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val retryMillis = res.header("Retry-After")?.toLongOrNull()?.times(1000)
                    val safeMessage = "Groq request failed with HTTP ${res.code}."
                    throw when (res.code) {
                        401, 403 -> LlmFailure.Unauthorized(safeMessage)
                        429 -> LlmFailure.RateLimited(retryMillis, safeMessage)
                        in 500..599 -> LlmFailure.Server(safeMessage)
                        else -> LlmFailure.InvalidResponse(safeMessage, res.code)
                    }
                }
                val json = try {
                    Json.parseToJsonElement(responseBody).jsonObject
                } catch (_: Exception) {
                    throw LlmFailure.InvalidResponse("Groq returned invalid JSON.")
                }
                val choice = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: throw LlmFailure.InvalidResponse("Groq returned no choices.")
                val content = choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                    ?: throw LlmFailure.InvalidResponse("Groq returned no message.")
                val usage = json["usage"]?.jsonObject
                LlmResponse(
                    provider = provider,
                    model = request.model,
                    content = content,
                    inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull,
                    outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull
                )
            }
        }
}
