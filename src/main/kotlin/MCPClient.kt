package org.example

import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.*
import kotlin.collections.map
import kotlin.text.contains
import kotlin.text.lowercase
import kotlin.text.substringAfterLast


class MCPClient : AutoCloseable {
    private val mcp: Client = Client(clientInfo = Implementation(name = "mcp-client-cli", version = "1.0.0"))

    // List of tools offered by the server
    private lateinit var tools: List<Tool>
    
    private var yandexGPTClient: YandexGPTClient? = null

    suspend fun connectToServer(serverScriptPath: String, onConnected: suspend () -> Unit) {
        try {
            // Build the command based on the file extension of the server script
            val command = buildList {
                when (serverScriptPath.substringAfterLast(".")) {
                    "js" -> add("node")
                    "py" -> add(
                        if (System.getProperty("os.name").lowercase().contains("win")) "python" else "python3"
                    )

                    "jar" -> addAll(listOf("java", "-jar"))
                    else -> throw kotlin.IllegalArgumentException("Server script must be a .js, .py or .jar file")
                }
                add(serverScriptPath)
            }

            // Start the server process
            val process = ProcessBuilder(command).start()

            // Setup I/O transport using the process streams
            val transport = StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered(),
            )

            // Connect the MCP client to the server using the transport
            mcp.connect(transport)

            // Request the list of available tools from the server
            val toolsResult = mcp.listTools()
            tools = toolsResult.tools
            println("Connected to server with tools: ${tools.map { it.name }}")
            onConnected()

        } catch (e: Exception) {
            println("Failed to connect to MCP server: $e")
            throw e
        }
    }

    suspend fun getMessagesAndCreateSummary(
        yandexGPTClient: YandexGPTClient,
        chatId: Long = 408840411
    ) {
        this.yandexGPTClient = yandexGPTClient
        
        // Создаем схему функции get_messages для YandexGPT
        val getMessagesTool = createGetMessagesTool()
        println("getMessagesTool: $getMessagesTool")
        
        // Создаем промпт для YandexGPT
        val userMessage = YandexGPTMessage(
            role = "user",
            text = "Вызови функцию get_messages для получения сообщений из диалога с chat_id=$chatId"
        )
        
        // Вызываем YandexGPT с функцией
        val response = yandexGPTClient.callWithTools(
            messages = listOf(userMessage),
            tools = listOf(getMessagesTool)
        )
        println("RESPONSE: $response")

        // Обрабатываем ответ от YandexGPT
        val alternative = response.result.alternatives.firstOrNull()
        val toolCalls = alternative?.message?.toolCalls
        
        if (toolCalls != null && toolCalls.isNotEmpty()) {
            // YandexGPT хочет вызвать функцию
            val toolCall = toolCalls.first()
            val functionName = toolCall.function.name
            val argumentsJson = Json.parseToJsonElement(toolCall.function.arguments).jsonObject
            
            if (functionName == "get_messages") {
                // Вызываем реальную функцию через MCP
                val chatIdParam = argumentsJson["chat_id"]?.jsonPrimitive?.long ?: chatId
                val page = argumentsJson["page"]?.jsonPrimitive?.int ?: 1
                val pageSize = argumentsJson["page_size"]?.jsonPrimitive?.int ?: 20
                
                println("Вызываю get_messages через MCP с параметрами: chat_id=$chatIdParam, page=$page, page_size=$pageSize")
                
                val mcpResult = mcp.callTool(
                    "get_messages",
                    mapOf(
                        "chat_id" to chatIdParam,
                        "page" to page,
                        "page_size" to pageSize
                    )
                )
                
                // Получаем текст сообщений из результата
                val messagesText = extractMessagesText(mcpResult)
                println("Получены сообщения:\n$messagesText")
                
                // Создаем саммари через YandexGPT
                println("\nСоздаю саммари диалога...")
                val summary = yandexGPTClient.generateSummary(messagesText)
                
                println("\n=== РЕЗЮМЕ ДИАЛОГА ===")
                println(summary)
                println("=====================\n")
            }
        } else {
            // YandexGPT не вызвал функцию, возможно вернул текст
            val text = alternative?.message?.text
            if (text != null) {
                println("YandexGPT ответил: $text")
            } else {
                println("Не удалось получить ответ от YandexGPT")
            }
        }
    }
    
    private fun extractMessagesText(mcpResult: io.modelcontextprotocol.kotlin.sdk.CallToolResultBase?): String {
        if (mcpResult == null) {
            return "Не удалось получить сообщения"
        }
        
        // Пытаемся извлечь текст из content
        val contentText = (mcpResult.content.firstOrNull() as? TextContent)?.text
        println("contentText: $contentText")
        if (contentText != null) {
            // Пытаемся распарсить JSON, если это JSON
            try {
                val json = Json.parseToJsonElement(contentText)
                if (json is JsonObject) {
                    // Если это JSON объект, пытаемся извлечь поле "result"
                    val result = json["result"]
                    if (result != null) {
                        // Если result - это строка
                        if (result is JsonPrimitive && result.isString) {
                            return result.content
                        }
                        // Если result - это объект, пытаемся преобразовать в строку
                        if (result is JsonObject) {
                            return Json.encodeToString(result)
                        }
                    }
                    // Если нет поля result, возвращаем весь JSON как строку
                    return Json.encodeToString(json)
                }
            } catch (e: Exception) {
                println("parser error: ${e.message}")
                // Если не JSON, возвращаем как есть
            }
            return contentText
        }
        
        return "Не удалось получить сообщения"
    }
    
    private fun createGetMessagesTool(): YandexGPTTool {
        val parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("chat_id") {
                    put("type", "integer")
                    put("description", "ID чата для получения сообщений")
                }
                putJsonObject("page") {
                    put("type", "integer")
                    put("description", "Номер страницы (по умолчанию 1)")
                    put("default", 1)
                }
                putJsonObject("page_size") {
                    put("type", "integer")
                    put("description", "Размер страницы (по умолчанию 20)")
                    put("default", 20)
                }
            }
            putJsonArray("required") {
                add("chat_id")
            }
        }
        
        return YandexGPTTool(
            type = "function",
            function = YandexGPTFunction(
                name = "get_messages",
                description = "Получить сообщения из Telegram диалога по chat_id",
                parameters = parameters
            )
        )
    }

    override fun close() {
        runBlocking {
            yandexGPTClient?.close()
            mcp.close()
        }
    }
}

