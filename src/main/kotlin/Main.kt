package org.example

import kotlinx.coroutines.runBlocking

fun main(vararg args: String): Unit = runBlocking{
    require(args.isNotEmpty()) {
        "Usage: java -jar <your_path>/build/libs/kotlin-mcp-client-0.1.0-all.jar <path_to_server_script>"
    }
    val serverPath = args.first()
    val client = MCPClient()
    client.use {
        client.connectToServer(serverPath)
    }
}