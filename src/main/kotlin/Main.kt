package org.example

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun main(vararg args: String): Unit = runBlocking {
    require(args.isNotEmpty()) {
        "Usage: java -jar libs/MCP-client-1.0-SNAPSHOT.jar <path_to_server_script> "
    }
    val serverPath = args[0]

    val yandexGPTClient = YandexGPTClient(
        iamToken = Keys.IAM_TOKEN,
        folderId = Keys.FOLDER_ID
    )

    val client = MCPClient()
    client.use {
        client.connectToServer(serverPath) {
            // Запрашиваем название чата у пользователя
            val chatName = withContext(Dispatchers.IO) {
                print("Введите название чата для суммаризации: ")
                readLine()?.trim()?.takeIf { it.isNotBlank() }
            }
            
            if (chatName == null || chatName.isBlank()) {
                println("Ошибка: название чата не может быть пустым")
                return@connectToServer
            }
            
            println("\n[PERIODIC] Начинаю создание суммари для чата: $chatName")
            val startTime = System.currentTimeMillis()
            client.getMessagesSummaryFromChat(yandexGPTClient, chatName)
            val endTime = System.currentTimeMillis()
            val duration = (endTime - startTime) / 1000.0
            println("[PERIODIC] Создание суммари завершено за ${duration}с")
            // Периодический вызов функции каждую минуту после завершения предыдущего
//            while (true) {
//                try {
//                    println("\n[PERIODIC] Начинаю создание суммари...")
//                    val startTime = System.currentTimeMillis()
//
//                    client.getMessagesAndCreateSummary(yandexGPTClient)
//
//                    val endTime = System.currentTimeMillis()
//                    val duration = (endTime - startTime) / 1000.0
//                    println("[PERIODIC] Создание суммари завершено за ${duration}с")
//
//                    println("[PERIODIC] Ожидание 90 сек до следующего вызова...")
//                    delay(90.seconds)
//                } catch (e: Exception) {
//                    println("[PERIODIC] Ошибка при создании суммари: ${e.message}")
//                    e.printStackTrace()
//                    println("[PERIODIC] Повторная попытка через 90 сек...")
//                    delay(90.seconds)
//                }
//            }
        }
    }
}