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
    companion object {
        const val VER: Byte = 0x05

        const val CMD_CONNECT: Byte = 0x01
        const val CMD_BIND: Byte = 0x02
        const val CMD_UDP_ASSOCIATE: Byte = 0x03

        const val METHOD_NO_AUTHENTICATION_REQUIRED: Byte = 0x00
        const val METHOD_GSSAPI: Byte = 0x01
        const val METHOD_USERNAME_PASSWORD: Byte = 0x02
        const val METHOD_NO_ACCEPTABLE_METHODS: Byte = 0xFF.toByte()

        const val ATYP_IPV4: Byte = 0x01
        const val ATYP_FQDN: Byte = 0x03
        const val ATYP_IPV6: Byte = 0x04

        const val REP_SUCCEEDED: Byte = 0x00
        const val REP_GENERAL_FAILURE: Byte = 0x01
        const val REP_CONNECTION_NOT_ALLOWED: Byte = 0x02
        const val REP_NETWORK_UNREACHABLE: Byte = 0x03
        const val REP_HOST_UNREACHABLE: Byte = 0x04
        const val REP_CONNECTION_REFUSED: Byte = 0x05
        const val REP_TTL_EXPIRED: Byte = 0x06
        const val REP_COMMAND_NOT_SUPPORTED: Byte = 0x07
        const val REP_ADDRESS_TYPE_NOT_SUPPORTED: Byte = 0x08
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        scope.launch {
            if (config.listenType == "port") {
                startPort(config.listenPort)
            } else {
                startUnixSocket(config.listenUnixSocket)
            }
        }
        logger("service started")
    }

    fun startPort(port: Int) {
        val serverSocket = ServerSocket(port)
        logger("listening on :${serverSocket.localPort}")
        while (true) {
            val clientSocket = serverSocket.accept()
            val remoteAddress = clientSocket.remoteSocketAddress.toString().removePrefix("/")
            scope.launch {
                clientSocket.use {
                    val input = it.inputStream
                    val output = it.outputStream
                    handleConnection(input, output, remoteAddress)
                }
            }
        }
    }

    fun startUnixSocket(name: String) {
        val serverSocket = LocalServerSocket(name)
        logger("listening on @${serverSocket.localSocketAddress.name}")
        while (true) {
            val clientSocket = serverSocket.accept()
            val remoteAddress = "@$name"
            scope.launch {
                clientSocket.use {
                    val input = it.inputStream
                    val output = it.outputStream
                    handleConnection(input, output, remoteAddress)
                }
            }
        }
    }

    private fun handleConnection(
        input: InputStream,
        output: OutputStream,
        remoteAddress: String
    ) {
        // https://www.rfc-editor.org/rfc/rfc1928

        val (atyp, target, port) = runCatching {
            // 1. 协议版本协商
            handleSocks(input, output)
            // 2. 处理请求, 读取目标地址
            handleRequest(input, output)
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
                output.write(byteArrayOf(VER, REP_SUCCEEDED, 0x00, atyp))
                if (atyp == ATYP_FQDN) {
                    it.inetAddress.hostName.toByteArray().apply {
                        output.write(this.size)
                        output.write(this)
                    }
                } else {
                    output.write(it.inetAddress.address)
                }
                output.write(ByteBuffer.allocate(2).putShort(port.toShort()).array())
                it
            },
            onFailure = {
                logger(it.message.toString())
                when (it) {
                    is java.net.UnknownHostException -> {
                        output.write(byteArrayOf(VER, REP_HOST_UNREACHABLE, 0x00, 1, 0, 0, 0, 0, 0, 0))
                    }

                    is java.net.ConnectException if it.message?.contains("Connection refused") == true -> {
                        output.write(byteArrayOf(VER, REP_CONNECTION_REFUSED, 0x00, 1, 0, 0, 0, 0, 0, 0))
                    }

                    is java.net.ConnectException if it.message?.contains("Network is unreachable") == true -> {
                        output.write(byteArrayOf(VER, REP_NETWORK_UNREACHABLE, 0x00, 1, 0, 0, 0, 0, 0, 0))
                    }

                    else -> {
                        output.write(byteArrayOf(VER, REP_GENERAL_FAILURE, 0x00, 1, 0, 0, 0, 0, 0, 0))
                    }
                }
                return
            }
        )
        // 4. 流量转发
        targetSocket.use {
            handleStream(input, output, it)
        }
    }

    private fun handleSocks(input: InputStream, output: OutputStream) {
        val ver = input.read().toByte()
        if (ver != VER) {
            throw Error("Unsupported version")
        }
        val nMethods = input.read()
        val methods = ByteArray(nMethods).apply { input.read(this) }
        for (method in methods) {
            if (method == METHOD_NO_AUTHENTICATION_REQUIRED) {
                output.write(byteArrayOf(ver, method))
                return
            }
        }
        output.write(byteArrayOf(ver, METHOD_NO_ACCEPTABLE_METHODS))
        throw Error("Unsupported method")
    }

    private fun handleRequest(input: InputStream, output: OutputStream): Triple<Byte, String, Int> {
        val (ver, cmd, rsv, atyp) = ByteArray(4).apply { input.read(this) }
        if (ver != VER) {
            throw Error("Unsupported protocol")
        }
        if (cmd != CMD_CONNECT) {
            output.write(byteArrayOf(VER, REP_COMMAND_NOT_SUPPORTED, 0x00, 1, 0, 0, 0, 0, 0, 0))
            throw Error("Unsupported protocol")
        }
        val address = when (atyp) {
            // IPv4
            ATYP_IPV4 -> ByteArray(4).apply { input.read(this) }
                .joinToString(".") { it.toInt().and(0xff).toString() }
            // host
            ATYP_FQDN -> ByteArray(input.read()).apply { input.read(this) }
                .decodeToString()
            // IPv6
            ATYP_IPV6 ->
                if (config.allowIPv6) {
                    ByteArray(16).apply { input.read(this) }
                        .let {
                            Array(8) { i ->
                                "%04x".format(((it[i * 2].toInt() and 0xFF) shl 8) or (it[i * 2 + 1].toInt() and 0xFF))
                            }.joinToString(":")
                        }
                } else {
                    output.write(byteArrayOf(VER, REP_ADDRESS_TYPE_NOT_SUPPORTED, 0x00, 1, 0, 0, 0, 0, 0, 0))
                    throw Error("IPv6 not allowed")
                }
            // other
            else -> {
                output.write(byteArrayOf(VER, REP_ADDRESS_TYPE_NOT_SUPPORTED, 0x00, 1, 0, 0, 0, 0, 0, 0))
                throw Error("Unknown addressType")
            }
        }
        val port = ByteBuffer.wrap(ByteArray(2).apply { input.read(this) })
            .short.toInt().and(0xffff)
        return Triple(atyp, address, port)
    }

    private fun handleStream(
        sourceInput: InputStream,
        sourceOutput: OutputStream,
        targetSocket: Socket
    ) {
        val latch = CountDownLatch(1)
        scope.launch {
            try {
                targetSocket.inputStream.copyTo(sourceOutput)
            } catch (e: Exception) {
                when (e) {
                    is java.net.SocketException if e.message == "Socket closed" -> {}
                    else -> logger(e.toString())
                }
            } finally {
                latch.countDown()
            }
        }
        scope.launch {
            try {
                sourceInput.copyTo(targetSocket.outputStream)
            } catch (e: Exception) {
                when (e) {
                    is java.net.SocketException if e.message == "Socket closed" -> {}
                    else -> logger(e.toString())
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }
}