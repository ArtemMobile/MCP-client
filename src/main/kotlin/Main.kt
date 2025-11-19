package org.example

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

fun main(vararg args: String): Unit = runBlocking {
    require(args.isNotEmpty()) {
        "Usage: java -jar libs/MCP-client-1.0-SNAPSHOT.jar <path_to_server_script>"
    }
    val serverPath = args[0]

    val yandexGPTClient = YandexGPTClient(
        iamToken = Keys.IAM_TOKEN,
        folderId = Keys.FOLDER_ID
    )

    val client = MCPClient()
    client.use {
        client.connectToServer(serverPath) {
            // Периодический вызов функции каждую минуту после завершения предыдущего
            while (true) {
                try {
                    println("\n[PERIODIC] Начинаю создание суммари...")
                    val startTime = System.currentTimeMillis()
                    
                    client.getMessagesAndCreateSummary(yandexGPTClient)
                    
                    val endTime = System.currentTimeMillis()
                    val duration = (endTime - startTime) / 1000.0
                    println("[PERIODIC] Создание суммари завершено за ${duration}с")
                    
                    println("[PERIODIC] Ожидание 90 сек до следующего вызова...")
                    delay(90.seconds)
                } catch (e: Exception) {
                    println("[PERIODIC] Ошибка при создании суммари: ${e.message}")
                    e.printStackTrace()
                    println("[PERIODIC] Повторная попытка через 90 сек...")
                    delay(90.seconds)
                }
            }
        }
    }
}