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

class GeminiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
) : LlmClient {
    override val provider = LlmProvider.GEMINI

    override suspend fun generate(apiKey: String, request: LlmRequest): LlmResponse =
        withContext(Dispatchers.IO) {
            val contents = buildJsonArray {
                request.messages.forEach { message ->
                    addJsonObject {
                        put("role", if (message.role == "assistant") "model" else "user")
                        putJsonArray("parts") {
                            addJsonObject {
                                put("text", message.content)
                            }
                        }
                    }
                }
            }
            val body = buildJsonObject {
                put("systemInstruction", buildJsonObject {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", request.systemPrompt)
                        }
                    }
                })
                put("contents", contents)
                put("generationConfig", buildJsonObject {
                    put("temperature", request.temperature)
                    put("maxOutputTokens", request.maxTokens.coerceIn(1, 8192))
                    if (request.jsonMode) put("responseMimeType", "application/json")
                })
            }.toString()

            val url = "https://generativelanguage.googleapis.com/v1beta/" +
                "models/${request.model}:generateContent"
            val httpRequest = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = try {
                http.newCall(httpRequest).execute()
            } catch (e: IOException) {
                throw LlmFailure.Network("Gemini network request failed.", e)
            }

            response.use { res ->
                val responseBody = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw mapFailure(res.code, res.header("Retry-After"), responseBody)
                }
                val json = try {
                    Json.parseToJsonElement(responseBody).jsonObject
                } catch (e: Exception) {
                    throw LlmFailure.InvalidResponse("Gemini returned invalid JSON.")
                }
                val candidate = json["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: throw LlmFailure.InvalidResponse("Gemini returned no candidate.")
                val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
                    ?: throw LlmFailure.InvalidResponse("Gemini returned no content.")
                val text = parts.mapNotNull {
                    it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                }.joinToString("\n")
                if (text.isBlank()) {
                    throw LlmFailure.InvalidResponse("Gemini returned empty content.")
                }
                val usage = json["usageMetadata"]?.jsonObject
                LlmResponse(
                    provider = provider,
                    model = request.model,
                    content = text,
                    inputTokens = usage?.get("promptTokenCount")?.jsonPrimitive?.intOrNull,
                    outputTokens = usage?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull
                )
            }
        }

    private fun mapFailure(code: Int, retryAfter: String?, body: String): LlmFailure {
        val retryMillis = retryAfter?.toLongOrNull()?.times(1000)
        val safeMessage = "Gemini request failed with HTTP $code."
        return when (code) {
            401, 403 -> LlmFailure.Unauthorized(safeMessage)
            429 -> LlmFailure.RateLimited(retryMillis, safeMessage)
            in 500..599 -> LlmFailure.Server(safeMessage)
            else -> LlmFailure.InvalidResponse(safeMessage)
        }
    }
}
