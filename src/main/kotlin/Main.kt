package com.kxxnzstdsw

import com.kxxnzstdsw.dispatcher.RequestDispatcher
import com.kxxnzstdsw.pool.PoolManager
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

fun main() {
    val logger = LoggerFactory.getLogger("Main")
    logger.info("IDB Engine started, listening on stdin...")

    // Use BufferedReader for more reliable line-by-line reading
    val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))

    // Add shutdown hook for graceful cleanup
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutdown hook triggered")
        PoolManager.closeAll()
    })

    try {
        logger.info("Entering main loop, waiting for input...")
        while (true) {
            val line = reader.readLine()

            // EOF reached (stdin closed)
            if (line == null) {
                logger.info("EOF detected (stdin closed), shutting down")
                break
            }

            // Check for exit command
            if (line.trim() == "CMD_EXIT") {
                logger.info("Received CMD_EXIT, shutting down gracefully")
                break
            }

            // Skip empty lines
            if (line.isBlank()) {
                continue
            }

            // Process request and send response
            val response = RequestDispatcher.dispatch(line)
            println(response)
            System.out.flush()
        }
    } catch (e: Exception) {
        logger.error("Fatal error in main loop", e)
    } finally {
        // Cleanup
        PoolManager.closeAll()
        logger.info("IDB Engine stopped")
        exitProcess(0)
    }
}