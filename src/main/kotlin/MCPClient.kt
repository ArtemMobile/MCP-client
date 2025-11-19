package org.example

import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlin.collections.map
import kotlin.text.contains
import kotlin.text.lowercase
import kotlin.text.substringAfterLast


class MCPClient : AutoCloseable {
    private val mcp: Client = Client(clientInfo = Implementation(name = "mcp-client-cli", version = "1.0.0"))

    // List of tools offered by the server
    private lateinit var tools: List<Tool>

    suspend fun connectToServer(serverScriptPath: String) {
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

            mcp.callTool(
                "get_messages", mapOf(
                    "chat_id" to 894613367,
                    "page" to 1,
                    "page_size" to 20
                )
            ).let {
                println(it?.content)
            }
        } catch (e: Exception) {
            println("Failed to connect to MCP server: $e")
            throw e
        }
    }

    override fun close() {
        runBlocking {
            mcp.close()
        }
    }
}

