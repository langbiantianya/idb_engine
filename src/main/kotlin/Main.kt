package com.kxxnzstdsw

import com.kxxnzstdsw.dispatcher.RequestDispatcher
import com.kxxnzstdsw.loader.DriverLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.system.exitProcess

fun main() = runBlocking {
    val logger = LoggerFactory.getLogger("Main")
    logger.info("IDB Engine started (async mode), listening on stdin...")

    // 动态加载 drivers/ 目录下的 JDBC 驱动
    DriverLoader.loadFromDir(File("drivers"))

    val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))

    // Channel for serializing stdout output (only one output at a time)
    val outputChannel = Channel<String>(Channel.UNLIMITED)

    // Add shutdown hook for graceful cleanup
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutdown hook triggered")
        PoolManager.closeAll()
        DriverLoader.closeAll()
    })

    // Launch output writer coroutine (serializes all stdout writes)
    val outputJob = launch(Dispatchers.IO) {
        for (response in outputChannel) {
            println(response)
            System.out.flush()
        }
    }

    try {
        logger.info("Entering main loop, waiting for input...")
        while (true) {
            // Read line in IO dispatcher (blocking operation)
            val line = withContext(Dispatchers.IO) {
                reader.readLine()
            }

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

            // Process request asynchronously (non-blocking)
            launch {
                try {
                    val masked = line.replace(Regex(""""password"\s*:\s*"[^"]*"""""), """"password":"***"""")
                    logger.debug("STDIN <<< {}", masked)
                    RequestDispatcher.dispatch(line, outputChannel)
                } catch (e: Exception) {
                    logger.error("Error processing request asynchronously", e)
                }
            }
        }
    } catch (e: Exception) {
        logger.error("Fatal error in main loop", e)
    } finally {
        // Wait for all pending outputs to complete
        outputChannel.close()
        outputJob.join()

        // Cleanup
        PoolManager.closeAll()
        logger.info("IDB Engine stopped")
        exitProcess(0)
    }
}