package de.torsm.ard

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.java.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.optional
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.*

class Config(args: Array<String>) {
    private val parser = ArgParser("ard")
    val workers by parser.option(ArgType.Int, "workers", "w", "Number of async workers").default(10)
    val delay by parser.option(ArgType.Int, "delay", "d", "Delay between opening requests in seconds").default(1)
    val url by parser.argument(ArgType.String, "url", "URL of file to download")
    val file by parser.argument(ArgType.String, "file", "Path of the output file").optional()

    init {
        parser.parse(args)
    }
}

class ProgressDisplay(private val total: Long, private val message: String, var displayProgress: Boolean = true) {
    private var progress = 0L
    private var percent = 0L

    init {
        process()
    }

    fun onProgress(bytesDownloaded: Long) {
        synchronized(this) {
            progress += bytesDownloaded
            process()
        }
    }

    private fun process() {
        if (displayProgress) {
            val newPercent = progress * 100 / total
            if (newPercent > percent) {
                percent = newPercent
                if (percent < 100)
                    print("$message... $percent%\r")
                else
                    println("$message... done!")
            }
        }
    }
}

fun main(args: Array<String>) {
    val config = Config(args)
    val url = Url(config.url)

    val client = HttpClient(Java) {
        BrowserUserAgent()
    }

    runBlocking(Dispatchers.IO) {
        val infoProgress = ProgressDisplay(1, "Fetching file info")
        val response: HttpResponse = client.head(url)
        val size = response.contentLength() ?: 0
        val bytesPerWorker = size / config.workers

        val fileName = config.file
            ?: response.headers["Content-Disposition"]
                ?.splitToSequence(";")
                ?.find { it.contains("filename") }
                ?.substringAfter("=")
                ?.trim('"')
            ?: url.encodedPath.splitToSequence("/").last()
        val file = Paths.get(fileName)

        infoProgress.onProgress(1)
        val downloadProgress = ProgressDisplay(size, "Downloading", false)

        AsynchronousFileChannel.open(file, WRITE, TRUNCATE_EXISTING, CREATE).use { fileChannel ->
            coroutineScope {
                val n = config.workers - 1
                val workerProgress = ProgressDisplay(config.workers.toLong(), "Launching workers", true)
                repeat(n) { i ->
                    val start = i * bytesPerWorker
                    launchWorker(fileChannel, url, client, start, start + bytesPerWorker - 1, downloadProgress)
                    workerProgress.onProgress(1)
                    delay(config.delay * 1000L)
                }
                launchWorker(fileChannel, url, client, n * bytesPerWorker, size - 1, downloadProgress)
                workerProgress.onProgress(1)
                downloadProgress.displayProgress = true
            }
        }
    }
}

fun CoroutineScope.launchWorker(file: AsynchronousFileChannel, url: Url, client: HttpClient, start: Long, end: Long, progressDisplay: ProgressDisplay) =
    launch {
        var progress = 0L
        client.get<HttpStatement>(url) {
            header("Range", "bytes=$start-$end")
            onDownload { bytesSentTotal, _ ->
                val diff = bytesSentTotal - progress
                progress = bytesSentTotal
                progressDisplay.onProgress(diff)
            }
        }.execute { response ->
            val buffer = ByteBuffer.allocate(4096)
            val stream = response.receive<ByteReadChannel>()
            var position = 0
            while (!stream.isClosedForRead) {
                buffer.clear()
                val bytesRead = stream.readAvailable(buffer)
                if (bytesRead == -1) break
                buffer.position(0)
                buffer.limit(bytesRead)
                file.aWrite(buffer, start + position)
                position += bytesRead
            }
        }
    }
