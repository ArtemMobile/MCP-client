package org.example

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class YandexGPTTool(
    val type: String = "function",
    val function: YandexGPTFunction
)

@Serializable
data class YandexGPTFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class YandexGPTMessage(
    val role: String,
    val text: String? = null,
    val toolCalls: List<YandexGPTToolCall>? = null
)

@Serializable
data class YandexGPTToolCall(
    val id: String,
    val type: String = "function",
    val function: YandexGPTFunctionCall
)

@Serializable
data class YandexGPTFunctionCall(
    val name: String,
    val arguments: String
)

@Serializable
data class YandexGPTRequest(
    val modelUri: String,
    val completionOptions: CompletionOptions,
    val messages: List<YandexGPTMessage>,
    val tools: List<YandexGPTTool>? = null
)

@Serializable
data class CompletionOptions(
    val stream: Boolean = false,
    val temperature: Double = 0.6,
    val maxTokens: Int = 2000
)

@Serializable
data class YandexGPTResponse(
    val result: YandexGPTResult
)

@Serializable
data class YandexGPTResult(
    val alternatives: List<YandexGPTAlternative>
)

@Serializable
data class YandexGPTAlternative(
    val message: YandexGPTMessage,
    val status: String
)

class YandexGPTClient(
    private val iamToken: String,
    private val folderId: String
) {
    private val client = HttpClient(Java) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            })
        }
    }

    private val apiUrl = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"
    private val modelUri = "gpt://$folderId/yandexgpt-lite/latest"

    suspend fun callWithTools(
        messages: List<YandexGPTMessage>,
        tools: List<YandexGPTTool>
    ): YandexGPTResponse {
        val request = YandexGPTRequest(
            modelUri = modelUri,
            completionOptions = CompletionOptions(
                stream = false,
                temperature = 0.6,
                maxTokens = 2000
            ),
            messages = messages,
            tools = tools
        )

        val response = client.post(apiUrl) {
            header("Authorization", "Bearer $iamToken")
            header("x-folder-id", folderId)
            header("Content-Type", "application/json")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        println("response raw: ${response.body<String>()}")

        return response.body()
    }

    suspend fun generateSummary(dialogue: String): String {
        val request = YandexGPTRequest(
            modelUri = modelUri,
            completionOptions = CompletionOptions(
                stream = false,
                temperature = 0.6,
                maxTokens = 2000
            ),
            messages = listOf(
                YandexGPTMessage(
                    role = "user",
                    text = "Создай подробное резюме этого диалога, сохранив ключевые темы, вопросы, решения и важные детали.\n\nРезюме должно быть на русском языке, информативным.\n\n$dialogue"
                )
            )
        )

        val response = client.post(apiUrl) {
            header("Authorization", "Bearer $iamToken")
            header("x-folder-id", folderId)
            header("Content-Type", "application/json")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        val gptResponse: YandexGPTResponse = response.body()
        return gptResponse.result.alternatives.firstOrNull()?.message?.text
            ?: "Не удалось сгенерировать резюме."
    }

    fun close() {
        client.close()
    }
}

