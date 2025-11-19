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
import org.example.Keys.USER_ID
import kotlin.collections.map
import kotlin.text.contains
import kotlin.text.lowercase
import kotlin.text.substringAfterLast
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


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
            println("[MCP] Запрашиваю список доступных инструментов...")
            val toolsResult = mcp.listTools()
            tools = toolsResult.tools
            println("[MCP] Подключено к серверу с инструментами: ${tools.map { it.name }}")
            onConnected()

        } catch (e: Exception) {
            println("Failed to connect to MCP server: $e")
            throw e
        }
    }

    suspend fun getMessagesAndCreateSummary(
        yandexGPTClient: YandexGPTClient,
        chatId: Long = USER_ID
    ): String? {
        this.yandexGPTClient = yandexGPTClient
        
        // Шаг 1: Создаем схему функции get_messages для YandexGPT
        println("[YandexGPT] Создаю схему функции get_messages...")
        val getMessagesTool = createGetMessagesTool()
        
        // Шаг 2: Создаем промпт для YandexGPT
        val userMessage = YandexGPTMessage(
            role = "user",
            text = "Давай возьмем последние несколько сообщений из диалога с chat_id=$chatId, " +
                    "а затем сделай из них summary, с рассказом о том, что обсуждалось в диалоге, сохранив ключевые темы, вопросы, решения и важные детали. Учти, диалог приходит в порядке убывания," +
                    " тебе нужно отсортировать его по хронологии. Ориентируйся на \"Date\""
        )
        
        // Шаг 3: Вызываем YandexGPT с функцией
        println("[YandexGPT] Запрашиваю вызов функции get_messages через YandexGPT...")
        val response = yandexGPTClient.callWithTools(
            messages = listOf(userMessage),
            tools = listOf(getMessagesTool)
        )

        // Шаг 4: Обрабатываем ответ от YandexGPT
        val alternative = response.result.alternatives.firstOrNull()
        val toolCallList = alternative?.message?.toolCallList
        println()
        println()
        println("-----------------------------------------------------------------")
        println()
        println()
        println("[YandexGPT] Обрабатываю ответ. Статус: ${alternative?.status}")
        
        if (toolCallList != null && toolCallList.toolCalls.isNotEmpty()) {
            // YandexGPT хочет вызвать функцию
            println("[YandexGPT] Обнаружен вызов функции в ответе")
            val toolCall = toolCallList.toolCalls.first()
            val functionCall = toolCall.functionCall
            val functionName = functionCall.name
            val argumentsJson = functionCall.arguments // Это уже JsonObject, не строка!
            
            println("[YandexGPT] Функция: $functionName, аргументы: ${Json.encodeToString(argumentsJson)}")
            
            if (functionName == "get_messages") {
                // Шаг 5: Вызываем реальную функцию через MCP
                val chatIdParam = argumentsJson["chat_id"]?.jsonPrimitive?.long ?: chatId
                val page = argumentsJson["page"]?.jsonPrimitive?.int ?: 1
                val pageSize = argumentsJson["page_size"]?.jsonPrimitive?.int ?: 8
                
                println("[MCP] Вызываю get_messages с параметрами: chat_id=$chatIdParam, page=$page, page_size=$pageSize")
                
                val mcpResult = mcp.callTool(
                    "get_messages",
                    mapOf(
                        "chat_id" to chatIdParam,
                        "page" to page,
                        "page_size" to pageSize
                    )
                )
                println("[MCP] Получен ответ от get_messages")
                
                // Получаем текст сообщений из результата
                val messagesText = extractMessagesText(mcpResult)
                println("Получены сообщения:\n$messagesText")
                println()
                println()
                println("-----------------------------------------------------------------")
                println()
                println()
                
                // Шаг 6: Отправляем результат функции обратно в YandexGPT
                // Создаем сообщение с результатом функции и промптом для создания саммари
                val summaryPrompt = "Создай подробное резюме диалога, сохранив ключевые темы, вопросы, решения и важные детали.\n\nРезюме должно быть на русском языке, информативным, кратким."
                
                val toolResultMessage = YandexGPTMessage(
                    role = "user",
//                    text = summaryPrompt,
                    toolResultList = YandexGPTToolResultList(
                        toolResults = listOf(
                            YandexGPTToolResult(
                                functionResult = YandexGPTFunctionResult(
                                    name = functionName,
                                    content = messagesText
                                )
                            )
                        )
                    )
                )
                
                // Шаг 7: Отправляем обновленный список сообщений для получения финального ответа (саммари)
                println("\nСоздаю саммари диалога...")
                val finalResponse = yandexGPTClient.continueWithToolResult(
                    messages = listOf(userMessage, alternative.message, toolResultMessage)
                )
                println()
                println()
                println("-----------------------------------------------------------------")
                println()
                println()
                
                // Шаг 8: Получаем финальный ответ (саммари)
                val finalAlternative = finalResponse.result.alternatives.firstOrNull()
                val finalText = finalAlternative?.message?.text
                
                val summaryText = if (finalText != null) {
                    println("\n=== РЕЗЮМЕ ДИАЛОГА ===")
                    println(finalText)
                    println("=====================\n")
                    finalText
                } else {
                    // Если финальный ответ не получен, создаем саммари напрямую через generateSummary
                    println("Не удалось получить саммари через function calling, создаю напрямую...")
                    val summary = yandexGPTClient.generateSummary(messagesText)
                    
                    println("\n=== РЕЗЮМЕ ДИАЛОГА ===")
                    println(summary)
                    println("=====================\n")
                    summary
                }
                
                // Сохраняем суммари в файл
                saveSummaryToFile(summaryText)
                return summaryText
            } else return null
        } else {
            // YandexGPT не вызвал функцию, возможно вернул текст
            val text = alternative?.message?.text
            return if (text != null) {
                println("YandexGPT ответил: $text")
                saveSummaryToFile(text)
                text
            } else {
                println("Не удалось получить ответ от YandexGPT. Статус: ${alternative?.status}")
                null
            }
        }
    }
    
    private fun saveSummaryToFile(summary: String) {
        try {
            val resourcesDir = File("resources")
            if (!resourcesDir.exists()) {
                resourcesDir.mkdirs()
                println("[FILE] Создана директория: ${resourcesDir.absolutePath}")
            }
            
            val dateTime = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            val dateTimeFormatted = dateTime.format(formatter)
            val dateTimeHeader = dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            
            val fileName = "summary_$dateTimeFormatted.txt"
            val file = File(resourcesDir, fileName)
            
            val content = buildString {
                appendLine("Summary диалога от: $dateTimeHeader")
                appendLine()
                appendLine(summary)
            }
            
            file.writeText(content, Charsets.UTF_8)
            println("[FILE] Суммари сохранено в файл: ${file.absolutePath}")
        } catch (e: Exception) {
            println("[FILE] Ошибка при сохранении суммари в файл: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun extractMessagesText(mcpResult: io.modelcontextprotocol.kotlin.sdk.CallToolResultBase?): String {
        if (mcpResult == null) {
            return "Не удалось получить сообщения"
        }
        
        // Пытаемся извлечь текст из content
        val contentText = (mcpResult.content.firstOrNull() as? TextContent)?.text
        println("[MCP] Извлечен текст из ответа (длина: ${contentText?.length ?: 0} символов)")
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
                    put("description", "Размер страницы (по умолчанию 8)")
                    put("default", 8)
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

