package org.example

import kotlinx.coroutines.runBlocking

fun main(vararg args: String): Unit = runBlocking {
//    require(args.size >= 1) {
//        "Usage: java -jar build/libs/MCP-client-1.0-SNAPSHOT.jar <path_to_server_script> <yandex_iam_token> <yandex_folder_id>"
    require(args.isNotEmpty()) {
        "Usage: java -jar libs/MCP-client-1.0-SNAPSHOT.jar <path_to_server_script>"
    }
    val serverPath = args[0]
//    val yandexIamToken = args[1]
//    val yandexFolderId = args[2]

    val yandexGPTClient = YandexGPTClient(
        iamToken = Keys.IAM_TOKEN,
        folderId = Keys.FOLDER_ID
    )

    val client = MCPClient()
    client.use {
        client.connectToServer(serverPath) {
            client.getMessagesAndCreateSummary(yandexGPTClient)
        }
    }
}