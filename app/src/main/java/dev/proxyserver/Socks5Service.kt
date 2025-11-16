package dev.proxyserver

import android.net.LocalServerSocket
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch

data class Config(
    val listenType: String,
    val listenPort: Int,
    val listenUnixSocket: String,
    val allowIPv6: Boolean
)

class Socks5Service(val config: Config, val logger: (String) -> Unit) {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        if (config.listenType == "port") {
            startPort(config.listenPort)
        } else {
            startUnixSocket(config.listenUnixSocket)
        }
        logger("service started")
    }

    fun startPort(port: Int) {
        val serverSocket = ServerSocket(port)
        scope.launch {
            while (true) {
                val clientSocket = serverSocket.accept()
                val remoteAddress = clientSocket.remoteSocketAddress.toString()
                scope.launch {
                    clientSocket.use {
                        val input = it.inputStream
                        val output = it.outputStream
                        handleConnection(input, output, remoteAddress)
                    }
                }
            }
        }
        logger("listening on :${serverSocket.localPort}")
    }

    fun startUnixSocket(name: String) {
        val serverSocket = LocalServerSocket(name)
        scope.launch {
            while (true) {
                val clientSocket = serverSocket.accept()
                val remoteAddress = name
                scope.launch {
                    clientSocket.use {
                        val input = it.inputStream
                        val output = it.outputStream
                        handleConnection(input, output, remoteAddress)
                    }
                }
            }
        }
        logger("listening on @${serverSocket.localSocketAddress.name}")
    }

    private suspend fun handleConnection(
        input: InputStream,
        output: OutputStream,
        remoteAddress: String
    ) {
        val (target, port) = runCatching {
            // 1. 协议版本协商
            handleSocks(input, output)
            // 2. 处理请求, 读取目标地址
            handleRequest(input)
        }.fold(
            onSuccess = { it },
            onFailure = {
                logger(it.toString())
                return
            }
        )
        logger("$remoteAddress -> $target:$port")
        // 3. 连接目标服务器
        val targetSocket: Socket = runCatching {
            Socket(target, port)
        }.fold(
            onSuccess = {
                output.write(byteArrayOf(0x05, 0x00, 0x00, 1, 0, 0, 0, 0, 0, 0))
                it
            },
            onFailure = {
                output.write(byteArrayOf(0x05, 0x00, 0x01, 1, 0, 0, 0, 0, 0, 0))
                return
            }
        )
        // 4. 流量转发
        targetSocket.use {
            handleStream(input, output, it)
        }
    }

    private fun handleSocks(input: InputStream, output: OutputStream) {
        if (input.read() != 0x05) {
            throw Error("Unsupported version")
        }
        val methods = ByteArray(input.read()).apply { input.read(this) }
        if (methods.any { it == 0x00.toByte() }) {
            output.write(byteArrayOf(0x05, 0x00))
        } else {
            throw Error("Unsupported method")
        }
    }

    private fun handleRequest(input: InputStream): Pair<String, Int> {
        val request = ByteArray(4).apply { input.read(this) }
        if (request[0] != 0x05.toByte() || request[1] != 0x01.toByte()) throw Error("Unsupported protocol")
        val address = when (request[3]) {
            // IPv4
            0x01.toByte() -> ByteArray(4).apply { input.read(this) }
                .joinToString(".") { it.toInt().and(0xff).toString() }
            // host
            0x03.toByte() -> ByteArray(input.read()).apply { input.read(this) }
                .decodeToString()
            // IPv6
            0x04.toByte() -> if (config.allowIPv6)
                ByteArray(16).apply { input.read(this) }
                    .let {
                        Array(8) { i ->
                            "%04x".format(((it[i * 2].toInt() and 0xFF) shl 8) or (it[i * 2 + 1].toInt() and 0xFF))
                        }.joinToString(":")
                    }
            else throw Error("IPv6 not allowed")

            else -> throw Error("Unknown addressType")
        }
        val port = ByteBuffer.wrap(ByteArray(2).apply { input.read(this) })
            .short.toInt().and(0xffff)
        return address to port
    }

    private suspend fun handleStream(
        sourceInput: InputStream,
        sourceOutput: OutputStream,
        targetSocket: Socket
    ) {
        val latch = CountDownLatch(1)
        val job1 = scope.launch {
            try {
                targetSocket.inputStream.copyTo(sourceOutput)
            } catch (_: Exception) {
            } finally {
                latch.countDown()
            }
        }
        val job2 = scope.launch {
            try {
                sourceInput.copyTo(targetSocket.outputStream)
            } catch (_: Exception) {
            } finally {
                latch.countDown()
            }
        }

        latch.await()
        try {
            withTimeout(30000) {
                joinAll(job1, job2)
            }
        } catch (_: TimeoutCancellationException) {
        } catch (e: Exception) {
            logger(e.stackTraceToString())
        }
    }
}